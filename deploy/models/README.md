# 模型文件托管

应用在「设置 → 模型中心」里按文件下载：每个文件填一个 HTTPS 直链和发布方给出的 SHA-256，应用下载后校验、再原子替换。
同步服务本身不存放、不分发模型；任何支持 HTTPS 的静态文件服务器（自建 nginx / Caddy、对象存储、CDN）都可以。

请先确认你有权再分发对应的模型权重，见 `../../docs/MODELS.md` 的许可说明。

## 需要托管的文件

| 文件 | 用途 | 必需 |
| --- | --- | --- |
| `sv.tflite` | SenseVoice-Small 语音识别（静态形状 TFLite） | 是 |
| `tokens.json` | 词表 | 是 |
| `am.mvn` | 声学特征归一化 | 是 |
| `spk.tflite` | CAM++ 声纹模型 | 否 |
| `model.gguf` | Qwen3.5 系列 GGUF（文件名可以不同，`.gguf` 结尾即可） | 否 |

## 要求

- HTTPS，证书有效；应用拒绝 `http://` 和自签名证书。
- 只提供文件下载，关闭目录列表（nginx `autoindex off;`，Caddy 的 `file_server` 默认不列目录）。
- 每个文件给出 SHA-256（64 位十六进制），用户在应用里需要填写。
- 文件一经发布不要原地替换；新版本用新文件名。

## 目录示例

```
/srv/recording-models/
├── models.json          # 可选：给用户看的清单，应用不自动读取
├── sv.tflite
├── tokens.json
├── am.mvn
├── spk.tflite
└── Qwen3.5-4B-Q4_0.gguf
```

nginx 与 Caddy 的 `/recording-models/` 配置示例见 `../nginx.example.conf` 和 `../Caddyfile.example`。

## 生成 SHA-256

```sh
sha256sum sv.tflite            # Linux
shasum -a 256 sv.tflite        # macOS
certutil -hashfile sv.tflite SHA256   # Windows
```

把小写的 64 位十六进制串写进 `models.json`（格式见 `models.example.json`），并提供给用户。发布后用 `curl -I https://your-domain.example/recording-models/sv.tflite` 确认可以访问。
