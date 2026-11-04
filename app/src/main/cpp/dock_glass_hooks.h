// SPDX-License-Identifier: Apache-2.0
#pragma once
// Preparation authenticates the pristine mapped image, before label hooks.
bool PrepareDockGlassAtLibraryLoad(void* handle);
bool InstallPreparedDockGlass();
