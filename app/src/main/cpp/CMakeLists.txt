# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.31.6)

# Declares the project name first
project("llamacpp")

# Per-ABI performance flags (must be set before add_subdirectory).
# OpenMP is OFF on both ABIs: it's the upstream-recommended Android path
# (see llama.cpp docs/android.md) and ggml's own pthread threadpool replaces
# it with comparable performance. Disabling OpenMP also produces cleaner
# native crash stacks (no libomp.so frames) and shrinks the APK by ~500 KB
# (no libomp.so packaged).
if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP OFF)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    endif()
endif()

# ── Vulkan host headers ──────────────────────────────────────────────
# ggml-vulkan needs three things the NDK sysroot does not ship: the Vulkan C++
# bindings (vulkan.hpp), the Khronos SPIR-V headers (spirv/unified1/spirv.hpp)
# and the SPIRV-Headers CONFIG package. build.gradle.kts probes the host for
# these (Homebrew, apt, LunarG SDK) and passes them in as -D flags.
#
# When a probe finds nothing we fetch the header-only Khronos repo instead.
# That is the normal case on Windows, which has no system package manager for
# them, and it keeps the build working with no manual setup. Hosts where the
# probes succeed (CI's apt packages, macOS Homebrew) never reach the fetch.
#
# Both repos are pinned to the same Vulkan SDK release, matching the Vulkan
# 1.3.275 headers in the pinned NDK. Bump both tags together.
set(KHRONOS_HEADERS_TAG "vulkan-sdk-1.3.275.0")

if(GGML_VULKAN)
    include(FetchContent)
endif()

# vulkan.hpp. SOURCE_SUBDIR points at a directory with no CMakeLists.txt so
# FetchContent downloads the repo without configuring it -- we only want the
# headers, and Vulkan-Headers' own targets would collide with FindVulkan's.
# Presetting the Vulkan_INCLUDE_DIR cache entry short-circuits FindVulkan's
# find_path, so it pairs these headers with the NDK's libvulkan.so.
if(GGML_VULKAN AND NOT DEFINED Vulkan_INCLUDE_DIR)
    message(STATUS "Vulkan C++ headers not found on host; fetching ${KHRONOS_HEADERS_TAG}")
    FetchContent_Declare(VulkanHeaders
            GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
            GIT_TAG ${KHRONOS_HEADERS_TAG}
            GIT_SHALLOW TRUE
            SOURCE_SUBDIR headers-only-no-cmake-build)
    FetchContent_MakeAvailable(VulkanHeaders)
    set(Vulkan_INCLUDE_DIR "${vulkanheaders_SOURCE_DIR}/include" CACHE PATH "Vulkan headers" FORCE)
endif()

# SPIR-V headers. Unlike Vulkan-Headers these are configured normally, because
# ggml-vulkan calls find_package(SPIRV-Headers CONFIG REQUIRED) and needs the
# package to resolve; OVERRIDE_FIND_PACKAGE makes that find_package pick up the
# fetched copy instead of searching the host.
if(GGML_VULKAN AND NOT DEFINED SPIRV_HEADERS_INCLUDE_DIR)
    message(STATUS "SPIR-V headers not found on host; fetching ${KHRONOS_HEADERS_TAG}")
    FetchContent_Declare(SPIRV-Headers
            GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
            GIT_TAG ${KHRONOS_HEADERS_TAG}
            GIT_SHALLOW TRUE
            OVERRIDE_FIND_PACKAGE)
    FetchContent_MakeAvailable(SPIRV-Headers)
    set(SPIRV_HEADERS_INCLUDE_DIR "${spirv-headers_SOURCE_DIR}/include")
endif()

# ggml-vulkan.cpp includes <spirv/unified1/spirv.hpp>, but the upstream
# ggml-vulkan target wires SPIRV-Headers only to the host shader generator
# (it assumes the Vulkan SDK bundles the SPIR-V headers in the Vulkan include
# dir). When SPIRV-Headers come from a separate prefix (e.g. Homebrew), inject
# the include dir globally before adding llama.cpp so ggml-vulkan can compile.
if(DEFINED SPIRV_HEADERS_INCLUDE_DIR)
    include_directories(${SPIRV_HEADERS_INCLUDE_DIR})
endif()

# ── Host toolchain for vulkan-shaders-gen (Windows hosts only) ───────
# ggml-vulkan compiles a host-side shader generator through ExternalProject
# while the main build cross-compiles for Android. Upstream autodetects that
# host toolchain, and both halves of the guess are wrong on a Windows host:
#
#   * detect_host_compiler() runs a plain find_program for "cl gcc clang". The
#     Android toolchain seeds CMAKE_PROGRAM_PATH with the NDK, so this resolves
#     to the NDK's own cross clang -- which has no host sysroot and cannot even
#     find <cstdio>, let alone link a Windows executable.
#   * the nested configure inherits -GNinja from us but not CMAKE_MAKE_PROGRAM,
#     and ninja is not on PATH on Windows (it ships inside the Android SDK, at
#     the path AGP passes us as CMAKE_MAKE_PROGRAM).
#
# Handing ggml an explicit toolchain file via its GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
# hook fixes both. macOS and Linux keep upstream's detection, which is correct
# there (a real cc and ninja are both on PATH), so CI is unaffected.

set(_host_toolchain "${CMAKE_BINARY_DIR}/host-toolchain-windows.cmake")

# Regenerate unless the user pointed the hook at a toolchain of their own.
# Comparing against our own output path rather than just testing the variable
# keeps this idempotent: we write that cache entry ourselves, so a plain
# "is it set?" test would skip the block on every reconfigure after the first
# and leave a stale file behind.
if(GGML_VULKAN AND CMAKE_HOST_WIN32 AND CMAKE_CROSSCOMPILING
        AND (NOT GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
             OR GGML_VULKAN_SHADERS_GEN_TOOLCHAIN STREQUAL _host_toolchain))
    # NO_DEFAULT_PATH + PATHS ENV PATH searches only the real PATH, which is
    # what keeps the NDK's cross clang out of the results.
    find_program(HOST_CLANGXX NAMES clang++ PATHS ENV PATH NO_DEFAULT_PATH)
    find_program(HOST_CLANG   NAMES clang   PATHS ENV PATH NO_DEFAULT_PATH)
    find_program(HOST_CL      NAMES cl      PATHS ENV PATH NO_DEFAULT_PATH)

    if(HOST_CLANGXX AND HOST_CLANG)
        # clang++ locates the installed MSVC headers/libs on its own, so it
        # works from a plain shell without a vcvars environment.
        set(_host_c "${HOST_CLANG}")
        set(_host_cxx "${HOST_CLANGXX}")
    elseif(HOST_CL)
        set(_host_c "${HOST_CL}")
        set(_host_cxx "${HOST_CL}")
    else()
        message(FATAL_ERROR
                "The Vulkan backend needs a host C++ compiler to build "
                "vulkan-shaders-gen, and none was found on PATH. Install LLVM "
                "(https://github.com/llvm/llvm-project/releases) or the Visual "
                "Studio C++ Build Tools, or build without Vulkan using "
                "-PnoVulkan.")
    endif()

    # AGP passes CMAKE_MAKE_PROGRAM with Windows separators. Those must be
    # normalised before being written into a CMake file, or the backslashes are
    # parsed as escape sequences (\U, \A, \n ...) and mangle the path.
    set(_host_make "${CMAKE_MAKE_PROGRAM}")
    if(NOT _host_make)
        find_program(_host_make NAMES ninja)
    endif()
    file(TO_CMAKE_PATH "${_host_make}" _host_make)
    file(TO_CMAKE_PATH "${_host_c}" _host_c)
    file(TO_CMAKE_PATH "${_host_cxx}" _host_cxx)

    # CMAKE_MAKE_PROGRAM must be a forced CACHE entry, not a plain set(): the
    # Ninja generator resolves its build command before the toolchain file's
    # normal variables are in scope, so a plain set() leaves try_compile running
    # an empty make command.
    #
    # Deliberately no CMAKE_RUNTIME_OUTPUT_DIRECTORY here: the ExternalProject
    # installs vulkan-shaders-gen into CMAKE_BINARY_DIR/$<CONFIG>, which is
    # where ggml-vulkan invokes it from. Mirroring the parent's value would
    # instead drop a host .exe into the per-ABI obj dir that AGP packages.
    file(WRITE "${_host_toolchain}"
            "# Generated by app/src/main/cpp/CMakeLists.txt -- do not edit.\n"
            "set(CMAKE_BUILD_TYPE Release)\n"
            "set(CMAKE_C_COMPILER \"${_host_c}\")\n"
            "set(CMAKE_CXX_COMPILER \"${_host_cxx}\")\n"
            "set(CMAKE_MAKE_PROGRAM \"${_host_make}\" CACHE FILEPATH \"\" FORCE)\n"
            "set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)\n"
            "set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)\n"
            "set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)\n")

    message(STATUS "vulkan-shaders-gen host compiler: ${_host_cxx}")
    # Must be the cache entry, not a normal variable: ggml/CMakeLists.txt does
    # set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "" CACHE FILEPATH ...) during
    # add_subdirectory below, and under CMP0126 OLD that drops any normal
    # variable of the same name. FORCE because the entry survives reconfigures.
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${_host_toolchain}" CACHE FILEPATH
            "Host toolchain for ggml's vulkan-shaders-gen" FORCE)
endif()

# Include llama.cpp subdirectory.
# The submodule points at the andriydruk/llama.cpp-android fork (android-b9496),
# which bakes "fd:N" file-descriptor handling into ggml_fopen / llama-mmap, so the
# old ggml_fopen_override.c static-lib + --whole-archive hack is no longer needed.
# The build uses BUILD_SHARED_LIBS=ON + GGML_BACKEND_DL (set in build.gradle.kts):
# ggml is a single shared libggml-base.so, and CPU/Vulkan backends are loaded at
# runtime via ggml_backend_load_all_from_path. libmtmd.so links the same shared
# ggml/llama, so it shares the one backend registry (no separate-registry problem).
add_subdirectory(llama.cpp)

# Include mtmd (multimodal) library for vision support.
# Set LLAMA_INSTALL_VERSION if not already set (needed by mtmd's CMakeLists).
if(NOT DEFINED LLAMA_INSTALL_VERSION)
    set(LLAMA_INSTALL_VERSION "0.0.0")
endif()
add_subdirectory(llama.cpp/tools/mtmd)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        LlamaModel.cpp
        LlamaGenerationSession.cpp
        LlamaEmbeddingSession.cpp)

# mtmd public headers (mtmd.h, mtmd-helper.h) for the vision integration.
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE llama.cpp/tools/mtmd)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama-common
        llama
        mtmd
        android
        log)
