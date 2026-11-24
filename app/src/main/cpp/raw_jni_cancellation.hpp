#pragma once

#include <jni.h>
#include <atomic>
#include <thread>

namespace theia::raw {

// LibRaw invokes callbacks inside some parallel regions. JNIEnv and the local
// callback reference belong exclusively to the originating JNI thread. Worker
// callbacks observe the latch; the owner polls Java at LibRaw stage boundaries.
class JniCancellation final {
public:
    JniCancellation(JNIEnv* env, jobject callback)
        : env_(env), callback_(callback), owner_(std::this_thread::get_id()) {
        if (!callback) return;
        jclass type = env->GetObjectClass(callback);
        if (type) {
            method_ = env->GetMethodID(type, "isCancelled", "()Z");
            env->DeleteLocalRef(type);
        }
    }

    bool requested() const {
        if (requested_.load(std::memory_order_relaxed)) return true;
        if (std::this_thread::get_id() != owner_) return false;
        const bool result = env_->ExceptionCheck() ||
            (callback_ && method_ && env_->CallBooleanMethod(callback_, method_) == JNI_TRUE);
        if (result || env_->ExceptionCheck()) requested_.store(true, std::memory_order_relaxed);
        return requested_.load(std::memory_order_relaxed);
    }

private:
    JNIEnv* env_;
    jobject callback_;
    jmethodID method_ = nullptr;
    std::thread::id owner_;
    mutable std::atomic_bool requested_{false};
};

}  // namespace theia::raw
