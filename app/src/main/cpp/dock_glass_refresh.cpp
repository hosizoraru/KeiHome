// SPDX-License-Identifier: Apache-2.0
#include "dock_glass_refresh.h"
#include "launcher_label_profile.h"

#include <string.h>

namespace {
struct WordMask { uint32_t word, mask; };

// Complete 0x64-byte generic Rx.value. Its observer lookup is the source of
// observer_slot; its subscription subject must equal refresh's subject field.
constexpr WordMask kValue[] = {
    {0xa9bf79fdu, 0xffffffffu}, {0xaa0f03fdu, 0xffffffffu},
    {0xd10021efu, 0xffffffffu}, {0xaa0103e0u, 0xffffffffu},
    {0xf81f83a1u, 0xffffffffu}, {0xf9403f41u, 0xffffffffu},
    {0xf9400021u, 0xffc003ffu}, {0x6b16003fu, 0xffffffffu},
    {0x54000061u, 0xffffffffu}, {0xaa0003e1u, 0xffffffffu},
    {0x14000005u, 0xffffffffu}, {0xb8400002u, 0xffe00fffu},
    {0x8b1c8042u, 0xffffffffu}, {0x94000000u, 0xfc000000u},
    {0xf85f83a1u, 0xffffffffu}, {0xb8400020u, 0xffe00fffu},
    {0x8b1c8000u, 0xffffffffu}, {0xf9402370u, 0xffffffffu},
    {0x6b10001fu, 0xffffffffu}, {0x54000080u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu}, {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu}, {0xf9400369u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
};

// Entire getter: native frame/LR, cached path, fallback, field and return.
// The ready-cache path writes no x9 and has no call before +0x54. In particular,
// do not generalize these masks to arbitrary register/branch/frame variants.
constexpr WordMask kGetter[] = {
    {0xa9bf79fdu, 0xffffffffu}, {0xaa0f03fdu, 0xffffffffu},
    {0xd10021efu, 0xffffffffu}, {0xf9403f40u, 0xffffffffu},
    {0xf9400000u, 0xffc003ffu}, {0x6b16001fu, 0xffffffffu},
    {0x540001a1u, 0xffffffffu}, {0xf9403f40u, 0xffffffffu},
    {0xf9400000u, 0xffc003ffu}, {0xf9402370u, 0xffffffffu},
    {0x6b10001fu, 0xffffffffu}, {0x54000061u, 0xffffffffu},
    {0xf9400362u, 0xffc003ffu}, {0x94000000u, 0xfc000000u},
    {0x91400370u, 0xffc003ffu}, {0xf9400210u, 0xffc003ffu},
    {0xf90001f0u, 0xffffffffu}, {0xf9400364u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u}, {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu}, {0x94000000u, 0xfc000000u},
    {0xaa1d03efu, 0xffffffffu}, {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};

// Exact 0x38-byte refresh frame/root/return shape. Both BL targets are linked
// below. RxList/Map/Set refresh copies are excluded by the value target.
constexpr WordMask kRefresh[] = {
    {0xa9bf79fdu, 0xffffffffu}, {0xaa0f03fdu, 0xffffffffu},
    {0xd10021efu, 0xffffffffu}, {0xb8400020u, 0xffe00fffu},
    {0x8b1c8000u, 0xffffffffu}, {0xf81f83a0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u}, {0xf85f83a1u, 0xffffffffu},
    {0xaa0003e2u, 0xffffffffu}, {0x94000000u, 0xfc000000u},
    {0xaa1603e0u, 0xffffffffu}, {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu}, {0xd65f03c0u, 0xffffffffu},
};
static_assert(sizeof(kRefresh) / sizeof(kRefresh[0]) * 4 == 0x38);

// Complete GetStream.add: preserves subject/data in native roots, invokes an
// optional native onData callback, then forwards both to _notifyData.
constexpr WordMask kStreamAdd[] = {
    {0xa9bf79fdu, 0xffffffffu}, {0xaa0f03fdu, 0xffffffffu},
    {0xd10041efu, 0xffffffffu}, {0xaa0103e4u, 0xffffffffu},
    {0xaa0203e3u, 0xffffffffu}, {0xf81f83a1u, 0xffffffffu},
    {0xf81f03a2u, 0xffffffffu}, {0xb8407082u, 0xffffffffu},
    {0x8b1c8042u, 0xffffffffu}, {0xaa0303e0u, 0xffffffffu},
    {0xaa1603e1u, 0xffffffffu}, {0x6b16005fu, 0xffffffffu},
    {0x540000e0u, 0xffffffffu}, {0xb8417044u, 0xffffffffu},
    {0x8b1c8084u, 0xffffffffu}, {0xf9400368u, 0xffc003ffu},
    {0xf8407089u, 0xffffffffu}, {0xf9400363u, 0xffc003ffu},
    {0xd63f0120u, 0xffffffffu}, {0xf85f83a1u, 0xffffffffu},
    {0xf85f03a2u, 0xffffffffu}, {0x94000000u, 0xfc000000u},
    {0xaa1603e0u, 0xffffffffu}, {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu}, {0xd65f03c0u, 0xffffffffu},
};

// Linked _notifyData prefix: native stream's notifying flag, subscription list,
// null/list-length checks. Prevent accepting add redirected to an unrelated
// framed function, including the generic value getter itself.
constexpr WordMask kNotifyData[] = {
    {0xa9bf79fdu, 0xffffffffu}, {0xaa0f03fdu, 0xffffffffu},
    {0xd10121efu, 0xffffffffu}, {0x910082c0u, 0xffffffffu},
    {0xaa0103e4u, 0xffffffffu}, {0xaa0203e3u, 0xffffffffu},
    {0xf81d03a1u, 0xffffffffu}, {0xf81c83a2u, 0xffffffffu},
    {0xb801f080u, 0xffffffffu}, {0xb841b085u, 0xffffffffu},
    {0x8b1c80a5u, 0xffffffffu}, {0xf81d83a5u, 0xffffffffu},
    {0x6b1600bfu, 0xffffffffu}, {0x54000800u, 0xffffffffu},
    {0xb840b0a0u, 0xffffffffu}, {0x93417c06u, 0xffffffffu},
};

constexpr WordMask kLogger[] = {
    {0xa9bf79fdu, 0xffffffffu}, {0xaa0f03fdu, 0xffffffffu},
    {0xaa0203e5u, 0xffffffffu}, {0xb8413080u, 0xffffffffu},
    {0xd1001002u, 0xffffffffu}, {0x7100085fu, 0xffffffffu},
    {0x540000abu, 0xffffffffu}, {0x8b22cba0u, 0xffffffffu},
    {0xf9400000u, 0xffc003ffu}, {0xaa0003e6u, 0xffffffffu},
    {0x14000002u, 0xffffffffu}, {0xaa1603e6u, 0xffffffffu},
    {0xf9400362u, 0xffc003ffu}, {0xaa1603e3u, 0xffffffffu},
    {0xaa1603e7u, 0xffffffffu}, {0x94000000u, 0xfc000000u},
    {0xaa1603e0u, 0xffffffffu}, {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu}, {0xd65f03c0u, 0xffffffffu},
};

bool Reject(const char** why, const char* text) {
    if (why) *why = text;
    return false;
}
bool Contains(const IconHighlightImage& im, size_t at, size_t n) {
    if (at > im.size || n > im.size - at || (at & 3u)) return false;
    for (size_t i = 0; i < im.range_count; ++i) {
        const auto& r = im.ranges[i];
        if (r.executable && at >= r.begin && at <= r.end && n <= r.end - at)
            return true;
    }
    return false;
}
uint32_t Word(const IconHighlightImage& im, size_t at) {
    uint32_t w = 0;
    if (Contains(im, at, 4)) memcpy(&w, im.base + at, 4);
    return w;
}
size_t Call(const IconHighlightImage& im, size_t at) {
    const uint32_t w = Word(im, at);
    if ((w & 0xfc000000u) != 0x94000000u) return 0;
    const int64_t n = w & 0x3ffffffu;
    const int64_t target = int64_t(at) + (n - (n & 0x2000000 ? 0x4000000 : 0)) * 4;
    return target > 0 && Contains(im, size_t(target), 4) ? size_t(target) : 0;
}
template<size_t N>
bool Matches(const IconHighlightImage& im, size_t at, const WordMask (&pattern)[N]) {
    if (!at || !Contains(im, at, N * 4)) return false;
    for (size_t i = 0; i < N; ++i) {
        if ((Word(im, at + i * 4) & pattern[i].mask) != pattern[i].word) return false;
    }
    return true;
}
uint32_t Slot(uint32_t w) { return ((w >> 10) & 4095u) * 8; }
int32_t Field(uint32_t w) {
    const uint32_t n = (w >> 12) & 511u;
    return int32_t(n) - (n & 256u ? 512 : 0);
}
bool ObjectField(int32_t field) { return field > 0 && (field & 3) == 3; }
bool DiscardPrefix(const IconHighlightImage& im, size_t call) {
    return (Word(im, call + 4) & 0xffe0001fu) == 0x92800011u &&
            Word(im, call + 8) == 0xf8716ba2u;
}
} // namespace

bool ResolveDockGlassRefresh(const IconHighlightImage& im,
                            const LauncherLabelProfile& labels,
                            DockGlassRefreshProfile* out, const char** why) {
    if (why) *why = nullptr;
    if (!out) return Reject(why, "missing Dock refresh output");
    *out = {};
    if (!ValidateArm64DartImage(im)) return Reject(why, "invalid Dock refresh image");
    if (!Matches(im, labels.config, kGetter) || !Matches(im, labels.value, kValue) ||
            Call(im, labels.config + 0x54) != labels.value)
        return Reject(why, "Dock getter/value native frame or call graph changed");
    const int32_t config_field = Field(Word(im, labels.config + 0x4c));
    const int32_t value_field = Field(Word(im, labels.value + 0x3c));
    const int32_t subject_field = Field(Word(im, labels.value + 0x2c));
    const uint32_t observer_slot = Slot(Word(im, labels.value + 0x18));
    if (!ObjectField(config_field) || !ObjectField(value_field) ||
            !ObjectField(subject_field) || !observer_slot ||
            Slot(Word(im, labels.config + 0x10)) != labels.grid_slot ||
            !labels.grid_slot || uint32_t(config_field) != labels.config_rx_field ||
            uint32_t(value_field) != labels.rx_value_field ||
            !Call(im, labels.value + 0x34) || !Call(im, labels.value + 0x60))
        return Reject(why, "Dock Rx owner/observer field linkage changed");
    if (!Contains(im, labels.change_event, 0x12c) ||
            Word(im, labels.change_event) != 0xa9bf79fdu ||
            Word(im, labels.change_event + 4) != 0xaa0f03fdu ||
            !Matches(im, labels.refresh_logger, kLogger))
        return Reject(why, "Dock item-event/logger role changed");

    DockGlassRefreshProfile p{};
    size_t calls = 0;
    for (size_t at = labels.change_event + 0x30; at < labels.change_event + 0x120; at += 4) {
        const size_t target = Call(im, at);
        if (!Matches(im, target, kLogger)) continue;
        if (target != labels.refresh_logger || !DiscardPrefix(im, at))
            return Reject(why, "Dock item-event logger target/result changed");
        if (!calls && at != labels.refresh_call)
            return Reject(why, "Dock first logger no longer label-owned");
        if (++calls == 2) {
            p.second_event_call = at;
            p.second_event_target = target;
            p.second_event_expected = Word(im, at);
        }
    }
    if (calls != 2 || !p.second_event_call ||
            Word(im, p.second_event_call + 12) != 0xb840f040u ||
            Word(im, p.second_event_call + 16) != 0x8b1c8000u)
        return Reject(why, "Dock second discarded-result logger is ambiguous");

    for (size_t i = 0; i < im.range_count; ++i) {
        const auto& r = im.ranges[i];
        if (!r.executable || r.end - r.begin < 0x38) continue;
        for (size_t at = r.begin; at <= r.end - 0x38; at += 4) {
            if (Word(im, at) != kRefresh[0].word || !Matches(im, at, kRefresh) ||
                    Call(im, at + 0x18) != labels.value) continue;
            const size_t add = Call(im, at + 0x24);
            if (Field(Word(im, at + 0xc)) != subject_field ||
                    !Matches(im, add, kStreamAdd) ||
                    !Matches(im, Call(im, add + 0x54), kNotifyData))
                return Reject(why, "Dock generic Rx.refresh subject/stream graph changed");
            if (p.refresh) return Reject(why, "ambiguous Dock generic Rx.refresh");
            p.refresh = at;
            p.stream_add = add;
        }
    }
    if (!p.refresh) return Reject(why, "missing Dock generic Rx.refresh");
    p.getter_value_call = labels.config + 0x54;
    p.getter_value_expected = Word(im, p.getter_value_call);
    p.rx_subject_field = uint32_t(subject_field);
    p.observer_slot = observer_slot;
    *out = p;
    return true;
}
