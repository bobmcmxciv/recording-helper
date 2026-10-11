@echo off
setlocal
if not defined ANDROID_NDK_HOME (echo Set ANDROID_NDK_HOME to NDK r28c & exit /b 1)
if not defined LLAMA_SRC (echo Set LLAMA_SRC to your compatible llama.cpp checkout & exit /b 1)
cmake -S "%~dp0" -B "%~dp0build" -G Ninja -DLLAMA_SRC="%LLAMA_SRC%" -DCMAKE_TOOLCHAIN_FILE="%ANDROID_NDK_HOME%\build\cmake\android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release
if errorlevel 1 exit /b 1
cmake --build "%~dp0build" --target llmjni -j 4
if errorlevel 1 exit /b 1
copy /y "%~dp0build\libllmjni.so" "%~dp0..\..\app\src\main\jniLibs\arm64-v8a\libllmjni.so"
