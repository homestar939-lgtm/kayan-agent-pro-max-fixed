cmake_minimum_required(VERSION 3.22.1)
project(kayan_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_BUILD_TYPE Release)

# === FIX ROOT CAUSE: 1 warning and 9 errors ===
# b4600 has -Werror by default, fails on NDK 27
set(LLAMA_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_CUDA OFF CACHE BOOL "" FORCE)
set(LLAMA_METAL OFF CACHE BOOL "" FORCE)
set(LLAMA_VULKAN OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENBLAS OFF CACHE BOOL "" FORCE)
set(LLAMA_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

# Allow warnings
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -w")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -w -O2")

include(FetchContent)
FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG b4600
        GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama)

add_library(kayan_llama SHARED
        kayan_llama_bridge.cpp
)

# Link only core libs - common is not needed for this bridge
target_link_libraries(kayan_llama
        PRIVATE
        llama
        ggml
        ggml-base
        ggml-cpu
        android
        log
)

target_include_directories(kayan_llama PRIVATE
        ${llama_SOURCE_DIR}/include
        ${llama_SOURCE_DIR}/ggml/include
)
