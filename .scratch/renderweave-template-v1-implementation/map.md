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
- [实现 Template create/read/save PostgreSQL 纵切](issues/06-implement-template-crud-slice.md) — 首个真实
  Template 纵切：`TemplateApplication.create/getCurrent/save` closed outcomes、ADR-0041 exact `TemplateModule`
  assembly seam、V018 aggregate/revision、ownerScope 只取 Host authority、expectedRevision 409；
  Testcontainers PostgreSQL + OpenAPI 0.10.0 + Web SDK + `full` 15/15；无 Editor/Asset/Evaluator/Renderer，
  Profile 未注册，Ticket 19 open。

## Not yet specified

- minimal canonical kernel 已完成；依据其真实接口和差异拆分完整 DesignDSL Profile 的 Node、Definition、Binding、Repeat、Conditional、TemplateUse 与 Property Identity 实施票。在全部 exact 语义原子通过前不登记 Profile available，且不得把本 kernel 的 fail-closed non-empty array 当作 set ordering 已实现。
- Asset persistence、replace/delete/restore、依赖影响确认、Asset UI 与 Renderer-only lease 的实施顺序已由
  Ticket 05 冻结为 T10 → T11 → T12a → T12b → T13；Template 依赖投影（从 DesignDSL 提取 authored AssetRef
  atom 的 current-only 投影、`AssetReferenceAuthority` 物化与 STALE 消费）随 DesignDSL full-Profile 拆分时
  登记，T12b 以其为 blocker。
- Expression/value binding、closure、capability、nested Template、layout lowering 与正式 RenderDocument 的实现切片，要等 Evaluator seam 给出稳定 ownership 和错误面后再登记。
- Rust layout、font shaping、resource decode、raster、PNG/JPEG encoding 与 exact pixel replay 的切片，要等 process protocol 和 build/certification contract 冻结后再登记。
- Product Editor 的 save/recovery/conflict/preview/browser automation 与 accessibility 实施票，要等状态架构
  prototype 结论后再登记；Asset picker/catalog UI 随 Editor 实施票同批。
- 正式 Case/Oracle、execution-class product target、Profile 发行、Editor J1、Renderer 双物理 Linux CPU-family 认证与最终生命周期升级，必须以届时真实产品 artifact 和 executor 为输入另行切票；Ticket 19 保持 open。

## Out of scope

- 改写或重解释现有 Schema/Inference v1 已提交历史，或在 dirty main 上直接实施 Template。
- Workspace、组织/成员/分享/协作、跨 ownerScope 复制、通用 connector、任意 SQL/HTTP/文件/脚本、插件节点或运行时代码注册。
- HTML/PDF/视频/3D、batch/variant 编排、render farm、生产部署、真实数据和付费 AI/provider 调用。
- 用 H2/SQLite 替代 PostgreSQL 语义，或用静态 fixture、synthetic raster、Windows/WSL 单机结果冒充物理 Linux Renderer 认证。
- 在 execution-class product target、独立 replay、人工/外部门控未完成时宣称 Template、Editor 或 Renderer READY，或关闭 Ticket 19。
