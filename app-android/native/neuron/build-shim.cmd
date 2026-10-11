@echo off
setlocal
cd /d "%~dp0"
if not defined ANDROID_NDK_HOME (echo Set ANDROID_NDK_HOME to NDK r28c & exit /b 1)
if not defined NEURON_SDK (echo Set NEURON_SDK to your licensed MediaTek USDK directory & exit /b 1)
if not exist tfinc\tensorflow\lite\nnapi\sl\public mkdir tfinc\tensorflow\lite\nnapi\sl\public
copy /y NeuralNetworksTypes.h tfinc\tensorflow\lite\nnapi\NeuralNetworksTypes.h >nul
copy /y NeuralNetworksSupportLibraryImpl.h tfinc\tensorflow\lite\nnapi\sl\public\NeuralNetworksSupportLibraryImpl.h >nul
set CXX=%ANDROID_NDK_HOME%\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android31-clang++.cmd
call "%CXX%" -std=c++17 -O2 -shared -fPIC -Wall -Wno-unused-function -Itfinc -I"%NEURON_SDK%\include" nnapi_neuron_shim.cc -L"%NEURON_SDK%\lib64" -lneuronusdk_adapter -static-libstdc++ -Wl,-rpath,$ORIGIN -Wl,-z,max-page-size=16384 -o libnnapi_neuron_shim.so
