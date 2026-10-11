# Native runtime inventory

The application currently reuses the existing arm64 native binaries under `app/src/main/jniLibs/arm64-v8a`.
They are not model weights. Model files are installed separately by the user.

- `llm/`: project JNI wrapper and CMake build for llama.cpp; set `LLAMA_SRC` and `ANDROID_NDK_HOME` before building. The inherited binary was built from a source archive without `.git`; its exact upstream commit is not recorded. A fully reproducible native release requires pinning and retesting that dependency. Do not claim bit-for-bit reproduction from an arbitrary current llama.cpp checkout.
- `asr/`: project audio decoder/features JNI wrapper, minimp3 sources/license and build script. Obtain opencore-amr 0.1.6 and fdk-aac 2.0.3 source trees into this directory; set `ANDROID_NDK_HOME` (r28c). Runtime falls back to Android MediaCodec/Kotlin features if this library cannot load.
- `neuron/`: project shim and Apache-licensed TensorFlow NNAPI headers. MediaTek USDK is an external licensed dependency, intentionally not redistributed here. Set `NEURON_SDK` to a compatible USDK include/lib64 tree to build the shim. Phones without this backend use other available ASR engines.
- `licenses/`: existing decoder/runtime license texts copied from the build machine. Qualcomm QNN and Google LiteRT remain Maven dependencies, subject to their respective upstream terms.

The inherited LLM binary targets ARMv8.2 + dotprod + i8mm + fp16. The app checks the required CPU features before loading a model. A generic arm64 build and regression testing are needed for older chips.

No proprietary vendor runtime copied from a phone, model weights, private keys, or user recordings should be published with source.
