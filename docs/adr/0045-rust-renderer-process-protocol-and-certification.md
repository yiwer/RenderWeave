# ADR-0045：Rust Renderer process protocol 与认证计划

- 状态：accepted
- 日期：2026-08-19
- 决策来源：Template v1 implementation Wayfinder Ticket 08（grilling），用户两轮对答（Q1–Q8 进程形态/帧
  协议/registry/fetch/cancel/构建/认证/边界，Q9–Q12 连接模型/并发/监督/仓库边界）逐项按推荐采纳
- 关联：ADR-0041、ADR-0043、ADR-0044、TV1-T08、冻结 checkpoint
  `0b485f4a13de9d754a81d07f464730776e13c14b`

## 背景与约束

冻结规格（旧 map tickets 15/16 与 CONTEXT.md glossary）已经把 Renderer 语义冻结：`renderweave-render/
1.0` 文档、`renderweave-render-command/1.0`/cancel/result/problem 实体、`renderweave-layout/1.0`/
`renderer/1.0`/`output-png/1.0`/`output-jpeg/1.0` Profile、CPU raster 与单一 CPU 路径、exact PNG/JPEG
编码算法、registry/join/replay/cancel/deadline/cooperative checkpoint、resource fetch 安全与重试、
READY 证据要求与旧 busbox 引擎的复用边界。ADR-0041 已冻结：Rendering 拥有 `RenderEngine` outbound
Interface，app 提供 production process Adapter、测试提供 scripted Adapter；Rust 是独立 executable，
不使用 JNI/FFI，不进入 Maven 图。ADR-0044 已冻结 Java port 的五态 outcome（SealedOutput | Joined |
Replayed | TerminalProblem | Unknown）与 Unknown 重发纪律，并明确 process framing/codec/deadline 与
exact wire 由本票（T08）冻结。本 ADR 只冻结进程协议、构建与认证计划，不创建 Rust 工程、Java 代码、
route、gate 组成或任何占位实现。

## 决策

### 1. 常驻 daemon + UDS，单连接 requestId 多路复用

Java process Adapter 与独立 Rust executable 之间使用常驻 daemon 形态：daemon 启动后监听一个 Unix
domain socket（生产为 Linux 本机部署路径），app Adapter 建立一条常驻连接，帧按 requestId 多路复用
（COMMAND/CANCEL/RESULT/PROBLEM 帧都携带 requestId）。registry/join/replay/cancel 语义要求常驻状态，
每请求一次性进程或 TCP loopback 均不采用。daemon 生命周期由 app Adapter 监督（见决策 7）。

### 2. 握手与类型化 length 前缀帧

- 连接建立后先交换握手帧：协议版本 + certified manifest identity（依赖/build/CPU 路径标识，见决策 8）
  + capability 声明；manifest 与部署配置不匹配时 Adapter 拒绝服务。
- 帧封装固定为：4 字节大端 length 前缀 + 1 字节帧类型 + payload。消息类型：
  `COMMAND`（strict JSON，复用 `renderweave-render-command/1.0` 的 closed canonical 字段）、
  `CANCEL`（strict JSON，复用 `renderweave-render-cancel/1.0` 字段）、`RESULT`（closed 结果 JSON 帧 +
  独立 raw image bytes 帧，逐帧核验 length 与 digest）、`PROBLEM`（closed
  `renderweave-render-problem/1.0` JSON）。
- stdout/stderr 只承载日志/诊断文本，永不携带 payload、token、URL 或图片 bytes；进程 exit code 固定
  语义（0 = 正常退出，非 0 = 启动/内部错误）。帧编码向量格式本 ADR 冻结，实际向量随首个
  Rust/process task 票落地（镜像 T07-Q6 语料纪律）。

### 3. registry 在 daemon 内存，Java 只映射 outcome

Engine request registry（reservation/active/terminal replay state/cancel tombstone）全部在 Rust daemon
内存实现：保留窗口按冻结规格（terminal registry 到 `max(sealedAt, deadlineAt) + 5 min` 供 exact
replay，pre-command cancel tombstone 固定 60 秒，访问/retry/cancel 不续期）。Java Adapter 只把响应帧
映射成 ADR-0044 的五态 outcome；daemon 崩溃/连接断开 = registry 丢失 → 原 deadline 内以同 canonical
Command 重发一次，仍失败返回 `RENDER_REQUEST_STATE_LOST`/`RENDER_INTERNAL_ERROR`，Java 可重启 daemon
但绝不猜测或重新执行已丢请求。

### 4. FIFO queue/slot 全在 daemon，数值归 Ticket 19

有界 FIFO admission queue 与并发 slot 在 daemon 内实现：active registry lookup 先于 queue admission，
只有原子取得 FIFO queue position 才线性化为 accepted execution；无法取位返回非 terminal
`RENDER_ENGINE_BUSY`（reservation 保留到原 deadline、同 digest 可重试）；一旦 accepted，queue wait 计入
deadline 且最多 5 秒，超时固定返回 terminal `RENDER_DEADLINE_EXCEEDED`。slot/queue length/等待上限等
数值由 Ticket 19 冻结，本 ADR 不猜值。

### 5. 资源 fetch 在 daemon，app 只经 AssetFetchEndpoint 供给

Rust daemon 按 manifest encounter order 直接 HTTPS fetch app origin（rustls）：只允许 canonical HTTPS
app-origin URL（拒绝 userinfo/fragment/非 canonical host/port/dot-segment 绕过、redirect、proxy
environment、cookie、range、caller header 与透明 compression），逐 attempt 重检 deadline/lease
expiry/累计预算，并依次核验 length、lowercase SHA-256、media/magic 与 descriptor；transport/5xx 按
Profile 固定 attempt/backoff（无 jitter）重试，4xx/expiry/integrity/decode 失败零重试。Java app 只经
AssetFetchEndpoint（ADR-0043 Port）核验 lease claims 后从对象存储供给；daemon 绝不直发 S3 presigned
URL。

### 6. CANCEL 帧、cooperative checkpoint 与零 partial output

CANCEL 经帧协议表达（复用规格 §3 的 closed cancel JSON 与 tombstone 语义）；daemon 内固定 cooperative
checkpoint 覆盖 queue、每次 fetch/retry、decode、font parse、layout 阶段、每次 Text shrink iteration、
paint chunk、encode chunk 与 seal。输出只经原子 seal 后以单一 RESULT 帧返回；daemon 中途崩溃/连接断开
时 Java 按决策 3 处理，任何路径都零 partial output、无 warning image、无 placeholder。

### 7. daemon 生命周期监督与 manifest 校验

app Adapter 拥有 daemon 生命周期：启动（超时或 exit code 非 0 = 部署失败 → `RENDER_INTERNAL_ERROR`）、
握手时校验 certified manifest 身份（Skia/FreeType/libjpeg-turbo/rustls/compiler/build hash 与单一
CPU 路径和部署配置一致；不匹配拒绝服务）、崩溃后按固定 backoff 重拉起（已丢请求返回
`RENDER_REQUEST_STATE_LOST`，不重放）、健康探测经握手/心跳。部署 manifest 只读、机器生成。

### 8. 仓库内 `renderer/` cargo workspace 与 hermetic build

Rust 工程位于仓库内新 `renderer/` cargo workspace：独立于 Maven reactor（不进 `TemplateV1ArchitectureTest`
的 TARGET_MODULES，架构测试只管 Maven 图）；`rust-toolchain` 钉死 + `cargo.lock` + 依赖 vendor；机器
可读 manifest 钉死 Skia/FreeType/libjpeg-turbo/rustls/compiler/build hash 与唯一 CPU 路径
（x86-64-v2，无 runtime CPU/SIMD dispatch）。Linux 是唯一生产构建/认证目标；Windows 构建仅限本地 dev
与 scripted 验证且永不进入认证证据。旧 `E:\rust-app\busbox-render-engine` 只可复用部分 Rust、Skia、
PNG/JPEG、fetch 与测试基础，必须实现新 parser/Profile/资源/控制路径并经本 ADR 阶梯认证，不得宣称
`haibo.render/1.0`/`haibo.dsl/1.0` 兼容或等价。

### 9. 四级认证阶梯

Renderer READY 的认证阶梯（本 ADR 冻结格式与顺序，实际执行需届时另行授权）：

1. **仓库内 replay**：strict wire/canonical/digest、Layout/shaping、exact pixels、exact PNG/JPEG
   bytes、QR/Barcode matrix 与 decode、malformed IMAGE/FONT 与 descriptor、SSRF/DNS/redirect/proxy/
   fetch fault、deadline/cancel/retry/registry/shard-loss、output/multipart 校验——Java primary +
   Rust 自证 + 独立 verifier 重放同一 corpus；
2. **双物理 Linux CPU-family**：x86-64 与 aarch64 各自独立重放同一 corpus（同一 exact manifest）；
3. **人工 J1 + 外部 A3 门控**：按仓库既定治理形成外部门；
4. **Ticket 19 数值冻结**：queue/slot/deadline/checkpoint/attempt/backoff/预算等数值与容量 oracle
   全部冻结后，部署才可能进入 READY。

Windows/WSL 单机结果与 scripted adapter 在任一等级都不计数；截图、单侧 unit test、旧 Haibo 测试或
“Skia 默认”均不足以认证。`render` gate 随首个实现票纳入 `full`；`renderweave-renderer/1.0` 等 Profile
在认证完成前持续 `NOT_REGISTERED`。

### 10. 交付形态与边界

本票（T08）产出 ADR-0045 + plan/map/NOTES 登记，零产品代码（不创建 Rust 工程、Java 代码、migration、
route、gate 组成或占位实现）；帧编码向量与 `render` gate 脚本随首个 Rust/process task 票落地。协议
wire 变化发布对应 contract version；旧 haibo 语义不得静默降级；Ticket 19 open；本 ADR 不证明 Renderer
READY。

## 备选方案

| 方案 | 未选择原因 |
| --- | --- |
| 每 Command 一次性进程 | registry/join/replay/cancel 常驻语义无法跨进程成立，与冻结规格 §3 冲突 |
| 常驻 + TCP loopback | 多一层暴露面与认证配置；UDS 更贴合本机进程 seam |
| 全 JSON + base64 图片 | 图片 bytes 膨胀 33% 且需额外 base64 校验，与 byte-exact/digest 纪律冲突 |
| 无握手/无帧类型极简协议 | 缺少版本/capability/manifest 校验，无法做 READY 门控 |
| Java 侧维护 registry 副本 | 双份状态漂移；规格明确 registry 是 Engine 侧语义 |
| Java 代 fetch 资源 | 资源准备/重试语义分裂，引擎无法独立核验 lease/descriptor |
| Java kill 代替 cancel 帧 | 无法实现 cancel 线性化/幂等/tombstone 语义 |
| 独立仓库托管 Rust | 跨仓版本同步与向量语料共享割裂，本仓库 gate 纪律无法复用 |
| 改造旧 busbox 仓库 | 旧 haibo 语义不兼容，只能复用部分基础，不能原位宣称等价 |
| 仅仓库内 replay 认证 | READY 证据链缺环，与 map 冻结的双物理 CPU-family 认证不符 |
| T08 即物化帧向量 | 无实现可重放，向量易与真实 wire 漂移；T08 是 freeze-only |

## 后果与边界

T08 后进程 seam 的形态、帧协议、registry/fetch/cancel/崩溃语义、构建与 supply-chain 计划、四级认证
阶梯与 READY 纪律全部冻结；代价是首个 Rust/process task 票需要同时物化 daemon、帧编解码、manifest
与仓库内 replay harness，且 READY 需要物理 Linux 双 CPU-family 与 J1/A3 外部执行（届时另行授权）。

本 ADR 只冻结实施合同：没有创建 Rust 工程、Java 代码、migration、route、gate 组成或产品代码；自动
文档/gate 通过也不证明 Renderer READY。Ticket 19、DesignDSL Profile available、Editor/Renderer 外部
认证状态不变；T08 resolve 后 T13（AssetResolver/lease，被 T07/T08/T11 阻塞）仍以首个 Rendering
实现票为前置，T09（Editor prototype）继续被 07/08 阻塞。
