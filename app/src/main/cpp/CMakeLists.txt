cmake_minimum_required(VERSION 3.22)

project(keihome_hyos_native LANGUAGES CXX ASM)

if(NOT ANDROID)
    message(FATAL_ERROR "KeiHome native payload requires the Android NDK")
endif()
if(NOT "${ANDROID_ABI}" STREQUAL "arm64-v8a")
    message(FATAL_ERROR "KeiHome supports only arm64-v8a for the launcher payload")
endif()

add_library(keihome_hyos_lsp SHARED
    keihome_hyos_native_hook.cpp
    lsposed_hook_backend.cpp
    arm64_exact_patch.cpp
    icon_highlight_profile.cpp
    icon_highlight_hooks.cpp
    icon_highlight_bridge.S
    launcher_label_profile.cpp
    launcher_label_hooks.cpp
    launcher_label_bridge.S
    dock_glass_profile.cpp
    dock_glass_refresh.cpp
    dock_glass_hooks.cpp
    dock_glass_payload.S
    launcher_glass_policy.cpp
    launcher_glass_material_profile.cpp
    launcher_glass_material_hooks.cpp
    launcher_glass_material_bridge.S
    launcher_glass_patch.cpp
    monet_hooks.cpp
    monet_image_identity.cpp
    monet_image_recovery.cpp
    monet_image_recovery_resolver.cpp
    monet_image_recovery_bridge.S
    monet_policy_server.cpp
    monet_tint_profile.cpp
    monochrome_gate_resolver.cpp
    monet_tint_bridge.S
    selection_profile.cpp
    monet_selection_bridge.S
)

# A disposable diagnostic build only. OFF contributes no observer or worker to
# the payload; changing Java policy/reloading API 102 cannot enable this option.
option(KEIHOME_MONET_IMAGE_TRACE "Bounded Dart Image ownership diagnostics (not a fix)" OFF)
if(KEIHOME_MONET_IMAGE_TRACE)
    set(KEIHOME_IMAGE_TRACE_DELAY_MS 30000 CACHE STRING "Delay before the single capture")
    set(KEIHOME_IMAGE_TRACE_WINDOW_MS 120000 CACHE STRING "Maximum capture duration")
    foreach(setting KEIHOME_IMAGE_TRACE_DELAY_MS KEIHOME_IMAGE_TRACE_WINDOW_MS)
        if(NOT "${${setting}}" MATCHES "^[0-9]+$" OR ${setting} LESS 1000 OR ${setting} GREATER 300000)
            message(FATAL_ERROR "${setting} must be an integer in [1000, 300000]")
        endif()
    endforeach()
    target_sources(keihome_hyos_lsp PRIVATE monet_image_trace.cpp
        monet_image_trace_resolver.cpp monet_image_trace_bridge.S)
    target_compile_definitions(keihome_hyos_lsp PRIVATE KH_MONET_IMAGE_TRACE=1
        KH_IMAGE_TRACE_DELAY_MS=${KEIHOME_IMAGE_TRACE_DELAY_MS}
        KH_IMAGE_TRACE_WINDOW_MS=${KEIHOME_IMAGE_TRACE_WINDOW_MS})
endif()

target_include_directories(keihome_hyos_lsp PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
)
target_compile_features(keihome_hyos_lsp PRIVATE cxx_std_17)
target_compile_options(keihome_hyos_lsp PRIVATE
    -fno-exceptions
    -fno-rtti
    -fno-threadsafe-statics
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -mbranch-protection=standard
    -Wall
    -Wextra
    -Werror
)
target_link_options(keihome_hyos_lsp PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
    -Wl,--gc-sections
    -Wl,--no-undefined
    -Wl,-z,relro
    -Wl,-z,now
)
target_link_libraries(keihome_hyos_lsp PRIVATE log dl binder_ndk)
set_target_properties(keihome_hyos_lsp PROPERTIES
    CXX_EXTENSIONS OFF
    OUTPUT_NAME "keihome_hyos_lsp"
)
