// SPDX-License-Identifier: Apache-2.0
#include "launcher_form_factor.h"
#include <sys/system_properties.h>

bool IsTabletLauncherDevice() {
    char value[PROP_VALUE_MAX]{};
    const int length = __system_property_get("ro.build.characteristics", value);
    return length > 0 && HasTabletCharacteristic(
        std::string_view(value, static_cast<size_t>(length)));
}
