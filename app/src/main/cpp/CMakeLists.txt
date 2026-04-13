# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.31.6)

# Declares the project name first
project("llamacpp")

# Per-ABI performance flags (must be set before add_subdirectory)
if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    endif()
endif()

# Create a static library with our ggml_fopen override
# This will be linked BEFORE ggml so our implementation takes precedence
add_library(ggml_override STATIC
        ggml_fopen_override.c)

# Build ggml/llama/mtmd as static libraries so they share the same
# backend registry and global state (Vulkan device, log callback).
# Without this, libmtmd.so has a separate ggml backend registry and
# cannot find the Vulkan GPU device registered by libllama.so.
set(BUILD_SHARED_LIBS OFF)

# Include llama.cpp subdirectory
add_subdirectory(llama.cpp)

# Include mtmd (multimodal) library for vision support
# Set LLAMA_INSTALL_VERSION if not already set (needed by mtmd's CMakeLists)
if(NOT DEFINED LLAMA_INSTALL_VERSION)
    set(LLAMA_INSTALL_VERSION "0.0.0")
endif()
add_subdirectory(llama.cpp/tools/mtmd)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        native-lib.cpp
        LlamaModel.cpp
        LlamaGenerationSession.cpp)

# target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${CMAKE_SOURCE_DIR}/OpenCL-Headers)
# target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${CMAKE_SOURCE_DIR}/clblast/include)

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
# NOTE: ggml_override is linked FIRST so our ggml_fopen takes precedence
# We use --whole-archive to force inclusion and --allow-multiple-definition to
# allow our symbol to coexist with the one from ggml (first definition wins)
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE llama.cpp/tools/mtmd)

target_link_libraries(${CMAKE_PROJECT_NAME}
        # Linker flags to allow multiple definitions (first wins)
        -Wl,--allow-multiple-definition
        # Our override library - linked first with whole-archive to ensure inclusion
        -Wl,--whole-archive
        ggml_override
        -Wl,--no-whole-archive
        # List libraries link to the target library
        common
        llama
        mtmd
        android
        log)
