# ADR-0044：Evaluator 与 RenderDocument 产品 seam

- 状态：accepted
- 日期：2026-08-19
- 决策来源：Template v1 implementation Wayfinder Ticket 07（grilling），用户两轮对答（Q1–Q7 顶层 seam、
  Q8–Q12 命令/输出/存储/引擎/闭包细节）逐项按推荐采纳
- 关联：ADR-0041、ADR-0042、ADR-0043、TV1-T07、冻结 checkpoint
  `0b485f4a13de9d754a81d07f464730776e13c14b`

## 背景与约束

冻结规格（旧 map tickets 06/14/15 与 CONTEXT.md glossary）已经把 Evaluation 定义为 9 个全序阶段：认证与
容量准入 → root current 解析与 closure 冻结 → 每 unique snapshot 权威重检 → RootDocument 验证与
AdmittedRenderInput 形成 → authored AssetRef 预准入 → CapabilityState 建立 → 惰性 materialization →
原子 seal → Renderer Command。ADR-0041 已冻结：Rendering 独占 Evaluator、`RenderResource`、RenderDocument、
Renderer Command、RenderOutput 与 Rendering problem；Rendering 拥有 `RenderEngine` outbound Interface（app
提供 production process Adapter、测试提供 scripted Adapter）；`renderweave-rendering` 可 compile-depend on
schema/validation/asset/template，任何 `.internal` 跨 artifact import 被拒绝。ADR-0042/0043 冻结了
Template 与 Asset 的 authoring/持久化 seam，但没有面向 Rendering 的只读 closure/recheck authority，也没有
Validation 侧的单文档 typed-view admission provider Interface。本 ADR 只冻结实施 seam，不重开规格语义，
不创建 Java Interface、migration、route、gate 组成或产品执行。

## 决策

### 1. Template-owned `TemplateClosureAuthority`（render 专用只读 seam）

Template.api 新增 provider-owned `TemplateClosureAuthority`，只供 Rendering 消费（镜像 T06 给 schema 补
`StaticSchemaAuthority` 的模式）。唯一 render 专用 closed 操作 `freezeClosure(renderRequestId, rootTemplateId)`
把冻结规格 stage 2–3 收口：root current 解析、每份候选 revision 的 exact parse/canonical/contentHash
integrity 复核（mismatch 是内部 integrity failure，不降级、不重算、不把 payload 交给 Evaluator）、递归
TemplateRef 闭包冻结、逐 unique snapshot 的 same-scope/DAG/Profile/无损 lowering-edge 权威重检、current
漂移的有界重试（耗尽返回 `TEMPLATE_CLOSURE_UNSTABLE`），返回不可变 closure snapshot 或 closed 失败。

`TemplateSnapshot` 与 closure manifest 值类型继续由 Template 独占（ADR-0041）；本 authority 与 T12b 的
`AssetReferenceAuthority`（delete 预检 proof/redactedCount）各自独立——操作面与消费方不同（render 冻结 vs
delete 预检），但共享同一 snapshot 值类型家族。closure 冻结后的 stage 5『提取全部 authored AssetRef atom
预准入』依赖 DesignDSL full-Profile 的 asset-ref 解析原子（当前 canonical kernel 不支持非空 children），
登记为后续依赖：`freezeClosure` 的 AssetRef-atom 提取部分随 DesignDSL full-Profile 拆分实现，本 ADR 不预建
接口方法。authority 的实现随首个 Rendering task 票落地。

### 2. 单一窄 `Evaluator` 接口与 closed outcome

`renderweave-rendering.api` 只暴露一个 provider Interface `Evaluator.evaluate(EvaluationCommand)`（镜像
`TemplateApplication`/`AssetApplication` 模式），返回 sealed `EvaluationOutcome`：
`SealedDocument(...)`（不可变 RenderDocument 交接值 + 成功身份成分）或 `Rejected(stage, code, 有界问题)`。
EvaluationCommand 携带：renderRequestId（Rendering 创建）、root Template 身份、原始 RenderInput bytes、
bounded output 选择（见决策 3）。input admission（strict envelope、Validation authority、Custom 消解）在
Rendering 内部经 provider 接口完成（ADR-0041 已定 AdmittedRenderInput 由 Rendering 独占），不拆成多个
公共接口；first-fail 串行语义、lazy materialization、Binding overlay、结构展开与 Asset occurrence 串行
resolve 全部收在 Rendering.internal。

### 3. Profile 选择权：caller 只选 bounded output，rendererProfile 服务端冻结

调用方可选 closed output 集合：`PNG{dpi}` / `JPEG{dpi, quality}`，缺省在构造前展开为 96/90；Layout
Profile 由 compatibility table 唯一确定、Renderer Profile 由服务端能力决定，调用方不能协商、不能选
`latest` 或 fallback。每条 Renderer Command 首版恰好输出根 Canvas 的一张完整 PNG/JPEG（冻结规格 §11）。

### 4. `RenderOutput` 携带最终图片 bytes 与有界描述

`renderweave-rendering.api` 的 RenderOutput 是 closed 值：Engine 原子封存后返回的 sealed 图片 bytes +
outputProfile + 有界描述（尺寸/格式/字节数）。公共 Render/Authoritative Preview 据此直接供预览/下载；
RenderDocument、Renderer Command、内部 digest、fetch lease 与业务身份一律不返回调用方、不持久化、不跨
请求复用。

### 5. Rendering.spi `CapabilityStateStore` port + app 侧加密落盘

Rendering.spi 定义 closed 三操作：`save(renderRequestId, fingerprint, state)` → opaque id、
`load(id, fingerprint)` → `snapshot | fingerprintConflict | missing`、固定 TTL 过期驱动（不续期）。app
Adapter 落盘时加密 state——Random nonce 是 server-only 秘密，不明文入库（窄版镜像既有 EnvelopeCrypto
模式）；Rendering 不碰 JDBC/加密实现。CapabilityState 的 Clock（单一 UTC 整秒）/Random（HMAC/rejection
派生）与 demand 语义全部 Rendering.internal。

### 6. `RenderEngine` port：单次 `execute` + closed 五态

Rendering.spi 的 `RenderEngine.execute(RendererCommand)` 返回 closed outcome：
`SealedOutput | Joined | Replayed | TerminalProblem | Unknown`。Java 侧对 `Unknown`（传输结果不明）在原
absolute deadline 与全部 lease 有效期内用同一 canonical Command 重发（不重新 seal、不续期 lease、不重新
resolve current、不重建 CapabilityState、不延长 deadline）；registry 状态丢失必须失败，新请求重新
Evaluation。cancel/join/replay/registry 是 Engine 侧语义，不暴露为 Java 子接口；deadline 由服务端按部署
配置在 Command 中冻结。

### 7. 诊断 sidecar 内部持有，app 只做有权限投影

请求级诊断 sidecar（opaque occurrenceId/resourceId → 完整 OccurrencePath、sourceNodeId、resourceId 安全
locator 映射）是 Rendering.internal 实现细节：容量受限、操作结束销毁、不进入 RenderDocument/identity/
cache/普通日志/长期历史。只有拥有对应 Template read 权限的诊断调用者经 app 把 sidecar 投影为
definitionId/bindingId/sourceNodeId 与逐段授权路径，无权 segment redact；Asset identity 按 asset.read
规则附加。

### 8. Rendering problem 基础形态与 oracle 归属

`renderweave-rendering.api` 冻结 problem 基础形态 `{code, stage, safeLocation, parameters}` 与 closed
stage enum：`REQUEST_ADMISSION | TEMPLATE_CLOSURE | INPUT_ADMISSION | ASSET_ADMISSION | CAPABILITY_STATE |
MATERIALIZATION | ASSET_RESOLUTION | DOCUMENT_SEAL | ENGINE`；容量 problem 的 parameters 只允许 closed
`limitId`。容量数值与 limitId→code/stage/reservation point/零写边界的机器 oracle 归 Ticket 19（不预建）。
内部违约（contentHash/Profile 兼容回归、malformed sealed document、manifest 不变量破坏）对外折叠为
`RENDER_INTERNAL_ERROR`；合法 final-geometry 约束失败是请求级 layout 错误，不改变 readiness。

### 9. 跨语言合同语料纪律

T07 冻结语料格式与重放计划，首个 task 票落向量：机器可读 RenderNodeContractCatalog（供 Java
sealer 与 Rust parser/validator 消费同一合同，不得各写一套漂移 switch）+ RenderDSL canonical/digest
vectors + Command/文档封存向量 manifest，镜像 T03/T10 kernel manifest 格式；Java primary 重放，T08 后
Rust independent 重放，届时新增 `render` gate 纳入 `full`。Design/Input/Expression authoring 反馈由
Java authority 与独立 TypeScript 重放相同语义向量（不要求无关语言重复不属于其 seam 的场景）。

### 10. T07/T08 边界与 READY 纪律

T07（本 ADR）只冻结 Java 侧合同形状、failure boundaries、语料计划与 port outcome 集合；T08 冻结
process framing/codec/deadline/cancel/stdout/stderr/exit/crash/length/digest 合同、hermetic build、
ELF closure、portable tricky-font、byte/pixel replay 与双物理 Linux CPU-family 外部认证。Windows/WSL
单机结果或 scripted adapter 永远不能升级为 Renderer READY；`renderweave-renderer/1.0` 等 Profile 在真实
Engine 认证完成前持续 `NOT_REGISTERED`；v1 明确排除 MaterializedScene、持久中间 IR、Java 预布局、
partial/streaming 文档、跨请求复用、public RenderDSL 上传下载、placeholder 与默认字体；Ticket 19 open。

## 备选方案

| 方案 | 未选择原因 |
| --- | --- |
| app 编排预冻结 closure/input，Rendering 只做 lowerer | Evaluation stage 2–3 散落 app，与『Rendering 拥有 Evaluation』冲突 |
| Rendering 定义 spi closure port 由 app 实现 | app 仍需 Template 公开只读接口，等于方案 1 加一层间接 |
| 拆多个公共接口（closure/input/evaluator 分离） | 破坏 first-fail 串行语义的原子性，调用方被迫编排阶段 |
| caller 选择 rendererProfile | 与 compatibility table 唯一确定、调用方不可协商 Profile 冲突 |
| RenderOutput 仅描述/引用 | 预览路径多一层状态与握手，公共面拿不到最终图片 |
| CapabilityStateStore 最小 put/get | 丢失 fingerprint 冲突与过期语义的合同保证 |
| RenderEngine 多方法 submit/poll/cancel | 把 Engine registry 时序泄漏进 Java 接口，跨进程状态机更复杂 |
| 合成宽只读 authority 服务 render+delete 预检 | 操作面与消费方不同，合成接口同时服务两个上下文 |
| T07 立即物化全部 contract/向量 | 无实现可验证，向量易与实际 seal 语义漂移；T07 是 freeze-only |
| 把 process wire 并入 T07 | ADR-0041 已把 framing/codec/wire 明确归 Ticket 08 |

## 后果与边界

T07 后 Rendering 的接口骨架（`Evaluator`/`EvaluationCommand`/`EvaluationOutcome`/`RenderOutput`、
`TemplateClosureAuthority`、`CapabilityStateStore`/`RenderEngine` ports、problem 基础形态与 stage enum、
语料格式）全部冻结，app 只实现 consumer-owned ports 与 process adapter；代价是首个 Rendering task 票
需要同时物化 authority、Evaluator 与 seal 的完整纵切，且 AssetRef-atom 提取等待 DesignDSL full-Profile。

本 ADR 只冻结实施合同：没有创建 Java Interface、migration、route、gate 组成或产品代码；自动文档/gate
通过也不证明 Renderer READY。Ticket 19、DesignDSL Profile available、Editor/Renderer 外部认证状态不变；
T08（Rust protocol grilling）随 T07 resolve 成为唯一 unblocked frontier，T13 仍以 T08 为 blocker。
