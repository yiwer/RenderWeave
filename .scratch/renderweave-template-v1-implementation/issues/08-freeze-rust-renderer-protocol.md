# 冻结 Rust Renderer process protocol 与认证计划

Type: grilling
Status: resolved
Claimed by: Codex `/root`
Blocked by: 02, 07

## Question

独立 Rust Renderer executable 与 Java process adapter 之间应冻结怎样的 framed protocol、Command/RenderDocument identity、resource fetch、deadline/cancel、stdout/stderr、exit、crash、length/digest、trace 和零 partial output 合同；同时怎样把 hermetic build、ELF closure、portable tricky-font、byte/pixel replay 与两种物理 Linux CPU-family 外部认证拆成诚实门控，使 Windows/WSL 或 scripted adapter 结果永远不能升级为 Renderer READY？

## Answer

T08 以两轮 HITL 对答（Q1–Q8 进程形态/帧协议/registry/fetch/cancel/构建/认证/边界、Q9–Q12 连接模型/
并发/监督/仓库边界）逐项按推荐采纳后，冻结 ADR-0045（Rust Renderer process protocol 与认证计划）；
本票没有创建 Rust 工程、Java 代码、migration、route、gate 组成或产品代码，Renderer 不 READY。

- 常驻 Rust daemon + Unix domain socket，app Adapter 建一条常驻连接、帧按 requestId 多路复用；
  registry/join/replay/cancel 常驻语义因此成立；不使用 JNI/FFI，Rust 不进入 Maven 图。
- 握手帧（协议版本 + certified manifest identity + capability，manifest 与部署配置不匹配拒绝服务）+
  类型化帧（4 字节大端 length + 帧类型）：COMMAND/CANCEL strict JSON（复用规格字段）、RESULT = closed
  结果 JSON 帧 + 独立 raw image bytes 帧（逐帧核验 length/digest）、PROBLEM closed JSON；stdout/stderr
  仅日志、exit code 固定语义；帧编码向量格式冻结，实际向量随首个实现票落地。
- registry（reservation/active/terminal replay/cancel tombstone）全在 daemon 内存（保留窗口按规格
  max(sealedAt,deadlineAt)+5min 与 60s tombstone）；Java 只映射 ADR-0044 五态 outcome；崩溃=Unknown→
  原 deadline 内同 canonical Command 重发→仍失败 RENDER_REQUEST_STATE_LOST/RENDER_INTERNAL_ERROR，
  绝不猜测或重新执行。
- FIFO admission queue/bounded slot 全在 daemon（queue wait ≤5s 计入 deadline，超时固定
  RENDER_DEADLINE_EXCEEDED；无法取位非 terminal RENDER_ENGINE_BUSY，reservation 保留到原 deadline）；
  slot/queue/attempt/backoff 数值归 Ticket 19。
- 资源 fetch 在 daemon：按 manifest order HTTPS fetch app origin（rustls；canonical HTTPS
  allowlist/无 redirect/proxy env/cookie/range/caller header；length/lowercase SHA-256/media/magic/
  descriptor 复验；transport/5xx 固定 attempt/backoff 无 jitter，4xx/expiry/integrity/decode 零重试）；
  app 只经 AssetFetchEndpoint 核验 lease claims 供给，daemon 绝不直发 S3。
- CANCEL 帧 + 固定 cooperative checkpoint（queue/fetch/retry/decode/font/layout/shrink/paint/encode/
  seal）+ 原子 seal 后单一 RESULT 帧；任何路径零 partial output。
- app Adapter 监督 daemon 生命周期：启动超时/exit 非 0 = 部署失败；握手 manifest 校验（Skia/FreeType/
  libjpeg-turbo/rustls/compiler/build hash + 单一 CPU 路径）；崩溃固定 backoff 重拉起（不重放已丢请求）。
- 仓库内新 `renderer/` cargo workspace（独立于 Maven reactor，不进架构测试 TARGET_MODULES）；
  rust-toolchain 钉死 + cargo.lock + vendor + 机器可读 manifest；唯一 CPU 路径 x86-64-v2 无 runtime
  SIMD dispatch；Linux 唯一生产/认证目标，Windows 仅 dev 永不入证据；旧 busbox 只作参考基础，
  不宣称 haibo 兼容。
- 四级认证阶梯：① 仓库内 corpus replay（wire/canonical/digest、Layout、exact pixel、PNG/JPEG bytes、
  QR/Barcode、malformed、SSRF/fetch fault、deadline/cancel/registry；Java primary + Rust 自证 + 独立
  verifier）；② 双物理 Linux CPU-family（x86-64 + aarch64）独立重放；③ 人工 J1 + 外部 A3；④ Ticket 19
  数值冻结。Windows/WSL/scripted 任一等级不计；`render` gate 随首个实现票纳入 `full`；Profile 持续
  NOT_REGISTERED。

验证：ADR/CONTEXT/plan/tracker 交叉一致、`git diff --check`、product-surface inventory（零新增产品面）；
`template` composite 与 `fast` 通过（docs-only，kernel 33/33、asset kernel 41/41、registry counts 不变，
输入未变可复用既有绿）。保证等级：文档/静态 gate A1；kernel/registry exact replay 在原边界仍为 A2；
无 A3/J1；物理 Linux 认证与 J1 属届时另行授权的执行级门控。

T08 resolve 后 Rendering 侧已无 unblocked grilling；T13（AssetResolver/lease）仍以首个 Rendering 实现
票与 T08 为前置，T09（Editor prototype）继续被 07/08 阻塞；push 待用户另行授权。
