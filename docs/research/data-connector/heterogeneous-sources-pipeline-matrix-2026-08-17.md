# 异构数据源与多 Schema 管道矩阵：兼容架构探索

> 状态：研究输入，不是产品决策。探索快照：2026-08-17。
> 前作：`docs/research/data-connector/architecture-exploration-2026-08-17.md`（三段式编译链与 connect 上下文草案）。
> 本文扩展：现实数据散落在其他系统、数据库、大量**不规则 Excel** 中；且存在 **N 个 Schema × M 个数据源** 的矩阵关系。如何在不引入通用 ETL 怪兽的前提下兼容这片混乱。

## 0. 新问题陈述

前作假设了"一个源 → 一次提取 → 一个映射 → 一个 Schema"的干净管道。现实是：

1. **形状混乱**：Excel 有合并单元格、多行表头、一 sheet 多表、转置布局（字段在行而非列）、自由批注、打印区分块；不同分公司上交同一业务的不同变体。
2. **矩阵爆炸**：N 个 StaticSchema × M 个数据源；一个宽表喂多个 Schema；一个 Schema 收多个来源的同类数据。
3. **持续漂移**：上游系统改列名、加列、换编码；管道需要在漂移下可诊断、可修复，而不是静默出错。

核心架构回应：**把编译器的"前端 / IR / 后端"三层结构搬到数据链上**。

## 1. 核心升级：Dialect → RecordContract(IR) → Mapping → Schema

前作中 SourceSchema 直接对接 MappingSpec。现在把它拆成三层：

```text
Excel变体A ──┐
Excel变体B ──┼─→ RecordContract v1 ──┬─→ MappingSpec α → StaticSchema P
CSV导出C ────┘   (IR 类型合同)        └─→ MappingSpec β → StaticSchema Q

DB视图D ───────→ RecordContract v2 ────→ MappingSpec γ → StaticSchema P
```

| 新术语 | 精确定义 | 不代表什么 |
|---|---|---|
| Extraction Dialect | 把一种具体源形状（某变体 Excel、某 CSV 导出、某 SQL 视图）降级为符合某个 exact RecordContract 的记录流的**版本化前端**；每次保存产生不可变 revision | 不是自由脚本、不是通用解析器配置堆、不能产出 RecordContract 声明之外的字段 |
| RecordContract | 中间表示（IR）的**类型合同**：一组有类型字段 + 基数 + nullability，版本化、发布后不可变，由 exact `{contractKey, versionTag}` 标识 | 不是 StaticSchema、不参与渲染验证、不描述任何具体源的文件格式 |
| RecordIR | 一次提取产出的、声明符合某 exact RecordContract 的记录流；是 MappingPlan 的唯一输入形状 | 不是原始文件、不是 RootDocument、不是通用 JSON 数组 |
| StaticDialect | Dialect 的不可变发布物，发布时针对其声明的 exact RecordContract 做产出符合性静态检查 | 不是指向 Draft 最新的视图 |

类比关系：**Dialect : RecordContract : Mapping : Schema ≈ 前端 : IR : 后端 : 目标平台**（LLVM 之于数据管道）。

矩阵性质由此变得可管理：

- **N 个 Dialect 实现同一 RecordContract**（各地分公司 Excel 变体 → 统一"订单记录"IR）：映射只写一次。
- **1 个 RecordContract 经多个 MappingSpec 喂多个 Schema**（宽表扇出）：提取只做一次。
- **1 个 Schema 可收多个 RecordContract**（每个来源族各配一个 MappingSpec）：准入权威不变。
- MappingSpec 两端都绑定 exact 不可变引用（RecordContract version + StaticSchemaRef）→ **完全静态可检查**，前作的双向类型检查语义原样成立。

Dialect 是 connect 上下文里凭证/格式知识的唯一合法居所；RecordContract 以上，世界是纯类型化的。

## 2. 不规则 Excel 生存指南

### 2.1 RawGrid 快照：数据管道的时间旅行

Excel 提取分两阶段：

```text
文件 bytes → RawGrid 快照（忠实单元格矩阵：值/合并区/格式信号，内容寻址+信封加密+Payload Expiry）
           → 提取计划作用于 RawGrid → RecordIR
```

RawGrid 即 StagedPayload。**修复提取方案不需要重读文件、不需要用户重传**——用同一 RawGrid 重跑新 Dialect revision 即可。这与 Provenance 重放语义（前作 §4.6）一致，且把"重取"与"重提取"明确分开：重取是新 run，重提取可以复用未过期快照。

### 2.2 锚点式区域提取 DSL

不规则 Excel 不能用"A1:Z100"这种绝对坐标。提取算子基于**地标锚点**：

- `anchor(text|regex)`：找到含"订单号"的单元格；
- `extend(right|down, until=blank|style-change|max)`：从锚点延伸出区域；
- `header-rows(n)` + `flatten(join=".")`：多行表头拍平；
- `transpose`：字段在行、记录在列的转置表；
- `repeat-block(each-sheet|each-region)`：每 sheet 一条记录、每打印区块一条记录；
- `skip-matching(regex)`：跳过批注行/合计行。

算子集是**封闭的**，每个算子有稳定 problem code 与上限（扫描行数/区域数）。不是自由脚本——和前作"不做 DAG"同一哲学：封闭合同才能静态证明、才能认证。

### 2.3 样式是不可信证据

粗体/填充/边框/合并**暗示**表头边界与区块结构，但永不成为权威——这是项目既有立场"OCR text 是不可信 ephemeral 数据"在 Excel 域的同构。样式信号可参与锚点候选排序，最终结构必须由确定性规则（值匹配、空行/空列边界、声明的列类型）确认；样式与确定性证据冲突时 fail-closed。

### 2.4 复杂度诚实边界

有些 Excel 本质上是画出来的图，不是数据表。Dialect 必须能**明确拒绝**而不是尽力而为：超过算子表达力时产出稳定 code（如 `DIALECT_STRUCTURE_BEYOND_GRAMMAR`），引导用户走"先整理/另存为规范模板"路径（配合前作主线 D 的模板下载）。诚实拒绝优于静默错提。

## 3. IngestInbox 与结构指纹路由：应对"众多 Excel 表"

文件从各处到达（上传 API、watch folder、邮件投递），需要一个不依赖人类分诊的路由层：

| 机制 | 语义 |
|---|---|
| IngestInbox | 统一摄取入口；每次到达产生 inbox item，只登记 payload-free 元数据 + 加密 payload 引用 |
| 结构指纹 | 对**形状**做 domain-separated hash：表头骨架、列类型序列、sheet 拓扑、合并区模式；**不含业务内容**（与 Live Input Manifest 的 payload-free 身份投影同构） |
| 指纹路由 | 指纹命中某 exact StaticDialect → 自动创建 IngestRun；未命中 → 进入 **onboarding 队列**（不是失败），引导创建新 Dialect |
| 漂移检测（免费副产品） | 同一来源族的历史指纹稳定，新文件指纹突变 = 上游结构已变 → 对应 Dialect 进入 STALE，fail-closed 而不是按旧方案硬提 |

Dialect 生命周期由此扩展：`ONBOARDING → CERTIFIED → STALE → DEPRECATED → RETIRED`，配合 sunset 台账激励上游改用规范模板（反 Excel 宣言：老变体逐步退役，而不是无限兼容）。

## 4. Schema × Pipeline 矩阵的管理面

- **Pipeline Catalog**：双索引视图——按 StaticSchema 看"谁在喂它"（Feed 列表 + 健康：最近 admitted、quarantine 率、新鲜度）；按 Connection 看"它喂谁"。
- **FeedReadiness 投影**：`READY / STALE / INVALID`，语义镜像 TemplateReadiness——指纹漂移或 RecordContract 退役 → STALE；重检后恢复或持续准入失败 → READY/INVALID。投影可替换、非权威，重检才是权威。
- **影响半径分析**：Provenance 图反查——"这个源结构变了，影响哪些 Schema / Template / 已渲染图片"是图查询，不是考古。
- **兼容性撮合**（矩阵的正面利用）：
  - 新 StaticSchema 发布 → 静态计算哪些 RecordContract 类型兼容，产出覆盖率报告（必填字段 x/y 可由该 IR 满足）；
  - 新 RecordContract 上线 → 反向推荐它可喂的 Schema 清单。
  - 撮合是**静态类型关系的物化**，不是 AI 猜测；AI（前作主线 E）只负责在兼容集合内提议具体映射。

## 5. Schema 演进与映射迁移：不可变治理的复利

两端不可变带来一个杀手级副产品：

1. Schema v1 → v2 都是不可变发布物 → **diff 可静态计算**；
2. 系统自动生成 **MappingSpec 迁移建议**：未变字段平移、新增必填字段标记为缺口（需作者补源或转 optional）、删除字段标记为死映射；
3. 作者确认后经普通保存产生新 MappingSpec revision——语义镜像 DesignDSL migration（显式作者操作、纯转换预览、接受后追加新 revision，绝不读取时升级）。

RecordContract 演进同理。迁移从"人肉比对两份 JSON"变成静态分析问题——这是 exact identity + 不可变发布物治理模式的复利，通用 ETL 工具给不了。

## 6. 失败驱动修复与 quarantine 学习

- Quarantine 按稳定 problem code 聚类，聚类模式驱动**确定性 bounded repair 提议**：只在存在**唯一可证候选**时提议修复（与 inference 的 OBSERVE bounded repair 完全同哲学）。
  - 例：列改名 "订单号"→"订单编号"，位置与类型序列唯一一致 → 提议新 Dialect revision；多个候选 → 保持 fail-closed，不猜。
- **Extraction Review Workbench**：低置信/边界提取进人工审核台——RawGrid overlay + 锚点/区域的 evidence 指针 + 逐项 verdict。这是 Candidate 审核模式（提议-证据-人审-原子落地）在数据链的**第三个实例**（前两个：Schema 识别、Mapping 提议）。

## 7. 反向闭环：从数据发现 Schema

给一堆 Excel/DB 表，AI 提议"这批数据里存在什么业务结构"——Schema Candidate，人工审核后创建 Draft、发布，再反向撮合 RecordContract 并配管道。这是 Candidate 模式的**第四个实例**，并把整条链闭合成飞轮：

```text
data → (发现) Schema → (撮合) RecordContract → (提议) Mapping → data → …
```

## 8. 混沌测试与认证基础设施

- **Dialect Certification**：镜像 FrozenCertificationCycle——冻结 golden 文件语料（5/20/60 + HOLDOUT）、固定阈值、独立复核、人工 J1；Dialect Profile 版本化不可变。认证按变体分级：`CERTIFIED / PROVISIONAL / ONBOARDING`，避免"每个分公司一个变体认证不起"的成本爆炸——ONBOARDING 变体允许运行但其产出强制走影子准入 + 人工审核台，CERTIFIED 才允许自动 admitted。
- **Property-based 混沌变异**：对 golden 文件做程序化变异（插入行、列重排、合并区变体、编码/BOM 变体、表头同义词替换），证明提取方案在声明的抗扰动包线内鲁棒；超出包线必须 fail-closed。
- 每次提取的预算：行数/字节/时长硬上限，reservation 同事务持久化后才发执行许可（同前作 §4.4）。

## 9. 治理镜像总表（更新版）

| 数据链概念 | 镜像的既有概念 | 复用的治理语义 |
|---|---|---|
| RawGrid / StagedPayload | Inference Payload | 信封加密、Payload Expiry、Tombstone、内容寻址 |
| 结构指纹 | Live Input Manifest | payload-free 身份投影，绑定内容与决策 |
| Dialect Certification | Profile Certification | 冻结语料、append-only 事件、双层 J1 |
| Extraction Review | Candidate Review | 逐项 verdict、禁止 confirm-all、原子落地 |
| FeedReadiness | TemplateReadiness | READY/STALE/INVALID 可替换投影、重检权威 |
| Bounded repair | OBSERVE bounded repair | 唯一可证候选才修复，否则 fail-closed |
| Mapping 迁移建议 | DesignDSL migration | 显式作者操作、预览、追加新 revision |
| Egress permit / 预算 | ProviderEgressPermit / 成本账本 | reservation 同事务、per-kind 许可、拒绝名单进测试 |
| Onboarding 队列 | REVIEW_REQUIRED | 不确定不静默，升级给人 |

## 10. 新增/修订的待决策清单

1. **RecordContract 是否一等公民**：独立 `{contractKey, versionTag}` 身份（本文立场），还是 SourceSchema 的改名升级？独立身份才能支撑 N:1 与 1:N 矩阵，但多一个概念。
2. **指纹路由误判**：形状相同但语义不同的文件撞指纹（不同业务的"编号/名称/金额"三列表）——指纹只路由到 Dialect 候选，仍需 Dialect 的声明性前置校验（如锚点必须命中指定文本）做最终确认，误判即 fail-closed 进 onboarding。
3. **认证分级阈值**：PROVISIONAL → CERTIFIED 需要多少连续零 quarantine run？是否镜像 Profile Certification 的 5/20/60，还是按变体复杂度分档？
4. **算子包线**：锚点 DSL 的表达力上限在哪里（嵌套重复块？跨 sheet 引用？），超出后统一走"模板先行"路径还是引入受控表达式？
5. **Excel 方言库策略**：平台预置通用方言（标准表头表、转置表、分块报表）vs 每个变体定制；预置方言的认证由平台还是部署方背书？
6. **Schema 发现（§7）的外发治理**：样本数据送 AI 提议 Schema 涉及 live 治理，是否同样首版只做本地启发式（列名聚类+类型推断）？
7. **扇出的原子性**：一次提取喂多个 Schema 时，单 Schema 准入失败是否阻塞其他扇出分支？（建议：分支独立成败，IngestRun 汇总为 PARTIAL，与既有生命周期一致。）

## 11. 推进顺序修订

| Phase | 内容 | 新增要点 |
|---|---|---|
| P0 领域语言 | 增加 RecordContract / Dialect / RawGrid / 结构指纹 / FeedReadiness 术语；三段式扩展为 **Dialect→Contract→Mapping→Admission 四段** | 否定性决策补充：不做自由提取脚本、样式永不权威 |
| P1 最薄纵切片 | 标准 CSV 直通（Dialect 退化为恒等）+ 单 Schema 准入 | RawGrid 快照与重提取即从此切片落地 |
| P1.5 第一个真实方言 | 一个**标准表头 Excel** Dialect（锚点 DSL 最小算子集）+ 结构指纹 + IngestInbox 手动路由 | 混沌变异测试随方言一起建 |
| P2 映射治理 | RecordContract 一等化、双向静态检查、影子准入、迁移建议 | — |
| P3 路由与矩阵 | 指纹自动路由、onboarding 队列、Pipeline Catalog、FeedReadiness 投影 | — |
| P4 认证与审核 | Dialect Certification 周期、Extraction Review Workbench、bounded repair | — |
| P5 研究分支 | AI Mapping Candidate、Schema 发现、CDC、代码生成 | 每项独立 J1/账本决策 |

## 12. 总结论

前作的结论是"造一条数据侧的编译链"；本文把它补完：**编译链需要前端、IR 与后端**。Dialect 是把混乱源形状驯化为类型化 IR 的前端，RecordContract 是让 N×M 矩阵退化为 N+M 的 IR 合同，Mapping 与 Admission 是后端。所有治理模式——不可变发布物、exact identity、认证周期、人审台、payload-free 审计、fail-closed——在每一层都有既有镜像，没有一项需要发明新治理。

面对不规则现实的原则只有一条：**能静态证明的绝不运行时猜，能确定性确认的绝不信样式，猜不透的诚实拒绝并升级给人**——与这个项目在图像识别上学到的教训逐字相同。
