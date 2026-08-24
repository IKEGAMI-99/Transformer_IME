cmake_minimum_required(VERSION 3.22.1)
project(zenzjni LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

set(LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp source missing. CI clones pinned b4846 into app/src/main/cpp/llama.cpp")
endif()

add_subdirectory(${LLAMA_SRC} llama-build EXCLUDE_FROM_ALL)

add_library(zenzjni SHARED zenz_jni.cpp)
target_include_directories(zenzjni PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
)
target_compile_options(zenzjni PRIVATE -O3 -ffast-math)
target_link_libraries(zenzjni PRIVATE llama android log)
