# The native inference runtime, for the opt-in local brain.
#
# The llama.cpp sources are vendored under `cpp/llama.cpp`. They are not
# committed here: the build fetches them, and the build fails with a clear
# message when they are missing rather than quietly building without the
# local brain.
#
# The default build does not configure this file at all. `:platform:inference`
# only passes it to CMake when `-Plumen.localInference=true` is set, so the
# agent compiles, lints, tests and packages on a machine with no native
# toolchain and no model runtime.

cmake_minimum_required(VERSION 3.22.1)
project(lumen-inference CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "The llama.cpp sources are not vendored at ${LLAMA_DIR}. "
        "Fetch them before building with -Plumen.localInference=true, or drop "
        "the property to build without the local brain.")
endif()

add_subdirectory(${LLAMA_DIR} llama-build EXCLUDE_FROM_ALL)

add_library(lumen-inference SHARED lumen_inference.cpp)

target_link_libraries(lumen-inference PRIVATE llama llama-common android log)

target_compile_options(lumen-inference PRIVATE -O3 -ffast-math)
