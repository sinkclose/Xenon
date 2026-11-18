// Adapted from NEORUAA/Glasmio liquid_menu_sdf.cpp (Apache-2.0).
// Revision 1a0652509071c36fedb35300abdfd12316cc3c54. Only the menu field and FP16 packer are used.
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <cstring>
#include <vector>
#if defined(__aarch64__)
#include <arm_neon.h>
#elif defined(__SSE2__)
#include <emmintrin.h>
#endif
#if !defined(LIQUID_MENU_STANDALONE)
#include <jni.h>
#endif

namespace {
constexpr float kExpansion = 1.5286649465560913f;
constexpr float kAdjustment = 0.5286649465560913f;
struct Normal {
    float x, y;
};
struct Sample {
    float distance, nx, ny;
};
inline float saturate(float value) { return std::clamp(value, 0.0f, 1.0f); }

// CASDF's continuous field and its independently ovalized normal, in isotropic coordinates.
struct RoundedField {
    float cx, cy, radius, inverseX, inverseY, halfWidth, halfHeight;
    float expanded, curvatureX, curvatureY, baseRadius;
    bool circular;
    explicit RoundedField(const float *g, bool edges = false) {
        cx = edges ? (g[0] + g[2]) / 2.0f : g[0] + g[2] / 2.0f;
        cy = edges ? (g[1] + g[3]) / 2.0f : g[1] + g[3] / 2.0f;
        const float rx = std::max(g[4], 0.01f), ry = std::max(g[5], 0.01f);
        radius = std::min(rx, ry);
        inverseX = radius / rx;
        inverseY = radius / ry;
        halfWidth = (edges ? g[2] - g[0] : g[2]) / 2.0f * inverseX;
        halfHeight = (edges ? g[3] - g[1] : g[3]) / 2.0f * inverseY;
        expanded = radius * kExpansion;
        curvatureX = saturate(2.8915570351735034f - 1.4457785175867517f * (halfWidth * 2.0f) / expanded);
        curvatureY = saturate(2.8915570351735034f - 1.4457785175867517f * (halfHeight * 2.0f) / expanded);
        baseRadius = radius * (kExpansion - kAdjustment * std::max(curvatureX, curvatureY));
        circular = curvatureX == 1.0f && curvatureY == 1.0f;
    }
    float distance(float x, float y) const {
        const float qx = std::abs((x - cx) * inverseX) - halfWidth;
        const float qy = std::abs((y - cy) * inverseY) - halfHeight;
        const float bx = qx + baseRadius, by = qy + baseRadius;
        const float interior = std::min(std::max(bx, by), 0.0f);
        if (circular) {
            const float dx = std::max(bx, 0.0f), dy = std::max(by, 0.0f);
            return interior + std::sqrt(dx * dx + dy * dy) - radius;
        }
        const float ux = (qx + expanded) / expanded, uy = (qy + expanded) / expanded;
        const float zx = std::max(ux, 0.0f), zy = std::max(uy, 0.0f);
        const float maximum = std::max(zx, zy);
        const float t = maximum > 0.0f ? std::min(zx, zy) / maximum : 0.0f;
        const float length = std::sqrt(zx * zx + zy * zy);
        const float polynomial =
            ((((-0.9260540008544922f * t + 3.1560099124908447f) * t - 3.6412200927734375f) * t +
              1.268030047416687f) *
                 t +
             0.2685309946537018f) *
            t * t;
        const float continuous = length + 1.0f - 1.0f / (1.0f - polynomial * std::min(length, 1.0f));
        const float ax = std::max(kExpansion * ux - kAdjustment, 0.0f);
        const float ay = std::max(kExpansion * uy - kAdjustment, 0.0f);
        const float correction =
            0.3458344340324402f - continuous + 0.6541655659675598f * std::sqrt(ax * ax + ay * ay);
        const float weight = saturate((zx < zy ? 1.0f : -1.0f) * (t - 1.0f) + 0.5f);
        return interior + expanded * (continuous - 1.0f +
                                      correction * (curvatureX + (curvatureY - curvatureX) * weight));
    }
    Normal normal(float x, float y, float normalMix = 0.5f) const {
        const float lx = (x - cx) * inverseX, ly = (y - cy) * inverseY;
        const float qx = std::abs(lx) - halfWidth + baseRadius;
        const float qy = std::abs(ly) - halfHeight + baseRadius;
        float nx = std::max(qx, 0.0f), ny = std::max(qy, 0.0f);
        const float cornerLength = std::sqrt(nx * nx + ny * ny);
        if (cornerLength > 0.000001f) {
            nx /= cornerLength;
            ny /= cornerLength;
        } else if (qx > qy) {
            nx = 1.0f;
            ny = 0.0f;
        } else {
            nx = 0.0f;
            ny = 1.0f;
        }
        if (lx < 0.0f)
            nx = -nx;
        if (ly < 0.0f)
            ny = -ny;
        if (normalMix == 0.0f) {
            // Navigation contour folding uses only the rounded-box normal.
            // Avoid computing an oval normal whose mix weight is exactly zero.
            const float length = std::sqrt(nx * nx + ny * ny);
            if (length <= 0.000001f)
                return {0.0f, 0.0f};
            return {nx / length * inverseX, ny / length * inverseY};
        }
        float ox = lx, oy = ly * halfWidth / std::max(halfHeight, 0.000001f);
        const float ovalLength = std::sqrt(ox * ox + oy * oy);
        if (ovalLength > 0.000001f) {
            ox /= ovalLength;
            oy /= ovalLength;
        } else {
            ox = nx;
            oy = ny;
        }
        if (normalMix == 0.5f) {
            nx += ox;
            ny += oy;
        } else {
            nx += (ox - nx) * normalMix;
            ny += (oy - ny) * normalMix;
        }
        const float length = std::sqrt(nx * nx + ny * ny);
        if (length <= 0.000001f)
            return {0.0f, 0.0f};
        return {nx / length * inverseX, ny / length * inverseY};
    }
};

#if defined(__aarch64__) || defined(__SSE2__)
// Lane-wise float operations for NEON and SSE2 vectors. Comparisons and selection reproduce
// the scalar std::min/max/clamp tie and NaN rules, so each lane is bit-identical to the scalar code.
template <typename V> struct Lanes;
#if defined(__aarch64__)
using Float4 = float32x4_t;
template <> struct Lanes<float32x2_t> {
    using V = float32x2_t;
    using M = uint32x2_t;
    static V dup(float v) { return vdup_n_f32(v); }
    static V add(V a, V b) { return vadd_f32(a, b); }
    static V sub(V a, V b) { return vsub_f32(a, b); }
    static V mul(V a, V b) { return vmul_f32(a, b); }
    static V div(V a, V b) { return vdiv_f32(a, b); }
    static V sqrt(V a) { return vsqrt_f32(a); }
    static V abs(V a) { return vabs_f32(a); }
    static V neg(V a) { return vneg_f32(a); }
    static M lt(V a, V b) { return vclt_f32(a, b); }
    static M gt(V a, V b) { return vcgt_f32(a, b); }
    static M le(V a, V b) { return vcle_f32(a, b); }
    static V select(M m, V a, V b) { return vbsl_f32(m, a, b); }
};
template <> struct Lanes<float32x4_t> {
    using V = float32x4_t;
    using M = uint32x4_t;
    static V dup(float v) { return vdupq_n_f32(v); }
    static V add(V a, V b) { return vaddq_f32(a, b); }
    static V sub(V a, V b) { return vsubq_f32(a, b); }
    static V mul(V a, V b) { return vmulq_f32(a, b); }
    static V div(V a, V b) { return vdivq_f32(a, b); }
    static V sqrt(V a) { return vsqrtq_f32(a); }
    static V abs(V a) { return vabsq_f32(a); }
    static V neg(V a) { return vnegq_f32(a); }
    static M lt(V a, V b) { return vcltq_f32(a, b); }
    static M gt(V a, V b) { return vcgtq_f32(a, b); }
    static M le(V a, V b) { return vcleq_f32(a, b); }
    static V select(M m, V a, V b) { return vbslq_f32(m, a, b); }
};

#else
using Float4 = __m128;
template <> struct Lanes<Float4> {
    using V = Float4;
    using M = __m128;
    static V dup(float v) { return _mm_set1_ps(v); }
    static V add(V a, V b) { return _mm_add_ps(a, b); }
    static V sub(V a, V b) { return _mm_sub_ps(a, b); }
    static V mul(V a, V b) { return _mm_mul_ps(a, b); }
    static V div(V a, V b) { return _mm_div_ps(a, b); }
    static V sqrt(V a) { return _mm_sqrt_ps(a); }
    static V abs(V a) { return _mm_andnot_ps(_mm_set1_ps(-0.0f), a); }
    static V neg(V a) { return _mm_xor_ps(_mm_set1_ps(-0.0f), a); }
    static M lt(V a, V b) { return _mm_cmplt_ps(a, b); }
    static M gt(V a, V b) { return _mm_cmpgt_ps(a, b); }
    static M le(V a, V b) { return _mm_cmple_ps(a, b); }
    static V select(M m, V a, V b) {
        return _mm_or_ps(_mm_and_ps(m, a), _mm_andnot_ps(m, b));
    }
};
#endif

inline Float4 load4(const float *source) {
#if defined(__aarch64__)
    return vld1q_f32(source);
#else
    return _mm_loadu_ps(source);
#endif
}
inline void store4(float *target, Float4 value) {
#if defined(__aarch64__)
    vst1q_f32(target, value);
#else
    _mm_storeu_ps(target, value);
#endif
}
inline bool all4(typename Lanes<Float4>::M mask) {
#if defined(__aarch64__)
    return vminvq_u32(mask) != 0u;
#else
    return _mm_movemask_ps(mask) == 15;
#endif
}
inline void store3(float *target, Float4 d, Float4 nx, Float4 ny) {
#if defined(__aarch64__)
    const float32x4x3_t samples = {{d, nx, ny}};
    vst3q_f32(target, samples);
#else
    float distances[4], normalsX[4], normalsY[4];
    store4(distances, d); store4(normalsX, nx); store4(normalsY, ny);
    for (int i = 0; i < 4; ++i) {
        target[i * 3] = distances[i];
        target[i * 3 + 1] = normalsX[i];
        target[i * 3 + 2] = normalsY[i];
    }
#endif
}

// RoundedField::distance and normal(x, y, 0) over SIMD lanes. Lanes hold either different fields
// at one point (FieldLanes<float32x2_t>::pair) or one field at different points
// (FieldLanes<float32x4_t>::broadcast). Every lane repeats the scalar operations in order.
template <typename V> struct FieldLanes {
    using L = Lanes<V>;
    using M = typename L::M;
    V cx, cy, radius, inverseX, inverseY, halfWidth, halfHeight;
    V expanded, curvatureX, curvatureY, baseRadius;
    M circular;
    bool anyCircular, allCircular;

    // std::min(a, b) is (b < a) ? b : a; std::max(a, b) is (a < b) ? b : a.
    static V minOf(V a, V b) { return L::select(L::lt(b, a), b, a); }
    static V maxOf(V a, V b) { return L::select(L::lt(a, b), b, a); }
    // std::clamp(v, 0, 1) is (v < 0) ? 0 : (1 < v) ? 1 : v.
    static V saturateLanes(V v) {
        const V zero = L::dup(0.0f), one = L::dup(1.0f);
        return L::select(L::lt(v, zero), zero, L::select(L::lt(one, v), one, v));
    }

    V distance(V x, V y) const {
        const V zero = L::dup(0.0f), one = L::dup(1.0f);
        const V qx = L::sub(L::abs(L::mul(L::sub(x, cx), inverseX)), halfWidth);
        const V qy = L::sub(L::abs(L::mul(L::sub(y, cy), inverseY)), halfHeight);
        const V bx = L::add(qx, baseRadius), by = L::add(qy, baseRadius);
        const V interior = minOf(maxOf(bx, by), zero);
        V round = zero;
        if (anyCircular) {
            const V dx = maxOf(bx, zero), dy = maxOf(by, zero);
            round = L::sub(L::add(interior, L::sqrt(L::add(L::mul(dx, dx), L::mul(dy, dy)))), radius);
            if (allCircular) return round;
        }
        const V ux = L::div(L::add(qx, expanded), expanded);
        const V uy = L::div(L::add(qy, expanded), expanded);
        const V zx = maxOf(ux, zero), zy = maxOf(uy, zero);
        const V maximum = maxOf(zx, zy);
        const V t = L::select(L::gt(maximum, zero), L::div(minOf(zx, zy), maximum), zero);
        const V length = L::sqrt(L::add(L::mul(zx, zx), L::mul(zy, zy)));
        V polynomial = L::mul(L::dup(-0.9260540008544922f), t);
        polynomial = L::mul(L::add(polynomial, L::dup(3.1560099124908447f)), t);
        polynomial = L::mul(L::sub(polynomial, L::dup(3.6412200927734375f)), t);
        polynomial = L::mul(L::add(polynomial, L::dup(1.268030047416687f)), t);
        polynomial = L::mul(L::mul(L::add(polynomial, L::dup(0.2685309946537018f)), t), t);
        const V continuous = L::sub(L::add(length, one),
            L::div(one, L::sub(one, L::mul(polynomial, minOf(length, one)))));
        const V ax = maxOf(L::sub(L::mul(L::dup(kExpansion), ux), L::dup(kAdjustment)), zero);
        const V ay = maxOf(L::sub(L::mul(L::dup(kExpansion), uy), L::dup(kAdjustment)), zero);
        const V correction = L::add(L::sub(L::dup(0.3458344340324402f), continuous),
            L::mul(L::dup(0.6541655659675598f), L::sqrt(L::add(L::mul(ax, ax), L::mul(ay, ay)))));
        const V sign = L::select(L::lt(zx, zy), one, L::dup(-1.0f));
        const V weight = saturateLanes(L::add(L::mul(sign, L::sub(t, one)), L::dup(0.5f)));
        const V result = L::add(interior, L::mul(expanded, L::add(L::sub(continuous, one),
            L::mul(correction, L::add(curvatureX, L::mul(L::sub(curvatureY, curvatureX), weight))))));
        return anyCircular ? L::select(circular, round, result) : result;
    }

    void normal(V x, V y, V &outX, V &outY, bool oval = false) const {
        const V zero = L::dup(0.0f), one = L::dup(1.0f), epsilon = L::dup(0.000001f);
        const V lx = L::mul(L::sub(x, cx), inverseX);
        const V ly = L::mul(L::sub(y, cy), inverseY);
        const V qx = L::add(L::sub(L::abs(lx), halfWidth), baseRadius);
        const V qy = L::add(L::sub(L::abs(ly), halfHeight), baseRadius);
        V nx = maxOf(qx, zero), ny = maxOf(qy, zero);
        const V cornerLength = L::sqrt(L::add(L::mul(nx, nx), L::mul(ny, ny)));
        const M corner = L::gt(cornerLength, epsilon);
        const M horizontal = L::gt(qx, qy);
        nx = L::select(corner, L::div(nx, cornerLength), L::select(horizontal, one, zero));
        ny = L::select(corner, L::div(ny, cornerLength), L::select(horizontal, zero, one));
        nx = L::select(L::lt(lx, zero), L::neg(nx), nx);
        ny = L::select(L::lt(ly, zero), L::neg(ny), ny);
        if (oval) {
            V ox = lx, oy = L::div(L::mul(ly, halfWidth), maxOf(halfHeight, epsilon));
            const V ovalLength = L::sqrt(L::add(L::mul(ox, ox), L::mul(oy, oy)));
            const M valid = L::gt(ovalLength, epsilon);
            ox = L::select(valid, L::div(ox, ovalLength), nx);
            oy = L::select(valid, L::div(oy, ovalLength), ny);
            nx = L::add(nx, ox);
            ny = L::add(ny, oy);
        }
        const V length = L::sqrt(L::add(L::mul(nx, nx), L::mul(ny, ny)));
        const M degenerate = L::le(length, epsilon);
        outX = L::select(degenerate, zero, L::mul(L::div(nx, length), inverseX));
        outY = L::select(degenerate, zero, L::mul(L::div(ny, length), inverseY));
    }
};

#if defined(__aarch64__)
// Lane 0 holds [a], lane 1 holds [b].
inline FieldLanes<float32x2_t> fieldPair(const RoundedField &a, const RoundedField &b) {
    auto pair = [](float u, float v) {
        const float lanes[2] = {u, v};
        return vld1_f32(lanes);
    };
    FieldLanes<float32x2_t> f;
    f.cx = pair(a.cx, b.cx);
    f.cy = pair(a.cy, b.cy);
    f.radius = pair(a.radius, b.radius);
    f.inverseX = pair(a.inverseX, b.inverseX);
    f.inverseY = pair(a.inverseY, b.inverseY);
    f.halfWidth = pair(a.halfWidth, b.halfWidth);
    f.halfHeight = pair(a.halfHeight, b.halfHeight);
    f.expanded = pair(a.expanded, b.expanded);
    f.curvatureX = pair(a.curvatureX, b.curvatureX);
    f.curvatureY = pair(a.curvatureY, b.curvatureY);
    f.baseRadius = pair(a.baseRadius, b.baseRadius);
    const uint32_t flags[2] = {a.circular ? ~0u : 0u, b.circular ? ~0u : 0u};
    f.circular = vld1_u32(flags);
    f.anyCircular = a.circular || b.circular;
    f.allCircular = a.circular && b.circular;
    return f;
}

#endif

// All four lanes hold [a].
inline FieldLanes<Float4> fieldBroadcast(const RoundedField &a) {
    using L = Lanes<Float4>;
    FieldLanes<Float4> f;
    f.cx = L::dup(a.cx);
    f.cy = L::dup(a.cy);
    f.radius = L::dup(a.radius);
    f.inverseX = L::dup(a.inverseX);
    f.inverseY = L::dup(a.inverseY);
    f.halfWidth = L::dup(a.halfWidth);
    f.halfHeight = L::dup(a.halfHeight);
    f.expanded = L::dup(a.expanded);
    f.curvatureX = L::dup(a.curvatureX);
    f.curvatureY = L::dup(a.curvatureY);
    f.baseRadius = L::dup(a.baseRadius);
    f.circular = L::gt(L::dup(a.circular ? 1.0f : 0.0f), L::dup(0.0f));
    f.anyCircular = f.allCircular = a.circular;
    return f;
}

// Four adjacent texels repeat sample()'s arithmetic. Whole-group decisions avoid computing
// an unused shape's normal without changing the branch selected by any individual texel.
inline void sample4(const FieldLanes<Float4> &incoming, const FieldLanes<Float4> &outgoing,
                    bool hasOutgoing, float smoothing, Float4 x, Float4 y, float *output) {
    using L = Lanes<Float4>;
    using F = FieldLanes<Float4>;
    const Float4 zero = L::dup(0.0f), one = L::dup(1.0f), half = L::dup(0.5f);
    const Float4 a = incoming.distance(x, y);
    Float4 anx, any;
    if (!hasOutgoing) {
        incoming.normal(x, y, anx, any, true);
        store3(output, a, anx, any);
        return;
    }
    const Float4 b = outgoing.distance(x, y);
    const auto separated = L::le(L::dup(smoothing), L::abs(L::sub(a, b)));
    const auto nearestA = L::le(a, b);
    if (all4(separated)) {
        Float4 nx, ny;
        if (all4(nearestA)) {
            incoming.normal(x, y, nx, ny, true);
        } else if (all4(L::lt(b, a))) {
            outgoing.normal(x, y, nx, ny, true);
        } else {
            Float4 bnx, bny;
            incoming.normal(x, y, anx, any, true);
            outgoing.normal(x, y, bnx, bny, true);
            nx = L::select(nearestA, anx, bnx);
            ny = L::select(nearestA, any, bny);
        }
        store3(output, F::minOf(a, b), nx, ny);
        return;
    }
    Float4 bnx, bny;
    incoming.normal(x, y, anx, any, true);
    outgoing.normal(x, y, bnx, bny, true);
    const Float4 alignment = L::add(L::mul(anx, bnx), L::mul(any, bny));
    const Float4 radius = L::mul(L::dup(smoothing), F::saturateLanes(
        L::sub(half, L::mul(half, alignment))));
    const Float4 weight = L::select(L::le(radius, L::dup(0.000001f)),
        L::select(nearestA, zero, one),
        F::saturateLanes(L::add(half, L::div(L::mul(half, L::sub(a, b)), radius))));
    const Float4 d = L::sub(L::add(a, L::mul(L::sub(b, a), weight)),
        L::mul(L::mul(radius, weight), L::sub(one, weight)));
    const Float4 nx = L::add(anx, L::mul(L::sub(bnx, anx), weight));
    const Float4 ny = L::add(any, L::mul(L::sub(bny, any), weight));
    store3(output, L::select(separated, F::minOf(a, b), d),
        L::select(separated, L::select(nearestA, anx, bnx), nx),
        L::select(separated, L::select(nearestA, any, bny), ny));
}
#endif

Sample sample(const RoundedField &incoming, const RoundedField &outgoing, bool hasOutgoing, float smoothing,
              float x, float y) {
    const float a = incoming.distance(x, y);
    const float b = hasOutgoing ? outgoing.distance(x, y) : INFINITY;
    if (!hasOutgoing || std::abs(a - b) >= smoothing) {
        const Normal n = (!hasOutgoing || a <= b) ? incoming.normal(x, y) : outgoing.normal(x, y);
        return {std::min(a, b), n.x, n.y};
    }
    const Normal an = incoming.normal(x, y), bn = outgoing.normal(x, y);
    const float alignment = an.x * bn.x + an.y * bn.y;
    const float radius = smoothing * saturate(0.5f - 0.5f * alignment);
    const float weight =
        radius <= 0.000001f ? (a <= b ? 0.0f : 1.0f) : saturate(0.5f + 0.5f * (a - b) / radius);
    return {a + (b - a) * weight - radius * weight * (1.0f - weight), an.x + (bn.x - an.x) * weight,
            an.y + (bn.y - an.y) * weight};
}
struct Scratch {
    std::vector<float> raw, horizontal;
};
thread_local Scratch scratch;

// Match java.lang.Math's signed-zero ordering, including normal channels at exact edges.
inline float javaMin(float a, float b) {
    if (std::isnan(a)) return a;
    if (std::isnan(b)) return b;
    if (a == 0.0f && b == 0.0f) return std::signbit(a) ? a : b;
    return a < b ? a : b;
}
inline float javaMax(float a, float b) {
    if (std::isnan(a)) return a;
    if (std::isnan(b)) return b;
    if (a == 0.0f && b == 0.0f) return std::signbit(a) ? b : a;
    return a > b ? a : b;
}
} // namespace
extern "C" void liquid_menu_sdf_build(const float *geometry, float originX, float originY, float step,
                                      int width, int height, const float *weights, int radius, float *rgba) {
    const RoundedField incoming(geometry), outgoing(geometry + 6);
    const bool hasOutgoing = geometry[13] != 0.0f;
    const float smoothing = std::max(geometry[12], 0.0f);
    const int paddedWidth = width + 2 * radius, paddedHeight = height + 2 * radius;
    const size_t rawSize = static_cast<size_t>(paddedWidth) * paddedHeight * 3;
    const size_t horizontalSize = static_cast<size_t>(width) * paddedHeight * 3;
    if (scratch.raw.size() < rawSize)
        scratch.raw.resize(rawSize);
    if (scratch.horizontal.size() < horizontalSize)
        scratch.horizontal.resize(horizontalSize);
    float *raw = scratch.raw.data();
    float *horizontal = scratch.horizontal.data();
#if defined(__aarch64__) || defined(__SSE2__)
    const auto incoming4 = fieldBroadcast(incoming), outgoing4 = fieldBroadcast(outgoing);
#endif
    for (int row = 0; row < paddedHeight; row++) {
        const float y = originY + (row - radius) * step;
        int column = 0;
#if defined(__aarch64__) || defined(__SSE2__)
        for (; column + 4 <= paddedWidth; column += 4) {
            const float xs[4] = {originX + (column - radius) * step,
                originX + (column + 1 - radius) * step,
                originX + (column + 2 - radius) * step,
                originX + (column + 3 - radius) * step};
            sample4(incoming4, outgoing4, hasOutgoing, smoothing, load4(xs), Lanes<Float4>::dup(y),
                    raw + (static_cast<size_t>(row) * paddedWidth + column) * 3);
        }
#endif
        for (; column < paddedWidth; column++) {
            const Sample s =
                sample(incoming, outgoing, hasOutgoing, smoothing, originX + (column - radius) * step, y);
            const size_t output = (static_cast<size_t>(row) * paddedWidth + column) * 3;
            raw[output] = s.distance;
            raw[output + 1] = s.nx;
            raw[output + 2] = s.ny;
        }
    }
    for (int row = 0; row < paddedHeight; row++) {
        const float *source = raw + (static_cast<size_t>(row) * paddedWidth + radius) * 3;
        float *output = horizontal + static_cast<size_t>(row) * width * 3;
        int channel = 0;
#if defined(__aarch64__) || defined(__SSE2__)
        // Four independent channel sums preserve every tap's Float32 operation order.
        // Keep field channels interleaved, including partial four-pixel groups.
        for (; channel + 4 <= width * 3; channel += 4) {
            using L = Lanes<Float4>;
            Float4 value = L::mul(load4(source + channel), L::dup(weights[0]));
            for (int offset = 1; offset <= radius; offset++) {
                const Float4 left = load4(source + channel - offset * 3);
                const Float4 right = load4(source + channel + offset * 3);
                value = L::add(value, L::mul(L::add(left, right), L::dup(weights[offset])));
            }
            store4(output + channel, value);
        }
#endif
        for (; channel < width * 3; channel++) {
            float value = source[channel] * weights[0];
            for (int offset = 1; offset <= radius; offset++)
                value += (source[channel - offset * 3] + source[channel + offset * 3]) * weights[offset];
            output[channel] = value;
        }
    }
    for (int row = 0; row < height; row++) {
        int column = 0;
#if defined(__SSE2__)
        // After the horizontal pass the raw halo is dead. Reuse its first row for
        // interleaved vertical sums, keeping SSE loads contiguous and taps in order.
        const float *source = horizontal + static_cast<size_t>(row + radius) * width * 3;
        int channel = 0;
        for (; channel + 4 <= width * 3; channel += 4) {
            using L = Lanes<Float4>;
            Float4 value = L::mul(load4(source + channel), L::dup(weights[0]));
            for (int offset = 1; offset <= radius; ++offset) {
                const Float4 above = load4(source + channel - offset * width * 3);
                const Float4 below = load4(source + channel + offset * width * 3);
                value = L::add(value, L::mul(L::add(above, below), L::dup(weights[offset])));
            }
            store4(raw + channel, value);
        }
        for (; channel < width * 3; ++channel) {
            float value = source[channel] * weights[0];
            for (int offset = 1; offset <= radius; ++offset)
                value += (source[channel - offset * width * 3] + source[channel + offset * width * 3]) * weights[offset];
            raw[channel] = value;
        }
        for (; column < width; ++column) {
            const float nx = raw[column * 3 + 1], ny = raw[column * 3 + 2];
            const float length = std::sqrt(nx * nx + ny * ny);
            const size_t output = (static_cast<size_t>(row) * width + column) * 4;
            rgba[output] = raw[column * 3];
            rgba[output + 1] = length > 0.000001f ? nx / length : 0.0f;
            rgba[output + 2] = length > 0.000001f ? ny / length : 0.0f;
            rgba[output + 3] = 1.0f;
        }
#endif
#if defined(__aarch64__)
        // Resolve four pixels together with exact square roots and division, not
        // reciprocal estimates. This matches the portable normal at every lane.
        for (; column + 4 <= width; column += 4) {
            const size_t center = (static_cast<size_t>(row + radius) * width + column) * 3;
            const float32x4x3_t values = vld3q_f32(horizontal + center);
            float32x4_t d = vmulq_n_f32(values.val[0], weights[0]);
            float32x4_t nx = vmulq_n_f32(values.val[1], weights[0]);
            float32x4_t ny = vmulq_n_f32(values.val[2], weights[0]);
            for (int offset = 1; offset <= radius; offset++) {
                const float32x4x3_t above = vld3q_f32(horizontal + center - offset * width * 3);
                const float32x4x3_t below = vld3q_f32(horizontal + center + offset * width * 3);
                const float weight = weights[offset];
                d = vaddq_f32(d, vmulq_n_f32(vaddq_f32(above.val[0], below.val[0]), weight));
                nx = vaddq_f32(nx, vmulq_n_f32(vaddq_f32(above.val[1], below.val[1]), weight));
                ny = vaddq_f32(ny, vmulq_n_f32(vaddq_f32(above.val[2], below.val[2]), weight));
            }
            const float32x4_t length = vsqrtq_f32(vaddq_f32(vmulq_f32(nx, nx), vmulq_f32(ny, ny)));
            const uint32x4_t valid = vcgtq_f32(length, vdupq_n_f32(0.000001f));
            const float32x4_t safeLength = vbslq_f32(valid, length, vdupq_n_f32(1.0f));
            const float32x4x4_t result = {{d, vbslq_f32(valid, vdivq_f32(nx, safeLength), vdupq_n_f32(0.0f)),
                vbslq_f32(valid, vdivq_f32(ny, safeLength), vdupq_n_f32(0.0f)), vdupq_n_f32(1.0f)}};
            vst4q_f32(rgba + (static_cast<size_t>(row) * width + column) * 4, result);
        }
#endif
        for (; column < width; column++) {
            const size_t center = (static_cast<size_t>(row + radius) * width + column) * 3;
            float d = horizontal[center] * weights[0], nx = horizontal[center + 1] * weights[0],
                  ny = horizontal[center + 2] * weights[0];
            for (int offset = 1; offset <= radius; offset++) {
                const size_t above = center - offset * width * 3, below = center + offset * width * 3;
                const float weight = weights[offset];
                d += (horizontal[above] + horizontal[below]) * weight;
                nx += (horizontal[above + 1] + horizontal[below + 1]) * weight;
                ny += (horizontal[above + 2] + horizontal[below + 2]) * weight;
            }
            const float length = std::sqrt(nx * nx + ny * ny);
            const size_t output = (static_cast<size_t>(row) * width + column) * 4;
            rgba[output] = d;
            rgba[output + 1] = length > 0.000001f ? nx / length : 0.0f;
            rgba[output + 2] = length > 0.000001f ? ny / length : 0.0f;
            rgba[output + 3] = 1.0f;
        }
    }
}
extern "C" void liquid_menu_sdf_pack(const float *rgba, int width, int height, int rowBytes, void *target) {
    for (int row = 0; row < height; row++) {
        const float *source = rgba + static_cast<size_t>(row) * width * 4;
        auto *output = reinterpret_cast<uint16_t *>(static_cast<uint8_t *>(target) +
                                                    static_cast<size_t>(row) * rowBytes);
        for (int index = 0; index < width * 4; index += 4) {
#if defined(__aarch64__)
            vst1_u16(output + index, vreinterpret_u16_f16(vcvt_f16_f32(vld1q_f32(source + index))));
#else
            auto *half = reinterpret_cast<_Float16 *>(output + index);
            for (int channel = 0; channel < 4; channel++)
                half[channel] = static_cast<_Float16>(source[index + channel]);
#endif
        }
    }
}
extern "C" JNIEXPORT jboolean JNICALL Java_org_telegram_ui_Components_GlasmioMenuField_buildNative(
    JNIEnv *env, jobject, jfloatArray geometry, jfloat originX, jfloat originY, jfloat step, jint width,
    jint height, jfloatArray weights, jint radius, jfloatArray output) {
    if (width < 2 || height < 2 || radius < 1 || radius > 16 || step <= 0 ||
        env->GetArrayLength(geometry) < 14 || env->GetArrayLength(weights) <= radius ||
        static_cast<int64_t>(width) * height * 4 > env->GetArrayLength(output))
        return JNI_FALSE;
    float g[14], w[17];
    env->GetFloatArrayRegion(geometry, 0, 14, g);
    env->GetFloatArrayRegion(weights, 0, radius + 1, w);
    auto *rgba = static_cast<float *>(env->GetPrimitiveArrayCritical(output, nullptr));
    if (!rgba)
        return JNI_FALSE;
    liquid_menu_sdf_build(g, originX, originY, step, width, height, w, radius, rgba);
    env->ReleasePrimitiveArrayCritical(output, rgba, 0);
    return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_org_telegram_ui_Components_GlasmioMenuField_packNative(
    JNIEnv *env, jobject, jfloatArray source, jint width, jint height, jint rowBytes, jobject target) {
    if (width < 1 || height < 1 || rowBytes < static_cast<int64_t>(width) * 8 ||
        static_cast<int64_t>(width) * height * 4 > env->GetArrayLength(source) ||
        static_cast<int64_t>(rowBytes) * height > env->GetDirectBufferCapacity(target))
        return JNI_FALSE;
    void *output = env->GetDirectBufferAddress(target);
    if (!output)
        return JNI_FALSE;
    auto *rgba = static_cast<float *>(env->GetPrimitiveArrayCritical(source, nullptr));
    if (!rgba)
        return JNI_FALSE;
    liquid_menu_sdf_pack(rgba, width, height, rowBytes, output);
    env->ReleasePrimitiveArrayCritical(source, rgba, JNI_ABORT);
    return JNI_TRUE;
}
