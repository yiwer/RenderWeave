# 数据连接器（Schema → Data Flow Connector/Adapter）架构探索

> 状态：研究输入，不是产品决策。探索快照：2026-08-17。
> 范围：RenderWeave 目标链路中 "Schema Definition → Data Flow Connector(Adapter)" 一段的架构与基础设施设计空间。v1 规格明确排除 Connector/数据适配（AC-025 不允许占位实现），本文是 v2+ 的前置研究材料。

## 0. 问题框定

目标链路全貌：

```text
Schema Definition → Template Design & Source Bindings
Schema Definition → Data Flow Connector(Adapter)
RootDocument + Template Design → (Compiler) → RenderDsl → (RenderServer) → Image
```

数据链路的内部阶段：

```text
外部世界(CSV/Excel/DB/HTTP/MQ/SaaS)
   → 提取(Extract)    —— 协议、凭证、网络都在这里
   → 归一化(Normalize) —— 源形状 → 中间表示
   → 映射(Map)        —— 中间表示 → 目标 Schema 形状
   → 准入(Admit)      —— 对 exact StaticSchemaRef 权威验证
   → RootDocument → RenderInput → Evaluation → RenderDSL → Image
```

既有约束（来自 CONTEXT.md 与 v1 规格）：

- `Connector`：在 Template 边界之外获取 CSV、Excel、数据库或 HTTP 等外部数据并归一化为渲染输入的集成组件。**不叫 `DataSource`，不是 DesignDSL 节点，也不能把凭证、SQL、文件或网络能力带入表达式求值。**
- `RenderInput` 是封闭 strict-JSON envelope（必填单一 `rootDocument` + 可省略 `customValues[]`），Schema 目标只来自 TemplateSnapshot，调用方不能选 Schema。
- 批量传输数组不属于 RootDocument；外层数组只是 transport envelope。
- `ABSENT` 是可选值未出现的内部有类型状态，不是 JSON null/空串/默认值。
- 模块方向：`schema ← validation ← … ← app`，不建通用 `common` 模块。

核心论点：**数据链与编译链同构**。编译链是"设计时事实 → 求值 → 静态降级"（DesignDSL → Evaluation → RenderDocument）；数据链是"源事实 → 映射 → 准入降级"。因此数据链应当被设计为**第二条编译链**，而不是外挂一个通用 ETL 工具。RenderWeave 已被反复验证的治理 DNA——不可变 revision、exact identity、Profile、Candidate+Evidence+人工审核、准入权威、payload-free 审计、fail-closed、A0–A3/J1 证据分级——都应在这条链上找到镜像。

一个重要推论：数据链的终点不是"把 JSON 递到渲染门口"，而是**生产出已被 exact StaticSchemaRef 权威验证过的 AdmittedDocument 集合**；渲染准入时 RenderInput 还会再验证一次。两次验证不是冗余，是两个上下文的各自权威。

## 1. 领域模型草案：新限界上下文 `connect`

沿用 CONTEXT.md 的术语表风格（全部为草案，用于讨论）：

| 术语 | 精确定义 | 不代表什么 |
|---|---|---|
| ConnectorKind | 全局封闭的协议能力种类：CSV、Excel、JDBC、HTTP 等，每种由 exact Connector Profile 冻结语义 | 不是用户可注册的插件、不是运行时反射发现的 driver 集合 |
| Connector Profile | 由 exact 标识冻结某一 ConnectorKind 的 wire envelope、能力、上限与错误语义的版本化合同 | 不是 driver 版本号、不是 latest 标记、不是部署方可热改的配置 |
| Connection | 以 opaque `connectionId` 标识的可变聚合：一个 ConnectorKind + 端点配置 + **凭证引用**（不是凭证本身） | 不是凭证容器、不是 DesignDSL 节点、不进入 RenderInput |
| SourceSchema | 一次探测或作者声明得到的**源侧形状描述**，保存为不可变 snapshot | 不是 StaticSchema、不参与渲染验证权威、不保证源系统不漂移 |
| MappingSpec / Draft | 从 SourceSchema 到目标 StaticSchemaRef 的版本化映射定义；每次成功保存产生不可变 revision | 不是通用脚本、不是 JSON Patch、不能访问映射声明之外的源数据 |
| StaticMapping | `{mappingKey, versionTag}` 标识的不可变发布映射物 | 不是指向 Draft 最新内容的视图 |
| MappingPlan | StaticMapping 发布时一次性编译的确定性执行计划（声明式降级产物） | 不是运行时解释执行的 AST、不是可热替换的 parser |
| Feed | 绑定 `{connectionId, exact StaticMappingRef, exact StaticSchemaRef, 触发方式}` 的可变聚合，是数据链的"流水线实例" | 不是调度器本身、不是渲染请求、不持有数据 |
| IngestRun | 一次 Feed 触发的提取+映射+准入执行；QUEUED→EXTRACTING→MAPPING→ADMITTING→COMPLETED / FAILED / PARTIAL | 不是渲染 run、不自动触发 Render |
| StagedPayload | 提取得到的原始内容，加密、内容寻址、有绝对过期期限 | 不是审计材料、不是永久数据湖、不是可公开下载对象 |
| AdmittedDocument | 通过权威验证的 RootDocument + 来源 manifest 的批量级语义值 | 不是原始行、不是通用 map、不允许部分准入冒充整体 |
| QuarantineItem | 准入失败单条的封闭问题投影：稳定 code + JSON Pointer + 来源定位符 | 不是自由文本日志、不是原始行转储、不是静默丢弃 |
| Watermark | 增量提取的 append-only 位置账本事实 | 不是可回拨的指针、不是业务时间戳猜测 |
| Provenance Manifest | 把 source query 摘要→提取→MappingPlan 版本→验证结果→准入串成 hash 链的 payload-free 身份投影 | 不是原始数据、不是可重放授权本身 |

依赖方向镜像现有结构：

```text
schema ── validation ── connect (domain: SourceSchema/MappingSpec/Feed/lifecycle + plan compiler)
                            └── app (JDBC repositories, sidecar assembly, worker, HTTP)
```

`connect` 只依赖 schema + validation 的 public API，永不反向。凭证、SQL、文件、网络全部留在 app 层的 port/adapter 与 sidecar 里；domain 模块中**不存在** `java.sql`/`java.net`/`java.nio.file`，由 ArchUnit 证明。

## 2. 方案族：五条架构主线

### 主线 A：拉式管道（Pull Pipeline）

Feed 被调度/手动触发 → Connector 拉数据 → 映射 → 准入 → 批量渲染。

- 优点：心智简单、失败域清晰、与 IngestRun 生命周期天然吻合。
- 缺点：实时性差；"渲染时数据必须最新"只能靠短周期轮询，浪费且仍有窗口。
- 适合：批量报表、标签打印、定时物料图。

### 主线 B：推式摄取（Push Ingest / Schema-as-API）

**发布 StaticSchema 时，平台自动铸造一个 exact 摄取端点**：`POST /api/v1/ingest/{schemaKey}/{versionTag}`，附带生成 OpenAPI 片段和 Fetch SDK。外部系统把数据**推**进来，准入即验证。

- 把"Schema 是事实源"推到逻辑终点：**Schema 就是 API 合同**。没有 Connector 也能活——Webhook、SaaS 回调、iPaaS 最后一跳天然是推式的。
- 基础设施成本极低：validation 模块已有权威验证，只需加 envelope、幂等（Idempotency-Key）、quarantine。
- 激进变体：**StaticSchema = 可寻址的数据合同**，每个版本天然是稳定的 API 版本——版本治理被 StaticSchema 不可变语义免费解决。
- 缺点：只覆盖能推的源；DB/文件类源仍需拉式。

### 主线 C：CDC/流式（Stream/CDC）

数据库变更数据捕获 → watermark 账本 → 逐条映射准入 → 渲染队列。

- 优点：新鲜度最高，数据驱动渲染（订单状态变化 → 自动重出图）。
- 缺点：基础设施最重（日志解析、位点管理、乱序/重复语义），与"单节点、不引入 queue service"的部署哲学冲突最大。
- 判断：**远期方向**。先把拉式 watermark 做成 append-only 账本，CDC 未来只是换提取器，domain 不动。

### 主线 D：Schema-First 代码生成（Codegen）

从 StaticSchema 编译出：类型化摄取 SDK、SQL 视图 DDL、CSV 模板（表头+示例行）、Excel 模板。

- 反向使用 Schema：不是数据来找 Schema，而是**把 Schema 物化成数据生产者手里的工件**。CSV/Excel 场景极划算——"下载模板"按钮让映射问题在源头消失大半。
- 与主线 B 正交：B 是运行时合同，D 是设计时工件。

### 主线 E：AI 辅助映射（Mapping Candidate + Evidence）

把 inference 模式整个镜像到数据映射：

1. 用户上传源样本（CSV 头+若干行、JSON 样例、DB 表 sample）；
2. AI 提出 **Mapping Candidate**：每个映射建议带 Evidence（源字段指针、目标 field path、推断理由类型）；
3. 人工审核（合同禁止 confirm-all，逐项 verdict）；
4. 通过后原子创建 MappingSpec Draft，Candidate 局部 ID 丢弃。

合理性：本项目已为解决"AI 提议、人审核、证据可追溯、fail-closed"付过全部学费（Candidate Bundle、Evidence、REVIEW_REQUIRED、逐项确认、apply 原子性）。**数据映射是同一问题的第二个实例**；架构同构，代码不共用。

风险与配套：映射错误的爆炸半径比 Schema 识别大（错映射的数据直接变成错图片），因此 Mapping Candidate 必须配套影子准入（见 §4.4）作为第二道闸。样本外发涉及 live 治理（逐 run ExternalTransferConfirmation），首版可只做本地启发式（字段名相似度+类型兼容），付费 live 留待后续决策。

## 3. 推荐主架构：三段式编译，不是自由 DAG

通用 iPaaS 喜欢给用户自由 DAG 画布。建议反其道而行：RenderWeave 数据流是**受约束的三段式**，每段有独立合同：

```text
ExtractSpec   →   MappingSpec   →   AdmissionSpec
(从哪取/取什么)   (形状怎么变)      (对哪个 exact StaticSchemaRef 验证)
```

- **不建通用 DAG 引擎**。三段是封闭结构，不是可无限嵌套的节点图。理由与 DesignDSL 不做自由 properties bag 相同：封闭合同才能静态证明、才能编译成确定性计划、才能有稳定 problem code。
- **MappingSpec 表达能力刻意受限**：字段重命名/嵌套、受 ValueType 约束的类型转换、常量注入、枚举映射表（有序 first-match + required otherwise，直接复用 MappingDefinition 语义）、同质 list 收集。**不做**任意表达式、不做 join、不做聚合——join/聚合发生在源侧（SQL 视图作为受支持的 Source 类型）或业务侧，不在这根管道里。若未来确需表达式能力，复用 RenderWeave Expression Profile，让映射表达式与设计表达式共享同一套版本化语言合同。
- **MappingPlan 编译**：StaticMapping 发布时一次性把 "SourceSchema ∧ 映射 ∧ StaticSchema" 三方做**双向静态类型检查**（源端每个被消费字段存在且可转；目标每个必填字段有来源），然后降级为确定性执行计划。这是数据链的 compiler 环节，与 DesignDSL→RenderDSL lowering 同构。类型检查不过，发布事务零写入。
- **ABSENT 语义直通**：可选源字段缺失 → ABSENT 传播；绝不允许 null/空串/默认值偷渡（CONTEXT.md 第 85 条语义在数据链上同源）。

## 4. 基础设施设计

### 4.1 模块与运行时拓扑

- `renderweave-connect`：纯 domain。SourceSchema、MappingSpec DSL 与生命周期、三段式模型、plan compiler、problem codes。零 IO。
- `renderweave-app`：JDBC repositories、worker assembly、HTTP。
- **Connector sidecar**：重协议（Excel 解析、特殊 SaaS SDK）走 sidecar，照搬 OCR sidecar 的成熟做法：pinned digest base image、`--require-hashes` 全量锁、stdio 或 HTTP-over-UDS JSON envelope、资源上限（CPU/内存/PID/探针）、**默认无网络**。轻协议（CSV/HTTP/JDBC）可以是进程内 adapter，同样走 port。
- **Egress 治理镜像 ProviderEgressPermit**：每种 ConnectorKind 有独立出口许可；HTTP Connector 目标 URL 走 per-Connection allowlist（exact URL 前缀，拒绝名单进测试）；凭证只以只读环境变量/`_FILE` 形式存在，**只由对应 adapter 读取**。

### 4.2 持久化（PostgreSQL，镜像现有风格）

| 表 | 语义 |
|---|---|
| `connection` | 可变聚合，**只存凭证引用**（secret name），不存凭证值 |
| `source_schema_snapshot` | 不可变 jsonb，探测一次存一次 |
| `mapping_draft` / `static_mapping` | revision 全量 snapshot + 发布时一次性编译的 MappingPlan（json，读取不重序列化），完全复制 Draft/StaticSchema 治理形状 |
| `feed` | 可变聚合，绑定三方 exact ref |
| `ingest_run` | 生命周期 + payload-free 指标（行数/字节/admitted 计数/quarantine 计数） |
| `staging_blob_ref` | 内容寻址（SHA-256）、信封加密、**Payload Expiry 绝对期限 + Tombstone**，继承 Inference Payload 删除治理（撤回/到期立即禁止读取并驱动物理+密码删除） |
| `admission_ledger` | append-only，每条 admitted 文档的 provenance manifest 身份；**永不 UPDATE/DELETE** |
| `watermark_ledger` | append-only 增量位点 |

Flyway 唯一迁移入口；Testcontainers PostgreSQL；不用 JPA。

### 4.3 凭证与安全

- 信封加密：内容密钥 + KEK，KEK 轮换只 re-wrap；KEK 丢失 = 等效 crypto-erasure。
- 凭证永不进入：日志、problem、DesignDSL、RenderInput、Expression 求值环境、MappingSpec 本体（MappingSpec 引用字段名，不引用连接）。
- HTTP Connector 的 response body 上限、超时、重试上限全部冻结在 Connector Profile 里，调用方不可协商。

### 4.4 预算与准入

- 每次 IngestRun 建立预算：行数/字节/时长硬上限；reservation 同事务持久化成功后才发 egress permit。
- **影子准入（dry-run / SHADOW 模式）**：Feed 完整走提取+映射+验证，只产出 "would-admit 报告" 和 quarantine 预览，零 admitted 写入、零渲染触发。这是 Mapping Candidate（主线 E）的强制配套：AI 提议的映射必须先跑影子准入才允许发布。
- 失败语义：fail-closed、稳定 problem code、quarantine 不静默丢弃；retry 走 Idempotency-Key/typed 409；歧义结果（请求可能已到达外部系统）按 Ambiguous Attempt 模式保守处理。

### 4.5 可观测性

- payload-free：结构化 JSON 日志带 traceId + ingestRunId；label 只用封闭枚举；**永不记录行内容、SQL 字面量、凭证、完整 RootDocument**。
- 指标：每 Feed 的 admitted/quarantined/failed 计数、提取字节、各阶段耗时。应用内聚合 + actuator JSON + PG 快照，不绑 Prometheus。

### 4.6 Provenance 与重放

- 每条 AdmittedDocument 的 manifest 把"源查询摘要 → 提取 blob hash → MappingPlan exact id → 验证结果 → 准入时间"串成 hash 链。
- 重放 = 用同一 manifest 重跑 IngestRun；staging blob 未过期则确定性重放，过期则显式拒绝（不是静默重取——重取是新 run）。
- 产品性质：任何一张图片都能反查到"它吃的是哪一批、哪一版映射、哪一次验证"。

## 5. 关键设计张力（待决策清单）

1. **Feed 与渲染的耦合度**：admitted 之后自动触发批量 Render，还是人工/下游显式触发？倾向两者并存（Feed 声明 `AUTO_RENDER` 策略引用 exact Template identity），首版只做显式触发。
2. **批量语义**：批量渲染 = N 次独立 Evaluation，还是引入"批量渲染 run"一等聚合？前者零新概念，后者可观测性更好。
3. **SourceSchema 的信任等级**：探测快照 vs 作者声明 vs 每次运行时重探测。建议：快照 + 每次提取做 shape 兼容性检查（fail-closed），不做主动漂移监控。
4. **映射语言边界**：join/聚合真的不做吗？把"源侧视图声明"（SQL 视图成为受支持的 Source 类型）作为泄压阀，可让大部分 join 需求推回数据库，管道保持纯映射。
5. **AI 映射的账本问题**：复用 inference 预算/确认体系会把数据映射拖进 live 治理（样本外发需逐 run 确认）。首版只做本地启发式是否足够？
6. **命名雷区**：CONTEXT.md 已禁用 `DataSource`；Connection/Feed/IngestRun 与渲染侧 RenderInput/AdmittedRenderInput 的边界需在 CONTEXT.md 写死"不代表什么"。

## 6. 推进顺序建议

| Phase | 内容 | 明确排除 |
|---|---|---|
| P0 领域语言 | §1 术语表与三段式结构定稿；否定性决策记录（不做 DAG、不做 join、两段验证） | 任何代码、表、接口 |
| P1 最薄纵切片 | CSV Connector（进程内 adapter）+ 手写 MappingSpec + exact StaticSchemaRef 准入 + quarantine + 显式触发批量渲染 | AI、sidecar、调度 |
| P2 映射治理 | MappingSpec Draft/Static 生命周期 + MappingPlan 编译器 + 双向静态类型检查 + 影子准入 | — |
| P3 推式摄取 | Schema-as-API 端点 + OpenAPI 片段生成（主线 B，性价比最高） | — |
| P4 第二协议 | JDBC Connector + watermark 账本（拉式增量），或 HTTP Connector + egress allowlist | — |
| P5 研究分支 | AI Mapping Candidate（主线 E）、CDC（主线 C）、代码生成（主线 D）按价值排序 | — |

## 7. 总结论

这个项目最稀缺的资产不是某个模块，而是**那套被反复验证过的治理模式**——不可变发布物、exact identity、逐 run 授权、payload-free 审计、fail-closed、证据分级。数据连接器几乎是这些模式的完美第二舞台：凭证治理镜像 Provider 治理、Staging 镜像 Inference Payload、Mapping 审核镜像 Candidate 审核、Admission 镜像 Render 准入。

**不要造一个 ETL 工具；造一条"数据侧的编译链"，让 Schema 的治理语义自然延伸到数据出生的地方。**

## 8. 后续入口

- 将 §1 术语表打磨为 CONTEXT.md 增量草案（需走正式决策流程）。
- 对本文做一轮 grilling（逐条逼问 §5 决策清单）。
- 起草 P1 纵切片的 spec delta 骨架。

> 注意：按 v1 治理约定，本文任何概念在实际版本决策前不得落地为表、接口或占位页面（AC-025 同构约束适用于未来版本规划）。
