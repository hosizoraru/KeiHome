// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "monochrome_gate_resolver.h"

bool ResolveLoadedMonochromeGate(void* handle, MonochromeGatePatch* out,
                                const char** rejection);
bool InstallMonetHooksAtLibraryLoad(void* handle);
