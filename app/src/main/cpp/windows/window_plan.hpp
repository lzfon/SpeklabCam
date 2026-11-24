#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>

// Explicit shader layouts; never infer a pixel stride from pooled capacity.
// Inspired by SAINTTaiYi/SpektraLab-Android's offset/range dispatch design.
namespace theia::export_windows {
constexpr std::size_t binding_count = 16;
struct Push {
    uint32_t base = 0;
    uint32_t end = 0;
    std::array<uint32_t, binding_count> origin{};
};
static_assert(sizeof(Push) == 72);
static_assert(offsetof(Push, origin) == 8);

enum class Kind { Point, Take, Pack16, Fir, Iir, Stride, Zoom, Geometry, Copy, Opaque };
struct Rule {
    const char* kernel;
    Kind kind;
    std::array<uint32_t, binding_count> stride;
};
inline const Rule* rule(const char* kernel) {
    static constexpr Rule rules[] = {
        {"spk_affine3", Kind::Point, {4, 0, 0, 0, 4}},
        {"spk_boost", Kind::Point, {4, 0, 0, 4}},
        {"spk_bw_correct", Kind::Point, {12, 0, 0, 12}},
        {"spk_cam16ucs_compress", Kind::Point, {12, 0, 0, 0, 0, 0, 12}},
        {"spk_view_cam16", Kind::Point, {12, 0, 0, 0, 0, 0, 12}},
        {"spk_cctf_decode", Kind::Point, {4, 0, 4}},
        {"spk_cctf_encode_matrix", Kind::Point, {12, 0, 0, 12}},
        {"spk_couplers_correction", Kind::Point, {12, 0, 0, 0, 0, 0, 12}},
        {"spk_curves", Kind::Point, {12, 0, 0, 0, 0, 12}},
        {"spk_di_encode", Kind::Point, {12, 0, 0, 12}},
        {"spk_di_normalise", Kind::Point, {12, 0, 0, 0, 12}},
        {"spk_edr", Kind::Point, {12, 0, 0, 0, 12}},
        {"spk_geometry_resample_df", Kind::Geometry, {0, 0, 0, 12}},
        {"spk_glare_add", Kind::Point, {4, 4, 0, 0, 4}},
        {"spk_grain_layer_one", Kind::Point, {12, 0, 0, 0, 0, 0, 0, 12}},
        {"spk_grain_layers", Kind::Point, {12, 0, 0, 0, 0, 0, 0, 12}},
        {"spk_grain_simple", Kind::Point, {12, 0, 0, 0, 12}},
        {"spk_highlight_headroom", Kind::Point, {12, 0, 0, 12}},
        {"spk_iir_df_acc", Kind::Iir, {}},
        {"spk_lincomb3", Kind::Point, {4, 4, 0, 0, 0, 4}},
        {"spk_log10_guarded", Kind::Point, {4, 0, 0, 4}},
        {"spk_lognormal_field", Kind::Point, {0, 0, 12}},
        {"spk_lut2d_cubic", Kind::Point, {8, 4, 0, 0, 12}},
        {"spk_lut3d_trilinear", Kind::Point, {12, 0, 0, 0, 0, 12}},
        {"spk_matmul3", Kind::Point, {12, 0, 0, 12}},
        {"spk_mul", Kind::Point, {4, 4, 0, 4}},
        {"spk_print_exposure", Kind::Point, {4, 0, 0, 4}},
        {"spk_scene_latitude", Kind::Point, {12, 0, 0, 12}},
        {"spk_sep_fir_acc", Kind::Fir, {}},
        {"spk_spectral_epilogue", Kind::Point, {12, 0, 0, 0, 0, 0, 12}},
        {"spk_stride_sample", Kind::Stride, {0, 0, 12}},
        {"spk_take_rgb", Kind::Take, {0, 0, 12}},
        {"spk_tc_b", Kind::Point, {12, 0, 0, 8, 4}},
        {"spk_to_rgba16", Kind::Pack16, {12, 0, 0}},
        {"spk_zoom_bilinear_mirror", Kind::Zoom, {0, 0, 12}},
        {"spk_view_grade", Kind::Point, {8, 0, 0, 0, 12}},
        {"spk_view_grade_di", Kind::Point, {8, 0, 0, 0, 12}},
        {"spk_window_copy", Kind::Copy, {}},
        {"spk_math_probe", Kind::Opaque, {}},
        {"spk_rng_probe", Kind::Opaque, {}},
        {"spk_view_geometry", Kind::Opaque, {}},
        {"spk_view_pack", Kind::Opaque, {}},
        {"spk_view_pair", Kind::Opaque, {}},
        {"spk_overscan_canvas", Kind::Opaque, {}},
        {"spk_overscan_add_mask", Kind::Opaque, {}},
        {"spk_overscan_film_present", Kind::Point, {12, 0, 0, 12}},
        {"spk_overscan_light", Kind::Point, {12, 0, 0, 12}},
    };
    for (const auto& entry : rules)
        if (std::strcmp(kernel, entry.kernel) == 0)
            return &entry;
    return nullptr;
}

struct Span {
    uint64_t begin = 0, end = 0;
};
inline Span elements(uint64_t first, uint64_t end, uint64_t stride) { return {first * stride, end * stride}; }
inline Span rows(uint64_t first, uint64_t end, uint64_t width) { return elements(first, end, width * 12); }
inline Span aligned(Span span, uint64_t alignment) {
    if (alignment)
        span.begin -= span.begin % alignment;
    return span;
}
} // namespace theia::export_windows
