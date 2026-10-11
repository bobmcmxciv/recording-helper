# 架构

```
系统录音目录 / 文件选择器 / 系统分享 / 应用内麦克风 / 粘贴文字
        │
        ▼
持久化队列（recording-queue.db，只存引用）
        │
        ▼
本机语音转文字  SenseVoice-Small · TFLite · CPU 或 NPU
   └─ 可选 CAM++ 声纹：说话人区分、声纹库匹配
        │
        ▼
本机 SQLite（local-v2.db：录音、待办、同步版本、冲突副本）
        │
        ▼
本机 LLM  Qwen3.5 GGUF · llama.cpp  →  纪要 + 有原文依据的待办 + 可选润色稿
        │
        ▼
原生界面：概览 / 录音 / 待办 / 我的
        │
        ▼（仅在用户开启后）
HTTPS /api/v2/sync  ⇄  自托管同步服务  →  浏览器看板
```

## Android 模块

| 包 | 职责 |
| --- | --- |
| `asr/` | 音频解码（MediaCodec + 本地解码库）、Fbank 前端、SenseVoice 推理与引擎选择、CAM++ 向量提取与说话人聚类 |
| `llm/` | llama.cpp JNI 封装、润色 |
| `local/` | 数据存储（`LocalStore`、`RecordingQueue`）、自动发现与范围策略、后台 Worker（扫描、转写、整理、同步）、麦克风服务、声纹库与跨录音关联、模型安装 |
| `ui/` | 四个标签页、录音详情、待办编辑、文字导入、共享绑定与主题工具 |
| 根包 | `MainActivity`、`SettingsActivity`、`TranscribeLiveActivity`、分享接收、偏好、常驻唤醒 |

### 后台任务

全部基于 WorkManager，唯一名称避免重复：

- `auto-scan`（每 15 分钟）与 `manual-scan`：发现录音，按范围入队
- `recording-queue`：每批最多 3 条或 4 分钟，转写后写入 `LocalStore`
- `local-import`：手动导入 / 分享的文件
- `local-analysis`（充电时）与 `local-analysis-now`：LLM 整理
- `model-install`：模型复制或下载、哈希校验、原子替换
- `local-sync`：推送脏数据、分页拉取

`ModelGate` 保证同一时间只有一套大模型在内存中；麦克风录音和声纹分析会让后台整理让出模型。

### 存储

| 位置 | 内容 |
| --- | --- |
| `databases/local-v2.db` | 录音与待办（JSON body）、本地版本、服务器 rev、脏标记、冲突副本 |
| `databases/recording-queue.db` | 自动转写队列 |
| `files/imports/` | 手动导入的音频副本，转写后删除 |
| `files/mic-recordings/` | 应用内录音 WAV 与分段元数据 |
| `files/voice-library-v1.json` | 声纹向量（不同步、不导出） |
| `files/voice-relations-v1.json` | 跨录音候选依据 |
| `Android/data/…/files/asr/`、`files/llm/` | 模型文件 |

## 同步协议

设备与服务端之间只传递两种条目：`recording` 与 `todo`，均为 JSON body 加单调递增的服务器 `rev`。

- `POST /api/v2/sync`：`{device, changes:[{id, kind, baseRev, localVersion, body}]}`。服务端按 `baseRev` 做 CAS；不一致返回 `conflict:true` 与服务器当前版本，设备把自己的版本存入冲突副本。`(device, id, localVersion)` 作为幂等回执，重试不会重复应用。
- `GET /api/v2/sync?cursor=N`：按 `rev` 分页拉取。
- 删除以 `deleted:true` 墓碑传播。
- 字段白名单：音频、路径、声纹、模型永远不在协议内。

服务端实现与部署见 `server/README.md`。
