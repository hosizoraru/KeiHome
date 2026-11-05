// SPDX-License-Identifier: Apache-2.0
#pragma once
#define KH_GLASS_MATERIAL_INSTALLED        0
#define KH_GLASS_MATERIAL_CONTROL          8
#define KH_GLASS_MATERIAL_REVISION         16
#define KH_GLASS_MATERIAL_NOTIFIED         24
#define KH_GLASS_MATERIAL_CONTROLLER_SLOT  32
#define KH_GLASS_MATERIAL_RX_FIELD         40
#define KH_GLASS_MATERIAL_SUBJECT_FIELD    48
#define KH_GLASS_MATERIAL_VALUE_FIELD      56
#define KH_GLASS_MATERIAL_OBSERVER_SLOT    64
#define KH_GLASS_MATERIAL_REFRESH          72
#define KH_GLASS_MATERIAL_EVENT_RETURN     80
#define KH_GLASS_MATERIAL_TRUE_OFFSET      88
#define KH_GLASS_MATERIAL_FALSE_OFFSET     96
#define KH_GLASS_MATERIAL_LISTENERS_FIELD  104
#define KH_GLASS_MATERIAL_BUSY_FIELD       112
#define KH_GLASS_MATERIAL_LENGTH_FIELD     120
#define KH_GLASS_MATERIAL_CONFIG_SIZE      128

#ifndef __ASSEMBLER__
#include <stdint.h>
struct LauncherGlassMaterialConfig {
    uint64_t installed, control, revision, notified;
    uint64_t controllerSlot, rxField, subjectField, valueField, observerSlot;
    uint64_t refresh, eventReturn;
    uint64_t trueOffset, falseOffset;
    uint64_t listenersField, busyField, lengthField;
};
static_assert(sizeof(LauncherGlassMaterialConfig) == KH_GLASS_MATERIAL_CONFIG_SIZE);
extern "C" {
extern const uint8_t kh_glass_material_begin[], kh_glass_material_choose[];
extern const uint8_t kh_glass_material_original[], kh_glass_material_taken[];
extern const uint8_t kh_glass_material_fall[], kh_glass_material_light[];
extern const uint8_t kh_glass_material_dark[], kh_glass_material_refresh[];
extern const uint8_t kh_glass_material_event_original[], kh_glass_material_event_resume[];
extern const uint8_t kh_glass_material_config[], kh_glass_material_end[];
extern uint64_t kh_glass_material_revision, kh_glass_material_notified;
}
#endif
