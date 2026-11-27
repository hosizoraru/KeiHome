// SPDX-License-Identifier: Apache-2.0
#pragma once
struct VerifiedDartImage;
bool InstallLauncherLabelsAtLibraryLoad(void* handle);
bool InstallLauncherLabelsForVerifiedImage(const VerifiedDartImage& verified);
