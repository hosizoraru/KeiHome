// SPDX-License-Identifier: Apache-2.0
#include "launcher_glass_material_profile.h"

#include <string.h>

namespace {
struct WordMask { uint32_t word, mask; };
// Reviewed source shapes; PC/data operands are validated by linked roles below.
constexpr WordMask kBuild[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10041efu, 0xffffffffu},
    {0xf81f83a1u, 0xffffffffu},
    {0xd2800021u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xf81f03a1u, 0xffffffffu},
    {0xb800f020u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f03a2u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xf81f83a0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xb800b001u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};
constexpr WordMask kClosure[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10061efu, 0xffffffffu},
    {0xf9400ba0u, 0xffffffffu},
    {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0xf81f83a1u, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0x6b16001fu, 0xffffffffu},
    {0x54000001u, 0xff00001fu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000001u, 0xff00001fu},
    {0xf9400362u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0x91400370u, 0xffc003ffu},
    {0xf9400210u, 0xffc003ffu},
    {0xf90001f0u, 0xffffffffu},
    {0xf9400364u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0x37200000u, 0xfff8001fu},
    {0xf85f83a0u, 0xffffffffu},
    {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0xb8400020u, 0xffe00fffu},
    {0x8b1c8000u, 0xffffffffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000001u, 0xff00001fu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0x14000000u, 0xfc000000u},
    {0xf85f83a0u, 0xffffffffu},
    {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0xb8400020u, 0xffe00fffu},
    {0x8b1c8000u, 0xffffffffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000001u, 0xff00001fu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xf81f03a1u, 0xffffffffu},
    {0xb8400002u, 0xffe00fffu},
    {0x8b1c8042u, 0xffffffffu},
    {0xb8400040u, 0xffe00fffu},
    {0x8b1c8000u, 0xffffffffu},
    {0x6b16001fu, 0xffffffffu},
    {0x54000000u, 0xff00001fu},
    {0xb8400002u, 0xffe00fffu},
    {0x8b1c8042u, 0xffffffffu},
    {0xf81f83a2u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f03a1u, 0xffffffffu},
    {0xb800f001u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb8013001u, 0xffffffffu},
    {0x910082c1u, 0xffffffffu},
    {0xb8017001u, 0xffffffffu},
    {0x9100c2c1u, 0xffffffffu},
    {0xb801f001u, 0xffffffffu},
    {0xf85f83a1u, 0xffffffffu},
    {0xb800b001u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
};
constexpr WordMask kSetterOld[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10061efu, 0xffffffffu},
    {0xaa0103e0u, 0xffffffffu},
    {0xf81f03a1u, 0xffffffffu},
    {0xf81e83a2u, 0xffffffffu},
    {0xb8400003u, 0xffe00fffu},
    {0x8b1c8063u, 0xffffffffu},
    {0xaa0303e1u, 0xffffffffu},
    {0xf81f83a3u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85e83a0u, 0xffffffffu},
    {0x6b01001fu, 0xffffffffu},
    {0x54000000u, 0xff00001fu},
    {0x0a010010u, 0xffffffffu},
    {0x36000010u, 0xfff8001fu},
    {0xf85ff010u, 0xffffffffu},
    {0xd34c7e10u, 0xffffffffu},
    {0xf100f61fu, 0xffffffffu},
    {0x54000001u, 0xff00001fu},
    {0xf85ff030u, 0xffffffffu},
    {0xd34c7e10u, 0xffffffffu},
    {0xf100f61fu, 0xffffffffu},
    {0x54000001u, 0xff00001fu},
    {0xf8407010u, 0xffffffffu},
    {0xf8407031u, 0xffffffffu},
    {0xeb11021fu, 0xffffffffu},
    {0x54000000u, 0xff00001fu},
    {0xf85f03a3u, 0xffffffffu},
    {0xf85f83a1u, 0xffffffffu},
    {0xaa0003e2u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f03a0u, 0xffffffffu},
    {0xb8400003u, 0xffe00fffu},
    {0x8b1c8063u, 0xffffffffu},
    {0xf85e83a0u, 0xffffffffu},
    {0xf81f83a3u, 0xffffffffu},
    {0x7100101fu, 0xffffffffu},
    {0x910082d0u, 0xffffffffu},
    {0x9100c2d1u, 0xffffffffu},
    {0x9a910202u, 0xffffffffu},
    {0xaa0303e1u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f83a1u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf9400341u, 0xffc003ffu},
    {0xf9000020u, 0xffc003ffu},
    {0xaa1603e0u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};
constexpr WordMask kSetterNew[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10061efu, 0xffffffffu},
    {0xaa0103e0u, 0xffffffffu},
    {0xf81f03a1u, 0xffffffffu},
    {0xf81e83a2u, 0xffffffffu},
    {0xb8400003u, 0xffe00fffu},
    {0x8b1c8063u, 0xffffffffu},
    {0xaa0303e1u, 0xffffffffu},
    {0xf81f83a3u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85e83a0u, 0xffffffffu},
    {0x6b01001fu, 0xffffffffu},
    {0x54000000u, 0xff00001fu},
    {0x0a010010u, 0xffffffffu},
    {0x36000010u, 0xfff8001fu},
    {0xf85ff010u, 0xffffffffu},
    {0xd34c7e10u, 0xffffffffu},
    {0xf100f61fu, 0xffffffffu},
    {0x54000001u, 0xff00001fu},
    {0xf85ff030u, 0xffffffffu},
    {0xd34c7e10u, 0xffffffffu},
    {0xf100f61fu, 0xffffffffu},
    {0x54000001u, 0xff00001fu},
    {0xf8407010u, 0xffffffffu},
    {0xf8407031u, 0xffffffffu},
    {0xeb11021fu, 0xffffffffu},
    {0x54000000u, 0xff00001fu},
    {0xf85f03a3u, 0xffffffffu},
    {0xf85f83a1u, 0xffffffffu},
    {0xaa0003e2u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f03a0u, 0xffffffffu},
    {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0xf85e83a0u, 0xffffffffu},
    {0x7100101fu, 0xffffffffu},
    {0x910082d0u, 0xffffffffu},
    {0x9100c2d1u, 0xffffffffu},
    {0x9a910202u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa1603e0u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};

constexpr WordMask kCache[] = {
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0x6b16001fu, 0xffffffffu},
    {0x54000001u, 0xff00001fu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000001u, 0xff00001fu},
    {0xf9400362u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0x91400370u, 0xffc003ffu},
    {0xf9400210u, 0xffc003ffu},
    {0xf90001f0u, 0xffffffffu},
    {0xf9400364u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
};

constexpr WordMask kStreamClose[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10021efu, 0xffffffffu},
    {0xaa0103e0u, 0xffffffffu},
    {0xf81f83a1u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f83a1u, 0xffffffffu},
    {0xb801b036u, 0xffffffffu},
    {0xb801f036u, 0xffffffffu},
    {0xaa1603e0u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};
constexpr WordMask kNotifyDone[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd100a1efu, 0xffffffffu},
    {0x910082c0u, 0xffffffffu},
    {0xf81e03a1u, 0xffffffffu},
    {0xb801f020u, 0xffffffffu},
    {0xb8400022u, 0xffe00fffu},
    {0x8b1c8042u, 0xffffffffu},
    {0xf81e83a2u, 0xffffffffu},
    {0x6b16005fu, 0xffffffffu},
    {0x54000000u, 0xff00001fu},
    {0xb8400040u, 0xffe00fffu},
    {0x93417c03u, 0xffffffffu},
    {0xf81f03a3u, 0xffffffffu},
    {0xd2800000u, 0xffffffffu},
    {0xb8400044u, 0xffe00fffu},
};

#ifndef __ANDROID__
// Saved-source research only; never admitted or scanned by the Android runtime.
constexpr WordMask kExtraFolderOld[] = {
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0x6b16001fu, 0xffffffffu},
    {0x540001a1u, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000061u, 0xffffffffu},
    {0xf9400362u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0x91400370u, 0xffc003ffu},
    {0xf9400210u, 0xffc003ffu},
    {0xf90001f0u, 0xffffffffu},
    {0xf9400364u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0x372001e0u, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000081u, 0xffffffffu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0x1400000eu, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000081u, 0xffffffffu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xf81e83a1u, 0xffffffffu},
    {0xb100041fu, 0xffffffffu},
    {0x54000061u, 0xffffffffu},
    {0xd2800002u, 0xffffffffu},
    {0x14000002u, 0xffffffffu},
    {0xd2800022u, 0xffffffffu},
    {0xfc5e03a0u, 0xffffffffu},
    {0xf81f03a2u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85e83a1u, 0xffffffffu},
    {0xb800b001u, 0xffffffffu},
    {0xf801701fu, 0xffffffffu},
    {0xf85f83a1u, 0xffffffffu},
    {0xf801f001u, 0xffffffffu},
    {0xd2800021u, 0xffffffffu},
    {0xf8027001u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb802f001u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb8033001u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb8037001u, 0xffffffffu},
    {0xfc5e03a0u, 0xffffffffu},
    {0xfc03f000u, 0xffffffffu},
    {0x910082c1u, 0xffffffffu},
    {0xb8047001u, 0xffffffffu},
    {0x9100c2c1u, 0xffffffffu},
    {0xb804b001u, 0xffffffffu},
    {0x1e6e1000u, 0xffffffffu},
    {0xfc04f000u, 0xffffffffu},
    {0xf805701fu, 0xffffffffu},
    {0x1e639001u, 0xffffffffu},
    {0xfc05f001u, 0xffffffffu},
    {0xfc067000u, 0xffffffffu},
    {0xd2800781u, 0xffffffffu},
    {0xf806f001u, 0xffffffffu},
    {0xf85f03a1u, 0xffffffffu},
    {0xf8077001u, 0xffffffffu},
    {0xf807f01fu, 0xffffffffu},
    {0xd2801fe1u, 0xffffffffu},
    {0xf8087001u, 0xffffffffu},
    {0xf808f01fu, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};
#endif

constexpr WordMask kExtraSearchCapsule[] = {
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0x6b16001fu, 0xffffffffu},
    {0x540001a1u, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000061u, 0xffffffffu},
    {0xf9400362u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0x91400370u, 0xffc003ffu},
    {0xf9400210u, 0xffc003ffu},
    {0xf90001f0u, 0xffffffffu},
    {0xf9400364u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0x37200160u, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000081u, 0xffffffffu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e3u, 0xffffffffu},
    {0x1400000au, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000081u, 0xffffffffu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e3u, 0xffffffffu},
    {0xf85f83a2u, 0xffffffffu},
    {0xf85f03a1u, 0xffffffffu},
    {0xf85e03a0u, 0xffffffffu},
    {0x6e201c00u, 0xffffffffu},
    {0xf81d83a3u, 0xffffffffu},
    {0xfc407021u, 0xffffffffu},
    {0xfc1d03a1u, 0xffffffffu},
    {0x1e602020u, 0xffffffffu},
    {0x910082d0u, 0xffffffffu},
    {0x9100c2d1u, 0xffffffffu},
    {0x9a91c201u, 0xffffffffu},
    {0xf81e83a1u, 0xffffffffu},
    {0xb8400044u, 0xffe00fffu},
    {0x8b1c8084u, 0xffffffffu},
    {0xf81f03a4u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85d83a0u, 0xffffffffu},
    {0xf81f83a1u, 0xffffffffu},
    {0xb800b020u, 0xffffffffu},
    {0xf801703fu, 0xffffffffu},
    {0xf801f03fu, 0xffffffffu},
    {0xd2800020u, 0xffffffffu},
    {0xf8027020u, 0xffffffffu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0xb802f022u, 0xffffffffu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0xb8033022u, 0xffffffffu},
    {0xf85f03a2u, 0xffffffffu},
    {0xb8037022u, 0xffffffffu},
    {0xfc5d03a0u, 0xffffffffu},
    {0xfc03f020u, 0xffffffffu},
    {0x9100c2c2u, 0xffffffffu},
    {0xb8047022u, 0xffffffffu},
    {0xf85e83a3u, 0xffffffffu},
    {0xb804b023u, 0xffffffffu},
    {0x1e6e1000u, 0xffffffffu},
    {0xfc04f020u, 0xffffffffu},
    {0xf8057020u, 0xffffffffu},
    {0x1e611001u, 0xffffffffu},
    {0xfc05f021u, 0xffffffffu},
    {0xfc067020u, 0xffffffffu},
    {0xd2800783u, 0xffffffffu},
    {0xf806f023u, 0xffffffffu},
    {0xf8077020u, 0xffffffffu},
    {0xf807f03fu, 0xffffffffu},
    {0xd2801fe0u, 0xffffffffu},
    {0xf8087020u, 0xffffffffu},
    {0xf808f03fu, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85e03a1u, 0xffffffffu},
    {0xb800f001u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb8017001u, 0xffffffffu},
    {0x9100c2c1u, 0xffffffffu},
    {0xb801b001u, 0xffffffffu},
    {0xf85f83a1u, 0xffffffffu},
    {0xb800b001u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
    {0xa9bf79fdu, 0xffffffffu},
};

#ifndef __ANDROID__
// Saved-source research only; never admitted or scanned by the Android runtime.
constexpr WordMask kExtraPressLight[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10121efu, 0xffffffffu},
    {0xf81b83a2u, 0xffffffffu},
    {0xb8400020u, 0xffe00fffu},
    {0x8b1c8000u, 0xffffffffu},
    {0xf81c03a0u, 0xffffffffu},
    {0xb8400023u, 0xffe00fffu},
    {0x8b1c8063u, 0xffffffffu},
    {0xf81c83a3u, 0xffffffffu},
    {0xb8400024u, 0xffe00fffu},
    {0x8b1c8084u, 0xffffffffu},
    {0xf81d03a4u, 0xffffffffu},
    {0xb8400025u, 0xffe00fffu},
    {0x8b1c80a5u, 0xffffffffu},
    {0xf81d83a5u, 0xffffffffu},
    {0xb8400026u, 0xffe00fffu},
    {0x8b1c80c6u, 0xffffffffu},
    {0xf81e03a6u, 0xffffffffu},
    {0xb8400027u, 0xffe00fffu},
    {0x8b1c80e7u, 0xffffffffu},
    {0xf81e83a7u, 0xffffffffu},
    {0xb8400028u, 0xffe00fffu},
    {0x8b1c8108u, 0xffffffffu},
    {0xf81f03a8u, 0xffffffffu},
    {0xb8400029u, 0xffe00fffu},
    {0x8b1c8129u, 0xffffffffu},
    {0xf81f83a9u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85c03a1u, 0xffffffffu},
    {0xb8007001u, 0xffffffffu},
    {0xf85c83a1u, 0xffffffffu},
    {0xb800b001u, 0xffffffffu},
    {0xf85d03a1u, 0xffffffffu},
    {0xb800f001u, 0xffffffffu},
    {0xf85d83a1u, 0xffffffffu},
    {0xb8013001u, 0xffffffffu},
    {0xf85e03a1u, 0xffffffffu},
    {0xb8017001u, 0xffffffffu},
    {0xf85e83a1u, 0xffffffffu},
    {0xb801b001u, 0xffffffffu},
    {0xf85f03a1u, 0xffffffffu},
    {0xb801f001u, 0xffffffffu},
    {0xf85b83a1u, 0xffffffffu},
    {0xb8023001u, 0xffffffffu},
    {0xf85f83a1u, 0xffffffffu},
    {0xb8027001u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};
#endif

constexpr WordMask kExtraWidgetAllocator[] = {
    {0xd2814382u, 0xffe1ffffu},
    {0xf2a00002u, 0xffe0001fu},
    {0x14000000u, 0xfc000000u},
    {0x360000e0u, 0xffffffffu},
    {0xf85ff009u, 0xffffffffu},
    {0xd34c7d29u, 0xffffffffu},
    {0xd2800011u, 0xffe0001fu},
    {0xeb11013fu, 0xffffffffu},
    {0x54000041u, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
    {0xf9400350u, 0xffc003ffu},
    {0xd61f0200u, 0xffffffffu},
};
#ifndef __ANDROID__
// Saved-source research only; never admitted or scanned by the Android runtime.
constexpr WordMask kExtraFolderNew[] = {
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0x6b16001fu, 0xffffffffu},
    {0x540001a1u, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000061u, 0xffffffffu},
    {0xf9400362u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0x91400370u, 0xffc003ffu},
    {0xf9400210u, 0xffc003ffu},
    {0xf90001f0u, 0xffffffffu},
    {0xf9400364u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0x37200220u, 0xffffffffu},
    {0x14000002u, 0xffffffffu},
    {0x372001e0u, 0xffffffe0u},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000081u, 0xffffffffu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0x1400000eu, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000081u, 0xffffffffu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xf81e83a1u, 0xffffffffu},
    {0xb100041fu, 0xffffffffu},
    {0x54000061u, 0xffffffffu},
    {0xd2800002u, 0xffffffffu},
    {0x14000002u, 0xffffffffu},
    {0xd2800022u, 0xffffffffu},
    {0xfc5e03a0u, 0xffffffffu},
    {0xf81f03a2u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85e83a1u, 0xffffffffu},
    {0xb800b001u, 0xffffffffu},
    {0xf801701fu, 0xffffffffu},
    {0xf85f83a1u, 0xffffffffu},
    {0xf801f001u, 0xffffffffu},
    {0xd2800021u, 0xffffffffu},
    {0xf8027001u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb802f001u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb8033001u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb8037001u, 0xffffffffu},
    {0xfc5e03a0u, 0xffffffffu},
    {0xfc03f000u, 0xffffffffu},
    {0x910082c1u, 0xffffffffu},
    {0xb8047001u, 0xffffffffu},
    {0x9100c2c1u, 0xffffffffu},
    {0xb804b001u, 0xffffffffu},
    {0x1e6e1000u, 0xffffffffu},
    {0xfc04f000u, 0xffffffffu},
    {0xf805701fu, 0xffffffffu},
    {0x1e639001u, 0xffffffffu},
    {0xfc05f001u, 0xffffffffu},
    {0xfc067000u, 0xffffffffu},
    {0xd2800781u, 0xffffffffu},
    {0xf806f001u, 0xffffffffu},
    {0xf85f03a1u, 0xffffffffu},
    {0xf8077001u, 0xffffffffu},
    {0xf807f01fu, 0xffffffffu},
    {0xd2801fe1u, 0xffffffffu},
    {0xf8087001u, 0xffffffffu},
    {0xf808f01fu, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};

constexpr WordMask kExtraFolderDefaultColors[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10021efu, 0xffffffffu},
    {0x37200361u, 0xffffffffu},
    {0xd28000c0u, 0xffffffffu},
    {0xaa0003e2u, 0xffffffffu},
    {0xaa1603e1u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf81f83a0u, 0xffffffffu},
    {0x91400370u, 0xffc003ffu},
    {0xf9400210u, 0xffc003ffu},
    {0xb800f010u, 0xffffffffu},
    {0x91400370u, 0xffc003ffu},
    {0xf9400210u, 0xffc003ffu},
    {0xb8013010u, 0xffffffffu},
    {0x91400370u, 0xffc003ffu},
    {0xf9400210u, 0xffc003ffu},
    {0xb8017010u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xb800f020u, 0xffffffffu},
    {0xd28000c0u, 0xffffffffu},
    {0xb800b020u, 0xffffffffu},
    {0xaa0103e0u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
    {0xd28000c0u, 0xffffffffu},
    {0xaa0003e2u, 0xffffffffu},
    {0xaa1603e1u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf81f83a0u, 0xffffffffu},
    {0x91400370u, 0xffc003ffu},
    {0xf9400210u, 0xffc003ffu},
    {0xb800f010u, 0xffffffffu},
    {0x91400370u, 0xffc003ffu},
    {0xf9400210u, 0xffc003ffu},
    {0xb8013010u, 0xffffffffu},
    {0x91400370u, 0xffc003ffu},
    {0xf9400210u, 0xffc003ffu},
    {0xb8017010u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f83a1u, 0xffffffffu},
    {0xb800f001u, 0xffffffffu},
    {0xd28000c1u, 0xffffffffu},
    {0xb800b001u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};

constexpr WordMask kExtraFolderDarkConfig[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10061efu, 0xffffffffu},
    {0xb8400080u, 0xffe00fffu},
    {0x7100081fu, 0xffffffffu},
    {0x540000abu, 0xffffffffu},
    {0x8b20cba1u, 0xffffffffu},
    {0xf9400021u, 0xffc003ffu},
    {0xaa0103e0u, 0xffffffffu},
    {0x14000002u, 0xffffffffu},
    {0xaa1603e0u, 0xffffffffu},
    {0xf90001e0u, 0xffffffffu},
    {0x91400371u, 0xffc003ffu},
    {0xfd400220u, 0xffc003ffu},
    {0xf9400364u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0x910082c1u, 0xffffffffu},
    {0xfc1f03a0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf81f83a0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xfc5f03a0u, 0xffffffffu},
    {0xfc007000u, 0xffffffffu},
    {0xfc00f000u, 0xffffffffu},
    {0x910082c1u, 0xffffffffu},
    {0xb801b001u, 0xffffffffu},
    {0xf801f01fu, 0xffffffffu},
    {0xf85f83a1u, 0xffffffffu},
    {0xb8017001u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};

constexpr WordMask kExtraFolderLightConfig[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10061efu, 0xffffffffu},
    {0xb8400080u, 0xffe00fffu},
    {0x7100081fu, 0xffffffffu},
    {0x540000abu, 0xffffffffu},
    {0x8b20cba1u, 0xffffffffu},
    {0xf9400021u, 0xffc003ffu},
    {0xaa0103e0u, 0xffffffffu},
    {0x14000002u, 0xffffffffu},
    {0xaa1603e0u, 0xffffffffu},
    {0xf90001e0u, 0xffffffffu},
    {0x91400371u, 0xffc003ffu},
    {0xfd400220u, 0xffc003ffu},
    {0xf9400364u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0x9100c2c1u, 0xffffffffu},
    {0xfc1f03a0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf81f83a0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xfc5f03a0u, 0xffffffffu},
    {0xfc007000u, 0xffffffffu},
    {0xfc00f000u, 0xffffffffu},
    {0x910082c1u, 0xffffffffu},
    {0xb801b001u, 0xffffffffu},
    {0xf801f01fu, 0xffffffffu},
    {0xf85f83a1u, 0xffffffffu},
    {0xb8017001u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};

#endif

bool Reject(const char** why, const char* message) {
    if (why) *why = message;
    return false;
}
// Native source roles: register/field flow and finite allocations.
#ifndef __ANDROID__
// Saved-source research only; never admitted or scanned by the Android runtime.
constexpr WordMask kNativeStyleFolderInit[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd100a1efu, 0xffffffffu},
    {0xf81f83a1u, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0x6b16001fu, 0xffffffffu},
    {0x540001e1u, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000061u, 0xffffffffu},
    {0xf9400362u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0x91400370u, 0xffc003ffu},
    {0xf9400210u, 0xffc003ffu},
    {0xf90001f0u, 0xffffffffu},
    {0xf9400364u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0x14000002u, 0xffffffffu},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xd2802471u, 0xffffffffu},
    {0xb8716822u, 0xffffffffu},
    {0x8b1c8042u, 0xffffffffu},
    {0xaa0203e1u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f83a2u, 0xffffffffu},
    {0xb8013040u, 0xffffffffu},
    {0x360000e0u, 0xffffffffu},
    {0x385ff050u, 0xffffffffu},
    {0x385ff011u, 0xffffffffu},
    {0x8a500a30u, 0xffffffffu},
    {0xea5c821fu, 0xffffffffu},
    {0x54000040u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0203e1u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f83a2u, 0xffffffffu},
    {0xb801b040u, 0xffffffffu},
    {0x385ff050u, 0xffffffffu},
    {0x385ff011u, 0xffffffffu},
    {0x8a500a30u, 0xffffffffu},
    {0xea5c821fu, 0xffffffffu},
    {0x54000040u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0x6b16001fu, 0xffffffffu},
    {0x540001e1u, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000061u, 0xffffffffu},
    {0xf9400362u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0x91400370u, 0xffc003ffu},
    {0xf9400210u, 0xffc003ffu},
    {0xf90001f0u, 0xffffffffu},
    {0xf9400364u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0x14000002u, 0xffffffffu},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xd2802471u, 0xffffffffu},
    {0xb8716823u, 0xffffffffu},
    {0x8b1c8063u, 0xffffffffu},
    {0xaa0003e2u, 0xffffffffu},
    {0xf81f03a3u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xf9400370u, 0xffc003ffu},
    {0xf85f03beu, 0xffffffffu},
    {0xa900c1feu, 0xffffffffu},
    {0xf90001e0u, 0xffffffffu},
    {0xf9400364u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f83a1u, 0xffffffffu},
    {0xb8017020u, 0xffffffffu},
    {0x385ff030u, 0xffffffffu},
    {0x385ff011u, 0xffffffffu},
    {0x8a500a30u, 0xffffffffu},
    {0xea5c821fu, 0xffffffffu},
    {0x54000040u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa1603e0u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};
constexpr WordMask kNativeStyleFolderChanged[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10041efu, 0xffffffffu},
    {0xf81f83a1u, 0xffffffffu},
    {0xf81f03a2u, 0xffffffffu},
    {0xd2800041u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xb800f020u, 0xffffffffu},
    {0xf85f03a2u, 0xffffffffu},
    {0xb8013022u, 0xffffffffu},
    {0xb8400003u, 0xffe00fffu},
    {0x8b1c8063u, 0xffffffffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10007fu, 0xffffffffu},
    {0x54000280u, 0xffffffffu},
    {0x93417c44u, 0xffffffffu},
    {0x36000042u, 0xffffffffu},
    {0xf8407044u, 0xffffffffu},
    {0x93417c62u, 0xffffffffu},
    {0x36000043u, 0xffffffffu},
    {0xf8407062u, 0xffffffffu},
    {0xeb02009fu, 0xffffffffu},
    {0x54000100u, 0xffffffffu},
    {0xaa0103e2u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f83a1u, 0xffffffffu},
    {0xaa0003e2u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa1603e0u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
    {0x91400369u, 0xffc003ffu},
    {0xf9400129u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
};
constexpr WordMask kNativeStyleFolderObserver[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xf9400fa0u, 0xffffffffu},
    {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0xf9400ba2u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};
constexpr WordMask kNativeStyleFolderCallback[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10021efu, 0xffffffffu},
    {0xf9400ba0u, 0xffffffffu},
    {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0xb8400022u, 0xffe00fffu},
    {0x8b1c8042u, 0xffffffffu},
    {0xf81f83a2u, 0xffffffffu},
    {0xb8400020u, 0xffe00fffu},
    {0x8b1c8000u, 0xffffffffu},
    {0xb8013040u, 0xffffffffu},
    {0x360000e0u, 0xffffffffu},
    {0x385ff050u, 0xffffffffu},
    {0x385ff011u, 0xffffffffu},
    {0x8a500a30u, 0xffffffffu},
    {0xea5c821fu, 0xffffffffu},
    {0x54000040u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0203e1u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f83a1u, 0xffffffffu},
    {0xb801b020u, 0xffffffffu},
    {0x385ff030u, 0xffffffffu},
    {0x385ff011u, 0xffffffffu},
    {0x8a500a30u, 0xffffffffu},
    {0xea5c821fu, 0xffffffffu},
    {0x54000040u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa1603e0u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};
constexpr WordMask kNativeStyleFolderBuild[] = {
    {0xb8400020u, 0xffe00fffu},
    {0x8b1c8000u, 0xffffffffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000040u, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0x91400369u, 0xffc003ffu},
    {0xf9400129u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
};
constexpr WordMask kNativeStyleStateSet[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10061efu, 0xffffffffu},
    {0xaa0203e0u, 0xffffffffu},
    {0xf81f83a1u, 0xffffffffu},
    {0xf90001e0u, 0xffffffffu},
    {0xf9400364u, 0xffc003ffu},
    {0xf841f002u, 0xffffffffu},
    {0xd63f0040u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0x6b16003fu, 0xffffffffu},
    {0x540007a0u, 0xffffffffu},
    {0xb8400022u, 0xffe00fffu},
    {0x8b1c8042u, 0xffffffffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10005fu, 0xffffffffu},
    {0x54000720u, 0xffffffffu},
    {0x93417c41u, 0xffffffffu},
    {0x36000042u, 0xffffffffu},
    {0xf8407041u, 0xffffffffu},
    {0xf1000c3fu, 0xffffffffu},
    {0x5400052cu, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000061u, 0xffffffffu},
    {0xf9400362u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xf81f03a1u, 0xffffffffu},
    {0xb8400002u, 0xffe00fffu},
    {0x8b1c8042u, 0xffffffffu},
    {0x6b16005fu, 0xffffffffu},
    {0x54000500u, 0xffffffffu},
    {0xb8400043u, 0xffe00fffu},
    {0x8b1c8063u, 0xffffffffu},
    {0x6b16007fu, 0xffffffffu},
    {0x540004a0u, 0xffffffffu},
    {0xf90001e3u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf90001e0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xb8400002u, 0xffe00fffu},
    {0x8b1c8042u, 0xffffffffu},
    {0x6b16005fu, 0xffffffffu},
    {0x54000380u, 0xffffffffu},
    {0xb8400043u, 0xffe00fffu},
    {0x8b1c8063u, 0xffffffffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10007fu, 0xffffffffu},
    {0x54000300u, 0xffffffffu},
    {0x93417c62u, 0xffffffffu},
    {0x36000043u, 0xffffffffu},
    {0xf8407062u, 0xffffffffu},
    {0xaa0203e3u, 0xffffffffu},
    {0xaa0103e2u, 0xffffffffu},
    {0xf85f03a1u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f83a0u, 0xffffffffu},
    {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0x6b16003fu, 0xffffffffu},
    {0x540001c0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa1603e0u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf9400369u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0x94000000u, 0xfc000000u},
    {0x94000000u, 0xfc000000u},
    {0x94000000u, 0xfc000000u},
    {0xf9400369u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0x94000000u, 0xfc000000u},
};
constexpr WordMask kNativeStyleClosure[] = {
    {0xa9461340u, 0xffffffffu},
    {0x9100c000u, 0xffffffffu},
    {0xeb00009fu, 0xffffffffu},
    {0x54000009u, 0xff00001fu},
    {0xf9003340u, 0xffffffffu},
    {0xd100bc00u, 0xffffffffu},
    {0xd2800004u, 0xffe0001fu},
    {0xf2a00004u, 0xffe0001fu},
    {0xf81ff004u, 0xffffffffu},
    {0xaa1603e4u, 0xffffffffu},
    {0xb8007004u, 0xffffffffu},
    {0xb800b004u, 0xffffffffu},
    {0xb800f004u, 0xffffffffu},
    {0xb8013001u, 0xffffffffu},
    {0xb8017002u, 0xffffffffu},
    {0xb801b004u, 0xffffffffu},
    {0xf8407024u, 0xffffffffu},
    {0xf801f004u, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};
constexpr WordMask kNativeStyleEver[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd100a1efu, 0xffffffffu},
    {0xb8400080u, 0xffe00fffu},
    {0x35000060u, 0xffffffffu},
    {0xaa1603e2u, 0xffffffffu},
    {0x14000005u, 0xffffffffu},
    {0xb8400080u, 0xffe00fffu},
    {0x8b20cba1u, 0xffffffffu},
    {0xf9400021u, 0xffc003ffu},
    {0xaa0103e2u, 0xffffffffu},
    {0xf9400fa1u, 0xffffffffu},
    {0xf9400ba0u, 0xffffffffu},
    {0xf81f83a2u, 0xffffffffu},
    {0xd2800021u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf9400ba0u, 0xffffffffu},
    {0xb800f020u, 0xffffffffu},
    {0xaa0103e2u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xb800b020u, 0xffffffffu},
    {0xf9400fa0u, 0xffffffffu},
    {0xf85ff002u, 0xffffffffu},
    {0xd34c7c42u, 0xffffffffu},
    {0xa900d9f6u, 0xffffffffu},
    {0xf90001f6u, 0xffffffffu},
    {0xaa0103f0u, 0xffffffffu},
    {0xaa0203e1u, 0xffffffffu},
    {0xaa1003e2u, 0xffffffffu},
    {0xaa0003f0u, 0xffffffffu},
    {0xaa0103e0u, 0xffffffffu},
    {0xaa1003e1u, 0xffffffffu},
    {0x91400364u, 0xffc003ffu},
    {0xf9400084u, 0xffc003ffu},
    {0xd2800011u, 0xffe0001fu},
    {0x8b11001eu, 0xffffffffu},
    {0xf87e7abeu, 0xffffffffu},
    {0xd63f03c0u, 0xffffffffu},
    {0xf81f83a0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e3u, 0xffffffffu},
    {0x9100c2c0u, 0xffffffffu},
    {0xf81f03a3u, 0xffffffffu},
    {0xb800f060u, 0xffffffffu},
    {0xf85f83a2u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f03a0u, 0xffffffffu},
    {0xb8007001u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb800b001u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};
constexpr WordMask kNativeStyleRefresh[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10021efu, 0xffffffffu},
    {0xb8400020u, 0xffe00fffu},
    {0x8b1c8000u, 0xffffffffu},
    {0xf81f83a0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f83a1u, 0xffffffffu},
    {0xaa0003e2u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa1603e0u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};
constexpr WordMask kNativeStyleConfigAllocator[] = {
    {0xd2800002u, 0xffe0001fu},
    {0xf2a00002u, 0xffe0001fu},
    {0x14000000u, 0xfc000000u},
    {0x360000c0u, 0xffffffffu},
    {0xf85ff009u, 0xffffffffu},
    {0xd34c7d29u, 0xffffffffu},
    {0xf100013fu, 0xffc003ffu},
    {0x54000041u, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
    {0xf9400350u, 0xffc003ffu},
    {0xd61f0200u, 0xffffffffu},
    {0x36000000u, 0xfff8001fu},
};
constexpr WordMask kNativeStyleChildOld[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10081efu, 0xffffffffu},
    {0xb8400020u, 0xffe00fffu},
    {0x8b1c8000u, 0xffffffffu},
    {0x6b16001fu, 0xffffffffu},
    {0x54000c40u, 0xffffffffu},
    {0xb8400002u, 0xffe00fffu},
    {0x8b1c8042u, 0xffffffffu},
    {0xf81e03a2u, 0xffffffffu},
    {0xb8400003u, 0xffe00fffu},
    {0x8b1c8063u, 0xffffffffu},
    {0xf81e83a3u, 0xffffffffu},
    {0xb8400004u, 0xffe00fffu},
    {0x8b1c8084u, 0xffffffffu},
    {0xf81f03a4u, 0xffffffffu},
    {0xb8400005u, 0xffe00fffu},
    {0x8b1c80a5u, 0xffffffffu},
    {0x37200065u, 0xffffffffu},
    {0xd2800020u, 0xffffffffu},
    {0x1400000au, 0xffffffffu},
    {0xb8400020u, 0xffe00fffu},
    {0x8b1c8000u, 0xffffffffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000a00u, 0xffffffffu},
    {0x93417c01u, 0xffffffffu},
    {0x36000040u, 0xffffffffu},
    {0xf8407001u, 0xffffffffu},
    {0xaa0103e0u, 0xffffffffu},
    {0xf81f83a0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85e83a1u, 0xffffffffu},
    {0xb800b001u, 0xffffffffu},
    {0xf85f03a1u, 0xffffffffu},
    {0xb800f001u, 0xffffffffu},
    {0xf85e03a1u, 0xffffffffu},
    {0xb804f001u, 0xffffffffu},
    {0xf85f83a1u, 0xffffffffu},
    {0xf8013001u, 0xffffffffu},
    {0xf801b01fu, 0xffffffffu},
    {0xf802301fu, 0xffffffffu},
    {0xf802b01fu, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb8033001u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb8037001u, 0xffffffffu},
    {0xf803b01fu, 0xffffffffu},
    {0xd2800c81u, 0xffffffffu},
    {0xf8043001u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb804b001u, 0xffffffffu},
    {0x1e6c1000u, 0xffffffffu},
    {0xfc053000u, 0xffffffffu},
    {0xf805b01fu, 0xffffffffu},
    {0x1e6e1001u, 0xffffffffu},
    {0xfc063001u, 0xffffffffu},
    {0xfc06b001u, 0xffffffffu},
    {0xfc073001u, 0xffffffffu},
    {0xfc07b001u, 0xffffffffu},
    {0x1e649002u, 0xffffffffu},
    {0xfc083002u, 0xffffffffu},
    {0xfc08b000u, 0xffffffffu},
    {0x91400371u, 0xffc003ffu},
    {0xfd400222u, 0xffc003ffu},
    {0xfc093002u, 0xffffffffu},
    {0x1e7a1002u, 0xffffffffu},
    {0xfc09b002u, 0xffffffffu},
    {0xfc0a3001u, 0xffffffffu},
    {0xfc0ab001u, 0xffffffffu},
    {0xfc0b3001u, 0xffffffffu},
    {0x91400371u, 0xffc003ffu},
    {0xfd400222u, 0xffc003ffu},
    {0xfc0bb002u, 0xffffffffu},
    {0xfc0c3000u, 0xffffffffu},
    {0x91400371u, 0xffc003ffu},
    {0xfd400220u, 0xffc003ffu},
    {0xfc0cb000u, 0xffffffffu},
    {0x91400371u, 0xffc003ffu},
    {0xfd400220u, 0xffc003ffu},
    {0xfc0d3000u, 0xffffffffu},
    {0xfc0db001u, 0xffffffffu},
    {0xfc0e3001u, 0xffffffffu},
    {0xfc0eb001u, 0xffffffffu},
    {0xfc0f3002u, 0xffffffffu},
    {0xf9400361u, 0xffc003ffu},
    {0xb80fb001u, 0xffffffffu},
    {0xb80ff001u, 0xffffffffu},
    {0xd2802071u, 0xffffffffu},
    {0xb8316801u, 0xffffffffu},
    {0xd28020f1u, 0xffffffffu},
    {0xb8316801u, 0xffffffffu},
    {0xd2802171u, 0xffffffffu},
    {0xb831681fu, 0xffffffffu},
    {0xd28021f1u, 0xffffffffu},
    {0xb831681fu, 0xffffffffu},
    {0xd2802271u, 0xffffffffu},
    {0xb8316801u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0x91400369u, 0xffc003ffu},
    {0xf9400129u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
};
constexpr WordMask kNativeStyleGrowable[] = {
    {0xa9460b40u, 0xffffffffu},
    {0x91008000u, 0xffffffffu},
    {0xeb00005fu, 0xffffffffu},
    {0x54000109u, 0xffffffffu},
    {0xf9003340u, 0xffffffffu},
    {0xd1007c00u, 0xffffffffu},
    {0xd2800002u, 0xffe0001fu},
    {0xf2a00002u, 0xffe0001fu},
    {0xf81ff002u, 0xffffffffu},
    {0xf8007001u, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
    {0xd2800002u, 0xffe0001fu},
    {0xf2a00002u, 0xffe0001fu},
    {0xf9400350u, 0xffc003ffu},
    {0xd61f0200u, 0xffffffffu},
};
constexpr WordMask kNativeStyleArray[] = {
    {0x37000002u, 0xfff8001fu},
    {0xb27d37f1u, 0xffffffffu},
    {0x6b11005fu, 0xffffffffu},
    {0x54000008u, 0xff00001fu},
    {0xf9400340u, 0xffc003ffu},
    {0xd2800003u, 0xffe0001fu},
    {0x0b020463u, 0xffffffffu},
    {0x927cec63u, 0xffffffffu},
    {0xab000067u, 0xffffffffu},
    {0x54000002u, 0xff00001fu},
    {0xf9400350u, 0xffc003ffu},
    {0xeb1000ffu, 0xffffffffu},
    {0x54000002u, 0xff00001fu},
    {0xf9003347u, 0xffffffffu},
    {0x91000400u, 0xffffffffu},
    {0xf103c07fu, 0xffffffffu},
    {0xd37cec70u, 0xffffffffu},
    {0x9a839203u, 0xffffffffu},
    {0x9a8383e3u, 0xffffffffu},
    {0xd2800010u, 0xffe0001fu},
    {0xf2a00010u, 0xffe0001fu},
    {0xaa100063u, 0xffffffffu},
    {0xf81ff003u, 0xffffffffu},
    {0xb8007001u, 0xffffffffu},
    {0xb800b002u, 0xffffffffu},
    {0x91003c03u, 0xffffffffu},
    {0x92407ed0u, 0xffffffffu},
    {0xaa108210u, 0xffffffffu},
    {0xa8814070u, 0xffffffffu},
    {0xeb07007fu, 0xffffffffu},
    {0x54ffffc3u, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
};
constexpr WordMask kNativeStyleChild[] = {
    {0xa9bf79fdu, 0xffffffffu},
    {0xaa0f03fdu, 0xffffffffu},
    {0xd10081efu, 0xffffffffu},
    {0xf81f83a1u, 0xffffffffu},
    {0xf9400340u, 0xffc003ffu},
    {0xf9400000u, 0xffc003ffu},
    {0xf9400370u, 0xffc003ffu},
    {0x6b10001fu, 0xffffffffu},
    {0x54000081u, 0xffffffffu},
    {0x91400362u, 0xffc003ffu},
    {0xf9400042u, 0xffc003ffu},
    {0x94000000u, 0xfc000000u},
    {0xb8400001u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xaa0003e1u, 0xffffffffu},
    {0xf85f83a0u, 0xffffffffu},
    {0xb8400002u, 0xffe00fffu},
    {0x8b1c8042u, 0xffffffffu},
    {0x6b16005fu, 0xffffffffu},
    {0x54000c00u, 0xffffffffu},
    {0xf841b040u, 0xffffffffu},
    {0x93417c23u, 0xffffffffu},
    {0x36000041u, 0xffffffffu},
    {0xf8407023u, 0xffffffffu},
    {0xb8400041u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0x36200061u, 0xffffffffu},
    {0xeb00007fu, 0xffffffffu},
    {0x54000061u, 0xffffffffu},
    {0xd2800020u, 0xffffffffu},
    {0x14000002u, 0xffffffffu},
    {0xd2800040u, 0xffffffffu},
    {0xf81e03a0u, 0xffffffffu},
    {0xb8400041u, 0xffe00fffu},
    {0x8b1c8021u, 0xffffffffu},
    {0xf81e83a1u, 0xffffffffu},
    {0xb8400043u, 0xffe00fffu},
    {0x8b1c8063u, 0xffffffffu},
    {0xf81f03a3u, 0xffffffffu},
    {0xb8400044u, 0xffe00fffu},
    {0x8b1c8084u, 0xffffffffu},
    {0xf81f83a4u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
    {0xf85f03a1u, 0xffffffffu},
    {0xb800b001u, 0xffffffffu},
    {0xf85f83a1u, 0xffffffffu},
    {0xb800f001u, 0xffffffffu},
    {0xf85e83a1u, 0xffffffffu},
    {0xb804f001u, 0xffffffffu},
    {0xf85e03a1u, 0xffffffffu},
    {0xf8013001u, 0xffffffffu},
    {0xf801b01fu, 0xffffffffu},
    {0xf802301fu, 0xffffffffu},
    {0xf802b01fu, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb8033001u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb8037001u, 0xffffffffu},
    {0xf803b01fu, 0xffffffffu},
    {0xd2800c81u, 0xffffffffu},
    {0xf8043001u, 0xffffffffu},
    {0x91400361u, 0xffc003ffu},
    {0xf9400021u, 0xffc003ffu},
    {0xb804b001u, 0xffffffffu},
    {0x1e6c1000u, 0xffffffffu},
    {0xfc053000u, 0xffffffffu},
    {0xf805b01fu, 0xffffffffu},
    {0x1e6e1001u, 0xffffffffu},
    {0xfc063001u, 0xffffffffu},
    {0xfc06b001u, 0xffffffffu},
    {0xfc073001u, 0xffffffffu},
    {0xfc07b001u, 0xffffffffu},
    {0x1e649002u, 0xffffffffu},
    {0xfc083002u, 0xffffffffu},
    {0xfc08b000u, 0xffffffffu},
    {0x91400371u, 0xffc003ffu},
    {0xfd400222u, 0xffc003ffu},
    {0xfc093002u, 0xffffffffu},
    {0x1e7a1002u, 0xffffffffu},
    {0xfc09b002u, 0xffffffffu},
    {0xfc0a3001u, 0xffffffffu},
    {0xfc0ab001u, 0xffffffffu},
    {0xfc0b3001u, 0xffffffffu},
    {0x91400371u, 0xffc003ffu},
    {0xfd400222u, 0xffc003ffu},
    {0xfc0bb002u, 0xffffffffu},
    {0xfc0c3000u, 0xffffffffu},
    {0x91400371u, 0xffc003ffu},
    {0xfd400220u, 0xffc003ffu},
    {0xfc0cb000u, 0xffffffffu},
    {0x91400371u, 0xffc003ffu},
    {0xfd400220u, 0xffc003ffu},
    {0xfc0d3000u, 0xffffffffu},
    {0xfc0db001u, 0xffffffffu},
    {0xfc0e3001u, 0xffffffffu},
    {0xfc0eb001u, 0xffffffffu},
    {0xfc0f3002u, 0xffffffffu},
    {0xf9400361u, 0xffc003ffu},
    {0xb80fb001u, 0xffffffffu},
    {0xb80ff001u, 0xffffffffu},
    {0xd2802071u, 0xffffffffu},
    {0xb8316801u, 0xffffffffu},
    {0xd28020f1u, 0xffffffffu},
    {0xb8316801u, 0xffffffffu},
    {0xd2802171u, 0xffffffffu},
    {0xb831681fu, 0xffffffffu},
    {0xd28021f1u, 0xffffffffu},
    {0xb831681fu, 0xffffffffu},
    {0xd2802271u, 0xffffffffu},
    {0xb8316801u, 0xffffffffu},
    {0xaa1d03efu, 0xffffffffu},
    {0xa8c179fdu, 0xffffffffu},
    {0xd65f03c0u, 0xffffffffu},
    {0x94000000u, 0xfc000000u},
};

#endif

struct Reader {
    const IconHighlightImage& im;
    bool Has(size_t at, size_t size, bool code = false) const {
        if (at > im.size || size > im.size - at) return false;
        for (size_t i = 0; i < im.range_count; ++i) {
            const auto& r = im.ranges[i];
            if ((!code || r.executable) && at >= r.begin && at < r.end &&
                    size <= r.end - at) return true;
        }
        return false;
    }
    uint64_t Read(size_t at, unsigned bytes = 4) const {
        uint64_t result = 0;
        if (bytes <= 8 && Has(at, bytes)) memcpy(&result, im.base + at, bytes);
        return result;
    }
    uint32_t W(size_t at) const { return uint32_t(Read(at)); }
    size_t Relative(size_t at, unsigned bits, unsigned shift) const {
        const uint32_t x = (W(at) >> shift) & ((1u << bits) - 1);
        const int64_t off = (int64_t(x) ^ (int64_t(1) << (bits - 1))) -
                (int64_t(1) << (bits - 1));
        const int64_t dest = int64_t(at) + off * 4;
        return dest > 0 && Has(size_t(dest), 4, true) ? size_t(dest) : 0;
    }
    size_t Call(size_t at) const {
        return (W(at) & 0xfc000000u) == 0x94000000u ? Relative(at, 26, 0) : 0;
    }
    template<size_t N> bool Match(size_t at, const WordMask (&words)[N]) const {
        if (!Has(at, N * 4, true)) return false;
        for (size_t i = 0; i < N; ++i)
            if ((W(at + i * 4) & words[i].mask) != words[i].word) return false;
        return true;
    }
};
int Field(uint32_t w) {
    const unsigned v = (w >> 12) & 511;
    return int(v) - (v & 256 ? 512 : 0);
}
unsigned Slot(uint32_t w) { return ((w >> 10) & 4095) * 8; }
unsigned PoolSlot(const Reader& r, size_t add, size_t load) {
    return ((r.W(add) >> 10) & 4095) * 4096 + Slot(r.W(load));
}
int MovnOffset(uint32_t w) {
    if ((w & 0xffe0001fu) != 0x92800011u) return 0;
    return ~int((w >> 5) & 65535);
}

bool ClosureFlow(const Reader& r, size_t at, LauncherGlassMaterialProfile* p) {
    if (at < sizeof(kBuild) / 2 || !r.Match(at - sizeof(kBuild) / 2, kBuild)) return false;
    // WordMask has two words; machine bytes are half sizeof(pattern).
    p->build = at - sizeof(kBuild) / 2;
    p->closure = at;
    p->valueCall = at + 0x64;
    p->valueOriginal = r.Call(p->valueCall);
    p->valueReturnPc = p->branch = p->valueCall + 4;
    p->branchWord = r.W(p->branch);
    p->resultRegister = uint8_t(p->branchWord & 31);
    p->boolBit = uint8_t(((p->branchWord >> 19) & 31) | ((p->branchWord >> 26) & 32));
    p->darkTarget = p->branch + 4;
    p->lightTarget = r.Relative(p->branch, 14, 5);
    p->nativeJoin = r.Relative(at + 0x9c, 26, 0);
    p->controllerSlot = Slot(r.W(at + 0x20));
    p->wallpaperRxField = Field(r.W(at + 0x5c));
    p->darkCacheField = Field(r.W(at + 0x78));
    p->lightCacheField = Field(r.W(at + 0xac));
    p->capturedStateField = Field(r.W(at + 0x70));
    const size_t darkLate = r.Call(at + 0x94), lightLate = r.Call(at + 0xc8);
    if (p->resultRegister || p->boolBit != 4 || p->lightTarget != at + 0xa0 ||
            p->nativeJoin != at + 0xd0 || !p->valueOriginal ||
            p->wallpaperRxField <= 0 || (p->wallpaperRxField & 3) != 3 ||
            p->lightCacheField != p->darkCacheField + 4 || p->darkCacheField <= 0 ||
            p->capturedStateField != Field(r.W(at + 0xa4)) ||
            p->capturedStateField != Field(r.W(at + 0xd8)) ||
            !p->controllerSlot || !darkLate || darkLate != lightLate ||
            r.Relative(at + 0x88, 19, 5) != at + 0x98 ||
            r.Relative(at + 0xbc, 19, 5) != at + 0xcc ||
            PoolSlot(r, at + 0x8c, at + 0x90) + 8 !=
                PoolSlot(r, at + 0xc0, at + 0xc4)) return false;
    // Rx getter's result is consumed immediately, after subscription collection.
    // Both native cache branches join at the same image-filter argument store.
    return r.W(at + 0x6c) == 0xf85f83a0u && r.W(at + 0xa0) == 0xf85f83a0u &&
            r.W(at + 0x98) == 0xaa0003e1u && r.W(at + 0xcc) == 0xaa0003e1u &&
            r.W(at + 0xd0) == 0xf85f83a0u && r.W(at + 0xd4) == 0xf81f03a1u;
}

bool ControllerSources(const Reader& r, const LauncherLabelProfile& labels,
        LauncherGlassMaterialProfile* p) {
    const size_t at = p->closure + 0x1c;
    const unsigned type = PoolSlot(r, at + 0x2c, at + 0x30);
    const size_t find = r.Call(at + 0x3c);
    const uint32_t tableLoad = r.W(labels.config + 0xc);
    const uint32_t sentinelLoad = r.W(labels.config + 0x24);
    if (r.W(at) != tableLoad || r.W(at + 0x10) != tableLoad ||
            r.W(at + 0x18) != sentinelLoad ||
            r.W(p->closure + 0x80) != sentinelLoad ||
            r.W(p->closure + 0xb4) != sentinelLoad ||
            !find || find != r.Call(labels.config + 0x48) ||
            Slot(r.W(at + 0x14)) != Slot(r.W(labels.config + 0x20))) return false;
    for (size_t i = 0; i < r.im.range_count; ++i) {
        const auto& range = r.im.ranges[i];
        if (!range.executable || range.end - range.begin < sizeof(kCache) / 2) continue;
        for (size_t pc = range.begin; pc <= range.end - sizeof(kCache) / 2; pc += 4) {
            if ((r.W(pc) & kCache[0].mask) != kCache[0].word || !r.Match(pc, kCache) ||
                    PoolSlot(r, pc + 0x2c, pc + 0x30) != type ||
                    r.Call(pc + 0x3c) != find) continue;
            if (r.W(pc) != tableLoad || r.W(pc + 0x10) != tableLoad ||
                    r.W(pc + 0x18) != sentinelLoad ||
                    Slot(r.W(pc + 4)) != p->controllerSlot ||
                    Slot(r.W(pc + 0x14)) != Slot(r.W(at + 0x14))) return false;
            ++p->controllerSourceCount;
        }
    }
    return p->controllerSourceCount >= 2;
}
bool Setter(const Reader& r, size_t at, bool old, const LauncherGlassMaterialProfile& p) {
    const size_t size = old ? sizeof(kSetterOld) / 2 : sizeof(kSetterNew) / 2;
    const size_t tail = at + size - (old ? 76 : 52);
    // Source tail: reload this, load its Rx, compare the colorMode Smi with 4,
    // select native true/false, then call the SAME Rx setter as capability Rx.
    const unsigned rxReg = old ? 3 : 1;
    const uint32_t load = r.W(tail + 4);
    const size_t compare = tail + (old ? 20 : 16);
    const size_t setCall = tail + (old ? 40 : 32);
    if ((load & 0xffe00fffu) != (0xb8400000u | rxReg) ||
            Field(load) != p.wallpaperRxField || r.W(compare) != 0x7100101fu ||
            r.W(compare + 4) != 0x910082d0u || r.W(compare + 8) != 0x9100c2d1u ||
            r.W(compare + 12) != 0x9a910202u || !r.Call(setCall))
        return false;
    const size_t setter = r.Call(setCall);
    unsigned setCalls = 0;
    for (size_t pc = at; pc < tail; pc += 4)
        if (r.Call(pc) == setter) ++setCalls;
    if (setCalls != 1 || r.W(setter) != 0xa9bf79fdu ||
            r.W(setter + 4) != 0xaa0f03fdu) return false;
    if (old && (r.W(tail + 16) != 0xf81f83a3u ||
            r.W(tail + 36) != 0xaa0303e1u ||
            r.Call(tail + 48) != p.valueOriginal ||
            r.W(tail + 52) != 0xf9403f41u ||
            Slot(r.W(tail + 56)) != p.controllerSlot + 8)) return false;
    if (r.Call(at + 0x28) != p.valueOriginal) return false;
    return true;
}

bool Checkpoint(const Reader& r, const LauncherLabelProfile& labels,
        LauncherGlassMaterialProfile* p) {
    p->event = labels.change_event;
    p->firstLoggerCall = labels.refresh_call;
    p->secondLoggerCall = p->rx.second_event_call;
    p->returnPc = labels.refresh_call + 4;
    p->replayWord = r.W(p->returnPc);
    p->resume = p->returnPc + 4;
    p->eventFrameBytes = (r.W(p->event + 8) >> 10) & 4095;
    unsigned interpolationCalls = 0;
    const size_t interpolation = r.Call(p->returnPc + 12);
    for (size_t pc = p->event; pc < p->firstLoggerCall; pc += 4)
        if (r.Call(pc) == interpolation) ++interpolationCalls;
    const int original = MovnOffset(p->replayWord);
    if (!original || (original & 7) || p->secondLoggerCall != p->returnPc + 32 ||
            r.Call(p->firstLoggerCall) != labels.refresh_logger ||
            r.Call(p->secondLoggerCall) != labels.refresh_logger ||
            r.W(p->returnPc + 4) != 0xf8716ba2u ||
            (r.W(p->returnPc + 8) & 0xffc003ffu) != 0xf9400361u ||
            !interpolation || interpolationCalls != 1 ||
            !MovnOffset(r.W(p->returnPc + 16)) ||
            r.W(p->returnPc + 20) != 0xf8716ba1u ||
            r.W(p->returnPc + 24) != 0xaa0003e2u ||
            r.W(p->returnPc + 28) != r.W(p->firstLoggerCall - 4) ||
            r.W(p->secondLoggerCall + 4) != p->replayWord ||
            !p->eventFrameBytes || (p->eventFrameBytes & 7)) return false;
    // The first original post-logger instruction defines only scratch x17;
    // no logger result x0 is read before the native interpolation call defines it.
    return true;
}

bool StreamState(const Reader& r, LauncherGlassMaterialProfile* p) {
    const size_t notify = r.Call(p->rx.stream_add + 0x54);
    if (!notify || !r.Has(notify, 0x114, true)) return false;
    p->subjectNotifyDataEntry = notify;
    p->subjectListenersField = uint32_t(Field(r.W(notify + 0x24)));
    p->subjectBusyField = uint32_t(Field(r.W(notify + 0x20)));
    p->subjectListenerLengthField = uint32_t(Field(r.W(notify + 0x38)));
    if (r.W(notify + 0xf8) != 0xaa0403e0u || r.W(notify + 0xfc) != 0x9100c2c1u ||
            (r.W(notify + 0x100) & 0xffe00fffu) != 0xb8000001u ||
            Field(r.W(notify + 0x100)) != int(p->subjectBusyField) ||
            r.W(notify + 0x104) != 0xaa1603e0u ||
            r.W(notify + 0x108) != 0xaa1d03efu ||
            r.W(notify + 0x10c) != 0xa8c179fdu ||
            r.W(notify + 0x110) != 0xd65f03c0u) return false;
    unsigned closes = 0;
    for (size_t i = 0; i < r.im.range_count; ++i) {
        const auto& range = r.im.ranges[i];
        if (!range.executable || range.end - range.begin < sizeof(kStreamClose) / 2) continue;
        for (size_t at = range.begin; at <= range.end - sizeof(kStreamClose) / 2; at += 4) {
            if (r.W(at) != kStreamClose[0].word || !r.Match(at, kStreamClose)) continue;
            const size_t done = r.Call(at + 0x14);
            if (!done || !r.Match(done, kNotifyDone) ||
                    Field(r.W(at + 0x1c)) != int(p->subjectListenersField) ||
                    Field(r.W(at + 0x20)) != int(p->subjectBusyField) ||
                    Field(r.W(done + 0x14)) != int(p->subjectBusyField) ||
                    Field(r.W(done + 0x18)) != int(p->subjectListenersField) ||
                    Field(r.W(done + 0x2c)) != int(p->subjectListenerLengthField) ||
                    r.W(done + 0xb0) != 0xaa0103e0u ||
                    r.W(done + 0xb4) != 0x9100c2c1u ||
                    (r.W(done + 0xb8) & 0xffe00fffu) != 0xb8000001u ||
                    Field(r.W(done + 0xb8)) != int(p->subjectBusyField) ||
                    r.W(done + 0xbc) != 0xaa1603e0u ||
                    r.W(done + 0xc0) != 0xaa1d03efu ||
                    r.W(done + 0xc4) != 0xa8c179fdu ||
                    r.W(done + 0xc8) != 0xd65f03c0u) continue;
            if (++closes != 1) return false;
            p->subjectCloseEntry = at;
            p->subjectNotifyDoneEntry = done;
        }
    }
    return closes == 1;
}

bool Leb(const Reader& r, size_t* at, size_t end, size_t* value) {
    *value = 0;
    for (unsigned shift = 0; *at < end && shift < 64; shift += 7) {
        const uint8_t b = r.im.base[(*at)++];
        if (shift == 63 && (b & 127) > 1) return false;
        *value |= size_t(b & 127) << shift;
        if (!(b & 128)) return true;
    }
    return false;
}
bool SnapshotInteger(const Reader& r, size_t* at, size_t end, size_t* value) {
    *value = 0;
    for (unsigned shift = 0; *at < end && shift < 63; shift += 7) {
        const uint8_t b = r.im.base[(*at)++];
        if (b & 128) { *value |= size_t(b - 128) << shift; return true; }
        *value |= size_t(b) << shift;
    }
    return false;
}
bool Payload(const Reader& r, size_t at, unsigned flags, size_t* start, size_t* end) {
    if (!r.Has(at, 4)) return false;
    const uint32_t header = r.W(at);
    if ((header & 3) != flags || !r.Has(at + 4, header >> 2)) return false;
    *start = at + 4;
    *end = *start + (header >> 2);
    return true;
}
bool StackPoint(const Reader& r, size_t table, size_t total, size_t codeBase,
        size_t function, size_t pc, uint32_t spills, uint64_t lowBits,
        LauncherGlassMaterialStackMap* out) {
    size_t local = 0, found = 0;
    for (size_t i = 0; i < total; ++i)
        if (codeBase + r.Read(table + 16 + i * 8) == function) {
            local = size_t(r.Read(table + 20 + i * 8));
            ++found;
        }
    const size_t global = size_t(r.Read(table));
    size_t lb = 0, le = 0, gb = 0, ge = 0;
    if (found != 1 || local > r.im.size - table || global > r.im.size - table ||
            !Payload(r, table + local, 2, &lb, &le) ||
            !Payload(r, table + global, 1, &gb, &ge)) return false;
    size_t cursor = lb, offset = 0, seen = 0;
    while (cursor < le) {
        size_t delta = 0, item = 0;
        if (!Leb(r, &cursor, le, &delta) || !Leb(r, &cursor, le, &item) ||
                delta > 65536 || offset > 65536 - delta || item >= ge - gb) return false;
        offset += delta;
        size_t bitmap = gb + item, n = 0, other = 0;
        if (!Leb(r, &bitmap, ge, &n) || !Leb(r, &bitmap, ge, &other) ||
                n > 512 || other > 512 || (n + other + 7) / 8 > ge - bitmap) return false;
        if (function + offset != pc) continue;
        if (++seen != 1 || n != spills || other) return false;
        for (size_t j = 0; j < (n + 7) / 8; ++j) {
            const uint8_t expected = j < 8 ? uint8_t(lowBits >> (j * 8)) : 0;
            if (r.im.base[bitmap + j] != expected) return false;
        }
        *out = {table + local, bitmap, uint32_t(n), uint32_t(other)};
    }
    return seen == 1;
}
bool StackMaps(const Reader& r, LauncherGlassMaterialProfile* p) {
    size_t snapshot = 0, end = 0;
    for (size_t at = 0x350; at < 0x10000; at += 16) {
        if (r.W(at) != 0xdcdcf5f5u) continue;
        const uint64_t length = r.Read(at + 4, 8);
        if (length < 52 || length > r.im.size || !r.Has(at, size_t(length) + 4)) continue;
        if (snapshot) return false;
        snapshot = at;
        end = at + size_t(length) + 4;
    }
    if (!snapshot) return false;
    size_t cursor = snapshot + 52;
    while (cursor < end && cursor < snapshot + 4096 && r.im.base[cursor]) ++cursor;
    if (cursor >= end || cursor == snapshot + 4096) return false;
    ++cursor;
    size_t head[5]{};
    for (auto& value : head)
        if (!SnapshotInteger(r, &cursor, end, &value)) return false;
    const size_t image = (end + 63) & ~size_t(63);
    if (image > r.im.size || head[4] > r.im.size - image ||
            r.im.size - image - head[4] < 16) return false;
    const size_t table = image + head[4] + 16;
    if (!r.Has(table, 16)) return false;
    const size_t total = size_t(r.Read(table + 4)), first = size_t(r.Read(table + 8));
    if (!total || total > 500000 || first > total || total - first != head[3] ||
            r.W(table + 12) || !r.Has(table + 16, total * 8)) return false;
    size_t code = 0;
    for (size_t i = 0; i < r.im.range_count; ++i) {
        const auto& range = r.im.ranges[i];
        if (!range.executable) continue;
        const size_t vmBytes = size_t(r.Read(range.begin, 8));
        if (vmBytes < 64 || (vmBytes & 63) || vmBytes > range.end - range.begin ||
                r.Read(range.begin + 8, 8) != 64) continue;
        const size_t isolate = range.begin + vmBytes, bytes = size_t(r.Read(isolate, 8));
        if (bytes < 64 || r.Read(isolate + 8, 8) != 64 || !r.Has(isolate, bytes, true)) continue;
        if (code) return false;
        code = isolate;
    }
    if (!code || !p->eventFrameBytes || (p->eventFrameBytes & 7)) return false;
    const unsigned spills = p->eventFrameBytes / 8;
    // At the original logger return PC, the map retains the null spill,
    // the receiver, the reused second-argument slot and the change list.
    // Dart numbers these bits from FP-8. Frame growth and root movement
    // are independent, so replay the compiler's actual FP displacements.
    const uint32_t nullStore = r.W(p->event + 12);
    if ((nullStore & 0xffe00fffu) != 0xf80003b6u ||
            r.W(p->event + 20) != 0xf8316ba1u ||
            r.W(p->event + 28) != 0xf8316ba2u ||
            r.W(p->event + 64) != 0xf8316ba1u ||
            p->replayWord != r.W(p->event + 60) ||
            r.W(p->firstLoggerCall - 16) != r.W(p->event + 24) ||
            r.W(p->firstLoggerCall - 12) != 0xf8716ba1u)
        return false;
    uint64_t eventBits = 0;
    const int roots[] = {Field(nullStore), MovnOffset(r.W(p->event + 16)),
                         MovnOffset(r.W(p->event + 24)), MovnOffset(p->replayWord)};
    for (const int offset : roots) {
        if (offset >= 0 || (offset & 7)) return false;
        const unsigned slot = unsigned(-offset) / 8 - 1;
        if (slot >= spills || slot >= 64 || (eventBits & (uint64_t{1} << slot)))
            return false;
        eventBits |= uint64_t{1} << slot;
    }
    p->instructionsTable = table;
    return StackPoint(r, table, total, code, p->closure, p->branch, 3, 1, &p->branchMap) &&
            StackPoint(r, table, total, code, p->event, p->returnPc,
                spills, eventBits, &p->eventMap) &&
            StackPoint(r, table, total, code, p->rx.refresh, p->rx.refresh + 0x1c,
                0, 0, &p->refreshValueMap) &&
            StackPoint(r, table, total, code, p->rx.refresh, p->rx.refresh + 0x28,
                1, 0, &p->refreshAddMap);
}
} // namespace

bool ResolveLauncherGlassMaterial(const IconHighlightImage& image,
        const LauncherLabelProfile& labels, LauncherGlassMaterialProfile* out,
        const char** why) {
    if (why) *why = nullptr;
    if (!out) return Reject(why, "missing material profile output");
    memset(out, 0, sizeof(*out));
    if (!ValidateArm64DartImage(image)) return Reject(why, "invalid material AOT image");
    LauncherGlassMaterialProfile p{};
    if (!ResolveDockGlassRefresh(image, labels, &p.rx, why)) return false;
    p.rxSubjectField = p.rx.rx_subject_field;
    p.rxValueField = labels.rx_value_field;
    p.observerSlot = p.rx.observer_slot;
    p.refreshEntry = p.rx.refresh;
    Reader r{image};
    unsigned closures = 0, setters = 0;
    for (size_t i = 0; i < image.range_count; ++i) {
        const auto& range = image.ranges[i];
        if (!range.executable || range.end - range.begin < sizeof(kClosure) / 2) continue;
        for (size_t at = range.begin; at <= range.end - sizeof(kClosure) / 2; at += 4) {
            if (r.W(at) != kClosure[0].word || !r.Match(at, kClosure)) continue;
            LauncherGlassMaterialProfile candidate = p;
            if (!ClosureFlow(r, at, &candidate) || candidate.valueOriginal != labels.value) continue;
            if (++closures != 1) return Reject(why, "ambiguous material closure source");
            p = candidate;
        }
    }
    if (closures != 1) return Reject(why, "missing material closure source");
    if (!ControllerSources(r, labels, &p))
        return Reject(why, "inconsistent Wallpaper controller type/cache ownership");
    for (size_t i = 0; i < image.range_count; ++i) {
        const auto& range = image.ranges[i];
        if (!range.executable) continue;
        for (size_t at = range.begin; at + 4 <= range.end; at += 4) {
            if (r.W(at) != kSetterOld[0].word) continue;
            const bool old = r.Match(at, kSetterOld), modern = r.Match(at, kSetterNew);
            if ((!old && !modern) || !Setter(r, at, old, p)) continue;
            if (++setters != 1) return Reject(why, "ambiguous native wallpaper setter");
            p.wallpaperSetter = at;
            const size_t size = old ? sizeof(kSetterOld) / 2 : sizeof(kSetterNew) / 2;
            const size_t compare = at + size - (old ? 76 : 52) + (old ? 20 : 16);
            p.nativeTrueOffset = (r.W(compare + 4) >> 10) & 4095;
            p.nativeFalseOffset = (r.W(compare + 8) >> 10) & 4095;
        }
    }
    if (setters != 1) return Reject(why, "missing native wallpaper bool source");
    if (!StreamState(r, &p)) return Reject(why, "unproven subject close/listener/busy ownership");
    if (!Checkpoint(r, labels, &p)) return Reject(why, "unsafe material logger-return replay");
    if (!StackMaps(r, &p)) return Reject(why, "material original-PC stackmap proof failed");
    *out = p;
    return true;
}

namespace {
bool SearchCapsuleMatches(const Reader& r, size_t at) {
    constexpr size_t tail = 0x188;
    const bool extended = r.W(at + tail) == 0xf801f01fu;
    const size_t count = sizeof(kExtraSearchCapsule) / sizeof(kExtraSearchCapsule[0]);
    for (size_t i = 0; i < count; ++i) {
        const size_t off = i * 4;
        const auto& expected = kExtraSearchCapsule[i];
        const size_t pc = at + off + (extended && off >= tail ? 4 : 0);
        if (!r.Has(pc, 4, true) || (r.W(pc) & expected.mask) != expected.word)
            return false;
    }
    if (!extended) return true;
    // The shifted tail belongs to the larger native ClipRRect allocator.
    const size_t clip = r.Call(at + 0x168);
    return clip && (r.W(clip) & 0xffe1ffffu) == 0xd2806382u &&
            (r.W(clip + 4) & 0xffe0001fu) == 0xf2a00002u &&
            (r.W(clip + 8) & 0xfc000000u) == 0x14000000u;
}

bool ExtraWallpaperSource(const Reader& r, const LauncherGlassMaterialProfile& material,
        size_t at) {
    const size_t dock = material.closure + 0x1c;
    return r.Match(at, kCache) && r.W(at) == r.W(dock) &&
            r.W(at + 0x10) == r.W(dock + 0x10) &&
            r.W(at + 0x18) == r.W(dock + 0x18) &&
            Slot(r.W(at + 4)) == material.controllerSlot &&
            Slot(r.W(at + 0x14)) == Slot(r.W(dock + 0x14)) &&
            PoolSlot(r, at + 0x2c, at + 0x30) == PoolSlot(r, dock + 0x2c, dock + 0x30) &&
            r.Call(at + 0x3c) == r.Call(dock + 0x3c) &&
            Field(r.W(at + 0x40)) == int(material.wallpaperRxField) &&
            r.Call(at + 0x48) == material.valueOriginal;
}

bool ExtraAllocator(const Reader& r, size_t at) {
    if (!r.Match(at, kExtraWidgetAllocator)) return false;
    const uint32_t tags = ((r.W(at) >> 5) & 65535u) |
            (((r.W(at + 4) >> 5) & 65535u) << 16);
    return (tags >> 12) == ((r.W(at + 0x18) >> 5) & 65535u) &&
            r.Has(r.Relative(at + 8, 26, 0), 4, true);
}

bool ExtraChoose(const Reader& r, const LauncherGlassMaterialProfile& material,
        size_t at, uint32_t kind, bool old, LauncherGlassMaterialChooseSite* out) {
    if (!ExtraWallpaperSource(r, material, at)) return false;
    const bool folder = kind == kLauncherGlassMaterialFolder;
    const size_t trueAt = at + (folder && !old ? 0x58 : 0x50);
    const size_t lightAt = at + (folder ? (old ? 0x88 : 0x90) : 0x78);
    const size_t join = at + (folder ? (old ? 0xbc : 0xc4) : 0x9c);
    const size_t branch = at + 0x4c;
    const size_t allocation = at + (folder ? (old ? 0xe0 : 0xe8) : 0xd8);
    if ((r.W(branch) & 0xfff8001fu) != 0x37200000u ||
            r.Relative(branch, 14, 5) != lightAt ||
            r.W(trueAt) != r.W(at) || r.W(lightAt) != r.W(at) ||
            r.W(trueAt + 8) != r.W(at + 0x18) ||
            r.W(lightAt + 8) != r.W(at + 0x18) ||
            r.Call(trueAt + 0x1c) != r.Call(at + 0x28) ||
            r.Call(lightAt + 0x1c) != r.Call(at + 0x28)) return false;
    const uint32_t trueSlot = Slot(r.W(trueAt + 4));
    const uint32_t falseSlot = Slot(r.W(lightAt + 4));
    const unsigned trueDescriptor = PoolSlot(r, trueAt + 0x14, trueAt + 0x18);
    const unsigned falseDescriptor = PoolSlot(r, lightAt + 0x14, lightAt + 0x18);
    if (!trueSlot || trueSlot != falseSlot + 8 || !trueDescriptor ||
            falseDescriptor != trueDescriptor + 8 ||
            !ExtraAllocator(r, r.Call(allocation))) return false;
#ifndef __ANDROID__
    if (folder) {
        const size_t press = r.Call(at + (old ? 0x7c : 0x84));
        if (!press || !r.Match(press, kExtraPressLight) ||
                r.Call(at + (old ? 0xb4 : 0xbc)) != press) return false;
        if (!old) {
            const uint32_t argument = r.W(at + 0x54) & 31;
            if ((argument != 2 && argument != 3) ||
                    r.W(at - 8) != (0x6b16001fu | (argument << 5)) ||
                    (r.W(at - 4) & 0xff00001fu) != 0x54000001u ||
                    r.Relative(at - 4, 19, 5) != at + 0x54) return false;
        }
    }
#endif
    LauncherGlassMaterialChooseSite p{};
    p.kind = kind;
    p.word = r.W(branch);
    p.branch = branch;
    p.darkTarget = branch + 4;
    p.lightTarget = lightAt;
    p.resultRegister = uint8_t(p.word & 31);
    p.boolBit = uint8_t(((p.word >> 19) & 31) | ((p.word >> 26) & 32));
    p.trueTokenSlot = trueSlot;
    p.falseTokenSlot = falseSlot;
    p.valueCall = branch - 4;
    p.valueOriginal = material.valueOriginal;
    p.nativeJoin = join;
    p.source = at;
    p.widgetAllocator = r.Call(allocation);
    p.lightSchemeTarget = p.darkTarget;
    p.darkSchemeTarget = p.lightTarget;
    *out = p;
    return true;
}

#ifndef __ANDROID__
// Saved-source research only; never admitted or scanned by the Android runtime.
bool DefaultColorsChoose(const Reader& r, size_t at,
        LauncherGlassMaterialChooseSite* out, bool* ambiguous) {
    *ambiguous = false;
    if (!r.Match(at, kExtraFolderDefaultColors)) return false;
    const size_t array = r.Call(at + 0x1c);
    const size_t growable = r.Call(at + 0x50);
    if (!array || !growable || array == growable ||
            r.Call(at + 0x84) != array || r.Call(at + 0xb8) != growable ||
            !r.Match(array, kNativeStyleArray) ||
            !r.Match(growable, kNativeStyleGrowable)) return false;
    size_t dark = 0, light = 0;
    unsigned darkCount = 0, lightCount = 0;
    for (size_t i = 0; i < r.im.range_count; ++i) {
        const auto& range = r.im.ranges[i];
        if (!range.executable || range.end - range.begin < 4) continue;
        for (size_t pc = range.begin; pc <= range.end - 4; pc += 4) {
            if (pc < 0x48 || r.Call(pc) != at) continue;
            const size_t start = pc - 0x48;
            if (r.Match(start, kExtraFolderDarkConfig)) {
                dark = start;
                ++darkCount;
            }
            if (r.Match(start, kExtraFolderLightConfig)) {
                light = start;
                ++lightCount;
            }
        }
    }
    if (darkCount > 1 || lightCount > 1) *ambiguous = true;
    if (darkCount != 1 || lightCount != 1 ||
            !r.Call(dark + 0x3c) || r.Call(dark + 0x3c) != r.Call(light + 0x3c) ||
            !r.Call(dark + 0x50) || r.Call(dark + 0x50) != r.Call(light + 0x50))
        return false;
    const size_t allocator = r.Call(dark + 0x50);
    if (!r.Match(allocator, kNativeStyleConfigAllocator)) return false;
    const uint32_t tag = ((r.W(allocator) >> 5) & 65535u) |
            (((r.W(allocator + 4) >> 5) & 65535u) << 16);
    if ((tag & 4095u) != 0x31cu ||
            (tag >> 12) != ((r.W(allocator + 0x18) >> 10) & 4095u))
        return false;
    LauncherGlassMaterialChooseSite p{};
    p.kind = kLauncherGlassMaterialFolderDefaultColors;
    p.source = at;
    p.branch = at + 0xc;
    p.word = r.W(p.branch);
    p.resultRegister = 1;
    p.boolBit = 4;
    // This constructor's TRUE is Dark, unlike Wallpaper's TRUE=Light.
    p.darkTarget = p.branch + 4;
    p.lightTarget = r.Relative(p.branch, 14, 5);
    if (p.lightTarget != at + 0x78) return false;
    p.lightSchemeTarget = p.lightTarget;
    p.darkSchemeTarget = p.darkTarget;
    p.widgetAllocator = allocator;
    p.darkConfigFactory = dark;
    p.lightConfigFactory = light;
    *out = p;
    return true;
}
#endif

} // namespace

bool ResolveLauncherGlassSearchCapsule(const IconHighlightImage& image,
        const LauncherGlassMaterialProfile& material,
        LauncherGlassMaterialChooseSites* out, const char** why) {
    if (why) *why = nullptr;
    if (!out) return Reject(why, "missing search material output");
    *out = {};
    if (!ValidateArm64DartImage(image)) return Reject(why, "invalid search AOT image");
    Reader r{image};
    if (!material.closure || !r.Match(material.closure, kClosure))
        return Reject(why, "material source changed before search admission");
    unsigned count = 0;
    LauncherGlassMaterialChooseSite candidate{};
    for (size_t i = 0; i < image.range_count; ++i) {
        const auto& range = image.ranges[i];
        if (!range.executable || range.end - range.begin < 4) continue;
        for (size_t at = range.begin; at <= range.end - 4; at += 4) {
            if ((r.W(at) & kExtraSearchCapsule[0].mask) != kExtraSearchCapsule[0].word ||
                    !SearchCapsuleMatches(r, at)) continue;
            LauncherGlassMaterialChooseSite site{};
            if (!ExtraChoose(r, material, at, kLauncherGlassMaterialSearchCapsule,
                    false, &site)) continue;
            candidate = site;
            if (++count > 1) {
                out->ambiguousMask = kLauncherGlassMaterialSearchCapsule;
                return true;
            }
        }
    }
    if (count == 1) {
        out->sites[0] = candidate;
        out->count = 1;
        out->admittedMask = kLauncherGlassMaterialSearchCapsule;
    }
    return true;
}

#ifndef __ANDROID__
// These experimental source proofs remain available to host audits only.
bool ResolveLauncherGlassMaterialChooseSites(const IconHighlightImage& image,
        const LauncherGlassMaterialProfile& material, LauncherGlassMaterialChooseSites* out,
        const char** why) {
    if (why) *why = nullptr;
    if (!out) return Reject(why, "missing optional material choose output");
    memset(out, 0, sizeof(*out));
    if (!ValidateArm64DartImage(image)) return Reject(why, "invalid optional material AOT image");
    Reader r{image};
    if (!r.Match(material.build, kBuild) || !r.Match(material.closure, kClosure) ||
            material.branch != material.closure + 0x68 ||
            material.branchWord != r.W(material.branch) ||
            material.valueCall != material.branch - 4 ||
            material.valueOriginal != r.Call(material.valueCall) ||
            material.controllerSlot != Slot(r.W(material.closure + 0x20)) ||
            material.wallpaperRxField != Field(r.W(material.closure + 0x5c)))
        return Reject(why, "optional sites require pristine admitted Dock source");
    LauncherGlassMaterialChooseSites p{};
    unsigned counts[3]{};
    LauncherGlassMaterialChooseSite candidates[3]{};
    for (size_t i = 0; i < image.range_count; ++i) {
        const auto& range = image.ranges[i];
        if (!range.executable || range.end - range.begin < 4) continue;
        for (size_t at = range.begin; at <= range.end - 4; at += 4) {
            if (r.W(at) == kExtraFolderDefaultColors[0].word &&
                    r.Match(at, kExtraFolderDefaultColors)) {
                LauncherGlassMaterialChooseSite candidate{};
                bool ambiguous = false;
                if (DefaultColorsChoose(r, at, &candidate, &ambiguous)) {
                    if (counts[2] < 2) ++counts[2];
                    candidates[2] = candidate;
                }
                if (ambiguous) counts[2] = 2;
            }
            if (r.W(at) != 0xf9403f40u) continue;
            const bool old = r.Match(at, kExtraFolderOld);
            const bool modern = r.Match(at, kExtraFolderNew);
            const bool search = r.Match(at, kExtraSearchCapsule);
            if (!old && !modern && !search) continue;
            const unsigned role = search ? 1 : 0;
            LauncherGlassMaterialChooseSite candidate{};
            if (!ExtraChoose(r, material, at, role + 1, old, &candidate)) continue;
            if (counts[role] < 2) ++counts[role];
            candidates[role] = candidate;
        }
    }
    if (counts[0] == 1 && counts[1] == 1 &&
            (candidates[0].widgetAllocator != candidates[1].widgetAllocator ||
             candidates[0].trueTokenSlot != candidates[1].trueTokenSlot ||
             candidates[0].falseTokenSlot != candidates[1].falseTokenSlot)) {
        p.ambiguousMask = 3;
        counts[0] = counts[1] = 0;
    }
    for (unsigned i = 0; i < 3; ++i) {
        if (counts[i] > 1) p.ambiguousMask |= 1u << i;
        if (counts[i] != 1) continue;
        p.admittedMask |= 1u << i;
        p.sites[p.count++] = candidates[i];
    }
    *out = p;
    return true;
}

bool ResolveLauncherGlassMaterialFolderRefresh(const IconHighlightImage& image,
        const LauncherGlassMaterialProfile& material,
        LauncherGlassMaterialFolderRefreshProfile* out, const char** why) {
    if (why) *why = nullptr;
    if (!out) return Reject(why, "missing folder refresh profile output");
    memset(out, 0, sizeof(*out));
    if (!ValidateArm64DartImage(image))
        return Reject(why, "invalid folder refresh AOT image");
    Reader r{image};
    LauncherGlassMaterialFolderRefreshProfile p{};
    p.refresh = material.refreshEntry;
    if (!p.refresh || p.refresh != material.rx.refresh ||
            !r.Match(p.refresh, kNativeStyleRefresh) ||
            r.Call(p.refresh + 0x18) != material.valueOriginal ||
            r.Call(p.refresh + 0x24) != material.rx.stream_add ||
            Field(r.W(p.refresh + 0xc)) != int(material.rxSubjectField) ||
            Field(r.W(material.valueOriginal + 0x2c)) != int(material.rxSubjectField) ||
            Field(r.W(material.valueOriginal + 0x3c)) != int(material.rxValueField) ||
            Slot(r.W(material.valueOriginal + 0x18)) != material.observerSlot ||
            r.W(material.returnPc) != material.replayWord ||
            material.resume != material.returnPc + 4)
        return Reject(why, "folder refresh inherited Rx/frame source changed");
    LauncherGlassMaterialProfile maps = material;
    if (!StackMaps(r, &maps))
        return Reject(why, "folder inherited refresh GC maps changed");

    unsigned inits = 0, changed = 0;
    for (size_t i = 0; i < image.range_count; ++i) {
        const auto& range = image.ranges[i];
        if (!range.executable || range.end - range.begin < 12) continue;
        for (size_t pc = range.begin; pc <= range.end - 12; pc += 4) {
            if (r.W(pc) != 0xa9bf79fdu || r.W(pc + 4) != 0xaa0f03fdu) continue;
            if (r.W(pc + 8) == kNativeStyleFolderInit[2].word &&
                    r.Match(pc, kNativeStyleFolderInit) &&
                    (r.Match(r.Call(pc + 0x9c), kNativeStyleChild) ||
                            r.Match(r.Call(pc + 0x9c), kNativeStyleChildOld))) {
                p.initState = pc;
                ++inits;
            }
            if (r.W(pc + 8) == kNativeStyleFolderChanged[2].word &&
                    r.Match(pc, kNativeStyleFolderChanged) &&
                    r.Match(pc + 0x9c, kNativeStyleFolderCallback) &&
                    (r.Match(r.Call(pc + 0xec), kNativeStyleChild) ||
                            r.Match(r.Call(pc + 0xec), kNativeStyleChildOld))) {
                p.onChanged = pc;
                ++changed;
            }
        }
    }
    if (inits != 1 || changed != 1)
        return Reject(why, "missing/ambiguous folder refresh source roots");

    const size_t init = p.initState, change = p.onChanged;
    p.controllerSlot = Slot(r.W(init + 0x14));
    p.rxField = (r.W(init + 0x60) >> 5) & 65535u;
    p.buildChild = r.Call(init + 0x9c);
    p.observerCallback = change - 0x28;
    p.rebuildCallback = change + 0x9c;
    p.stateSetState = r.Call(change + 0x7c);
    p.closureAllocator = r.Call(change + 0x70);
    p.observerDescriptorSlot = PoolSlot(r, init + 0x124, init + 0x128);
    p.rebuildDescriptorSlot = PoolSlot(r, change + 0x68, change + 0x6c);
    const size_t ever = r.Call(init + 0x144);
    if (!p.controllerSlot || p.controllerSlot == material.controllerSlot ||
            p.buildChild != init + sizeof(kNativeStyleFolderInit) / 2 ||
            Slot(r.W(init + 0xc4)) != p.controllerSlot ||
            Slot(r.W(init + 0x24)) != Slot(r.W(init + 0xd4)) ||
            PoolSlot(r, init + 0x3c, init + 0x40) !=
                    PoolSlot(r, init + 0xec, init + 0xf0) ||
            !r.Call(init + 0x4c) || r.Call(init + 0x4c) != r.Call(init + 0xfc) ||
            r.Call(init + 0x70) != material.valueOriginal ||
            !r.Match(p.observerCallback, kNativeStyleFolderObserver) ||
            r.Call(p.observerCallback + 0x18) != change ||
            !r.Match(p.rebuildCallback, kNativeStyleFolderCallback) ||
            r.Call(p.rebuildCallback + 0x50) != p.buildChild ||
            !r.Match(p.stateSetState, kNativeStyleStateSet) ||
            !r.Match(p.closureAllocator, kNativeStyleClosure) ||
            r.Call(init + 0x12c) != p.closureAllocator ||
            !r.Match(ever, kNativeStyleEver) ||
            !(r.Match(p.buildChild, kNativeStyleChild) ||
                    r.Match(p.buildChild, kNativeStyleChildOld)) ||
            !p.observerDescriptorSlot ||
            p.rebuildDescriptorSlot != p.observerDescriptorSlot + 8 ||
            PoolSlot(r, change + 0x90, change + 0x94) !=
                    p.rebuildDescriptorSlot + 8)
        return Reject(why, "folder subscription/cache callback links changed");

    unsigned rxLoads = 0;
    for (size_t off = 0; off < 0x178; off += 4) {
        if ((r.W(init + off) & 0xffe0001fu) != 0xd2800011u) continue;
        if (((r.W(init + off) >> 5) & 65535u) != p.rxField)
            return Reject(why, "folder cached Rx receiver field disagrees");
        ++rxLoads;
    }
    if (rxLoads != 2 || !p.rxField)
        return Reject(why, "folder cached Rx receiver source missing");

    p.stateModeField = uint32_t(Field(r.W(change + 0x30)));
    p.stateChildField = uint32_t(Field(r.W(init + 0xa4)));
    p.contextStateField = uint32_t(Field(r.W(change + 0x24)));
    p.contextValueField = uint32_t(Field(r.W(change + 0x2c)));
    p.functionEntryField = uint32_t(Field(r.W(p.closureAllocator + 0x40)));
    p.closureEntryField = uint32_t(Field(r.W(p.closureAllocator + 0x44)));
    if (Field(r.W(init + 0x78)) != int(p.stateModeField) ||
            Field(r.W(p.rebuildCallback + 0x2c)) != int(p.stateModeField) ||
            Field(r.W(p.rebuildCallback + 0x58)) != int(p.stateChildField) ||
            Field(r.W(p.rebuildCallback + 0x18)) != int(p.contextStateField) ||
            Field(r.W(p.rebuildCallback + 0x24)) != int(p.contextValueField) ||
            p.stateModeField == p.stateChildField ||
            p.contextStateField == p.contextValueField ||
            p.functionEntryField != 7 || p.closureEntryField != 0x1f)
        return Reject(why, "folder cached child/context fields changed");

    unsigned builds = 0, childCallers = 0;
    for (size_t i = 0; i < image.range_count; ++i) {
        const auto& range = image.ranges[i];
        if (!range.executable || range.end - range.begin < 4) continue;
        for (size_t pc = range.begin; pc <= range.end - 4; pc += 4) {
            if (r.Call(pc) == p.buildChild) {
                if (pc != init + 0x9c && pc != p.rebuildCallback + 0x50)
                    return Reject(why, "unexpected native cached child caller");
                ++childCallers;
            }
            if ((r.W(pc) & 0xffe00fffu) != kNativeStyleFolderBuild[0].word ||
                    !r.Match(pc, kNativeStyleFolderBuild) ||
                    Field(r.W(pc)) != int(p.stateChildField) ||
                    PoolSlot(r, pc + 0x20, pc + 0x24) + 16 !=
                            p.observerDescriptorSlot) continue;
            p.stateBuild = pc;
            ++builds;
        }
    }
    if (builds != 1 || childCallers != 2)
        return Reject(why, "missing/ambiguous native cached child getter");

    p.equalityBranch = change + 0x60;
    p.equalityWord = r.W(p.equalityBranch);
    p.equalityFallthrough = p.equalityBranch + 4;
    p.equalityTaken = r.Relative(p.equalityBranch, 19, 5);
    p.onChangedRet = change + 0x8c;
    p.onChangedRetWord = r.W(p.onChangedRet);
    p.smiShift = 1;
    p.smiValueBits = 31;
    p.boxedIntValueField = uint32_t(Field(r.W(change + 0x4c)));
    if (p.equalityWord != 0x54000100u ||
            p.equalityTaken != change + 0x80 ||
            p.onChangedRetWord != 0xd65f03c0u ||
            p.boxedIntValueField != 7 ||
            Field(r.W(change + 0x58)) != int(p.boxedIntValueField))
        return Reject(why, "folder integer equality/unbox source changed");
    // Fallthrough moves the captured context to x2, then the native allocator
    // defines x0 before its first read. Taken immediately defines x0 as null.
    p.x0DeadOnBothSuccessors = true;
    p.requiresRuntimeCallbackEntryCheck = true;
    p.onChangedStateFrameOffset = -8;
    p.onChangedFrameBytes = 16;
    p.subjectField = material.rxSubjectField;
    p.valueField = material.rxValueField;
    p.observerSlot = material.observerSlot;
    p.subjectListenersField = material.subjectListenersField;
    p.subjectBusyField = material.subjectBusyField;
    p.subjectListenerLengthField = material.subjectListenerLengthField;
    p.refreshRet = p.refresh + 0x34;
    p.refreshRetWord = r.W(p.refreshRet);
    p.refreshValueReturn = p.refresh + 0x1c;
    p.refreshAddReturn = p.refresh + 0x28;
    p.refreshSubjectFrameOffset = -8;
    p.refreshRetSubjectDartSpOffset = -24;
    p.refreshFrameBytes = 8;
    p.refreshSubjectLiveAcrossAdd = false;
    p.nativeModeMin = 1;
    p.nativeModeMax = 2;
    *out = p;
    return true;
}

#endif // !__ANDROID__
