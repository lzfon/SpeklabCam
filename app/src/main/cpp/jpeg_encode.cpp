// Public NDK system codec, loaded only on API 30+. Same quality setting and
// sRGB RGBA8 samples as Bitmap.compress, without allocating an Android Bitmap.
#include <jni.h>
#include <android/bitmap.h>
#include <android/data_space.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <cstdint>
#include <algorithm>
#include <thread>
#include <atomic>

namespace {
using Write = bool (*)(void*, const void*, size_t);
using Compress = int (*)(const AndroidBitmapInfo*, int32_t, const void*, int32_t, int32_t, void*, Write);

Compress codec() {
    static auto function = reinterpret_cast<Compress>(dlsym(RTLD_DEFAULT, "AndroidBitmap_compress"));
    return function;
}

void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("com/spektralab/android/engine/SpkException"), message);
}

struct Sink {
    JNIEnv* env;
    jobject output;
    jmethodID write;
    jbyteArray buffer;
    std::thread::id owner;
    void* mapping;
    size_t size;
    size_t written = 0;
    std::atomic_bool wrong_thread{false};
};

bool write_encoded(void* context, const void* data, size_t size) {
    auto& sink = *static_cast<Sink*>(context);
    // The system's streaming writer is synchronous. Never use a JNIEnv on a
    // different thread should a vendor implementation violate that contract.
    if (std::this_thread::get_id() != sink.owner) { sink.wrong_thread.store(true, std::memory_order_relaxed); return false; }
    if (sink.env->ExceptionCheck()) return false;
    const auto* bytes = static_cast<const jbyte*>(data);
    while (size) {
        const auto count = static_cast<jsize>(std::min(size, size_t(64 * 1024)));
        sink.env->SetByteArrayRegion(sink.buffer, 0, count, bytes);
        if (sink.env->ExceptionCheck()) return false;
        sink.env->CallVoidMethod(sink.output, sink.write, sink.buffer, 0, count);
        if (sink.env->ExceptionCheck()) return false;
        bytes += count; size -= count; sink.written += count;
    }
    if (sink.written >= 64 * 1024) {
        // These are clean file-backed pages, not discarded image data. They
        // can fault back with the exact bytes if the codec revisits a row.
        madvise(sink.mapping, sink.size, MADV_DONTNEED);
        sink.written = 0;
    }
    return true;
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_spektralab_android_engine_NativeJpegEncoder_nativeAvailable(JNIEnv*, jobject) {
    return codec() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_spektralab_android_engine_NativeJpegEncoder_nativeEncode(
    JNIEnv* env, jobject, jint fd, jint width, jint height, jint quality, jobject output) {
    const auto encode = codec();
    if (!encode || !output || width <= 0 || height <= 0 || width > 131072 || height > 131072 || quality < 1 || quality > 100) {
        fail(env, "Invalid/unavailable system JPEG encoder"); return;
    }
    const auto size = uint64_t(width) * uint64_t(height) * 4;
    struct stat info{};
    if (fstat(fd, &info) != 0 || uint64_t(info.st_size) != size || !S_ISREG(info.st_mode)) {
        fail(env, "Incomplete system JPEG input"); return;
    }
    void* mapping = mmap(nullptr, size_t(size), PROT_READ, MAP_PRIVATE, fd, 0);
    if (mapping == MAP_FAILED) { fail(env, "Cannot map system JPEG rows"); return; }
    madvise(mapping, size_t(size), MADV_SEQUENTIAL);
    jclass type = env->GetObjectClass(output);
    jmethodID write = type ? env->GetMethodID(type, "write", "([BII)V") : nullptr;
    if (type) env->DeleteLocalRef(type);
    jbyteArray buffer = write ? env->NewByteArray(64 * 1024) : nullptr;
    if (!buffer) { munmap(mapping, size_t(size)); return; }
    AndroidBitmapInfo bitmap{};
    bitmap.width = uint32_t(width); bitmap.height = uint32_t(height); bitmap.stride = uint32_t(width) * 4;
    bitmap.format = ANDROID_BITMAP_FORMAT_RGBA_8888;
    bitmap.flags = ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE;
    Sink sink{env, output, write, buffer, std::this_thread::get_id(), mapping, size_t(size)};
    const int result = encode(&bitmap, ADATASPACE_SRGB, mapping, ANDROID_BITMAP_COMPRESS_FORMAT_JPEG, quality, &sink, write_encoded);
    env->DeleteLocalRef(buffer);
    munmap(mapping, size_t(size));
    if (result != ANDROID_BITMAP_RESULT_SUCCESS || sink.wrong_thread.load(std::memory_order_relaxed)) fail(env, "System JPEG encoding failed");
}
