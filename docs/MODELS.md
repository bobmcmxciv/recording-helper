# 模型文件

应用不内置任何模型权重。安装后在「设置 → 模型中心」从本地文件导入，或填写 HTTPS 直链与 SHA-256 下载。
模型放在应用专用外部目录：`Android/data/com.bob.recordinghelper/files/asr/` 与 `files/llm/`。

## 需要哪些文件

| 文件 | 用途 | 必需 | 大小 | 本项目验证过的 SHA-256 |
| --- | --- | --- | --- | --- |
| `sv.tflite` | SenseVoice-Small 语音识别（本项目转换的静态形状 TFLite） | 是 | 447 MB | `0c1bf068c38f58a9ca6d70e1180a3f7923804aec08e7f892e87d2f218ba7155b` |
| `tokens.json` | SenseVoice 词表 | 是 | 246 KB | `0e5c79ba743175e7e1252b507998bc63cb0dd27287c7853a3591933f315a34b9` |
| `am.mvn` | SenseVoice 声学特征归一化（CMVN） | 是 | 11 KB | `29b3c740a2c0cfc6b308126d31d7f265fa2be74f3bb095cd2f143ea970896ae5` |
| `spk.tflite` | CAM++ 声纹模型（说话人区分、声纹库） | 否 | 27 MB | `78f1255064f937b3d0f53dd639f173c84555af83fa1500657b7fa09e5f5172ac` |
| `model.gguf` | Qwen3.5-4B Q4_0（纪要、待办、润色） | 否 | 2.4 GB | `298fcb5fe7a77ccc79745ae24751560c5ac56874caff4bb39b1f2055bd72b8bb` |

没有 `spk.tflite` 时仍可转写，只是不区分说话人；没有 `model.gguf` 时只保留转写原文，不生成纪要和待办。

## 来源与许可

| 模型 | 上游 | 许可 | 说明 |
| --- | --- | --- | --- |
| SenseVoice-Small | [ModelScope iic/SenseVoiceSmall](https://www.modelscope.cn/models/iic/SenseVoiceSmall) · [Hugging Face FunAudioLLM/SenseVoiceSmall](https://huggingface.co/FunAudioLLM/SenseVoiceSmall) | Hugging Face 标注 [FunASR Model Open Source License v1.1](https://raw.githubusercontent.com/modelscope/FunASR/main/MODEL_LICENSE)；ModelScope 元数据标 Apache-2.0，两处不一致 | 协议把衍生权重纳入同一条款，并要求注明出处和保留模型名。商用条款表述不清，请自行判断 |
| CAM++ | [ModelScope iic/speech_campplus_sv_zh-cn_16k-common](https://www.modelscope.cn/models/iic/speech_campplus_sv_zh-cn_16k-common) | ModelScope 元数据 Apache-2.0；仓库内无许可证文件 | 代码来自 [3D-Speaker](https://github.com/alibaba-damo-academy/3D-Speaker)（Apache-2.0） |
| Qwen3.5-4B | [Hugging Face Qwen/Qwen3.5-4B](https://huggingface.co/Qwen/Qwen3.5-4B) | Apache-2.0 | 官方没有发布 GGUF。上表哈希对应 [unsloth/Qwen3.5-4B-GGUF](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF) 中的 `Qwen3.5-4B-Q4_0.gguf`（社区量化，同为 Apache-2.0） |
| llama.cpp | [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | GGUF 架构名 `qwen35`，需要 b7990 之后的版本 |

## 推荐来源（应用内一键下载）

首次打开的向导和「设置 → 模型中心」会读取一份清单 `models.json`（默认地址 `https://github.com/bobmcmxciv/recording-helper/releases/download/models-v1/models.json`），逐个下载并按 SHA-256 校验：

- 语音识别三件套与声纹模型托管在本仓库 Release `models-v1`，附来源与许可说明（SenseVoice 按 FunASR Model Open Source License v1.1 注明出处并保留模型名；CAM++ 为 Apache-2.0）。
- 本地大模型从 `hf-mirror.com` 下载（镜像 `huggingface.co`），约 2.4 GB。

清单地址可以在模型中心改成你自己的 HTTPS 服务器（见 `deploy/models/README.md`），格式与 `deploy/models/models.example.json` 相同。也可以完全不下载，自行转换后从本地文件导入。

## 下载 GGUF

Qwen3.5-4B Q4_0 大于 2 GiB，无法作为 GitHub Release 附件。可直接从社区仓库下载：

```
https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_0.gguf
https://hf-mirror.com/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_0.gguf
```

下载后用上表的 SHA-256 校验。在应用里可以直接填 HTTPS 直链和哈希，由应用下载并校验。

任何 Qwen3.5 系列 GGUF 都可以尝试，文件名以 `.gguf` 结尾即可；4B Q4 在 8 GB 内存手机上实测可用。

## 自行转换 SenseVoice-Small 为 TFLite

转换在 PC 上进行（Windows / Linux，Python 3.12）。脚本在 `tools/models/`。

```sh
pip install funasr onnx "onnx2tf[tensorflow]" tensorflow
python tools/models/export_sensevoice_onnx.py        # 导出 sv_onnx/model.onnx、tokens.json、am.mvn
onnx2tf -i sv_onnx/model.onnx -o sv_tflite -ois speech:1,167,560 speech_lengths:1 language:1 textnorm:1
```

`sv_tflite/model_float32.tflite` 重命名为 `sv.tflite`，连同 `sv_onnx/tokens.json`、`sv_onnx/am.mvn` 一起导入。
静态形状 `1,167,560` 对应 10 秒一段的 LFR 特征，这是应用分段转写的固定窗口；换别的形状应用无法运行。

转换环境版本不同会得到不同的哈希，这不影响使用；哈希只用于下载校验。

## 自行转换 CAM++ 为 TFLite

`spk.tflite` 由 ModelScope 的 `campplus_cn_common.bin`（sha256 `3388cf5f…5ada8`）转换而来，输入为 148 帧 × 80 维 Fbank（1.5 秒窗），输出 192 维向量，模型内做了均值归一化。
本仓库尚未附带可复现的转换脚本；在补齐之前，请使用上表中经过验证的文件哈希核对文件来源。

## 在自己的服务器上分发

`deploy/models/README.md` 说明了如何用任意静态 HTTPS 服务器托管模型文件并生成 SHA-256。请先确认你有权再分发对应权重。
