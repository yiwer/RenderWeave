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
  现也已 resolve（automated_verified）：空 Frame/Stack/Grid/Group 的 HUG intrinsic 退化子闭包已完成；一般 HUG/
  multiple Stack main-axis FILL/
  跨多个 AUTO 的平均 deficit/multiple FRACTION、资源、world scene 与 daemon output 仍未提前实现。
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
  singleton AUTO 的资源无关 FIXED-child contribution；T41 已完成 independent multi-AUTO constraints；其余
  HUG/multiple Stack main-axis FILL/跨多个 AUTO 的平均 deficit/multiple FRACTION/resource/world scene/raster/
  JPEG/Engine 接线仍须另行登记，物理 Linux 双 CPU-family
  认证与 J1/A3 属届时另行授权的执行级门控。
- Editor T27/E1、T28/E2、T29/E3、T30/E4a、T31/E4b、T32/E5、T35/E7、T36/E8 与 T37/E9 均已单独登记并完成；
  E6 仍被真实 Renderer output/public preview seam 阻塞。T38 也已完成不依赖未冻结 residual tolerance 的 definite
  Stack singleton main-axis FILL 子闭包；T39 definite Grid 每轴 singleton FRACTION 子闭包也已完成；T40
  singleton AUTO 固定贡献子闭包现也已完成，T41 independent multi-AUTO constraint 子闭包也已完成；
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
