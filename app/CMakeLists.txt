cmake_minimum_required(VERSION 3.22.1)
project(kayan_llama VERSION 2.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ── llama.cpp b4600 via FetchContent ─────────────────────────────────────────
set(LLAMA_TAG "b4600" CACHE STRING "llama.cpp git tag to build against")

include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        ${LLAMA_TAG}
    GIT_SHALLOW    TRUE
)

# ── Disable everything we don't need ─────────────────────────────────────────
# LLAMA_BUILD_COMMON=OFF  →  NO_COMMON: avoids POSIX deps (readline, etc.)
#                             that fail to compile on Android NDK
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)   # ← NO_COMMON
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)   # mandatory for cross-compile
set(GGML_USE_VULKAN      OFF CACHE BOOL "" FORCE)
set(GGML_USE_OPENMP      OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

# ── Android log library ───────────────────────────────────────────────────────
find_library(log-lib log)

# ── JNI bridge ────────────────────────────────────────────────────────────────
add_library(kayan_llama SHARED llama_jni.cpp)

target_include_directories(kayan_llama PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}
)

# Link: llama (includes ggml internally) + android + log
# NO EGL   — GPU vendor detection uses __system_property_get only
# NO GLESv2 — removed with EGL
# NO common — LLAMA_BUILD_COMMON=OFF above
target_link_libraries(kayan_llama
    PRIVATE
    llama
    android
    ${log-lib}
)

# ── Strip in release ──────────────────────────────────────────────────────────
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET kayan_llama POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:kayan_llama>
        COMMENT "Stripping kayan_llama.so"
    )
endif()
