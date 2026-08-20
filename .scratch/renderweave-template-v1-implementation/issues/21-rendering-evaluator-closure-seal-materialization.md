# 物化首个 Rendering 纵切：TemplateClosureAuthority / Evaluator / seal 与 RenderNodeContract 向量语料（Java primary）

Type: task
Status: resolved / automated_verified
Claimed by: Claude（single-writer）
Blocked by: 07, 08, 20（均已 resolved：ADR-0044 / ADR-0045 / DesignDSL full-Profile 原子与依赖投影）

## Resolution（2026-08-20）

按 Answer 设计完整物化：renderweave-rendering 首个 artifact + TemplateClosureAuthority/
DesignSemanticAuthority template seams + CanonicalEvaluator stage 1–8 + CapabilityState
加密落盘（V023）+ Sealer canonical RenderDocument/digests + renderweave-render-seam-v1/1
向量语料（独立 Python 期望值，Java primary 重放）+ RenderEngine 五态 port 合同。端到端
assembly 证明：Testcontainers PostgreSQL 上 create → evaluate → SealedDocument（含量化断言）。
rendering 102 tests + app 全量绿；template/asset 无回归；canary 22→23；contractVersion 保持
0.13.0。审查后修复：预准入按成员名取 exact kind（imageRef→IMAGE/fontRef→FONT）、PUBLIC Custom
override AssetRef atom 同批预准入、resourceId 哈希移除公式外的 assetId 成分、依赖缺位收口改用
通用 EVALUATION_FAILED（冻结码集无 asset-unavailable 专用码）、canonical/seal 两处 JSON 转义
合并单点。诚实边界（均记录于 NOTES）：无公开 route（Engine 执行随 Rust Renderer 实现票）；
AssetResolutionPort 生产 bridge 随 T13；callPosition 简化对象待完整 OccurrencePath 硬化；
节点 default 展开随 catalog 数据深化；asset-selection digest domain 为命名家族推断值并由向量
锁定；CapabilityStateStore 的 save/replay/conflict 编排（stage 6 与 resend 编排绑定
evaluationFingerprint 的完整闭环）随 Engine 时代接线——本票物化并测试 V023 加密落盘 Adapter
合同（replay/conflict/TTL），Evaluator 尚未调用 store。Profile 保持 NOT_REGISTERED；T13 成为
唯一 unblocked frontier。

## Question

ADR-0044 已冻结 Evaluator/RenderDocument 产品 seam，DesignDSL full-Profile 原子（T14–T20）与
Template/Asset 纵切（T06–T12b）均已落地。如何物化首个 Rendering 纵切：`renderweave-rendering`
首个 artifact、Template-owned `TemplateClosureAuthority`（render 专用 `freezeClosure`）、单一窄
`Evaluator.evaluate` → closed outcome 的 stage 1–8 完整串行（REQUEST_ADMISSION → DOCUMENT_SEAL）、
CapabilityState 加密落盘 port、`RenderEngine` 五态 port 合同、RenderNodeContractCatalog 与
RenderDSL canonical/digest/Command 封存向量语料（Java primary），同时不预建 Engine 执行、公开
render route、AssetResolver 实现或容量数值 oracle？

## Answer（本票冻结的实施决定）

- **模块**：新建 `renderweave-rendering` Maven module（ADR-0041 compile 上界：→ schema/validation/
  asset/template），`api/spi/internal` ownership 与 template/asset 同构；`RenderingModule` final
  static factory 是唯一 app 可见 `.internal`（登记进 `TemplateV1ArchitectureTest`
  `APP_ASSEMBLY_EXCEPTIONS` 与 baseline edges）；新增 `RenderingModuleArchitectureTest` 镜像既有规则。
- **TemplateClosureAuthority**（`renderweave-template.api`，provider-owned，镜像
  `StaticSchemaAuthority` 模式）：唯一 render 专用 closed 操作
  `freezeClosure(renderRequestId, rootTemplateId)` → 不可变 closure snapshot（逐 unique
  `TemplateSnapshot`：templateId/revision/ownerScope/permanent StaticSchemaRef/canonical DesignDSL
  bytes/contentHash）或 closed 失败。物化内容：root current 解析、每份候选 revision 的 exact
  parse/canonical/contentHash integrity 复核（fresh sha256 与 `DesignDslAuthority.admit` 重放比对；
  mismatch = 内部 integrity failure，对外折叠 `RENDER_INTERNAL_ERROR`，不降级不重算）、递归
  TemplateRef 闭包冻结（经 `loadUseTargets`）、逐 snapshot same-scope/DAG/Profile/无损
  lowering-edge 权威重检、current 漂移有界重试（3 次，耗尽 `TEMPLATE_CLOSURE_UNSTABLE`）。
  `TemplateSnapshot`/closure 值类型 Template 独占；与 T12b `AssetReferenceAuthority` 独立。
- **Evaluator**（`renderweave-rendering.api` 唯一 provider Interface）：
  `evaluate(EvaluationCommand)` → sealed `EvaluationOutcome { SealedDocument, Rejected(stage, code, 有界问题) }`。
  EvaluationCommand 携带 renderRequestId（Rendering 创建）、root TemplateId、ownerScope 授权
  invocation、原始 RenderInput bytes、bounded output 选择（`PNG{dpi}` / `JPEG{dpi,quality}`，缺省
  96/90；Layout/Renderer Profile 服务端冻结不可协商）。stage 1–8 first-fail 串行：
  1. REQUEST_ADMISSION：Rendering-owned ownerScope facet 授权 + strict-JSON envelope 最外层容量。
  2–3. TEMPLATE_CLOSURE：经 TemplateClosureAuthority。
  4. INPUT_ADMISSION：envelope 检查（rootDocument 必填 + customValues[] 规则）→ 复用 app
     `ValidationTargetResolver` bean 构造 exact StaticSchemaRef 固定的 `ResolvedValidationTarget`，
     `RootDocumentValidator` 单文档 typed-view 验证 → `AdmittedRenderInput`（closed typed context、
     PRESENT/ABSENT、有效 Custom map、类型证明）；Schema 目标只来自 root TemplateSnapshot。
  5. ASSET_ADMISSION：全部 authored AssetRef atom + PUBLIC Custom override AssetRef atom 预准入
     （same ownerScope/存在/ACTIVE/kind exact；不 pin contentVersion、不读 metadata、无锁无 lease）。
  6. CAPABILITY_STATE：闭包内声明合同收集、线性化初始化 Clock snapshot（单一 UTC 整秒）与
     Random nonce（256-bit server-only）；`CapabilityStateStore.save` 绑定 evaluationFingerprint，
     同 key 同 fingerprint 重放、异 fingerprint `CAPABILITY_STATE_CONFLICT`、固定 TTL 不续期。
  7. MATERIALIZATION：`renderweave-expression/1.0` 完整求值（grammar/precedence/strict typing/
     ERROR 传播/`&& || if coalesce` lazy/expression input memoization/Definition 按 declaration
     frame memoize）、ValueSource 五 kind 消解、Binding overlay（policy 类型与再验证，失败即失败无
     baseline 回退）、Repeat 展开（loop frame、`system-basic-*@v1`、PACK→stack/grid、原 inputIndex
     不重编号）、Conditional 求值与剪枝（absentPolicy ERROR|FALSE；剪枝不占 occurrenceId）、
     TemplateUse 展开（ContextSelector、contextAbsentPolicy ERROR|SKIP、fills、隔离子 invocation、
     compositionViewport lowering）、固定消费序 lazy materialization、消费点 Asset 串行 resolve
     （首个 demand 失败即停）。
  8. DOCUMENT_SEAL：RenderNodeContractCatalog 驱动的 lowering（mm→pt = ×360/127，HALF_EVEN ≤6
     位小数，`-0`→0；`*ResourceId` 替换；authored-only 字段移除；默认展开）、静态树先序
     occurrenceId（`rwocc_` + 16 位零填充小写十六进制）、RenderResource manifest 与树双射、
     canonical RenderDocument bytes（c14n 合同 C14N-P001..P013）与 RenderDocument digest
     （domain `renderweave-render-document/1\0`）、capability result digest、asset selection
     digest、evaluation result digest、Renderer Command 构造（`renderweave-render-command/1.0`，
     deadline 服务端配置冻结）。
- **Asset 消费 seam**：Rendering.spi consumer-owned `AssetResolutionPort`（ADR-0041 consumer-owned
  seam 模式；镜像 T12b `AssetReferencePort` 方向对偶）：`precheckAdmission(ownerScope, assetId,
  expectedKind)` 与 `resolve(renderRequestId, ownerScope, resourceId, assetId, expectedKind,
  rendererAudience, renderDeadline)`（输入成分与 ADR-0043 冻结的 `AssetResolver` 对齐，T13 物化
  provider 与 app bridge）；`resourceId = "rwres_" + 64-hex SHA-256(canonical OccurrencePath +
  ConsumerPropertyRef + expectedKind)`。本票 app 侧无生产 bridge（fail-closed：含 Asset 请求在
  ASSET_ADMISSION 以依赖不可用收口），测试用 scripted adapter——不是 test-only bypass，是 ADR-0041
  明确授权的测试 seam；生产 bridge 随 T13 同批接线。
- **CapabilityStateStore**（Rendering.spi closed 三操作）+ app Adapter：V023
  `rendering_capability_state`；AES-GCM-256 加密落盘，key 来自部署配置（缺失不装配、失败封闭），
  96-bit nonce 由 HMAC-SHA256(key, recordId || fingerprint) 派生（server-only，不明文入库）；
  `@Scheduled` 固定 TTL 过期清扫（只删过期行，不续期）。
- **RenderEngine port**（Rendering.spi）：`execute(RendererCommand)` → closed 五态
  `SealedOutput | Joined | Replayed | TerminalProblem | Unknown`；本票仅落合同与 scripted adapter
  测试（Unknown → 同 canonical Command 原 deadline 内重发纪律），无生产 process adapter（随 T08
  实现票）；cancel/join/replay/registry 仍是 Engine 侧语义。
- **problem 与容量**：`renderweave-rendering.api` 冻结 `{code, stage, safeLocation, parameters}` 与
  九值 stage enum；容量 parameters 只允许 closed `limitId`，数值取冻结 `capacity-budgets-v1.json`
  结构常量（renderInput/closureAndExpansion/capabilityRuntime/renderDocument/diagnostics/problems
  组），计数守卫线程贯穿求值；机器 oracle/registry 属 Ticket 19 不预建；内部违约折叠
  `RENDER_INTERNAL_ERROR`；final-geometry 约束属 Engine 阶段不在本票。
- **诊断 sidecar**：Rendering.internal 请求级映射（opaque occurrenceId/resourceId → 完整
  OccurrencePath + sourceNodeId + resourceId locator），容量受限（超限
  `RENDER_DIAGNOSTIC_LIMIT_EXCEEDED`）、求值结束销毁、不进 RenderDocument/digest/日志；app 权限投影
  随产品面票据，本票无投影 route。
- **RenderNodeContractCatalog 与向量语料**：机器可读 catalog JSON（16 static kinds：canvas/group/
  frame/stack/grid/text/image/rect/ellipse/line/polygon/polyline/path/qrCode/barcode/
  compositionViewport；canvas root-only、compositionViewport lowering-only、repeat/conditional/
  templateUse 在 RenderDSL 非法；逐 kind closed payload/defaults/ContentModel/DesignDSL→RenderDSL
  lowering edges），Java sealer 单一消费（不得与未来 Rust parser 各写漂移 switch）；vectors
  manifest 镜像 T03/T10 kernel 格式（`vectorVersion`/`authorityContext`/`profileAvailability:
  NOT_REGISTERED`/cases），覆盖 RenderDSL canonical/digest（c14n 合同）、seal/Command 封存、
  capability HMAC 派生 exact 值；Java primary 重放为模块测试（A1），独立重放（Rust）与 `render`
  gate 入 `full` 随 T08 实现票；Design/Input/Expression authoring 反馈的 TypeScript 独立重放属
  Editor 实施票前置，不在本票。
- **产品面**：本票无公开 render/preview/diagnostic HTTP route、无 OpenAPI/Web SDK 变更、
  contractVersion 保持 0.13.0——RenderDocument/Command/digest 一律不返回调用方、不持久化、不跨请求
  复用；公开面随 Engine 实现票（`RenderOutput` 值类型本票随 api 冻结但不产出图片）。
- **Profile/READY**：`renderweave-renderer/1.0` 等 Profile 持续 `NOT_REGISTERED`；scripted adapter
  与 Windows 结果永不升级 Renderer READY；Ticket 19 open。

## 允许影响

root reactor 与 app POM compile edges、renderweave-rendering 新模块源码/测试/vectors、
renderweave-template api/internal（TemplateClosureAuthority + TemplateModule factory）、
renderweave-app Adapter/Configuration/V023 migration、contract/public-surface/architecture/
canary 测试、CONTEXT/tracker/plan/log/NOTES/evidence。

## 禁止影响

公开 render/preview/diagnostic route 与 OpenAPI/Web SDK 变更、AssetResolver/lease/fetch endpoint
实现（T13）、Rust 工程与 process adapter（T08 实现票）、`render` gate 入 `full`（同上）、Profile
available 注册、容量数值 oracle/registry（Ticket 19）、Editor 产品代码、真实 Engine 执行或
synthetic raster、付费 provider/真实数据。

## 局部验证

TDD red/green；rendering 模块 contract/public-surface/architecture；closure integrity/漂移重试/
TEMPLATE_CLOSURE_UNSTABLE；input admission typed-view/Custom 消解；expression exact 语义；Repeat/
Conditional/TemplateUse 展开与剪枝；capability HMAC exact 派生/demand/fingerprint/conflict；seal
occurrenceId 先序/c14n bytes/digests/Command；scripted engine Unknown 重发；Asset port 缺席
fail-closed 与 scripted 在场全链路。

## 受影响验证

`template`、`asset`、`server`、`web` 与完整 `full` 16/16（app-wiring：V023 + app Adapter；
canary 迁移数 22→23）；Testcontainers PostgreSQL。

## 保证等级

gate A1；向量 Java primary 重放（独立重放缺位，A2 随 Rust independent）；无 A3/J1。

## 完成信号

Ticket 21 resolved/`automated_verified`、T13 成为唯一 unblocked frontier、verified commit 且
worktree clean；push 待用户另行授权。
