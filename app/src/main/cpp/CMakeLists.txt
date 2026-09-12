cmake_minimum_required(VERSION 3.22.1)
project(kayan_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# ARM64-v8a optimizations - Professional Level
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -funroll-loops")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+fp16+dotprod -mtune=cortex-a76")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math")

# Llama.cpp b4600 - Latest stable
include(FetchContent)
set(LLAMA_TAG "b4600" CACHE STRING "llama.cpp version")
set(LLAMA_CURL OFF)
set(LLAMA_CUDA OFF)
set(LLAMA_METAL OFF)
set(LLAMA_VULKAN OFF)
set(LLAMA_OPENBLAS OFF)
set(LLAMA_BLAS OFF)

FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG ${LLAMA_TAG}
        GIT_SHALLOW TRUE
)

FetchContent_MakeAvailable(llama)

# Kayan Native Bridge
add_library(kayan_llama SHARED
        kayan_llama_bridge.cpp
)

target_link_libraries(kayan_llama
        PRIVATE
        llama
        common
        ggml
        ggml-base
        android
        log
)

target_include_directories(kayan_llama PRIVATE
        ${llama_SOURCE_DIR}/include
        ${llama_SOURCE_DIR}/common
        ${llama_SOURCE_DIR}/ggml/include
)

# 16KB page size support for Android 15+
target_link_options(kayan_llama PRIVATE "-Wl,-z,max-page-size=16384")
