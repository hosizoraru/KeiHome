// SPDX-License-Identifier: Apache-2.0
#pragma once

#include "dock_glass_refresh.h"
#include "launcher_label_profile.h"

struct LauncherGlassMaterialStackMap {
    size_t payload;
    size_t bitmap;
    uint32_t spillBits;
    uint32_t otherBits;
};

struct LauncherGlassMaterialProfile {
    size_t build;
    size_t closure;
    size_t valueCall;
    size_t valueOriginal;
    size_t valueReturnPc;
    size_t branch;
    uint32_t branchWord;
    uint8_t resultRegister;
    uint8_t boolBit;
    uint16_t reserved;
    size_t darkTarget;   // native TRUE/light wallpaper; historical OEM filter name
    size_t lightTarget;  // native FALSE/other wallpaper
    size_t nativeJoin;
    uint32_t controllerSlot; // byte offset in [x26 + 0x78] field table
    uint32_t controllerSourceCount;
    int32_t wallpaperRxField;
    int32_t darkCacheField;
    int32_t lightCacheField;
    int32_t capturedStateField;
    uint32_t rxSubjectField;
    uint32_t rxValueField;
    uint32_t observerSlot;
    size_t refreshEntry;
    uint32_t subjectListenersField; // compressed list pointer; null after close
    uint32_t subjectBusyField;      // canonical true/false; null after close
    uint32_t subjectListenerLengthField; // compressed Smi, relative to tagged list
    size_t subjectCloseEntry;
    size_t subjectNotifyDataEntry;
    size_t subjectNotifyDoneEntry;
    size_t wallpaperSetter;
    uint32_t nativeTrueOffset;  // x22 + offset
    uint32_t nativeFalseOffset;

    // Existing first item-logger return checkpoint, not a new Dart frame.
    // Replay word once on the notified pass, then tail-branch to resume.
    size_t returnPc;
    uint32_t replayWord;
    uint32_t eventFrameBytes;
    size_t resume;
    size_t firstLoggerCall;
    size_t secondLoggerCall;
    size_t event;
    size_t instructionsTable;
    LauncherGlassMaterialStackMap branchMap;
    LauncherGlassMaterialStackMap eventMap;
    LauncherGlassMaterialStackMap refreshValueMap;
    LauncherGlassMaterialStackMap refreshAddMap;
    DockGlassRefreshProfile rx;
};

// Read-only, bounded AOT source/relationship proof. No symbol/version/hash/
// address lookup tables, runtime hooks, retained Dart pointers or wallpaper writes.
// labels must be the existing authenticated, unpatched label source profile.
// All addresses in the result are relative to image.base.
bool ResolveLauncherGlassMaterial(const IconHighlightImage& image,
        const LauncherLabelProfile& labels, LauncherGlassMaterialProfile* out,
        const char** rejection = nullptr);

enum LauncherGlassMaterialChooseKind : uint32_t {
    // FolderBlurUtils' light/press layer, not the separate resting folder blur.
    // Admission establishes neither a resting preset nor resting refresh.
    kLauncherGlassMaterialFolder = 1,
    kLauncherGlassMaterialSearchCapsule = 2,
    kLauncherGlassMaterialFolderDefaultColors = 3,
};
constexpr size_t kLauncherGlassMaterialExtraChooseCapacity = 4;

struct LauncherGlassMaterialChooseSite {
    uint32_t kind;
    uint32_t word;
    size_t branch;
    // Historical names: native TRUE / FALSE respectively. Wallpaper roles
    // mean Light / Dark choice; finite default colors mean Dark / Light.
    size_t darkTarget;
    size_t lightTarget;
    uint8_t resultRegister;
    uint8_t boolBit;
    uint16_t reserved;
    uint32_t trueTokenSlot;
    uint32_t falseTokenSlot;
    size_t valueCall;
    size_t valueOriginal;
    size_t nativeJoin;
    size_t source;
    size_t widgetAllocator;
    // Explicit UI scheme targets. Wallpaper TRUE means Light; the native
    // default-color factory's TRUE means Dark. Do not infer polarity by kind.
    size_t lightSchemeTarget;
    size_t darkSchemeTarget;
    size_t darkConfigFactory;
    size_t lightConfigFactory;
};

struct LauncherGlassMaterialChooseSites {
    uint32_t count;
    uint32_t admittedMask;  // bit 0 light layer, 1 CAPSULE, 2 default-color factory
    uint32_t ambiguousMask;
    uint32_t reserved;
    LauncherGlassMaterialChooseSite sites[kLauncherGlassMaterialExtraChooseCapacity];
};

// Optional, independent of the Dock resolver/refresh admission. material must be
// its pristine-image result. Each role admits exactly one complete native source
// flow, or contributes no site; ambiguity disables that role. count can be 0..3.
// Conflicting token/constructor ownership between two otherwise unique roles
// disables both optional roles (ambiguousMask=3), without changing Dock admission.
// This covers FolderBlurUtils' default Wallpaper argument in the light/press
// layer and LauncherIndicator's glass search capsule. The separate resting
// CompatBlurBlendColorWidget has no admitted Wallpaper Rx choose site.
// Explicit caller-provided folder arguments, drawer search,
// labels/colors/status bars, and generic glass rendering/parameter builders are
// outside these sites. A choose leaf must preserve the original getter/result
// and run no Dart/native calls. Existing Rx.refresh admission remains unchanged.
// FolderDefaultColors selects finite OEM lists at construction, before config
// caching; valueCall/valueOriginal and historical darkTarget/lightTarget are 0.
// It admits no new refresh path and cannot itself rebuild a cached resting child.
// Shipping scope: Dock plus the unique native search capsule. Folder source
// audits do not establish a resting material pair or a safe refresh path.
bool ResolveLauncherGlassSearchCapsule(const IconHighlightImage&,
        const LauncherGlassMaterialProfile&, LauncherGlassMaterialChooseSites*,
        const char** why = nullptr);
#ifndef __ANDROID__
bool ResolveLauncherGlassMaterialChooseSites(const IconHighlightImage& image,
        const LauncherGlassMaterialProfile& material, LauncherGlassMaterialChooseSites* out,
        const char** rejection = nullptr);

struct LauncherGlassMaterialFolderRefreshProfile {
    size_t initState;
    size_t buildChild;
    size_t stateBuild;
    size_t onChanged;
    size_t observerCallback;
    size_t rebuildCallback;
    size_t stateSetState;
    size_t closureAllocator;
    uint32_t controllerSlot;
    uint32_t rxField;
    uint32_t subjectField;
    uint32_t valueField;
    uint32_t observerSlot;
    uint32_t subjectListenersField;
    uint32_t subjectBusyField;
    uint32_t subjectListenerLengthField;
    size_t refresh;
    size_t refreshRet;
    uint32_t refreshRetWord;
    size_t refreshValueReturn;
    size_t refreshAddReturn;
    int32_t refreshSubjectFrameOffset;
    int32_t refreshRetSubjectDartSpOffset;
    uint32_t refreshFrameBytes;
    bool refreshSubjectLiveAcrossAdd;
    int32_t onChangedStateFrameOffset;
    uint32_t onChangedFrameBytes;
    size_t equalityBranch;
    uint32_t equalityWord;
    size_t equalityFallthrough;
    size_t equalityTaken;
    size_t onChangedRet;
    uint32_t onChangedRetWord;
    uint32_t stateModeField;
    uint32_t stateChildField;
    uint32_t contextStateField;
    uint32_t contextValueField;
    uint32_t observerDescriptorSlot;
    uint32_t rebuildDescriptorSlot;
    uint32_t functionEntryField;
    uint32_t closureEntryField;
    uint32_t smiShift;
    uint32_t smiValueBits;
    uint32_t boxedIntValueField;
    uint32_t nativeModeMin;
    uint32_t nativeModeMax;
    bool x0DeadOnBothSuccessors;
    bool requiresRuntimeCallbackEntryCheck;
};

// Separate optional role; failure clears only this result. No setting mutation.
// The source gate accepts Smi / boxed int at +7. Native mode writers emit Smi
// 1/2. Readiness should require a Smi in that range; boxed class identity is not
// authenticated here. Before notifying require each current PP descriptor's
// functionEntryField to equal base + observerCallback / rebuildCallback.
// This check needs no Dart call, allocation or retained pointer.
//
// At the equality gate preserve x1 (native captured context) and NZCV. x0 is
// dead on both original successors; x16/x17 are native scratch registers.
// At refreshRet the original epilogue has restored caller FP/LR. Complete the
// captured primitive revision only for the captured owner FP + original first
// logger return PC, preserve x0/NZCV/SP/LR, then execute refreshRetWord (RET).
// IMPORTANT: the add-return GC map EXCLUDES the old subject spill. The raw
// bytes at refreshRetSubjectDartSpOffset may contain a pre-GC address. Never
// dereference or authenticate managed identity through that dead slot.
bool ResolveLauncherGlassMaterialFolderRefresh(const IconHighlightImage& image,
        const LauncherGlassMaterialProfile& material,
        LauncherGlassMaterialFolderRefreshProfile* out,
        const char** rejection = nullptr);

// Wallpaper semantic polarity: TRUE means applied LIGHT wallpaper, not dark
// wallpaper or necessarily darker pixels. safeAppliedLightWallpaper returns
// this Rx's value without inversion. Historical darkTarget names the TRUE
// path/OEM _darkFilter preset; lightTarget names the FALSE path. UI LIGHT=1
// therefore selects darkTarget (TRUE), and UI DARK=2 selects lightTarget (FALSE).
// With these canonical bools bit 4 is CLEAR for TRUE and SET for FALSE.
// Decode the original conditional's taken/fallthrough polarity from its word.
// Main's leaf contract:
// 1. Always execute valueOriginal at valueCall with its ORIGINAL Dart LR before
//    selecting a branch. Follow-native replays branchWord; forced material only
//    selects darkTarget/lightTarget. Preserve the native tagged Rx result.
// 2. At returnPc only, on the UI thread, load the cached controller from the
//    current field table, then its Rx/subject. Reject null/sentinel or non-null
//    activeObserver. Native close NULLS subjectListenersField and subjectBusyField;
//    there is NO separate closed boolean. Reject either null, and require the
//    busy field to equal canonical false before synchronous notification. The
//    non-null listener list's length is a Smi at subjectListenerLengthField;
//    require positive length when admission requires existing listeners.
//    Do not allocate, call Getx.find, cache these pointers, or write Rx.value.
// 3. Mark this saved-choice revision notified BEFORE tail-calling rx.refresh,
//    with x1 = the current Wallpaper Rx and x30 = the SAME returnPc. Native
//    refresh's null result is discarded here. On re-entry replay replayWord
//    exactly once, then resume; leave label and Dock continuations intact.

#endif // !__ANDROID__
