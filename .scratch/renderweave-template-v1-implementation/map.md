# Wayfinder Map：RenderWeave Template v1 Implementation

Label: wayfinder:map

## Destination

在不改写现有 Schema/Inference v1 历史的前提下，把冻结于 `0b485f4a13de9d754a81d07f464730776e13c14b` 的 Template v1 合同实现为可验证的新增产品能力：交付 DesignDSL、Template、Asset、Evaluator、Editor、RenderDocument 与独立 Rust Renderer，并以真实产品 target/executor、浏览器观察、人工 J1 和两种物理 Linux CPU-family 认证如实推进生命周期；外部门控未完成前不得宣称 Editor、Renderer 或 Template v1 READY。

## Notes

- 本地图是新的实施 effort。旧 `.scratch/renderweave-template-v1/map.md` 仍是决策完备的规格探索记录；其中“本地图不实施产品代码”只约束旧 effort，本地图依据用户明确授权承载产品实施。
- 权威起点由三部分组成：本地 `main` 的已提交锚点 `7848c821...`、Template 规格提交 `b14c2d7` 与 `0b485f4`、以及 7 份已核验的 Ticket 19 LF registry 修复。Ticket 01 已在相邻独立 worktree 中整合它们；dirty main 未被 reset、checkout、覆盖或清理。
- 实施分支以本地 main 已提交锚点建立；当前 P6 写入冻结。由于 tracker 没有原子 claim 能力，Template effort 保持单 writer，每次会话只 claim 一个 frontier 票据。
- 用户授权在新实施分支上按“已验证票据”提交；未经另行明确授权，不 push、不建 tag、不建 PR。规格 worktree 当前差异在首票迁移前也不提交。
- 允许修改 Java、Web、Rust、OpenAPI、迁移、测试和证据，但只能为已 claim 票据实现完整纵切；禁止 placeholder 页面、表、字段、接口、route、test-only bypass 或未接线模块。
- 三个 Java deep module 已冻结为 `renderweave-template`、`renderweave-asset`、`renderweave-rendering`；`renderweave-app` 只承载适配器。不得创建泛化 `common` 模块。精确依赖方向与 owning interfaces 由“冻结 Template 实施模块 interface 与依赖方向”决定。
- Rust Renderer 是独立进程/可执行文件 seam，不使用 JNI/FFI。Java 只面向 closed Command/RenderDocument interface；生产使用 process adapter，测试使用 scripted adapter。
- `ownerScope` 只能来自 Host capability `OwnerScopeAuthority`，请求不得自报。dev/test 可使用配置固定的 single-owner adapter；缺少 production auth adapter 时失败封闭并阻止 READY，Workspace/org auth 不进入 Template 领域。
- 首个产品代码增量是最小 DesignDSL canonical kernel：只覆盖 `system-empty@v1` 与单一 Canvas 的 strict admission/canonical bytes/domain-separated hash；不带 DB、API、UI 或 Renderer。
- canonical kernel 即使进入生产源码，也不得把不完整语义注册成可用的 `renderweave-design/1.0`。只有 Node/Definition/Binding/Repeat/Conditional/TemplateUse/Property Identity 全部 exact Profile 语义原子通过后，Profile catalog 才能启用。
- canonical kernel 暴露一个 deep interface：`DesignDslAuthority.admit(rawUtf8)` 返回 closed success/failure union；其内部隐藏 strict parse、预算、metadata normalization、set ordering、canonical encoding 与 hash，且不得访问 StaticSchema、Asset、Template、网络或数据库。
- 新增 `template` gate，并将其纳入 `full`。kernel 阶段由 Java primary 与独立 Python verifier 重放冻结的 canonical/digest/error/limit vectors，包括 duplicate key、illegal UTF-8、unknown member、decimal/`-0`、Unicode、semantic/set array ordering 与 16 MiB counting sink；不得启动 DB、网络、浏览器、provider 或产品 route。
- kernel milestone 不创建迁移。只有 Template aggregate/revision/ownerScope/create-save concurrency 已连成产品纵切时才选择实施分支当时的下一个 Flyway 版本；数据库验证必须使用 PostgreSQL/Testcontainers，迁移 forward-only，禁止占位表或列。
- Product Editor 是新增功能；可复用已批准的 prototype 视觉与交互决定，但不得复用刷新即失的内存状态模型。只有 Template API、canonical baseline、恢复和权威 preview 均真实存在时才开放产品 route；现有 `/prototype/template-designer` 保持 throwaway。
- `fast`、`server`、`web`、`runtime`、`full`、`e2e` 继续服从仓库 gate 约定；证据按 A0–A3/J0–J1 如实标注。静态 fixture、A1/A2 replay 不等于浏览器行为、人工验收、产品执行或物理 Linux 认证。
- grilling 票据使用 `grilling`、`domain-modeling` 与 `codebase-design`；task 票据使用 `agent-sdlc-workflow`，涉及实现时采用 `tdd`；prototype 票据使用 `prototype`。若后续出现必须查外部事实的明确问题，再登记独立 research 票据并使用 `research`，不得把模糊调研藏进实现票。
- 付费 provider、真实数据、API Key、生产部署和新的外部授权均不在自动执行范围。局部受阻时继续其他安全 frontier；只有产品语义必须改变、需要新授权或确实无安全路径时才询问用户。
- Goal（2026-08-20 恢复，用户明确指示）：持续推进 Template v1 直到完成。本会话已通过 goal tracker
  恢复为 active；本 tracker 继续作为票据/DAG 记账面，single-writer 纪律不变。

## Decisions so far

- [建立 Template v1 实施权威与反馈闭环](issues/01-establish-implementation-authority.md) — 隔离
  `feature/template-v1`、additive approved delta、7 份 LF repair 与无写 `template` gate 已形成可重复基线；
  没有产品代码或 readiness 声明。
- [冻结 Template 实施模块 interface 与依赖方向](issues/02-freeze-module-interfaces.md) — ADR-0041 冻结
  provider-owned public contracts、consumer-owned reverse/Host/process Seams、单向 staged Maven graph、closed
  outcomes 与 `api/spi/internal` ownership；现存 split package 已消除，非空转 architecture test 已进入 server
  gate。没有创建空 module 或产品能力。
- [实现 DesignDSL canonical kernel 与独立 replay](issues/03-implement-designdsl-canonical-kernel.md) — 首个真实
  `renderweave-template` artifact 通过单一 `DesignDslAuthority.admit(rawUtf8)` 对 empty definitions/bindings/
  children 的单 Canvas 原子执行 strict parse、九项预算、metadata/decimal normalization、16 MiB counting sink、
  canonical bytes 与 domain hash；33 个 exact vectors 由 Java primary/Python independent 重放。non-empty set/
  semantic arrays 继续 fail closed，DesignDSL Profile 未注册 available；没有 DB/API/UI/Renderer 或外部 I/O。
- [冻结 Template aggregate、revision 与 persistence seam](issues/04-freeze-template-aggregate-persistence.md) —
  ADR-0042 冻结 authoring `TemplateApplication`、Rendering/Asset 专用 provider Interfaces、Template-owned
  `OwnerScopeAuthority` 与 transaction-sized `TemplatePersistence`，以及 permanent scope/Schema、immutable
  revision/current、trusted read、closed disclosure/confirmation、zero-partial-write 与 forward-only invariants。
  本设计票没有创建 Java Interface、migration/table、OpenAPI/route 或产品执行；T06 才能物化真实
  create/current-read/save surface。
- [冻结 Asset admission 与 resolution 首个增量](issues/05-freeze-asset-admission-resolution.md) — ADR-0043
  冻结三个 Asset Interface（AssetApplication/AssetResolver/AssetAcceptanceAuthority）、acceptance kernel 先行
  与 Pillow/fontTools 独立 replay、S3 协议 Blob seam（生产 OSS、本地与测试 MinIO、AWS SDK v2）、app 侧事务
  容量计数器、Asset-owned Host facet、`AssetFetchEndpoint` Port 与 T10–T13 切片；无 Java/migration/route
  物化。
- [实现 Asset acceptance kernel 与独立 replay](issues/10-implement-asset-acceptance-kernel.md) — 首个真实
  `renderweave-asset` artifact：`AssetAcceptanceAuthority.admit` closed admission 覆盖 PNG/JPEG/WebP 与
  TTF/OTF 全切片；38 冻结 vectors Java=38/38 + Pillow/fontTools Python=38/38（A2）；`asset` gate 纳入 `full`；
  嵌入 ICC 暂时 fail-closed（T10b 补齐 canonical sRGB 等值），Profile 未注册，T10b/T11 解锁。
- [补齐 canonical sRGB ICC 等值接受原子](issues/10b-canonical-srgb-icc-equality.md) — 冻结 sRGB IEC 61966-2.1
  profile（3144B，sha256 2b3aa164…af7e）为模块资源并接线 PNG/JPEG/WebP 字节等值接受；manifest 41 vectors，
  asset gate Java=41/41 Python=41/41（A2）。
- [实现 Template create/read/save PostgreSQL 纵切](issues/06-implement-template-crud-slice.md) — 首个真实
  Template 纵切：`TemplateApplication.create/getCurrent/save` closed outcomes、ADR-0041 exact `TemplateModule`
  assembly seam、V018 aggregate/revision、ownerScope 只取 Host authority、expectedRevision 409；
  Testcontainers PostgreSQL + OpenAPI 0.10.0 + Web SDK + `full` 15/15；无 Editor/Asset/Evaluator/Renderer，
  Profile 未注册，Ticket 19 open。
- [实现 Asset create/current/catalog PostgreSQL+S3 纵切](issues/11-implement-asset-persistence-slice.md) — 首个
  真实 Asset 纵切：`AssetApplication` 六 closed 方法 + `AssetModule` exact assembly seam、V019 四表
  forward-only、幂等 create（24h、指纹 replay/conflict）、catalog 稳定游标 + closed 过滤、metadata
  expectedAssetRevision 409、版本列表/精确下载/内部预览、容量水位 fail-closed；`AssetController` HTTP 面 +
  problem+json + OpenAPI 0.11.0 + Web SDK + compose MinIO（同镜像建桶）+ 65MB transport（inference 权威预算
  改 app 层）；无 S3 endpoint 时 AssetApplication/Controller 都不装配（属性条件，Boot 4.1
  `@ConditionalOnBean` 同批求值不可靠）；Testcontainers PostgreSQL+MinIO + `full` 16/16；tagsAny
  `text[]` 绑定、Spring 7 `isHandler` 只认 `@Controller`、表单字段 `@RequestParam` 绑定等隐藏缺陷同票修复。
  无 replace/delete/restore/Resolver/UI，acceptance/1.0 未登记，Ticket 19 open。
- [实现 Asset content replace 与旧内容恢复](issues/12a-replace-and-restore-content.md) — `replaceContent`/
  `restoreContent` closed 方法：admission 按永久 kind 复验、与 current 相同字节成功 no-op（先于 revision
  校验）、restore 复用旧 Blob/descriptor 追加新 contentVersion、容量只对新建 Blob 计数；V020
  `asset_audit_event` 有界审计（create/metadata/replace/restore 逐事务追加，no-op 不产生）作为可靠可重放
  STALE 事实流（Template 依赖投影票消费）；`PUT /{id}/content` + `POST /{id}/restore` HTTP 面 + OpenAPI
  0.12.0/Web SDK；Testcontainers PostgreSQL+MinIO + `full` 16/16。无 delete/restore/Resolver/UI，
  acceptance/1.0 未登记，Ticket 19 open。
- [实现被引用 Asset 删除确认与恢复编排](issues/12b-delete-restore-reference-confirmation.md) — Asset-owned
  `AssetReferencePort`（app Adapter 桥接 T20 `AssetReferenceAuthority`，完整计数 + 可读 TemplateId 明细 +
  redactedCount + 完整排序引用集合 fingerprint）；`deletePrecheck` 签发 5 分钟单次确认 token（绑定稳定
  actorId/ownerScope/assetId/assetRevision/fingerprint，V022 表）；`delete` 单事务内独占 reservation +
  token 校验 + 重算 proof 比对，任一漂移零写（REQUIRED/EXPIRED/STALE/DEPENDENCY_UNAVAILABLE）；
  Template current 变更对引用 asset 行按 assetId 升序 FOR SHARE 读 reservation 线性化；`restore` 重新
  激活 DELETED 同 current 不新建 contentVersion；DELETE/RESTORE 审计事件经 T20 consumer 驱动
  STALE→INVALID/READY；HTTP delete-precheck/delete/restore-lifecycle + OpenAPI 0.13.0/Web SDK；
  Testcontainers PostgreSQL+MinIO + app 全量 302 tests + asset/template/fast gate 绿。无 Resolver/lease/UI，
  acceptance/1.0 未登记，Ticket 19 open。
- [物化首个 Rendering 纵切：closure/Evaluator stage 1–8/seal](issues/21-rendering-evaluator-closure-seal-materialization.md) —
  renderweave-rendering 首个 artifact：Template-owned `TemplateClosureAuthority.freezeClosure`
  （integrity 复核/递归闭包/漂移有界重试）与 `DesignSemanticAuthority` 语义树 seam；单一窄
  `Evaluator.evaluate` first-fail 串行 stage 1–8（envelope 预算、typed-view admission、
  Expression 引擎、结构展开、Binding overlay 后按 nodeId 换入重构文档重新 admission、
  Asset 预准入与串行 resolve consumer seam、CapabilityState exact HMAC 派生与 AES-GCM 加密落盘
  V023）；Sealer 产出 canonical RenderDocument（rwocc_ 先序 occurrenceId、mm→pt 量化、
  compositionViewport lowering）与 renderDocument/admittedInput/closure/assetSelection/
  evaluationResult digest；renderweave-render-seam-v1/1 向量语料独立 Python 期望值 Java primary
  重放；端到端 assembly 证明（Testcontainers create→evaluate→SealedDocument）。无公开 route、
  无 OpenAPI 变更（contractVersion 0.13.0）、Profile NOT_REGISTERED；Engine 执行与 Rust independent
  replay/render gate 随 T08 实现票，AssetResolutionPort 生产 bridge 随 T13。
- [冻结 Evaluator 与 RenderDocument 产品 seam](issues/07-freeze-evaluator-renderdocument-seam.md) —
  ADR-0044 经两轮 HITL 对答（Q1–Q12 逐项按推荐）冻结：Template-owned `TemplateClosureAuthority`
  （render 专用 `freezeClosure`，AssetRef-atom 提取依赖 DesignDSL full-Profile）、单一窄
  `Evaluator.evaluate` → closed outcome（input admission 在 Rendering 内部）、caller 只选 bounded output
  而 rendererProfile 服务端冻结、`RenderOutput` 携带最终图片 bytes+描述、`CapabilityStateStore` spi
  closed 三操作 + app 加密落盘、`RenderEngine.execute` 单次五态 outcome（Unknown 重发纪律）、诊断
  sidecar 内部持有 + 有权限投影、problem 基础形态与九值 stage enum（容量 oracle 归 Ticket 19）、
  跨语言 RenderNodeContract/向量语料纪律（首 task 票落向量，T08 后 Rust independent + `render` gate）、
  T07/T08 边界与 READY 纪律。无 Java/migration/route，Profile 未注册，Renderer 不 READY，Ticket 19 open。
- [冻结 Rust Renderer process protocol 与认证计划](issues/08-freeze-rust-renderer-protocol.md) —
  ADR-0045 经两轮 HITL 对答（Q1–Q12 逐项按推荐）冻结：常驻 Rust daemon + UDS（单连接 requestId 多路
  复用）、握手（版本 + certified manifest + capability）+ 类型化 length 前缀帧（COMMAND/CANCEL/RESULT
  分帧/raw image bytes/PROBLEM）、registry 全在 daemon 内存（Java 只映射五态 outcome，崩溃=Unknown→
  原 deadline 重发→terminal）、FIFO queue/slot 全在 daemon（数值归 Ticket 19）、daemon 侧 HTTPS fetch
  app origin（rustls/allowlist/复验/5xx backoff，app 只经 AssetFetchEndpoint）、CANCEL 帧 + cooperative
  checkpoint + 零 partial output、adapter 监督生命周期 + 握手 manifest 校验 + backoff 重启、仓库内
  `renderer/` cargo workspace（不进 Maven reactor）钉死工具链/manifest/唯一 CPU 路径、Linux-only 认证、
  四级认证阶梯（仓库内 replay → 双物理 Linux CPU-family → J1/A3 → Ticket 19 数值）、Windows/WSL/
  scripted 永不升级 READY、帧向量随首个实现票落地。无 Rust/Java 产品代码，Profile 未注册，Renderer
  不 READY，Ticket 19 open。
- [物化首个 Rust Renderer process protocol 纵切与 `render` gate](issues/22-renderer-process-protocol-vertical.md) —
  **resolved / automated_verified**；T08/T13/T21 解锁后已原子物化仓库内 offline Cargo workspace、
  常驻 Linux UDS daemon、严格握手/typed frame/内存 registry、Java `RenderEngine` process Adapter 与
  Supervisor、exact machine manifest、Java/Rust/Python vectors 和 Linux 容器 UDS replay；`render` gate
  已随票纳入 17-step `full`。本票 manifest 明确 Profile `NOT_REGISTERED`/certification `NOT_CERTIFIED`，
  合法 Command 只产生稳定 terminal problem；未实现 synthetic raster、公开 route、物理 Linux 认证或 READY。
- [物化 exact RenderDocument 合同、default 展开与跨语言 validator](issues/23-render-document-contract-defaults.md) —
  **resolved / automated_verified**；同一机器可读 RenderNodeContract 已冻结 16 static kinds、closed members 与
  全部可表达 default，Java Materializer/Sealer 完成 Binding/Repeat PACK/lowering，Rust document crate 在 daemon
  Profile lookup 前执行 strict admission，Java/Rust/Python 与 Linux UDS 共同重放 2 positive + 12 negative。
  不含 layout/resource decode/raster/codec，Profile 仍未注册，合法 Command 仍 terminal fail-closed。
- [实现 surface preflight 与 exact PNG encoder kernel](issues/24-surface-preflight-png-encoder-kernel.md) —
  **resolved / automated_verified**；在不接线 daemon success path、不注册 Profile 且保持 raster ABSENT 的边界内，
  以 Rust deep kernel + Python stdlib independent replay 物化 Canvas/bleed/DPI exact surface arithmetic、Ticket 19
  输出容量与 `renderweave-output-png/1.0` stored-DEFLATE/chunk/CRC/Adler bytes；10 surface + 6 PNG cases / 90
  independent checks 已纳入 `render` gate。
- [实现 Layout Profile 静态可判定预检内核](issues/25-layout-profile-static-preflight-kernel.md) —
  **resolved / automated_verified**；只消费 T23 `AdmittedRenderDocument` 的独立 Rust deep kernel 已防御性验证
  frozen size/min-max、HUG/FILL cycle、Stack/Grid、Text 与 statically-decidable QR 约束，并在 daemon 的
  document-admission 边界 fail closed；7 positive + 25 negative 由 Rust/Python 共同重放，独立验证 32/32、
  77 checks。未产生 box/scene，未选择 tolerance，未注册 Profile 或接线 RESULT。
- [实现资源无关的确定尺寸 ABSOLUTE box 布局内核](issues/26-definite-absolute-box-layout-kernel.md) —
  **resolved / automated_verified**；在不选择 Layout tolerance、不接 daemon success 的边界内深化现有 Rust
  layout crate，只对 Canvas→Frame/8 种资源无关视觉叶子的 FIXED/FILL + ABSOLUTE 闭包计算 binary64
  pre-transform local LayoutBox/ContentBox；6 laid-out + 9 unsupported 由 Rust/Python 共同重放，独立验证
  15/15、50 checks。合法未覆盖面继续返回 internal unsupported，不冒充 public problem。
- [实现资源无关的确定尺寸 Stack 布局内核](issues/33-definite-stack-layout-kernel.md) —
  **resolved / automated_verified**；同一 Rust layout deep module 已在不选择 water-fill residual tolerance
  的前提下支持 definite Stack、STACK child、signed margin/gap、六种 justify、cross-axis align/FILL 与递归
  资源无关容器。Rust/Python `/2` shared replay 为 21 laid-out + 10 unsupported、31/31、97 checks；HUG/
  main-axis FILL、scene/raster/daemon success 继续 fail closed。
- [实现资源无关的 FIXED-track definite Grid 布局内核](issues/34-definite-fixed-grid-layout-kernel.md) —
  **resolved / automated_verified**；沿用同一 layout deep module，落实 ContentBox 逐项扣除 floor-zero，并实现不依赖
  residual tolerance 或 intrinsic measurement 的 FIXED-track definite Grid、GRID child span/margin/FILL/alignment/
  递归；Rust/Python `/3` shared replay 为 23 laid-out + 11 unsupported、34/34、105 checks。AUTO/FRACTION/HUG、
  resource/world scene/raster/daemon success 继续 fail closed。
- [实现 Editor E1 canonical open、显式 readiness 重检与三模式工作区骨架](issues/27-editor-e1-canonical-open.md) —
  **resolved / automated_verified**；GET 保持无副作用，新增授权且绑定 current identity 的显式 readiness
  recheck；Web lossless 校验 canonical baseline、丢弃漂移结果并形成 Canvas Focus 三模式 Product shell。
  Template/Fast/Server/Node 24 Web 与最终 full 均通过；按 T09 J1 决定本票不发布产品 route，也不显示尚未实现的
  save/preview/recovery/import 占位能力。
- [实现 Editor E2 canonical working copy、结构化撤销/重做与 preview guard](issues/28-editor-e2-canonical-local-history.md) —
  **resolved / automated_verified**；在 E1 immutable canonical baseline 上建立 Structured-only working copy，
  以首个 `set-template-display-name` 结构命令完成 canonical dirty、100 条有界 undo/redo 与 preview generation
  失效；Node 24 Web、Fast 与最终 Full 17/17 全绿，且未新增 save/preview/recovery/import/API/route 占位能力。
- [实现 Editor E3 lossless save 与 conflict overwrite 重确认](issues/29-editor-e3-save-conflict-overwrite.md) —
  **resolved / automated_verified**；以 lossless expectedRevision 保存 exact working canonical，严格核验成功 adoption；
  409 覆盖 offer 绑定远端 revision 与本地 generation，确认后先重读 current，漂移必须重新确认；outcome unknown
  只锁定并留给 E5 reconciliation。Node 24 Web/Fast/Full 17/17 全绿，无 Java/OpenAPI/migration/route 差异。
- [实现 Editor E4a 依赖问题确认与 hard-error 零写](issues/30-editor-e4a-invalid-save-confirmation.md) —
  **resolved / automated_verified**；对 T20 已物化 AssetRef/TemplateRef dependency surface 增加 bounded problem set、
  5 分钟 opaque invalid-save token、fresh revalidation 与 SERIALIZABLE transaction snapshot fence；cycle/cross-scope/
  hard/truncated 永远零写，Web 提供与 draft generation 绑定的具名 INVALID 确认。Template/Web/Fast/Server/Full
  分级门控全绿；更深 field-path/child-fill validator 仍须另票完成。
- [实现 Editor E4b StaticSchema 与 child-fill 语义依赖校验](issues/31-editor-e4b-semantic-dependency-validator.md) —
  **resolved / automated_verified**；已补齐全部 StaticSchema field-path/type/presence proof、Repeat ancestor-only
  lexical item context、TemplateUse exact child Schema 与 PUBLIC fill target/type 校验；复用 T30 bounded
  confirmation/snapshot fence，Template 78、App 344、Node 24 Web 127 与分级门控全绿，未新增
  API/migration/Web placeholder，也未推进 E5–E9 或产品 route。
- [实现 Editor E5 outcome-unknown Save reconciliation](issues/32-editor-e5-save-reconciliation.md) —
  **resolved / automated_verified**；Web-only 复用现有 GET/PUT/410，写前冻结 attempt，unknown 后以 trusted current
  完成 adopted/retryable/conflict/deleted/fail-closed 五分类、显式 exact retry、mutation lock 与 bare draft export。
  Node 24 Web 20 files/139 tests 与分级门控全绿；不实现 E6–E9、跨刷新 recovery、API/migration/route 或 READY。
- [实现 Editor E7 Local recovery 生命周期与跨刷新 Save reconciliation](issues/35-editor-e7-local-recovery.md) —
  **resolved / automated_verified**；Web-owned deep module 已实现当前设备每 Template 一份的 versioned recovery
  envelope、严格 canonical/hash/identity 与 7 天生命周期、显式恢复/导出/放弃、漂移后 overwrite 确认，以及
  E5 unknown attempt 写前持久化与跨刷新只读 reconciliation。Node 24 Web 156 tests、最终 full 17/17；Web-only，
  不开放产品 route，E6 仍因真实 Renderer output/public preview seam 缺位而阻塞。
- [实现 Editor E8 严格导入、Raw Repair/Compatibility 与 dirty replacement guard](issues/36-editor-e8-import-modes.md) —
  **resolved / automated_verified**；Web-only 从本地字节严格接受 bare DesignDSL 或 exact revision export，文件
  identity/schema 永不覆盖当前目标，并以 Structured、Raw Repair、Compatibility 三种互斥模式表达可信度。
  import/recovery 共享 dirty replacement guard；Node 24 Web 195 tests，当前无 registered migration profile，只允许
  Compatibility exact export，不提供伪 migration action。
- [实现 Editor E9 键盘流、live region、有效宽度与问题定位投影](issues/37-editor-e9-accessibility-problem-locator.md) —
  **resolved / automated_verified**；Web-only 严格投影 bounded RFC 6901 problem pointer，补齐失败摘要 focus、
  roving tree、central live delta、1024 drawer、1440/1280/1024/等效 200% 与低于 1024 unsupported 浏览器证据。
  locator 14/14、Editor 136/136、Node 24 Web 212/212、E2E 23 passed/1 live-provider skip；测试 fixture 不开放产品
  route，真实浏览器 200% zoom/人工 keyboard J1 保持 pending。
- [实现 definite Stack 单主轴 FILL 子闭包](issues/38-definite-single-main-fill-stack-layout.md) —
  **resolved / automated_verified**；同一 Rust layout deep module 已实现每个 definite Stack 至多一个 main-axis
  FILL 的 exact allocation/min-max/justify 退化算法。Rust/Python `/4` shared replay 为 28 laid-out + 11 unsupported、
  39/39、120 checks；多个 FILL、HUG、Grid AUTO/FRACTION、资源、scene/raster、daemon output 与 Profile registration
  继续 fail closed。
- [实现 definite Grid 单 AUTO 的资源无关 contribution 子闭包](issues/40-definite-single-auto-grid-layout.md) —
  **resolved / automated_verified**；每轴至多一个 AUTO，仅由 FIXED GRID child 的 signed-margin contribution
  与唯一 AUTO deficit 精确求解，并与 singleton FRACTION 按 FIXED → AUTO → FRACTION 组合。Rust/Python `/6`
  replay 为 33 laid-out + 12 unsupported、45/45、137 checks；一般 intrinsic/multiple AUTO/multiple FRACTION
  继续 fail closed。
- [实现 definite Grid 多 AUTO 的独立 constraint 子闭包](issues/41-definite-independent-multi-auto-grid-layout.md) —
  **resolved / automated_verified**；每轴允许多 AUTO，但每条资源无关 FIXED-child span constraint 至多覆盖一个
  AUTO；Rust/Python `/7` replay 为 34 laid-out + 13 unsupported、47/47、142 checks。跨多个 AUTO 的平均
  deficit、HUG/intrinsic、multiple FRACTION、resource/scene/RESULT 继续 fail closed。
- [实现 definite 空容器 HUG intrinsic 子闭包](issues/42-definite-empty-intrinsic-container-layout.md) —
  **resolved / automated_verified**；只对空 Frame/Stack/Grid/Group 实现已冻结的 stroke/padding、
  FIXED/AUTO-zero track、gap、zero-union 与 min/max 退化结果；Rust/Python `/8` replay 为 40 laid-out +
  13 unsupported、53/53、160 checks。非空 HUG、transform union、resource/tolerance/scene/RESULT 继续 fail closed。
- [实现资源无关非空 Stack HUG intrinsic 子闭包](issues/43-resource-free-stack-hug-layout.md) —
  **resolved / automated_verified**；只消费可由 FIXED、T42 空容器或递归 Stack child 独立测得的 HUG，按
  stable-zero cursor/MarginExtent、signed margins、gap、padding/stroke 与 min→max 求解；Rust/Python `/9`
  replay 为 46 laid-out + 13 unsupported、59/59、178 checks。非空 Frame/Grid/Group、transform union、resource/
  tolerance/scene/RESULT 继续 fail closed。
- [实现 definite Grid 资源无关 HUG child AUTO contribution 子闭包](issues/44-grid-resource-free-hug-auto-contribution.md) —
  **resolved / automated_verified**；T42/T43 可独立测得的空容器/递归 Stack HUG intrinsic 已接入 T40/T41
  independent AUTO constraint；Rust/Python `/10` replay 为 48 laid-out + 13 unsupported、61/61、184 checks；跨
  多个 AUTO、非空 Frame/Grid/Group、transform/resource/tolerance/scene/RESULT 继续 fail closed。
- [实现资源无关非空 Grid HUG intrinsic 子闭包](issues/45-resource-free-grid-hug-layout.md) —
  **resolved / automated_verified**；非空 Grid HUG 轴复用 FIXED/independent AUTO/resource-free HUG child
  contribution 与 authored gaps，再按 padding/inward stroke、min→max 求 intrinsic；Rust/Python `/11` replay
  为 51 laid-out + 13 unsupported、64/64、193 checks。FRACTION-on-HUG、跨多 AUTO 平均、非空 Frame/Group、
  resource/transform/scene/RESULT 保持 fail closed。
- [实现 RenderResource manifest 防御性准入与静态容量内核](issues/46-render-resource-manifest-admission.md) —
  **resolved / automated_verified**；既有 Rust document deep module 现把 sealed manifest 转为 typed resource，
  并重验 scalar/kind-media/descriptor 与 fetch-before Ticket 19 静态容量；Rust/Python `/2` replay 为 14 document
  + 42 scalar/descriptor + 19 aggregate、75/75、97 checks。HTTPS/allowlist、lease deadline、actual bytes/hash/
  magic/decode/cache、scene/raster/RESULT/Profile 均留在本票之外。
- [实现 Command-bound RenderResource lease 覆盖准入](issues/47-command-resource-lease-admission.md) —
  **resolved / automated_verified**；strict Command deadline、typed manifest expiry 与 Ticket 19 已冻结的 5000ms
  minimum-inclusive oracle 已形成 `i128` 精确准入，按 manifest order 返回首个不足 lease 的稳定
  code/resourceId；Rust/Python `/3` 为 83/83、106 checks。URL/app-origin/fetch/attempt-time expiry/bytes/decode/
  scene/raster/RESULT/Profile 均留在本票之外。
- [实现 RenderResource body 完整性内核](issues/48-resource-body-integrity-kernel.md) —
  **resolved / automated_verified**；唯一 seam 为 typed `AdmittedRenderResource` + shared request-local physical-byte
  budget + caller-supplied chunks，冻结 `536_870_912` inclusive budget 与 length→lowercase SHA-256；Rust/Python
  15/15、34 checks。无 HTTPS、daemon 接线、decode/cache/Profile；`resourceBytes` 仍为 `UNFETCHED`。
- [实现 zero-rotation affine 非空 Frame HUG intrinsic 子闭包](issues/49-zero-rotation-frame-hug-layout.md) —
  **resolved / automated_verified**；`rotationDeg == 0` 的资源无关 direct ABSOLUTE child 以 axis-local
  scale/flip/origin transformed LayoutBox 最远正端参与 Frame HUG；shared `/12` Rust/Python 为 69/69、
  209 checks。任意非零 rotation、resource、tolerance、scene/raster/RESULT/Profile 继续 fail closed。
- [实现 zero-rotation affine 非空 Group HUG union/normalization 子闭包](issues/50-zero-rotation-group-hug-layout.md) —
  **resolved / automated_verified**；复用 T49 axis interval，对资源无关 direct ABSOLUTE child 求二维 union，以
  union min 归一化派生 child layout 并保持 Group placement 为 normalized union 左上角；shared `/13` Rust/Python
  为 77/77、232 checks。非零 child rotation、resource、tolerance、scene/raster/RESULT/Profile 继续 fail closed。
- [实现 exact-quarter-turn affine 非空 Frame/Group HUG AABB 子闭包](issues/51-exact-quarter-turn-frame-group-hug-layout.md) —
  **resolved / automated_verified**；只对 `[-360,360]` 内 binary64 精确 90 度倍数派生 clockwise quadrant，以
  加减乘/min/max 扩展 T49/T50 transformed interval；shared `/14` Rust/Python 为 83/83、249 checks。奇数
  quadrant 只接受两轴 FIXED 或 independently resource-free HUG；cross-axis FILL、非直角 rotation、
  resource/tolerance/scene/raster/RESULT/Profile 继续 fail closed。
- [实现 FIXED opposite-axis offer 下的 quarter-turn Frame HUG cross-axis FILL 子闭包](issues/52-fixed-cross-axis-fill-quarter-turn-frame-hug-layout.md) —
  **resolved / automated_verified**；只让 opposite axis 为 FIXED 的 HUG Frame 以现有 ContentBox floor-zero 规则向
  odd quarter-turn direct ABSOLUTE child 提供 cross-axis FILL offer；shared `/15` Rust/Python 为 88/88、264 checks。
  Frame 自身 opposite-axis FILL、非直角 rotation、resource/tolerance/scene/raster/RESULT/Profile 继续 fail closed。
- [实现 definite ABSOLUTE parent-offer 下的 quarter-turn Frame HUG opposite-axis FILL 传播子闭包](issues/53-definite-absolute-parent-offer-quarter-turn-frame-hug-layout.md) —
  **resolved / automated_verified**；already-definite ABSOLUTE parent ContentBox 的 cross-axis offer 现可沿 HUG
  Frame 链单向传播，复用 FILL inset/min-max、ContentBox floor-zero 与 T52 affine；shared `/16` Rust/Python 为
  93/93、279 checks。Stack/Grid offer、双向 HUG、非直角 rotation、resource/tolerance/scene/raster/RESULT/Profile
  继续 fail closed。
- [实现 definite Stack cross-axis offer 下的 quarter-turn Frame HUG opposite-axis FILL 子闭包](issues/54-definite-stack-cross-offer-quarter-turn-frame-hug-layout.md) —
  **resolved / automated_verified**；definite Stack cross-axis interval 现可解析成 direct STACK Frame 的 resolved
  opposite outer size，开放 ROW main-HUG/cross-FILL 与 COLUMN 对称路径；shared `/17` Rust/Python 为
  97/97、291 checks。main-FILL→cross-HUG 回馈、nested
  Stack/Grid offer、双向 HUG、非直角 rotation、resource/tolerance/scene/raster/RESULT/Profile 继续 fail closed。
- [实现 singleton Stack main-axis FILL 后 cross-axis Frame HUG 单次重测子闭包](issues/55-stack-main-fill-cross-hug-quarter-turn-frame-layout.md) —
  **resolved / automated_verified**；只复用 T38 singleton main FILL 的最终 outer size，以 typed resolved offer 对
  direct Frame cross HUG 重测一次；shared `/18` Rust/Python 为 101/101、303 checks。multiple FILL、nested
  Stack/Grid offer、双向 HUG、非直角 rotation、resource/
  tolerance/scene/raster/RESULT/Profile 继续 fail closed。
- [实现同轴 nested Stack main offer → cross HUG 逐层单向传播子闭包](issues/56-nested-stack-main-offer-cross-hug-propagation.md) —
  **resolved / automated_verified**；只沿同 direction、每层 singleton main-FILL/cross-HUG 的 nested Stack 链传播
  已解析 outer offer，并复用同一 Stack measurement/allocation helper；shared `/19` 为 91 laid-out + 14 unsupported、
  105 cases/315 checks。Grid/multiple FILL/general constraint/tolerance/scene/raster/RESULT/Profile 保持 fail closed。
- [实现 columns-first Grid cell outer offer → Frame HUG 单向传播子闭包](issues/57-columns-first-grid-cell-offer-frame-hug-layout.md) —
  **resolved / automated_verified**；只让 direct GRID Frame 在最终 cell arrange 消费 opposite-axis resolved outer offer，
  并让 row AUTO contribution 单向读取已完成 columns 的 cell width；column AUTO 不读取 future rows。shared `/20`
  为 95 laid-out + 15 unsupported、110 cases/329 checks；nested Stack→Grid、反向 feedback、tolerance 与一般
  constraint 保持 fail closed。
- [实现 Stack main offer → columns-first Grid cross HUG 单向传播子闭包](issues/58-stack-main-offer-columns-first-grid-cross-hug-layout.md) —
  **resolved / automated_verified**；只让 ROW Stack 的 singleton main-FILL Grid 在 final outer width 分配后扣除
  自身 stroke/padding，按 columns-first 执行一次 cross-HUG 重测；shared `/21` 为 99 laid-out + 15 unsupported、
  114 cases/341 checks。ABSOLUTE parent→Grid、Grid-in-Grid owning offer、rows→columns 与一般 constraint/tolerance
  保持 fail closed。
- [实现 ABSOLUTE parent offer → columns-first Grid cross HUG 单向传播子闭包](issues/59-absolute-parent-offer-columns-first-grid-cross-hug-layout.md) —
  **resolved / automated_verified**；只让 ABSOLUTE `widthMode=FILL,heightMode=HUG_CONTENT` Grid 从
  `AbsoluteParentContent` 解析最终 outer width，扣除自身 stroke/padding 后严格 columns→rows；shared `/22`
  为 103 laid-out + 15 unsupported、118 cases/353 checks。Grid-in-Grid owning offer、rows→columns 与一般
  constraint/tolerance 保持 fail closed。
- [实现 Grid cell offer → columns-first nested Grid cross HUG 单向传播子闭包](issues/60-grid-cell-offer-columns-first-nested-grid-cross-hug-layout.md) —
  **resolved / automated_verified**；只让 row-after-columns 的 direct GRID Grid 消费 final cell
  `ResolvedOuter`，扣除自身 stroke/padding 后逐层严格 columns→rows；shared `/23` 为 107 laid-out + 15
  unsupported、122 cases/365 checks。Grid→Stack、rows→columns 与一般 constraint/tolerance 保持 fail closed。
- [实现 Grid cell offer → ROW Stack main-first cross HUG 单向传播子闭包](issues/61-grid-cell-offer-row-stack-main-first-cross-hug-layout.md) —
  **resolved / automated_verified**；只让 row-after-columns 的 direct GRID ROW Stack 消费 final cell
  `ResolvedOuter`，复用 singleton main-FILL allocation 后的一次 cross-HUG 重测；shared `/24` 为 111
  laid-out + 15 unsupported、126 cases/377 checks。方向改变、rows→columns 与一般 constraint/tolerance 保持
  fail closed。
- [实现方向切换 nested Stack cross offer → main HUG 单向传播子闭包](issues/62-direction-changing-stack-cross-offer-main-hug-layout.md) —
  **resolved / automated_verified**；只让 direct ROW→COLUMN / COLUMN→ROW nested Stack 把父层 final main outer 当作自己的
  definite cross outer，并在扣除 ContentBox 后一次性求 main HUG，终点限定 direct Frame；shared `/25`
  为 114 laid-out + 15 unsupported、129 cases/386 checks。递归第二个 Stack link、main-HUG 内 FILL、Grid、
  fixed point 与一般 constraint/tolerance 保持 fail closed。
- [实现 nested Stack resolved opposite offer 结构递归子闭包](issues/63-recursive-nested-stack-resolved-opposite-offer-propagation.md) —
  **resolved / automated_verified**；只让 direct nested Stack 消费 measurement space 中 already-resolved 的 physical-axis
  FILL outer，再按自身 direction 复用 main-HUG/cross-HUG helper 并沿 authored tree 严格下降；shared `/26`
  为 117 laid-out + 15 unsupported、132 cases/395 checks。Grid consumer、unresolved/cyclic FILL、fixed point 与
  一般 constraint/tolerance 保持 fail closed。
- [实现 nested Stack resolved opposite offer → columns-first Grid terminal 子闭包](issues/64-nested-stack-resolved-opposite-offer-columns-first-grid-terminal.md) —
  **resolved / automated_verified**；只把 COLUMN Stack measurement 中 already-resolved width offer 交给 direct
  `widthMode=FILL,heightMode=HUG_CONTENT` Grid，复用既有 ContentBox 与严格 columns→rows consumer；shared `/27`
  为 119 laid-out + 15 unsupported、134 cases/401 checks。rows→columns、fixed point 与一般 constraint/tolerance
  保持 fail closed。
- [实现 definite multi-FRACTION Grid stable last-remainder 子闭包](issues/65-definite-multi-fraction-grid-last-remainder-layout.md) —
  **resolved / automated_verified**；definite Grid axis 现按 authored order 让前 `n-1` FRACTION 求 weighted share、
  最后一项接收稳定余量，并保留 singleton 与 overflow-zero 行为；shared `/28` 为 123 laid-out + 13 unsupported、
  136 cases/409 checks。跨多 AUTO deficit、Stack water filling、Profile tolerance 与一般 constraint/numeric error
  继续 fail closed。
- [实现 definite Grid 跨多 AUTO span stable deficit 子闭包](issues/66-definite-grid-multi-auto-span-stable-deficit.md) —
  **resolved / automated_verified**；既有 AUTO constraint 已按 `(spanLength,startIndex,materializedOrder)` stable
  order 各处理一次，对 covered AUTO tracks 按 authored order 分配前 `n-1` equal share、最后一项接收余量；
  shared `/29` 为 127 laid-out + 12 unsupported、139 cases/419 checks。不做 convergence，Stack water filling、
  rows→columns 与 general constraint/tolerance 保持 fail closed。
- [实现 definite Stack 无 bound 多 FILL stable remainder 子闭包](issues/67-definite-stack-bound-free-multi-fill-stable-remainder.md) —
  **resolved / automated_verified**；owning-axis 无 min/max 的多个 main FILL 已按 positive fillWeight authored
  order 分配前 `n-1` weighted share、最后一项接收余量，并逐个执行一次 deferred cross-HUG remeasure；shared
  `/30` 为 131 laid-out + 11 unsupported、142 cases/429 checks。bound freeze/redistribution、residual tolerance 与
  一般 water filling 保持 fail closed。
- [实现 definite Stack inactive-bound 多 FILL 子闭包](issues/68-definite-stack-inactive-bound-multi-fill.md) —
  **resolved / automated_verified**；只允许第一轮 weighted shares 已满足全部 owning-axis min/max（含 equality）的 multiple main
  FILL 继续布局；任何 active bound 仍在首个 authored FILL occurrence fail closed，不做 freeze、redistribution 或
  tolerance 判定。shared `/31` 为 134 laid-out + 12 unsupported、146 cases/440 checks。
- [实现 definite Stack 两 FILL 单 bound-freeze 子闭包](issues/69-definite-stack-two-fill-single-bound-freeze.md) —
  **resolved / automated_verified**；exactly two main FILL、唯一 active bound、另一项 owning-axis 无界且
  frozen bound 不超过 remaining 时，执行一次 freeze + exact remainder；shared `/32` 为 139 laid-out +
  13 unsupported、152 cases/457 checks。
- [实现 definite Stack 两 FILL 单 min-overflow 子闭包](issues/70-definite-stack-two-fill-single-min-overflow.md) —
  **resolved / automated_verified**；只接受 exactly two、唯一 active min 严格大于 remaining、另一项
  owning-axis 无界，冻结 min 并让唯一未冻结项取正零；shared `/33` 为 144 laid-out + 12 unsupported、
  156 cases/470 checks，second freeze、N-FILL redistribution 与 tolerance 保持 fail closed。
- [实现 definite Stack 三 FILL 单 bound 一次重分配子闭包](issues/71-definite-stack-three-fill-single-bound-redistribution.md) —
  **resolved / automated_verified**；只接受 exactly three、唯一 active bound、另两项
  owning-axis 无界且 frozen bound 不超过 remaining，冻结后按原 fillWeight/authored order 做一次 stable
  last-remainder 重分配；shared `/34` 为 149 laid-out + 15 unsupported、164/164 cases/491 checks，第二次
  freeze、unfrozen bound、多个 active、min-overflow、four-or-more FILL 与一般 water filling 保持 fail closed。
- [实现 definite Stack 两 min 第二次 freeze overflow 子闭包](issues/72-definite-stack-two-min-second-freeze-overflow.md) —
  **resolved / automated_verified**；只接受 exactly two、双方 min-only、首轮唯一 active min 不超过 remaining，且
  第一次冻结后的唯一余量严格低于另一 authored min，最终两项取 min 并允许 overflow；shared `/35` 为
  153 laid-out + 16 unsupported、169/169 cases/505 checks，max/mixed、three-or-more cascade 与一般 water filling
  保持 fail closed。
- [实现 definite Stack 两 max 第二次 freeze 与 justify 余量子闭包](issues/73-definite-stack-two-max-second-freeze-justify.md) —
  **resolved / automated_verified**；只接受 exactly two、双方 max-only、首轮唯一 active max，且第一次冻结后的唯一余量
  严格高于另一 authored max，最终两项取 max，并把 `maxSum < remaining` 的正余量交给既有 justify；shared `/36`
  为 157 laid-out + 17 unsupported、174/174 cases/519 checks，mixed、three-or-more cascade 与一般 water filling
  保持 fail closed。
- [实现 definite Stack 三 FILL 重分配后 inactive bound 子闭包](issues/74-definite-stack-three-fill-post-redistribution-inactive-bounds.md) —
  **resolved / automated_verified**；只接受 exactly three、首轮唯一 active bound、另外两项可带 optional bound，
  且一次 stable 重分配后的两个 share 仍全部满足各自 min/max；shared `/37` 为 162 laid-out + 16 unsupported、
  178/178 cases/532 checks，第二次 freeze、四项及以上与一般 water filling 保持 fail closed。
- [实现 definite Stack 三 FILL 第二 min freeze 与末项余量子闭包](issues/75-definite-stack-three-fill-second-min-freeze-remainder.md) —
  **resolved / automated_verified**；只接受 exactly three、首轮唯一 active min、一次重分配后恰好一个第二 min
  active，且两项 min 总和不超过 remaining；第二次 freeze 后唯一无界项接收 exact remainder。shared `/38` 为
  167 laid-out + 16 unsupported、183/183 cases/547 checks，second-max、sum overflow、第三次 freeze 与一般 water
  filling 保持 fail closed。
- [实现 definite Stack 三 FILL 第二 max freeze 与末项余量子闭包](issues/76-definite-stack-three-fill-second-max-freeze-remainder.md) —
  **resolved / automated_verified**；只接受 exactly three、首轮唯一 active max、一次重分配后恰好一个第二 max
  active，且末项 owning-axis 无界；第二次 freeze 后唯一无界项接收 exact remainder。shared `/39` 实际为
  172 laid-out + 16 unsupported、188/188 cases/562 checks，terminal bound、mixed、第三次 freeze 与一般 water filling
  保持 fail closed。
- [实现 definite Stack 两 FILL mixed active-max 第二 max freeze 子闭包](issues/77-definite-stack-two-fill-mixed-active-max-second-max-freeze.md) —
  **resolved / automated_verified**；只接受 exactly two、首轮唯一 active-max child 同时带合法且始终 inactive
  的 min、另一项 max-only，固定两次 max freeze 后把正余量交给既有 justify。shared `/40` 实际为 177 laid-out +
  16 unsupported、193 cases/577 checks；mixed active-min、第二项 mixed、多个 active 与一般 water filling 保持
  fail closed。
- [实现 definite Stack 两 FILL mixed active-min 第二 min freeze 子闭包](issues/78-definite-stack-two-fill-mixed-active-min-second-min-freeze.md) —
  **resolved / automated_verified**；只接受 exactly two、首轮唯一 active-min child 同时带合法且始终 inactive
  的 max、另一项 min-only，固定两次 min freeze 后保留既有 overflow/START fallback。shared `/41` 实际为 182 laid-out +
  16 unsupported、198 cases/592 checks；另一项 mixed、多个 active 与一般 water filling 保持 fail closed。
- [实现 definite Stack 三 FILL 第二 max freeze 末项 inactive min 子闭包](issues/79-definite-stack-three-fill-second-max-terminal-min.md) —
  **resolved / automated_verified**；只接受 exactly three、固定两次 max freeze 后唯一末项携带 finite/nonnegative
  且始终 inactive 的 min-only bound。shared `/42` 实际为 187 laid-out + 16 unsupported、203 cases/607 checks；
  terminal max/mixed、多个 active、四项及以上与一般 water filling 保持 fail closed。
- [实现 definite Stack 三 FILL 第二 max freeze 末项 inactive max 子闭包](issues/80-definite-stack-three-fill-second-max-terminal-max.md) —
  **resolved / automated_verified**；只接受 exactly three、固定两次 max freeze 后唯一末项携带 finite/nonnegative、
  在重分配 share 与 final exact remainder 下均 inactive 的 max-only bound。shared `/43` 实际为 192 laid-out + 16
  unsupported、208 cases/622 checks；terminal mixed/active max、第三次 freeze 与一般 water filling 保持 fail closed。
- [实现 definite Stack 三 FILL 第三 max freeze 与 free justify 子闭包](issues/81-definite-stack-three-fill-third-max-free-justify.md) —
  **resolved / automated_verified**；只接受 exactly three、固定两次 max freeze 后唯一末项 max 在重分配 share
  下 inactive、在 final exact remainder 下严格 active，并以第三次最终 max freeze 把剩余正空间交给既有 justify。
  shared `/44` 实际为 197 laid-out + 16 unsupported、213 cases/637 checks；terminal mixed、四项及一般循环保持
  fail closed。
- [实现 definite Stack 三 FILL 单 min-overflow 子闭包](issues/82-definite-stack-three-fill-single-min-overflow.md) —
  **resolved / automated_verified**；只接受 exactly three、首轮唯一 active finite/nonnegative min-only 严格大于
  remaining、另两项 owning-axis 完全无 bound，并以 `min, 0, 0` authored-position 退化终止。shared `/45` 实际为 202
  laid-out + 16 unsupported、218 cases/652 checks；mixed/unfrozen bound、多个 active 与一般循环保持 fail closed。
- [实现 definite Stack 三 FILL 第二 min freeze overflow 子闭包](issues/83-definite-stack-three-fill-second-min-freeze-overflow.md) —
  **resolved / automated_verified**；只接受 exactly three、固定两次 min-only freeze 后 second min 严格大于第一次
  重分配余量，并以 first min、second min、terminal positive-zero 退化终止。shared `/46` 实际为 207 laid-out +
  16 unsupported、223 cases/667 checks；mixed/terminal bound、第三次 freeze 与一般循环保持 fail closed。
- [实现 definite Stack 三 FILL mixed active-min overflow 子闭包](issues/84-definite-stack-three-fill-mixed-active-min-overflow.md) —
  **resolved / automated_verified**；只组合 T82 的 exactly-three `min/0/0` overflow 退化与 T78 的合法 mixed
  active-min bound shape，shared `/47` 实际为 212 laid-out + 16 unsupported、228 cases/682 checks；unfrozen bound、多个
  active、post-freeze redistribution、four-or-more 与一般 water filling 保持 fail closed。
- [实现 definite Stack 三 FILL mixed min-overflow inactive max 子闭包](issues/85-definite-stack-three-fill-mixed-min-overflow-inactive-max.md) —
  **resolved / automated_verified**；只允许 T84 `min/0/0` 路径的零或一个 unfrozen child 携带初始与终止正零下
  都 inactive 的 finite/nonnegative max-only bound。shared `/48` 实际为 217 laid-out + 16 unsupported、233/233
  cases/697 checks；unfrozen min、两个 bounded children、post-overflow redistribution 与一般 water filling 保持
  fail closed。
- [实现 definite Stack 三 FILL mixed active-min overflow 第二 min freeze 子闭包](issues/86-definite-stack-three-fill-mixed-active-min-overflow-second-min-freeze.md) —
  **resolved / automated_verified**；只允许 T85 overflow 路径的一个 unfrozen child 携带初始 inactive、终止正零下 active 的
  finite positive min-only bound，并按 authored position 提交 `firstMin/secondMin/0`；shared `/49` 实际为 222
  laid-out + 16 unsupported、238/238 cases/712 checks；second mixed、多个额外 bound、第三次 freeze 与一般 water
  filling 保持 fail closed。
- [实现 definite Stack 三 FILL mixed active-min overflow 第二 mixed-min freeze 子闭包](issues/87-definite-stack-three-fill-mixed-active-min-overflow-second-mixed-min-freeze.md) —
  **resolved / automated_verified**；只允许 T86 second-min child 再携带在初始 share 与最终 second min 下均 inactive
  的合法 finite/nonnegative max，并保持第三项完全无 bound；shared `/50` 实际为 227 laid-out + 16 unsupported、
  243/243 cases/727 checks。额外 bounded child、初始 multiple-active、第三次 freeze 与一般 mixed cascade 保持
  fail closed。
- [实现 definite Stack 三 FILL mixed active-min overflow 第二 mixed-min freeze terminal inactive max 子闭包](issues/88-definite-stack-three-fill-mixed-active-min-overflow-second-mixed-min-freeze-terminal-inactive-max.md) —
  **resolved / automated_verified**；只组合 T87 固定 second mixed-min freeze 与 T85 terminal inactive max-only，
  仍按 authored position 提交 `firstMin/secondMin/0`；shared `/51` 实际为 232 laid-out + 16 unsupported、
  248/248 cases/742 checks。second min-only + terminal max、terminal min/mixed、第三次 freeze 与一般 mixed
  cascade 保持 fail closed。
- [实现 definite Stack 三 FILL mixed active-min overflow 两个 mixed-min freeze 子闭包](issues/89-definite-stack-three-fill-mixed-active-min-overflow-two-mixed-min-freezes.md) —
  **resolved / automated_verified**；只允许 T88 early branch 的另外两项都为初始 inactive 的合法 mixed min/max，
  并按 authored position 固定提交三个 minima；shared `/52` 实际为 237 laid-out + 16 unsupported、
  253/253 cases/757 checks。
  mixed/min-only 组合、post-overflow redistribution、four-or-more FILL 与一般 water filling 保持 fail closed。
- [实现 definite Stack 三 FILL mixed active-min overflow mixed + min-only freezes 子闭包](issues/90-definite-stack-three-fill-mixed-active-min-overflow-mixed-and-min-only-freezes.md) —
  **resolved / automated_verified**；只把 T89 replacement negative 的一个 additional mixed + 一个
  additional min-only 组合转为固定 authored minima，既有 two-mixed 继续有效；shared `/53` 实际为
  242 laid-out + 16 unsupported、258/258 cases/772 checks。两个 additional min-only、post-overflow
  redistribution、four-or-more FILL 与一般 water filling 保持 fail closed。
- [实现 definite Stack 三 FILL mixed active-min overflow 两个 min-only freezes 子闭包](issues/91-definite-stack-three-fill-mixed-active-min-overflow-two-min-only-freezes.md) —
  **resolved / automated_verified**；只把 T90 replacement negative 的两个 additional min-only 组合转为固定
  authored minima，active child 仍必须为合法 mixed min/max；shared `/54` 实际为 247 laid-out + 16 unsupported、
  263/263 cases/787 checks。active min-only + two min-only、post-overflow redistribution、four-or-more FILL 与
  一般 water filling 保持 fail closed；正式 Template 产品 route 仍未开放。
- [实现空 Canvas 的真实 Engine PNG 输出内核](issues/92-empty-canvas-engine-png-kernel.md) —
  **resolved / automated_verified**；首个真实 Engine 纵切只让已准入、无 resource、无 child、背景 alpha 为
  0/255 的 Canvas 经过同一 layout、RGBA8 surface 与 exact PNG encoder 产生 bytes。shared `/1` 为 5 rendered +
  4 unsupported、9/9 cases、31 checks，vector SHA-256
  `4db688dd2136d1d83fba18ba727b6eaef909dd54902498181107e76f31d9c3c7`；Profile 仍 NOT_REGISTERED、raster
  inventory 仍 ABSENT、daemon 仍 UNWIRED、正式产品 route 仍 CLOSED。
- [实现像素对齐不透明 Rect 的真实 Engine PNG 输出内核](issues/93-pixel-aligned-opaque-rect-engine-png-kernel.md) —
  **resolved / automated_verified**；沿用 T92 唯一 `render_png` Interface，为一个 direct ABSOLUTE/FIXED、
  identity、零圆角、无 stroke、opaque fill 且 device edges 本来就精确为整数的 Rect 建立首个非空
  scene/paint/raster 子闭包。shared `/1` 为 7 rendered + 8 unsupported、15/15 cases、49 checks，vector SHA-256
  `578b2446b557059cddc49a57634c0aa65d5a3a5ba565b7963be3c854b67597ee`；不做 pixel snap，不选择 AA tie，
  Profile 仍 NOT_REGISTERED、raster inventory 仍 ABSENT、daemon 仍 UNWIRED、正式产品 route 仍 CLOSED。
- [实现 authored-order 多不透明 Rect 的真实 Engine PNG 输出内核](issues/94-authored-order-multi-opaque-rect-engine-png-kernel.md) —
  **resolved / automated_verified**；保持唯一 `render_png` Interface，把 direct pixel-aligned opaque Rect 扩为
  零个或多个，先 prepare 全部 child 再按 authored order hard-clipped paint，后 child 覆盖重叠 pixel。shared `/1`
  为 8 rendered + 8 unsupported、16/16 cases、52 checks，vector SHA-256
  `a34a2dd5eb9874691cf2e90f2f75f49dc7d0650d6d7fb306a62ec213c51e2d45`；`render`/`fast`/`server`/17-step
  `full` 与 resolution `fast` 全绿。Profile NOT_REGISTERED、daemon UNWIRED、E6/正式产品 route CLOSED，未把
  `/prototype` 当作交付。
- [实现 fixed identity Frame/Rect 先序真实 Engine PNG 内核](issues/95-fixed-identity-frame-rect-engine-png-kernel.md) —
  **resolved / automated_verified**；保持同一 `render_png` Interface，把 closed scene 扩为 fixed identity
  Frame/Rect preorder：Frame optional opaque fill 先于 descendants，padding 进入 child world origin，later sibling
  继续覆写；全部 paint 在最终 surface 分配前准备。Rust 2/2、Python 19/19 cases/61 checks，vector SHA-256
  `acb3adf55f8b67914918f20197e5bdb4668985759b51a2a02fe0810cb0eba363`；`render`/affected `fast`/顺序
  `server`/17-step `full`/resolution `fast` 均绿。Profile NOT_REGISTERED、daemon UNWIRED、E6/正式产品 route
  CLOSED，未把 `/prototype` 当作交付。
- [实现 pixel-aligned 矩形 Frame descendant clip 的真实 Engine PNG 内核](issues/96-pixel-aligned-rectangular-frame-clip-engine-png-kernel.md) —
  **resolved / automated_verified**；沿同一 `render_png` Interface，把 zero-stroke/zero-radius Frame 的
  `clipContent=true` 限定为 native pixel-aligned inner-border rectangle；padding 不是 clip boundary，nested clip
  与 ancestor/surface 求交，全部 clip/paint 仍在 surface 分配前 prepare。Rust 2/2、Python 20/20 cases/64 checks，
  vector SHA-256 `dc55cdad90e314ff642b94c79566f42a35bb09d8464e82b964134ed49ce7fe28`；`render`/affected
  `fast`/顺序 `server`/17-step `full`/resolution `fast` 均绿。Profile NOT_REGISTERED、daemon UNWIRED、E6/正式产品 route
  CLOSED，未把 `/prototype` 当作交付。
- [验证 Product Editor 状态、恢复与权威预览架构](issues/09-validate-product-editor-architecture.md) —
  throwaway 逻辑原型（`/prototype/editor-state-model`，不进产品 route）把冻结编辑器规则编码为确定性
  fixture 状态机：10 个引导走查场景 37/37 断言 + 自由操作冒烟 + 键盘焦点检查全部通过（Playwright A1，
  evidence `.sdlc/evidence/t09-prototype-observation/`），人工 J1 验收。结论：复用 T17 Canvas Focus
  IA/视觉决定（画布居中 + 五入口左导航 + 右检视器 + dock + 顶栏身份/readiness/revision + 非权威画布 +
  独立权威预览）；丢弃无 baseline/无 generation guard/无 reconciliation/无模式边界/场景切换器当导航的
  内存状态模型；验证通过 canonical baseline + mutation 单飞 + conflict/reconciliation + current-only
  preview（含 save-and-preview 顺序非原子）+ Local recovery + dirty guard + 三模式架构；E1–E9 占位-free
  实施纵切登记为后续 Editor 实施票的前置分解。无 Editor 产品代码，Editor 未 READY，Ticket 19 open。

## Not yet specified

- minimal canonical kernel 已完成；T14 容器增量已 resolve（NodeContractCatalog 与 Node/Property
  Identity 原子：canvas/group/frame/stack/grid 递归 admission/canonical，manifest
  `renderweave-template-canonical-kernel-v1/2` 57 vectors，Java primary/Python independent 57/57，
  Profile 仍 NOT_REGISTERED）；T14b visual leaf kinds 与 BindingPolicyCatalog 基础登记已 resolve
  （text/image/rect/ellipse/line/polygon/polyline/path/qrCode/barcode 全 property 树 + 只追加
  (nodeKind, propertyPathPattern) 授权集，manifest `renderweave-template-canonical-kernel-v1/5`
  152 vectors，Java/Python 152/152，bindability 消费属 T16）；
  T15 Definition/ValueSource 原子已 resolve（custom/mapping/expression + ValueSource closed union +
  词法 domain + graph cycle/dangling + canonical set sorting，manifest
  `renderweave-template-canonical-kernel-v1/3` 94 vectors）；T17 Repeat 原子已
  resolve（nodeId+loopId 双身份、items 结构 ValueSource 静态类型证明、PACK placement、STACK/GRID
  RepeatPackingSpec、loopId 唯一与 loop-domain/loopIndex 解锁，manifest
  `renderweave-template-canonical-kernel-v1/4` 116 vectors）；
  DesignDSL full-Profile 拆分登记为
  T20（Template 依赖投影，T12b 的 blocker）；T16 Binding 与
  BindingPolicyCatalog 消费已 resolve（bindingId 唯一、targetPropertyRef 解析/存在性/policy、
  source kinds context/loopIndex/definition、canonical sort，manifest
  `renderweave-template-canonical-kernel-v1/6` 176 vectors）；T19 TemplateUse 原子已 resolve
  （useId/templateRef current-only/contextSelector closed union/fills sorted，manifest
  `renderweave-template-canonical-kernel-v1/7` 197 vectors，T20 解锁）；T18 Conditional 原子已
  resolve（condition boolean 静态证明/absentPolicy FALSE|ERROR/非空 ABSOLUTE children，manifest
  `renderweave-template-canonical-kernel-v1/8` 211 vectors——DesignDSL v1 全部 kind 已 admission，
  Java/Python 211/211）；T20 Template 依赖投影已 resolve（AssetRef/TemplateUse 原子提取 + current-only
  投影物化（V021 整体替换）+ `AssetReferenceAuthority` 反向 proof + asset_audit_event 可重放 STALE
  消费 + `TemplateReadinessAuthority.recheck` READY/INVALID 重算持久化，Java primary/Python
  independent 提取 A2 与 `template` gate 全绿）；T12b 被引用 Asset 删除确认与恢复已 resolve
  （`AssetReferencePort` 桥接 + 5 分钟单次 token + 独占 reservation 重验零写 + 软删除/恢复 + assetId
  排序 FOR SHARE 读 reservation，V022 + OpenAPI 0.13.0 + Web SDK，app 全量 302 tests 绿）；
  admission/canonical 增量逐票带 exact vectors + template gate 扩展，全部 exact 语义原子通过前不登记
  Profile available，也不把本 kernel 的 fail-closed non-empty array 当作 set ordering 已实现。
- Asset persistence、replace/delete/restore、依赖影响确认、Asset UI 与 Renderer-only lease 的实施顺序已由
  Ticket 05 冻结为 T10 → T11 → T12a → T12b → T13；T10/T10b/T11/T12a/T12b 已完成。Template
  依赖投影（从 DesignDSL 提取 authored AssetRef atom 的 current-only 投影、`AssetReferenceAuthority` 物化与
  STALE 消费）已登记为 T20 并已 resolve，T12b 以其为 blocker 已 resolve；首个 Rendering 实现票已
  登记为 T21 并已 resolve（automated_verified）；T13 以 T21 为前置，现也已 resolve
  （automated_verified）：AssetResolver、加密 selection recovery record、Renderer-only signed fetch lease、
  app internal exact-byte endpoint 与 Rendering production bridge 已形成纵切。T22 也已 resolve
  （automated_verified）：首个 Rust daemon/process Adapter/manifest/replay 与 `render` gate 已形成纵切。
  T23 也已 resolve（automated_verified）：exact RenderDocument/default/lowering 与跨语言 validator 已形成纵切。
  T24 也已 resolve（automated_verified）：surface preflight 与 exact PNG encoder kernel 已形成独立纵切，但未接线
  daemon success path、未生成 raster，也未注册 Renderer/Output Profile。T25 现也已 resolve
  （automated_verified）：static layout preflight deep kernel 与 daemon defensive admission 已形成纵切。T26 现也已
  resolve（automated_verified）：资源无关 definite ABSOLUTE local boxes 已形成真实 arrange 子闭包。T33 现也已
  resolve（automated_verified）：definite Stack 中不依赖 water-fill tolerance 的子闭包已完成；T34 现也已
  resolve（automated_verified）：FIXED-track Grid 与 ContentBox floor-zero 子闭包已完成；T38 现也已 resolve
  （automated_verified）：singleton Stack main-axis FILL 的无 tolerance 退化子闭包已完成；T39 现也已 resolve
  （automated_verified）：definite Grid 每轴至多一个 FRACTION 的无 tolerance 退化子闭包已完成；T40 现也已
  resolve（automated_verified）：singleton AUTO 的资源无关 FIXED-child contribution 子闭包已完成；T41 现也已
  resolve（automated_verified）：多 AUTO 中每条 span 至多覆盖一个 AUTO 的独立 constraint 子闭包已完成；T42
  现也已 resolve（automated_verified）：空 Frame/Stack/Grid/Group 的 HUG intrinsic 退化子闭包已完成；T43
  现也已 resolve（automated_verified）：非空 Stack 的资源无关 HUG intrinsic 子闭包已完成；T44 现也已
  resolve（automated_verified）：该 intrinsic 已接入 definite Grid independent AUTO contribution；T45 现也已
  resolve（automated_verified）：非空 Grid 的资源无关 intrinsic 已完成；T46 现也已 resolve
  （automated_verified）：sealed RenderResource manifest 的 typed defensive admission 与 fetch-before 静态容量已完成；
  T47 现也已 resolve（automated_verified）：Command deadline + 5000ms 对每项 typed lease 的覆盖准入已完成；
  T48 现也已 resolve（automated_verified）：request-local physical fetch byte budget 与 caller-supplied body
  length/SHA-256 integrity 已完成，模块保持 UNWIRED/resource bytes UNFETCHED；其余一般
  HUG/multiple Stack main-axis FILL/
  跨多个 AUTO 的平均 deficit/multiple FRACTION、actual resource fetch/decode、world scene 与 daemon output 仍未提前实现。
  T49 现也已 resolve（automated_verified）：不需要三角函数 tolerance 的 zero-rotation affine 非空 Frame HUG
  子闭包已完成；Rust/Python 69/69、209 checks。
- Expression/value binding、closure、capability、nested Template、layout lowering 与正式 RenderDocument 的
  实现切片由 T21 物化首个 Rendering 纵切（`TemplateClosureAuthority`/`Evaluator` stage 1–8/seal 与
  RenderNodeContract/向量语料 Java primary，已 resolve）；仍待后续票：Engine 执行（Rust daemon +
  process adapter + `render` gate 入 full，T22 已 resolve）、AssetResolver/lease/fetch endpoint 生产
  bridge（T13，已 resolve）、Rust daemon/process Adapter/`render` gate（T22，已 resolve）、exact
  RenderDocument/default/跨语言 validator（T23，已 resolve）、公开
  render/preview 产品面（Engine 产品面票）、capability callPosition 完整
  OccurrencePath 硬化、Editor E1–E9 实施票。节点 default 展开已由 T23 catalog 数据深化票完成。
- Rust layout、font shaping、resource decode、raster、JPEG encoding 与 exact pixel replay 的切片，
  要等 process protocol 和 build/certification contract 冻结后再登记——ADR-0045 已给出该合同；首个
  Rust/process task T22 已 resolve，已物化 daemon、帧编解码、machine manifest 与仓库内 replay
  harness；`render` gate 已随 T22 纳入 `full`。T23 已补齐 Engine 不得猜测的 exact document/default 前置；
  T24 已单独登记 exact surface/PNG kernel，但不等于 layout/raster/daemon output；T25 已完成
  statically-decidable layout preflight，T26 也已完成 definite ABSOLUTE local box 子闭包；T33 已完成
  definite Stack 的非 water-fill 子闭包；T34 已完成 FIXED-track definite Grid 与 ContentBox floor-zero；T38 已完成
  singleton Stack main-axis FILL；T39 已完成 definite Grid 每轴 singleton FRACTION；T40 也已完成
  singleton AUTO 的资源无关 FIXED-child contribution；T41 已完成 independent multi-AUTO constraints；T43 已
  完成 Stack 的资源无关非空 HUG；T44 已完成 definite Grid 的资源无关 HUG child AUTO contribution；T45 已
  完成 owning Grid 的资源无关非空 HUG intrinsic；T46 已完成 RenderResource manifest defensive admission；
  T47 已完成 Command/resource lease coverage admission；T48 已完成 resource body integrity kernel；T49 已完成
  zero-rotation affine 非空 Frame HUG；T50 已完成 zero-rotation affine 非空 Group HUG union/normalization；T51
  已完成 exact-quarter-turn affine Frame/Group HUG AABB（Rust/Python 83/83、249 checks）；T52 已完成 FIXED
  opposite-axis Frame 的 odd-quarter-turn cross-axis FILL（Rust/Python 88/88、264 checks）；T53 已完成
  already-definite ABSOLUTE parent offer 沿 HUG Frame opposite-axis FILL 的单向传播（Rust/Python 93/93、279
  checks）；T54 已完成 definite Stack cross-axis offer → direct Frame main-HUG/opposite-FILL（Rust/Python
  97/97、291 checks）；T55 已完成 singleton Stack main-FILL 分配后对 direct Frame cross-HUG 的单次重测
  （Rust/Python 101/101、303 checks）；T56 已完成同轴 nested Stack 链逐层消费已解析 main outer offer，每层仍只
  执行一次 main allocation 与一次 cross-HUG remeasure（Rust/Python 105/105、315 checks）；T57 已完成 direct
  GRID Frame cell opposite-axis resolved outer offer 与 columns-first row AUTO 单向 contribution（Rust/Python
  110/110、329 checks）；T58 已完成 ROW Stack singleton main-FILL final outer width → columns-first Grid
  cross-HUG 单次重测，并可经同轴 nested Stack 链组合（Rust/Python 114/114、341 checks）；T59 已完成 ABSOLUTE
  parent ContentBox width → columns-first Grid cross-HUG 单向传播（Rust/Python 118/118、353 checks）；T60 已完成
  row-after-columns Grid cell final outer width → nested Grid ContentBox → columns-first rows 的有限递归组合
  （Rust/Python 122/122、365 checks）；T61 已完成 Grid cell→ROW Stack main-first cross HUG（Rust/Python
  126/126、377 checks）；T62 已完成 direct direction-changing nested Stack definite-cross→main-HUG 的有限组合
  （Rust/Python 129/129、386 checks）；T63 已完成 nested Stack resolved opposite offer 的结构递归
  （Rust/Python 132/132、395 checks）；T64 已完成既有 resolved width offer → columns-first Grid terminal 闭包
  （Rust/Python 134/134、401 checks），严格不开放 rows→columns；T65 已完成 definite multi-FRACTION authored-order
  stable last-remainder 与 overflow-zero 子闭包（Rust/Python 136/136、409 checks）；T66 已完成跨多个 AUTO span
  的 stable average-deficit 单调子闭包（Rust/Python 139/139、419 checks）；T67 已完成 definite Stack 无
  owning-axis bound 的 multi-FILL stable proportional/last-remainder 子闭包（Rust/Python 142/142、429 checks）；
  T68 已完成第一轮 share 不命中 owning-axis bound 的严格退化路径（Rust/Python 146/146、440 checks）；T69 已完成
  两项 FILL、唯一 active bound、另一项 owning-axis 无界且冻结值不超过 remaining 的单次 freeze + exact
  remainder 子闭包（Rust/Python 152/152、457 checks）；T70 已完成 exactly-two、唯一 active min 严格大于
  remaining、另一项无界的 min-overflow → positive-zero 退化路径（Rust/Python 156/156、470 checks）；T71 已完成
  exactly-three、唯一 active bound、另两项无界且 frozen bound 不超过 remaining 的一次稳定重分配
  （Rust/Python 164/164、491 checks）；T72 已完成 exactly-two、两项 min-only、第二次 freeze 后 min-sum
  overflow 的固定两轮退化路径（Rust/Python 169/169、505 checks）；T73 已完成 exactly-two、两项 max-only、
  第二次 freeze 后保留正 free space 给 justify 的固定两轮退化路径（Rust/Python 174/174、519 checks）；
  T74 已完成 exactly-three、首轮唯一 active bound、另外两项 bound 在一次重分配后仍 inactive 的复核终止路径
  （Rust/Python 178/178、532 checks）；其余非直角 rotation、mixed second freeze、three-FILL 的 second
  freeze/overflow、four-or-more active-bound FILL、
  一般 active-bound Stack water filling、
  general constraint、actual resource fetch+decode/world scene/raster/
  JPEG/Engine 接线仍须另行登记，物理 Linux 双 CPU-family
  认证与 J1/A3 属届时另行授权的执行级门控。
- Editor T27/E1、T28/E2、T29/E3、T30/E4a、T31/E4b、T32/E5、T35/E7、T36/E8 与 T37/E9 均已单独登记并完成；
  E6 仍被真实 Renderer output/public preview seam 阻塞。T38 也已完成不依赖未冻结 residual tolerance 的 definite
  Stack singleton main-axis FILL 子闭包；T39 definite Grid 每轴 singleton FRACTION 子闭包也已完成；T40
  singleton AUTO 固定贡献子闭包现也已完成，T41 independent multi-AUTO constraint 子闭包也已完成；T44 也已
  完成 AUTO 对 T42/T43 resource-free HUG child 的消费；
  `/templates/:templateId` 在 save/preview/recovery 等闭环完成前仍不接入产品路由。
- Product Editor 的 save/recovery/conflict/preview/browser automation 与 accessibility 实施票，要等状态架构
  prototype 结论后再登记——T09 已给出结论与 E1–E9 占位-free 纵切分解（open/baseline、本地编辑+undo+
  canonical dirty+preview guard、save+conflict overwrite、依赖二次确认、reconciliation、save-and-preview+
  失败撤下、Local recovery、import+三模式+dirty guard、a11y+问题定位投影）；各切片在自身前置满足后按
  single-writer 登记，Editor 产品 route 在 E1–E9 真实存在前不开放。Asset picker/catalog UI 随 Editor
  实施票同批。
- 正式 Case/Oracle、execution-class product target、Profile 发行、Editor J1、Renderer 双物理 Linux CPU-family 认证与最终生命周期升级，必须以届时真实产品 artifact 和 executor 为输入另行切票；Ticket 19 保持 open。

## Out of scope

- 改写或重解释现有 Schema/Inference v1 已提交历史，或在 dirty main 上直接实施 Template。
- Workspace、组织/成员/分享/协作、跨 ownerScope 复制、通用 connector、任意 SQL/HTTP/文件/脚本、插件节点或运行时代码注册。
- HTML/PDF/视频/3D、batch/variant 编排、render farm、生产部署、真实数据和付费 AI/provider 调用。
- 用 H2/SQLite 替代 PostgreSQL 语义，或用静态 fixture、synthetic raster、Windows/WSL 单机结果冒充物理 Linux Renderer 认证。
- 在 execution-class product target、独立 replay、人工/外部门控未完成时宣称 Template、Editor 或 Renderer READY，或关闭 Ticket 19。
