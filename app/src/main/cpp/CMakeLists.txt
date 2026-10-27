cmake_minimum_required(VERSION 3.22)

project(kei_home_lsp LANGUAGES CXX ASM)

if(NOT ANDROID)
  message(FATAL_ERROR "kei_home_lsp must use the Android NDK toolchain")
endif()

if(NOT "${ANDROID_ABI}" STREQUAL "arm64-v8a")
  message(FATAL_ERROR "Only arm64-v8a is supported; got '${ANDROID_ABI}'")
endif()

set(LSPlt_LIBRARY
  "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/liblsplt.so"
)
if(NOT EXISTS "${LSPlt_LIBRARY}")
  message(FATAL_ERROR "Missing 16 KB-aligned liblsplt.so: ${LSPlt_LIBRARY}")
endif()

add_library(lsplt::lsplt SHARED IMPORTED)
set_target_properties(
  lsplt::lsplt
  PROPERTIES IMPORTED_LOCATION "${LSPlt_LIBRARY}"
)

add_library(
  kei_home_lsp
  SHARED
  kei_home_dart_bridge.S
  kei_home_native_hook.cpp
  lsposed_hook_backend.cpp
)

target_compile_features(kei_home_lsp PRIVATE cxx_std_17)
target_include_directories(
  kei_home_lsp
  PRIVATE
  third_party/lsplt/include
)
target_compile_options(
  kei_home_lsp
  PRIVATE
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

target_link_options(
  kei_home_lsp
  PRIVATE
  "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
  -Wl,--gc-sections
  -Wl,--no-undefined
  "-Wl,-rpath,$ORIGIN"
  -Wl,-z,relro
  -Wl,-z,now
)

target_link_libraries(kei_home_lsp PRIVATE log dl lsplt::lsplt)

set_target_properties(
  kei_home_lsp
  PROPERTIES
  CXX_EXTENSIONS OFF
  OUTPUT_NAME "kei_home_lsp"
)
