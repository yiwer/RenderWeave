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

- minimal canonical kernel 已完成；依据其真实接口和差异拆分完整 DesignDSL Profile 的 Node、Definition、Binding、Repeat、Conditional、TemplateUse 与 Property Identity 实施票。在全部 exact 语义原子通过前不登记 Profile available，且不得把本 kernel 的 fail-closed non-empty array 当作 set ordering 已实现。
- Asset persistence、replace/delete/restore、依赖影响确认、Asset UI 与 Renderer-only lease 的实施顺序已由
  Ticket 05 冻结为 T10 → T11 → T12a → T12b → T13；T10/T10b/T11/T12a 已完成，下一 frontier 为 T12b。Template
  依赖投影（从 DesignDSL 提取 authored AssetRef atom 的 current-only 投影、`AssetReferenceAuthority` 物化与
  STALE 消费）随 DesignDSL full-Profile 拆分时登记，T12b 以其为 blocker。
- Expression/value binding、closure、capability、nested Template、layout lowering 与正式 RenderDocument 的
  实现切片，要等 Evaluator seam 给出稳定 ownership 和错误面后再登记——ADR-0044 已给出该 seam：首个
  Rendering task 票将同时物化 `TemplateClosureAuthority`/`Evaluator`/seal 纵切与 RenderNodeContract/
  向量语料（Java primary；Rust independent 与 `render` gate 随 T08）；AssetRef-atom 预准入提取随
  DesignDSL full-Profile 拆分实现。
- Rust layout、font shaping、resource decode、raster、PNG/JPEG encoding 与 exact pixel replay 的切片，
  要等 process protocol 和 build/certification contract 冻结后再登记——ADR-0045 已给出该合同：首个
  Rust/process task 票将同时物化 daemon、帧编解码、certified manifest 与仓库内 replay harness；
  `render` gate 随实现票纳入 `full`，物理 Linux 双 CPU-family 认证与 J1/A3 属届时另行授权的执行级门控。
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
