# RenderWeave 领域地图

单一领域模型的入口。术语表按限界上下文分片存放在 `docs/context/`：**按任务只加载路由命中的分片**，
不要全量读取。消费规则（词汇纪律、ADR 冲突处理）见 `docs/agents/domain.md`。

## 一句话

RenderWeave 已交付的 Schema/Inference v1 让技术型设计者定义可变的 Schema Draft，把精确 revision 发布为
不可变 StaticSchema，并通过确定性验证或带证据的 AI 推断获得可审核的数据结构。2026-08-17 起，Template v1
作为 approved additive effort 在 `main` 通过 skills-first ticket 持续实施；它不反向改写既有 Schema/Inference
语义，当前也不代表 Template、Editor 或 Renderer READY。

## 分片路由

| 分片 | 加载条件 |
|---|---|
| [`docs/context/schema-inference.md`](docs/context/schema-inference.md) | 触碰 Schema/Draft/StaticSchema、DSL、RootDocument 验证、InferenceRun/Candidate/Evidence，或图片识别获取（DocumentObservationIR、bounded inspection）。 |
| [`docs/context/live-admission.md`](docs/context/live-admission.md) | 触碰 Inference Profile、Profile Certification、live run 授权/预算账本、外传确认、Provider 出口、payload 生命周期或 readiness 投影。属停泊中的 IMAGE_ONLY admission 工作。 |
| [`docs/context/template-editor.md`](docs/context/template-editor.md) | 触碰 Template 聚合、revision/save/copy/delete、DesignDSL、Node/Binding/Expression/definition/placement 或 Editor 工作流。最大常载分片（Template v1 是当前主战线）。 |
| [`docs/context/asset.md`](docs/context/asset.md) | 触碰 Asset 聚合、图片/字体接纳、Blob、AssetRef/lease 或删除证明。常与 template-editor 同读。 |
| [`docs/context/rendering.md`](docs/context/rendering.md) | 触碰 RenderInput 准入、词法 frame、Evaluation/Capability、RenderDocument、Renderer Command、布局几何或 Authoritative Preview。 |
| [`docs/context/conformance.md`](docs/context/conformance.md) | 仅当处理 conformance registry、容量 limitId、execution class 或 Renderer 认证语料。A/J 证据分级已退役，此分片解释遗留机制词汇。 |

## 限界上下文与依赖

```text
schema (Schema DSL + lifecycle + reference graph + compiler)
  └── validation (exact-Schema RootDocument validation)
        └── inference (job + candidate + evidence)

asset (Asset aggregate + AssetRef + AssetResolver)
  └── template (Template aggregate + DesignDSL + closure; also depends on schema)
        └── rendering (Evaluation + RenderDocument; also depends on schema,
                       validation and asset)

app depends on every context for which it owns a real HTTP/JDBC/Host/process Adapter or assembly
Rust RenderEngine executable is outside the Maven graph and is reached only through a Rendering-owned Seam

web ── OpenAPI 3.1.2 / generated Fetch SDK ── app
```

- `schema` 不依赖数据库、Spring MVC、模型供应商或文件系统。
- `validation` 不能反向改变 Schema；通用 JSON Schema validator 只用于互操作测试。
- `inference` 只能通过窄 application command 原子创建新 Draft Bundle；没有发布、更新或删除能力。
- `asset` 不依赖 Template 或 Rendering；Template 保存 Asset-owned `AssetRef`，Rendering 消费
  Asset-owned `AssetResolver` 与 `ResolvedAsset`。
- `template` 不依赖 Rendering；它向 Rendering 提供一致 closure snapshot，并以
  `AssetReferenceAuthority` 向 Asset 删除流程提供 current-only proof/reservation。
- Asset→Template 的反向运行时协作通过 Asset-owned outbound Interface 和 app Adapter 完成，不形成
  compile edge、共享聚合、共享表或跨上下文数据库事务。
- Rendering 直接消费 Schema、Validation、Asset 与 Template 各自拥有的 immutable public contracts；失败在
  Rendering Interface 内收口，不把上游 problem、聚合或 persistence model 暴露给调用方。
- Host authorization 是一个宿主事实源，但 Template、Asset、Rendering 各自只消费本上下文的窄 authority facet；
  请求不能自报 ownerScope/capability，capability 名不能进入 DesignDSL、RenderInput、RenderDocument 或 Command。
- Template 作者侧 use cases 由一个 `TemplateApplication` deep Interface 收口；Rendering snapshot 与 Asset 删除
  proof 分属 `TemplateSnapshotAuthority`/`AssetReferenceAuthority`，不会通过 authoring read 或 persistence
  record 共享。Template persistence 是 transaction-sized outbound seam，不是 public repository/CRUD。
- 模块共享只通过明确 public Interface；禁止建立无边界的 `common` dumping ground、split package、domain→app
  依赖或 JNI/FFI seam。精确 Java ownership 与 staged graph 见 `docs/adr/0041-template-module-interfaces-and-process-isolation.md`。

## 身份与路径

- Draft 字段身份：`schemaKey + fieldKey`。
- Static 字段身份：`schemaKey + versionTag + fieldKey`。
- 嵌套出现位置：再加从 RootDocument 根开始、正确转义的 JSON Pointer。
- 正式模型不保存 fieldId；Candidate 可使用 run-local opaque ID 维持审核关联，创建 Draft 时丢弃。

## 生命周期摘要

```text
Draft ACTIVE ──save──> ACTIVE(revision + 1)
     │                    │
     ├──publish saved revision──> StaticSchema(immutable)
     └──soft delete──> DELETED ──restore with full validation──> ACTIVE(new revision)

InferenceRun:
QUEUED → RUNNING → REVIEW_REQUIRED → APPLYING → COMPLETED
              └──────────────→ FAILED / CANCELLED
```

## 跨版本与实施权威边界

- `specs/renderweave-v1.md` 的历史 Schema/Inference scope 不定义 Template DSL、映射语言、Workspace 或 Renderer
  API；其中 AC-025 继续禁止该历史计划为未来能力预建占位。
- approved additive Template v1 由 `specs/changes/20260817-template-v1-implementation-authority.md`、冻结
  checkpoint `0b485f4` 及其 source records 管辖，可按已批准的 ready ticket 实现真实纵切，但不得反向改变
  StaticSchema、Validation 或 Inference 的既有合同。
- Schema/Inference 提供给 Template 的稳定接缝是精确 StaticSchema 标识、不可变 DSL 快照和已保存的
  compiled JSON Schema。
- 本文件只放领域结构与不变量。票据进度以 `.scratch/` tracker、git log 为准；已退役工作流的记录在
  `docs/history/`，不得写回这里。
