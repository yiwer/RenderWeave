# 冻结 Evaluator 与 RenderDocument 产品 seam

Type: grilling
Status: resolved
Claimed by: Codex `/root`
Blocked by: 03, 04, 05

## Question

在 Template 与 Asset deep interfaces 稳定后，Evaluator 应如何拥有 closure snapshot、admitted input、Expression/capability materialization、Asset occurrence resolution、lowering、atomic seal 和诊断 sidecar，并通过一个 closed、跨语言可 canonicalize 的 RenderDocument/Command interface 交给 Renderer，确保动态语义不泄漏到 Renderer、RenderDocument 不被公开或跨请求复用、失败无 partial output，且不会误用现有 inference evaluator 或 synthetic rasterizer？

## Answer

T07 以两轮 HITL 对答（Q1–Q7 顶层 seam、Q8–Q12 命令/输出/存储/引擎/闭包细节）逐项按推荐采纳后，冻结
ADR-0044（Evaluator/RenderDocument 产品 seam）；本票没有创建 Java Interface、migration、route、gate
组成或产品代码，Renderer 不 READY。

- Template-owned `TemplateClosureAuthority`（render 专用唯一 `freezeClosure(renderRequestId, rootTemplateId)`
  closed 操作）：收口 root current 解析、canonical/contentHash integrity 复核（mismatch 是内部 integrity
  failure）、递归 TemplateRef 闭包、逐 snapshot 权威重检与漂移有界重试（耗尽
  `TEMPLATE_CLOSURE_UNSTABLE`）；`TemplateSnapshot`/closure 值类型继续 Template 独占；与 T12b
  `AssetReferenceAuthority` 独立但共享 snapshot 类型家族；stage 5 的 authored AssetRef atom 预准入依赖
  DesignDSL full-Profile 的 asset-ref 解析原子（当前 kernel 不支持非空 children），登记为后续依赖，
  不预建接口方法。
- 单一窄 `Evaluator.evaluate(EvaluationCommand)` → sealed `EvaluationOutcome`（SealedDocument |
  Rejected(stage, code, 有界问题)）；command 携带 renderRequestId（Rendering 创建）、root Template 身份、
  原始 RenderInput bytes 与 bounded output；input admission 在 Rendering 内部经 provider 接口完成；
  first-fail 串行、lazy materialization、Binding overlay、Asset occurrence 串行 resolve 全部
  Rendering.internal。
- Profile 选择权：caller 只选 bounded output（`PNG{dpi}`/`JPEG{dpi,quality}`，缺省 96/90），Layout
  Profile 由 compatibility table 唯一确定、Renderer Profile 服务端冻结，调用方不可协商。
- `RenderOutput` = closed 值（sealed 图片 bytes + outputProfile + 有界描述），公共预览/下载直接使用；
  RenderDocument/Command/内部 digest/lease 不返回调用方、不持久化、不跨请求复用。
- Rendering.spi `CapabilityStateStore` closed 三操作（save/load(fingerprint replay|conflict)/固定 TTL
  过期），app Adapter 加密落盘（nonce server-only 不明文入库）；Clock/Random/demand 语义 Rendering.internal。
- Rendering.spi `RenderEngine.execute(RendererCommand)` → `SealedOutput | Joined | Replayed |
  TerminalProblem | Unknown`；Unknown 在原 deadline/lease 内同 canonical Command 重发（不重新 seal/不续期/
  不延长 deadline），registry 丢失失败；cancel/join/replay 是 Engine 侧语义；deadline 服务端按部署配置冻结。
- 诊断 sidecar 内部持有（容量受限、请求级、结束销毁），app 只对拥有 Template read 权限的诊断调用者投影
  definitionId/bindingId/sourceNodeId/逐段授权路径，无权段 redact，Asset identity 按 asset.read 附加。
- Rendering problem 基础形态 `{code, stage, safeLocation, parameters}` + closed stage enum
  （REQUEST_ADMISSION…ENGINE 九值）；容量 limitId oracle 归 Ticket 19；内部违约折叠
  `RENDER_INTERNAL_ERROR`，final-geometry 失败是请求级 layout 错误不改变 readiness。
- 跨语言合同语料纪律：机器可读 RenderNodeContractCatalog + RenderDSL canonical/digest/Command 封存
  vectors manifest 格式冻结（镜像 T03/T10 kernel manifest），Java primary 重放、T08 后 Rust independent
  重放、届时 `render` gate 纳入 `full`；首个 task 票落向量。
- T07/T08 边界：本票只冻结 Java 侧合同形状、failure boundaries、语料计划与 port outcome；T08 冻结
  process framing/codec/wire、hermetic build、ELF closure、tricky-font、byte/pixel replay 与双物理 Linux
  CPU-family 外部认证；Windows/WSL/scripted adapter 结果永不升级 Renderer READY，Profile 持续
  NOT_REGISTERED，Ticket 19 open。

验证：ADR/CONTEXT/plan/tracker 交叉一致、`git diff --check`、product-surface inventory（零新增产品面）；
`template` composite 与 `fast` 通过（DesignDSL kernel 33/33、asset kernel 41/41、registry counts 不变，
docs-only 输入未变可复用既有绿）。保证等级：文档/静态 gate A1；kernel/registry exact replay 在原边界
仍为 A2；无 A3/J1。

T07 resolve 后 T08（Rust protocol grilling）成为唯一 unblocked frontier；T13 仍以 T08 为 blocker，
T09 随 07/08；push 待用户另行授权。
