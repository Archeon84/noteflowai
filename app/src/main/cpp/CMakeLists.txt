cmake_minimum_required(VERSION 3.18.1)
project("whisper_jni")

# Ensure 16KB page alignment for Android 15+
add_link_options("-Wl,-z,max-page-size=16384")

# Force Release-level optimizations and ARM SIMD flags for ALL native targets
# (ggml-base, ggml-cpu, llama, whisper all need this)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.4-a+dotprod+fp16+i8mm -O3" CACHE STRING "" FORCE)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.4-a+dotprod+fp16+i8mm -O3" CACHE STRING "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8.4-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

# LTO disabled - gold linker not available in Android NDK on Windows
set(GGML_LTO OFF CACHE BOOL "" FORCE)

# Include whisper.cpp
add_subdirectory(whisper.cpp)

# Our shared library
add_library(
    whisper_jni
    SHARED
    whisper_jni.cpp
    whisper_mel_jni.cpp
    qnn_env_setup.cpp
)

target_include_directories(whisper_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp
)

# Link against whisper and Android log
find_library(log-lib log)

target_link_libraries(whisper_jni
    whisper
    ${log-lib}
)

target_compile_options(whisper_jni PRIVATE
    -std=c++17
    -O3
    -Wall
)

# Native test library (debug builds only)
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_library(jni_test SHARED jni_test.cpp)
    target_include_directories(jni_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(jni_test ${log-lib})
    target_compile_options(jni_test PRIVATE -std=c++17 -O3 -Wall)
endif()
