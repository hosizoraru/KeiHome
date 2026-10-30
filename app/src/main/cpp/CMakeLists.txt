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
    monet_hooks.cpp
    monet_image_identity.cpp
    monet_policy_server.cpp
    monet_tint_profile.cpp
    monochrome_gate_resolver.cpp
    monet_tint_bridge.S
    selection_profile.cpp
    monet_selection_bridge.S
)

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
