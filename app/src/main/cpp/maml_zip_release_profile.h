// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "icon_highlight_profile.h"

// Resolves only Arc<zip::read::zip_archive::Shared>::drop_slow. In the
// authenticated native ABI x0 is the final-owned ArcInner pointer, not &Arc.
// Metadata contains ZIP entries/strings; MAML views and GPU state are excluded.
// Authenticates the reachable normal destructor graph, including ZipFileData
// elements, RawTable<String, usize>, raw vectors, Weak and imported free PLT/GOT.
// Exception landing pads and the implementation interposed behind libc free
// are outside this proof. Unknown graph shapes fail closed.
bool ResolveMamlZipRelease(const IconHighlightImage& image, uintptr_t* address,
                           const char** rejection = nullptr);
