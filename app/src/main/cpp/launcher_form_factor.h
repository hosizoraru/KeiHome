// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <string_view>

// Android's product classification, not a model, version or window-width gate.
inline bool HasTabletCharacteristic(std::string_view value) {
    while (!value.empty()) {
        const size_t comma = value.find(',');
        auto token = value.substr(0, comma);
        while (!token.empty() && (token.front() == ' ' || token.front() == '\t'))
            token.remove_prefix(1);
        while (!token.empty() && (token.back() == ' ' || token.back() == '\t'))
            token.remove_suffix(1);
        if (token == "tablet") return true;
        if (comma == std::string_view::npos) break;
        value.remove_prefix(comma + 1);
    }
    return false;
}

bool IsTabletLauncherDevice();
