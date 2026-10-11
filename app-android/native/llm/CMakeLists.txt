# libllmjni.so = llama.cpp (static, CPU only) + llm_jni.cpp, for arm64 Android. Built by build-llmjni.cmd;
# the result goes to app/src/main/jniLibs/arm64-v8a/. LLAMA_SRC points at a llama.cpp checkout.
cmake_minimum_required(VERSION 3.22)
project(llmjni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Snapdragon 8 Elite, Dimensity 9400 and 9500 all have dotprod + i8mm (Q4_0 is repacked for i8mm at load time)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} llama.cpp EXCLUDE_FROM_ALL)

add_library(llmjni SHARED llm_jni.cpp)
target_link_libraries(llmjni PRIVATE llama ggml log)
# 16 KB pages (Android 15+ devices), drop unused sections, strip
target_link_options(llmjni PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections -s)
