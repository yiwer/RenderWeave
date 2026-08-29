# T185 — canonical diagnostic sidecar 与字节预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T13, T21, T128, T154, T166, T184 (resolved)

## 目标

物化 Ticket 19 `RW-T19-S7-104`、ADR-0044 与 rendering-pipeline cap-024：
`diagnostics.sidecarBytes` 使用 MAX_INCLUSIVE `8388608`，observed `8388607/8388608/8388609`，
contract stage `REQUEST_SIDECAR`、public stage `MATERIALIZATION`、code
`RENDER_DIAGNOSTIC_LIMIT_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。超限必须丢弃完整请求级
sidecar 与未提交 RenderDocument，不能截断、跳项或调用 Engine。

cap-024 此前因 T21 的路径字符串近似和最终 opaque occurrence/resource locator 尚未物化而 deferred；本票先
收口该语义缺口，再对最终 canonical sidecar 字节计数，不把半成品 Materializer list 冒充正式 sidecar。

## Interface / seam

- 不新增 public API/SPI、route、DTO、持久化或 app wiring。sidecar 继续由 Rendering.internal 请求聚合拥有，
  只在内部 sealed evaluation 生命周期内存在，`Evaluator` 公共 outcome 不暴露 raw sidecar。
- 建立单一 internal OccurrencePath authority，复用 T128 已冻结的 ROOT/TEMPLATE_USE/REPEAT invocation segments，
  再追加 closed node/role segment；至少区分 source-node、repeat-container、repeat-item、conditional-frame、
  template-use-viewport 与 canvas-background。路径不含业务值。
- Materializer 的 SidecarEntry 与 ResourceEntry 使用该 exact path；resourceId 继续由 versioned canonical
  `OccurrencePath + ConsumerPropertyRef + expectedKind` 唯一派生，移除 T21 的路径字符串近似与 sibling collision。
- Sealer 在分配每个 `rwocc_` ordinal 时绑定 exact path，并形成 closed canonical request sidecar：occurrenceId →
  path/sourceNodeId，以及 resourceId → occurrence/property locator。它不进入 RenderDocument、任何 digest/cache/log/
  history，公共结果形成后立即失去引用。
- canonical writer 逐 UTF-8 chunk 在保留前经同一 request tracker 原子 reserve
  `DIAGNOSTICS_SIDECAR_BYTES`；失败返回冻结 MATERIALIZATION problem。T154 sidecarItems 计数语义保持独立，
  不重复实现 guard。

## TDD、验证与边界

- guard test 先引用缺失 limit，取得 compile RED；再以最终 canonical sidecar 的 below/at/above prefix 取得产品
  behavioral RED，证明 exact-at 成功、above 零 `SealedEvaluation`。
- 覆盖 root/sibling、Repeat、TemplateUse sourceCanvas 与 resource locator 的 exact canonical path/occurrenceId 映射，
  并证明 raw sidecar 不进入 RenderDocument/digest/public outcome。
- focused Rendering、受影响 reactor、`render` 与 `fast`；无 app wiring，不重复 server/full，除非受影响面扩大。
- 本票不实现 LayoutTrace、公开权限投影、正式 Ticket 19 records/product executor、Profile registration/
  certification、provider/API Key/真实数据、push/tag/PR。claim 时 A0、J0 pending、J1 未批准。

## Resolution（2026-08-29）

- 已建立 Rendering.internal 单一 `OccurrencePath` authority：复用 capability ROOT/TEMPLATE_USE/REPEAT
  invocation segments，追加 closed node/role segment；Materializer 对 sibling、Repeat item、TemplateUse viewport、
  child sourceCanvas 与普通 source node 形成 exact path，并强制 final static node 与 sidecar entry 一一对应。
- `ConsumerPropertyRef` 已物化为冻结 closed `{rootPropertyId,selectors[]}`，Image 使用 root-only，Text Run 使用
  `INDEX → MEMBER`；`resourceId` 按 versioned canonical `OccurrencePath + ConsumerPropertyRef + expectedKind`
  派生，移除了旧字符串 property/path 近似及 sibling collision。
- Sealer 分配 preorder `rwocc_` ordinal 时绑定 exact path，并生成 closed canonical request sidecar；重复 occurrence/
  resource locator 或资源无法映射到 sealed occurrence 均 fail closed。raw sidecar 不进入 RenderDocument、digest、
  public `Evaluator` outcome、app、日志或持久化。
- cap-024 唯一 production guard 已接入：MAX_INCLUSIVE `8388608`，canonical UTF-8 在保留前逐段 reserve；exact-at
  成功，above 返回 MATERIALIZATION / RENDER_DIAGNOSTIC_LIMIT_EXCEEDED 且零 `SealedEvaluation`、零文档输出。
- TDD 取得 missing-limit、sidecar accessor 与 typed ConsumerPropertyRef compile RED，以及 sibling resourceId collision、
  TemplateUse path behavioral RED，随后 focused tests 全绿。受影响 reactor：Schema 20/20、Validation 13/13、
  Template 86/86、Asset 92/92、Rendering 308/308，共 519/519。
- `render`：`.sdlc/evidence/20260829-152404-render/metadata.json`，passed/A1；其中既有 RenderDocument independent
  replay 83/83 无漂移。`fast`：`.sdlc/evidence/20260829-152454-fast/metadata.json`，3/3 passed/A1。
  T185-specific A2/A3 无；J0 pending，J1 未批准，状态仅为 automated_verified。
- 未新增 API/OpenAPI/Web/Flyway/app wiring/Profile/provider，未读取 API Key 或真实数据，未产生费用，未
  push/tag/PR；按影响面未重复 server/full。
