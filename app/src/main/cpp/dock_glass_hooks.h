// SPDX-License-Identifier: Apache-2.0
#pragma once
struct VerifiedDartImage;
// Preparation authenticates the pristine mapped image, before label hooks.
bool PrepareDockGlassAtLibraryLoad(void* handle);
bool PrepareDockGlassForVerifiedImage(const VerifiedDartImage& verified);
bool InstallPreparedDockGlass();
