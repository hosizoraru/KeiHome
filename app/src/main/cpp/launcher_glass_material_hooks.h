// SPDX-License-Identifier: Apache-2.0
#pragma once
struct VerifiedDartImage;
// Resolve pristine code before the existing label/Dock installations.
bool PrepareLauncherGlassMaterialAtLibraryLoad(void* handle);
bool PrepareLauncherGlassMaterialForVerifiedImage(const VerifiedDartImage& verified);
bool InstallPreparedLauncherGlassMaterial();
bool IsLauncherGlassInstalled();
