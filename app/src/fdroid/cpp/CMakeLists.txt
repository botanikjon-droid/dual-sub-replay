# Native translation engine for the F-Droid build: Mozilla's Bergamot translator
# (translations/inference, MPL-2.0) built from the pinned submodules, plus a small
# JNI bridge. PCRE2 is built from its submodule so nothing is downloaded here.
cmake_minimum_required(VERSION 3.22)
project(dualsub_bergamot CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# PCRE2 for ssplit's sentence splitter. JIT and tools are not needed.
set(PCRE2_BUILD_PCRE2GREP OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(PCRE2_SUPPORT_JIT OFF CACHE BOOL "" FORCE)
set(PCRE2_SUPPORT_LIBBZ2 OFF CACHE BOOL "" FORCE)
set(PCRE2_SUPPORT_LIBZ OFF CACHE BOOL "" FORCE)
set(PCRE2_SUPPORT_LIBEDIT OFF CACHE BOOL "" FORCE)
set(PCRE2_SUPPORT_LIBREADLINE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
add_subdirectory(pcre2 EXCLUDE_FROM_ALL)
# ssplit's FindPCRE2 keeps these cached values instead of searching the system.
set(PCRE2_LIBRARIES pcre2-8-static CACHE STRING "" FORCE)
set(PCRE2_INCLUDE_DIRS ${CMAKE_CURRENT_BINARY_DIR}/pcre2 CACHE PATH "" FORCE)

# Bergamot/Marian: CPU-only library. Ruy handles int8 GEMM on ARM, intgemm on x86-64.
set(GIT_SUBMODULE OFF CACHE BOOL "" FORCE)
set(USE_WASM_COMPATIBLE_SOURCE OFF CACHE BOOL "" FORCE)
set(COMPILE_CUDA OFF CACHE BOOL "" FORCE)
set(USE_MKL OFF CACHE BOOL "" FORCE)
set(USE_FBGEMM OFF CACHE BOOL "" FORCE)
set(USE_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(SSPLIT_COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "x86_64")
  set(BUILD_ARCH x86-64 CACHE STRING "" FORCE)
  # Marian's bundled faiss uses SSE types but relies on glibc to include their header.
  add_compile_options("SHELL:-include immintrin.h")
else()
  set(BUILD_ARCH armv8-a CACHE STRING "" FORCE)
endif()
# pathie-cpp (bundled with Marian) compiles glob() and iconv(), which Android only
# declares from API 28. Neither is called here: file names are UTF-8 on Android.
# Declaring them weak keeps the library loadable on API 26 and 27.
add_compile_definitions(PATHIE_ASSUME_UTF8_ON_UNIX __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
add_compile_options(-Wno-unguarded-availability -Wno-unguarded-availability-new)

# Marian generates common/git_revision.h from ../.git/logs/HEAD, which resolves to
# a missing path when translations is itself a submodule. Write the header once and
# drop that dependency; every other custom command is passed through unchanged.
function(add_custom_command)
  if("${ARGV}" MATCHES "git_revision\\.h")
    cmake_parse_arguments(REVISION "VERBATIM" "OUTPUT;WORKING_DIRECTORY" "COMMAND;DEPENDS" ${ARGN})
    if(NOT EXISTS "${REVISION_OUTPUT}")
      file(WRITE "${REVISION_OUTPUT}" "#define GIT_REVISION \"mozilla/translations submodule\"\n")
    endif()
    _add_custom_command(OUTPUT "${REVISION_OUTPUT}" COMMAND ${CMAKE_COMMAND} -E touch "${REVISION_OUTPUT}")
  else()
    _add_custom_command(${ARGN})
  endif()
endfunction()

add_subdirectory(translations/inference EXCLUDE_FROM_ALL)

# Marian sets its CPU definitions (ARM/Ruy or intgemm) for its own directory only,
# but Bergamot and the bridge include Marian headers that depend on them.
get_directory_property(MARIAN_DEFINITIONS DIRECTORY translations/inference/marian-fork COMPILE_DEFINITIONS)
get_directory_property(MARIAN_OPTIONS DIRECTORY translations/inference/marian-fork COMPILE_OPTIONS)
target_compile_definitions(bergamot-translator-source PUBLIC ${MARIAN_DEFINITIONS} USE_SENTENCEPIECE)
target_compile_options(bergamot-translator-source PUBLIC ${MARIAN_OPTIONS})

add_library(dualsub_bergamot SHARED bergamot_jni.cpp)
target_link_libraries(dualsub_bergamot PRIVATE bergamot-translator-source log)
target_link_options(dualsub_bergamot PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
