# 录音助手同步服务（Hub）

可选的自托管组件：让同一个人的多台设备共享一个工作空间，并提供浏览器看板。手机端不连接它也能完整工作。

## 用途与边界

- **只同步文字**：转写原文、润色稿、纪要和待办。不接收音频、文件路径、模型或声纹；服务端按字段白名单校验，多余字段整批拒绝。
- **单人多设备，不是多租户**：持有同一密钥的设备看到全部内容。要给另一个人用，请另起一个实例和密钥。
- **不做识别和推理**：服务端不调用任何外部网络服务，只保存和分发文字。
- **删除以墓碑同步**：设备把条目标记为 `deleted: true`，其他设备据此得知删除；看板和统计会隐藏这些条目及已删除录音下的待办。

## 快速开始

需要 Node.js 24 及以上，没有 npm 依赖，无需 `npm install`。

### 直接运行

```sh
# Linux / macOS
export RH_TOKEN="$(node -e "console.log(require('crypto').randomBytes(24).toString('base64url'))")"
echo "$RH_TOKEN"            # 记下密钥，手机和浏览器都要用
node server/index.js
```

```powershell
# Windows PowerShell
$env:RH_TOKEN = node -e "console.log(require('crypto').randomBytes(24).toString('base64url'))"
node server/index.js
```

浏览器打开 `http://127.0.0.1:8788/`，输入密钥即可连接看板。

### Docker

```sh
cd deploy
echo "RH_TOKEN=$(node -e "console.log(require('crypto').randomBytes(24).toString('base64url'))")" > .env
docker compose up -d --build
curl http://127.0.0.1:8788/api/v2/health
```

数据保存在命名卷 `hub-data`（容器内 `/data`）。端口只绑定到本机 `127.0.0.1:8788`。

### 示例数据

想先看看看板效果，可以往一个**空的**数据目录写入中文示例（6 条录音、12 条待办、2 台设备）：

```sh
node server/tools/seed-demo.js ./demo-data
RH_TOKEN=... RH_DATA_DIR="$PWD/demo-data" node server/index.js
```

不带参数时写入 `RH_DATA_DIR`（默认 `server/data`）。目录里已有数据时会拒绝写入，除非加 `--force`。启用了加密时同时设置 `RH_DATA_KEY_FILE`。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `RH_TOKEN` | 无，必填 | 工作空间密钥，至少 24 个字符，不能含空白字符。不满足时拒绝启动。 |
| `PORT` | `8788` | 监听端口。 |
| `HOST` | `127.0.0.1` | 监听地址。放在反向代理后面时保持默认；容器镜像内为 `0.0.0.0`。 |
| `RH_DATA_DIR` | `./data` | 数据目录，相对路径以 `server/` 目录为基准。 |
| `RH_DATA_KEY_FILE` | 空 | 可选。密钥文件路径（32 字节，base64）。设置后条目正文和回执以 AES-256-GCM 加密存储。 |

## API

需要认证的接口一律使用请求头 `X-Auth-Token: <RH_TOKEN>`；放在查询参数里的密钥无效。响应均为 JSON。

| 方法 | 路径 | 认证 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v2/health` | 否 | `{ "ok": true, "version": "2.0.0" }` |
| GET | `/api/v2/sync?cursor=N` | 是 | 拉取 `rev > N` 的条目，每页最多 50 条：`{ items: [{id, kind, body, rev}], cursor, more }` |
| POST | `/api/v2/sync` | 是 | 推送至多 50 条变更：`{ device, changes: [{id, kind, baseRev, localVersion, body}] }`，返回 `{ results: [{item, localVersion, conflict}] }` |
| GET | `/api/v2/stats` | 是 | `recordings`、`todos`、`openTodos`、`reviewedPending`、`devices: [{device, lastSeen, pushes}]`、`lastRev` |
| GET | `/api/v2/items/{id}` | 是 | 单个条目，不存在时 404 |

同步规则：

- **整批原子**：任何一条变更不合法，整批返回 400，什么都不写入。
- **乐观并发**：`baseRev` 与服务器当前 `rev` 不一致时返回 `conflict: true` 和服务器当前版本，不覆盖。新条目的 `baseRev` 必须为 0。
- **幂等重试**：同一 `(device, id, localVersion)` 重复提交，返回第一次的结果。
- **限制**：请求体最大 10 MB（超出返回 413），单条正文约 1 MB；`device` 为 4–80 位 `[a-zA-Z0-9_-]`；`id` 匹配 `^(rec|todo)-[a-zA-Z0-9-]{8,80}$`。
- **字段白名单**：录音 `id, title, text, polished, summary, occurredAt, status, error, createdAt, ownerDevice, deleted`，其中 `status` 为 `pending | analyzing | ready | error | silent`；待办 `id, title, due, evidence, recordingId, done, reviewed, createdAt, deleted`，其中 `done` 必须是布尔值。`deleted` 可省略，出现时必须是布尔值。

## 反向代理与 HTTPS

- 手机端只接受 `https://` 地址。Hub 本身只提供 HTTP 且默认只监听本机，必须由反向代理负责 TLS。
- 可以挂在子路径下，例如 `https://your-domain.example/recording-helper/`，代理时去掉前缀即可；看板全部使用相对地址。手机端的服务器地址填 `https://your-domain.example/recording-helper`。
- 示例配置：`deploy/nginx.example.conf`、`deploy/Caddyfile.example`。nginx 默认请求体上限只有 1 MB，务必设置 `client_max_body_size 11m;`。
- 看板与接口同源，不需要也不要开启 CORS。
- 不使用容器时，可参考 `deploy/recording-helper-hub.service` 以独立用户运行。

## 数据目录与备份

- 数据目录里只有一个 SQLite 数据库 `local-sync.db`（WAL 模式，运行时会伴随 `-wal`、`-shm` 文件）。
- 备份：停止服务后复制整个数据目录；或在运行中执行 `sqlite3 local-sync.db ".backup 'backup.db'"`。
- 静态加密（可选）：

  ```sh
  node -e "console.log(require('crypto').randomBytes(32).toString('base64'))" > /etc/recording-helper/data.key
  chmod 600 /etc/recording-helper/data.key
  ```

  然后设置 `RH_DATA_KEY_FILE=/etc/recording-helper/data.key`。密钥文件放在数据目录之外并单独备份，**丢失密钥就无法读取已加密的数据**。开启前写入的明文条目仍可读取，之后的写入全部加密。库里有密文但未配置密钥时，服务拒绝启动。Docker 下把密钥文件以只读方式挂载进容器，再设置同名环境变量。

## 安全说明

- 密钥等同于全部数据的访问权限：使用随机生成的长密钥，只通过 HTTPS 传输。怀疑泄露时更换 `RH_TOKEN` 并重启，再在各设备上重新填写。
- 密钥只通过请求头传输并以常量时间比较；日志每个请求一行，只记录时间、方法、路径、状态码和耗时，不记录密钥和查询参数。
- 看板启用严格的内容安全策略（只允许同源脚本、样式和请求，不允许内联脚本，禁止被其他页面嵌入），并设置 `X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`。
- 浏览器默认只在当前标签页保存密钥（sessionStorage）；勾选“在此浏览器记住密钥”才写入 localStorage。共享电脑上不要勾选，用完点“断开”。
- 服务本身不做限速。暴露在公网时，建议在反向代理上配置限速（如 nginx `limit_req`）。
- 进程不需要任何特权；示例 compose 和 systemd 配置都丢弃了全部 capability 并禁止提权。

## 测试

在仓库根目录运行：

```sh
node --test server/test/*.test.js
```
