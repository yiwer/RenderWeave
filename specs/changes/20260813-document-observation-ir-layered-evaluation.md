# Spec Delta：图片识别 vNext successor——DocumentObservationIR 与分层评测

- 状态：approved
- 日期：2026-08-13
- 触发任务：P6/T6-5 successor，当前只覆盖研究路线 R0–R1
- 触发证据：product-v45 已证明串行视觉流程能够到达 `REVIEW_REQUIRED`，但现有 RapidOCR 观察合同过早丢失源像素坐标、变换和推导身份；现有 60-case stage-gold 也不足以独立定位 OCR、布局、阅读顺序、重复组与后续语义阶段的误差来源
- 研究输入：2026-08-13《纯图片到可审核 Schema：架构与技术版图》；该报告本身不是产品决策
- 影响 AC/规则：细化 AC-VR-001、AC-VR-004、AC-VR-005、AC-VR-007、AC-VR-010；新增 AC-DOIR-001..012；RULE-ANCHOR-001、RULE-VAL-001、RULE-EVD-001、RULE-STATE-001
- 继承关系：本 delta 是 20260810 图片识别数据结构 vNext delta、P6/T6-5、ADR-0020/0022/0026/0028/0034/0035 与 product-v45 的 additive successor；不 supersede、不改写其历史事实、Profile、Prompt、pipeline、run snapshot、评测账本或证据
- 权威关系：`specs/renderweave-v1.md` 继续是 v1 权威规格；本 delta 已于 2026-08-13 获批，只补充本文明确列出的 R0–R1 行为
- 发布方式：仅保存在本地仓库；不创建或发布 issue，不添加 `ready-for-agent` 或其他 tracker 标签
- 再锚定关系：本 delta 一经批准，即成为 `RULE-ANCHOR-001` 的对照基准之一；批准前的需求演化不视为漂移

## Problem Statement / 问题陈述

从 Schema 设计者和审核者的视角，product-v45 已经可以把一张复杂图片沿
`OBSERVE → HIERARCHY → ELEMENT_BINDING → LOCAL_MATERIALIZE` 推进为可审核 Candidate，但一次指定图片到达
`REVIEW_REQUIRED`、生成两个 Schema，并不代表图片识别已经具备可解释、可替换、可认证的质量基础。该结果仍有
低置信 blocker，product-v45 也继续是 `EXPERIMENTAL`。

从工程视角，当前 `DocumentVisionPreprocessor` 输出直接面向 RapidOCR 的有序文本行用例。Java 在 adapter
边界立即把源像素 box 投影为 0..10000 坐标，并只保留连续整数 `readingOrder` 和置信度桶。这个合同满足 v45，
却没有把以下事实作为一个独立、版本化、供应商中立的感知合同表达出来：

- 输入 artifact 的规范化身份、源尺寸和坐标语义；
- source-pixel box 的边界约定，以及 source→Candidate 坐标投影算法版本；
- adapter、推理引擎、模型权重、预处理、后处理和顺序推导的 provenance；
- adapter 原生置信度与供当前流程使用的派生置信度桶之间的区别；
- OCR 文字的 ephemeral 敏感性和允许进入哪个阶段上下文的政策；
- 当前观察与后续 SLOT/GROUP、Entity、Relationship、Candidate 之间的类型边界。

如果直接把 PP-StructureV3、Tesseract、docTR 或其他 challenger 接到现有 Prompt，就无法公平判断差异来自 OCR、
布局、坐标投影、阅读顺序、Prompt 还是 semantic verifier；也容易让第三方 DTO、模型升级或隐式排序规则成为
新的事实源。

现有 `renderweave-visual-stage-corpus/1.0` 已提供 45 DEV + 15 HOLDOUT、元素、实体、关系、binding 和最终
Candidate 指标，是应当继承的评测底座。但它尚未完整标注 OCR ground truth、layout region、重复 item、
reading-order precedence edge、evidence owner 与 adapter 层误差，无法回答“元素是没看见、顺序错了、分组错了，
还是后续语义映射错了”。继续只优化最终 F1 或向 Prompt 追加规则，会把不同层级的失败混为一谈。

因此当前问题不是缺少开放式 Agent 或工作流框架，而是缺少一个稳定的 perception seam，以及能够沿该 seam
定位质量损失的分层评测。

## Solution / 方案

在不改变 product-v45 产品行为的前提下，增加一个内部深 Module `VisualEvidenceAcquisition`。它只暴露一个
主要 seam：

> `normalized ArtifactSet + AcquisitionPolicy → DocumentObservationIR/1.0`

`VisualEvidenceAcquisition` 隐藏 RapidOCR、进程协议、像素坐标、规范化投影、文字清理、排序、置信度分桶和
未来 adapter 差异。R0 只实现现有 RapidOCR/OpenVINO capability 到 `DocumentObservationIR/1.0` 的适配，
再通过确定性 compatibility projection 生成与 v45 相同的 stage 输入；不接入第二个感知引擎。

`DocumentObservationIR/1.0` 是本次 worker 内存中的 provider-neutral、versioned、strict、bounded IR。
它只描述可观测的文本行、源像素几何、顺序和 provenance，不包含 SLOT、GROUP、Entity、Relationship、
Field、Schema 或 Candidate 判断。OCR text 可以在当前政策允许的最小 stage context 中使用，但 IR、文字和
由文字派生的内容 hash 都不得进入 checkpoint、数据库、常规日志、常规 evidence、trace 或评测报告。

R0 同时为 OBSERVE、HIERARCHY、ELEMENT_BINDING 建立机器可读的 response-shape catalog，并生成 JSON Schema
文档。该 catalog 只权威描述 required、closed member、enum、数组和大小等语法形状；现有 strict codec、空间
不变量、semantic verifier、Candidate validator 和 RenderWeave DSL validator 继续拥有运行时与语义权威。
DashScope v45 继续使用 `json_object`，不能把这些文档宣传成 Provider strict structured output。

R1 新增不可变的分层评测语料版本，在现有 60-case、45 DEV + 15 HOLDOUT 分区和领域图基础上补充 OCR、
layout、order、repeat/item 与 evidence-owner 标注；同时提供 payload-safe 聚合报告、Java/Python 独立重算和
仅限受控本地环境的 visual diff。旧 corpus、旧 report、旧 EvaluationIdentity 和历史 live 结论保持不变。

外层继续使用现有 PostgreSQL durable typed state machine、lease、checkpoint、预算和事务；Provider 语义阶段
继续串行，validator 继续用 fixed issue code 决定最早失败阶段和有界回边。本文不增加开放式 Agent、通用工具
执行器、LangGraph 或 Temporal。

## User Stories / 用户故事

1. As a Schema designer, I want the same v45 image replay to produce an equivalent reviewable Candidate after the IR refactor, so that architecture work does not silently change my data definition.
2. As a Candidate reviewer, I want evidence boxes and their ordering to remain equivalent to v45, so that an internal adapter change does not move or detach the evidence I inspect.
3. As a Candidate reviewer, I want all low-confidence and unresolved items to remain visible, so that behavior equivalence cannot be achieved by suppressing blockers.
4. As an inference maintainer, I want one provider-neutral visual acquisition seam, so that OCR engines do not leak their DTOs throughout the worker and Prompt code.
5. As an inference maintainer, I want source-pixel geometry preserved until a deterministic projection boundary, so that coordinate loss can be diagnosed and tested.
6. As an inference maintainer, I want box semantics and transform versions bound to the observation identity, so that a coordinate convention cannot drift under an unchanged Profile.
7. As an inference maintainer, I want observation, semantic hypothesis, verified plan and Candidate to remain different types, so that an OCR result cannot accidentally become a Schema fact.
8. As an inference maintainer, I want stable canonical ordering of artifacts and observations, so that identical inputs produce reproducible IR and compatibility projections.
9. As an inference maintainer, I want adapter-native confidence preserved with provenance and a versioned bucket projection, so that scores from different engines are never silently averaged.
10. As a prompt maintainer, I want the current stage context projection to remain byte- or semantic-equivalent, so that R0 does not become an unmeasured Prompt change.
11. As a prompt maintainer, I want machine-readable response-shape contracts for the three semantic stages, so that required fields, enums and closed members cannot diverge between documentation and tests.
12. As a validator maintainer, I want JSON shape validation kept separate from semantic validation, so that schema-conformant output is never treated as visually true.
13. As a workflow maintainer, I want the existing PostgreSQL state machine and checkpoint rules unchanged, so that R0 does not introduce a second orchestration truth.
14. As a workflow maintainer, I want accepted stages not to replay after lease recovery, so that the IR refactor does not duplicate inference work or cost.
15. As a security reviewer, I want OCR text treated as untrusted ephemeral data, so that text embedded in an image cannot become an instruction or a durable data leak.
16. As a security reviewer, I want every ordinary log and evidence path to redact images, OCR text, Prompt and model output, so that richer diagnostics do not widen payload retention.
17. As a security reviewer, I want the model to retain an empty tool surface, so that image prompt injection has no filesystem, HTTP, SQL, publish or delete action to exploit.
18. As an operator, I want a missing or identity-mismatched acquisition capability to fail before any Provider reservation, so that local perception drift cannot create paid partial runs.
19. As an operator, I want payload-free capability and contract identities in readiness diagnostics, so that I can distinguish unavailable runtime from poor model quality.
20. As an evaluator, I want an immutable successor to the existing 60-case stage-gold corpus, so that old evaluation results remain reproducible while annotations become richer.
21. As an evaluator, I want OCR CER/WER separated from element and group recall, so that better character recognition is not mistaken for better Schema extraction.
22. As an evaluator, I want layout AP/IoU and evidence-owner accuracy, so that fields with correct names but incorrect visual grounding are reported as wrong.
23. As an evaluator, I want reading-order precedence metrics, so that a multi-column ordering failure is not hidden by a final contiguous integer.
24. As an evaluator, I want repeated-group and item-count diagnostics, so that a list flattened into a scalar can be attributed to the perception layer.
25. As an evaluator, I want entity, relationship, binding and topology-survival metrics retained, so that perception improvements can be checked against downstream regressions.
26. As an evaluator, I want Java results independently recomputed in Python, so that a bug in one scorer cannot certify itself.
27. As an evaluator, I want exact annotation, adapter, transform, evaluator and contract identities in EvaluationIdentity, so that results cannot be borrowed by a changed combination.
28. As an evaluator, I want local visual diffs for controlled synthetic or CC0 cases, so that I can inspect missed or extra regions without putting images into ordinary evidence.
29. As a release decision maker, I want v45 to remain `EXPERIMENTAL` unless existing AC-021 and AC-VR-010 gates pass, so that an architecture refactor is not presented as quality certification.
30. As a release decision maker, I want R0–R1 to perform zero external Provider calls, so that this work requires no inherited live authorization or paid-data transfer.
31. As a future adapter author, I want challengers to target `DocumentObservationIR` rather than Candidate, so that later experiments cannot bypass the same validators and materializer.
32. As a future Template or Connector author, I want this workflow to expose no Template, RootDocument or data-access capability, so that future contexts do not inherit image-recognition permissions by accident.

## Implementation Decisions / 实现决策

### 1. 继承、生命周期与版本边界

1. `specs/renderweave-v1.md` 的 IMAGE_ONLY 输入、Candidate、Evidence、durable run、Provider/Profile、人工审核和
   atomic create 规则全部继续有效。冲突时以 v1 权威规格和已批准 delta 为准。
2. 20260810 vNext delta 及 T6-5 的 AC-VR-001..010、预算、评测账本和历史处置不被本 delta 重置。R1 扩大诊断
   分辨率，但不降低 AC-021、AC-VR-010，也不把既有失败重分类为成功。
3. product-v45 的 pipeline 4.28、OBSERVE Prompt 12、hierarchy Prompt 7、binding Prompt 4、三个 Product
   Profile、Document Vision capability、价格、调用上限、超时、输出和费用边界保持不可变。
4. R0–R1 形成 additive contract/evaluation identity。任何会改变 v45 stage context、坐标投影或 runtime
   capability 语义的实现都必须使用新的 identity，不能复用 v45 Profile ID 或改写历史 snapshot。
5. 本 delta 不切换 Product Profile catalog，不晋级 Profile，不授权 live，不创建数据库 migration，也不增加
   Web/API 产品表面。若实现发现必须改变这些边界，应停止并提交新的 spec delta。
6. 本 delta 获批后，T6-5 计划增加两个串行 successor 节点：先完成 R0 行为等价，再完成 R1 分层评测。
   节点登记为 `pending` 不代表实现已开始、自动证据已形成或 N7 质量门已经关闭。

### 2. ADDED — R0：唯一主 seam 与深 Module

1. 新增内部深 Module `VisualEvidenceAcquisition`。它的唯一主要测试/调用 seam 是：规范化
   `ArtifactSet` 与不可变 `AcquisitionPolicy` 输入，返回 `DocumentObservationIR/1.0` 或稳定、无载荷的失败。
2. `ArtifactSet` 只表示已经通过 v1 magic/header、尺寸、像素、EXIF、sRGB 和 metadata 规范化的 artifact。
   它可以在调用期提供受控内存字节，但 IR 不包含图片字节、Base64、文件路径或远程 URL。
3. `AcquisitionPolicy/1.0` 至少绑定：
   - observation contract version；
   - exact adapter/capability identity；
   - engine、model manifest、预处理和后处理 identity；
   - source-pixel coordinate、box semantics、source→0..10000 projection 和 reading-order derivation version；
   - artifact、observation、文字、响应字节和执行时限；
   - OCR text exposure policy；
   - canonicalization version。
4. `AcquisitionPolicy` 不包含 Provider model routing、Prompt 自由选择、文件/HTTP/SQL capability 或 Candidate
   写权限。Provider Profile 仍由现有 Inference Profile 管理。
5. 现有 `DocumentVisionPreprocessor` 和本地 RapidOCR/OpenVINO 进程实现成为该 Module 内部 adapter；调用方
   不再依赖 RapidOCR 或 Python 协议 DTO。
6. R0 只实现一个生产基线 adapter。PP-StructureV3、Tesseract、docTR、PaddleOCR-VL 和其他 challenger
   不在当前依赖或 runtime 中出现。

### 3. ADDED — `DocumentObservationIR/1.0` 合同

`DocumentObservationIR/1.0` 是 strict、closed、bounded、canonical 的内部值，至少表达以下信息：

| 分区 | 规则 |
|---|---|
| Contract identity | 固定 `DocumentObservationIR/1.0`、AcquisitionPolicy identity 与 capability identity；未知版本 fail-closed |
| Artifact identity | `artifactId`、`sourceOrdinal`、规范化 media type、width、height、orientation 已应用事实；不含 bytes/path/URL |
| Coordinate space | 左上原点、`SOURCE_PIXEL` 单位、明确的 half-open box 语义；所有 box 必须位于对应 artifact 且面积大于零 |
| Observation | R0 只接纳有界 `TEXT_LINE`；每项拥有 run-local observation ID、artifact 引用、source-pixel box、canonical order、confidence 和 ephemeral text |
| Provenance | adapter/capability、engine/model identity、confidence scale、预处理/后处理、order derivation 与 projection version |
| Confidence | 保留 adapter-native 有界分数及 scale identity；当前 LOW/MEDIUM/HIGH 是版本化派生值，不允许跨 adapter 直接平均或比较 |
| Ordering | R0 保留与 v45 等价的确定性线性顺序和其推导版本；部分序、column/group edge 属于 R3，不在 1.0 运行时合同中提前实现 |
| Sensitivity | OCR text 标记为 `EPHEMERAL_UNTRUSTED`；任何字符串表示、异常、日志和 telemetry 只能暴露计数/长度与固定 code |

附加不变量：

1. artifact 按 `sourceOrdinal` canonical 排序；artifact ID 和 ordinal 在一个 IR 内唯一。
2. observation ID 在一个 IR 内唯一、仅在当前 run/调用内引用，不成为 Candidate ID、fieldId、Draft 或 StaticSchema
   身份，也不得进入常规 evidence。
3. RapidOCR 的 source-pixel box 保留到 compatibility projection；left/top 使用既有向下取整语义，right/bottom
   使用既有向上取整语义投影到 0..10000，不 clamp 非法结果。
4. Unicode NFC、空白折叠、单行与总文字字节上限保持 v45 行为；控制字符、空文字和越界文字 fail-closed。
5. source confidence 到置信度桶的边界保持 v45 行为并进入 derivation identity；R0 不重新校准阈值。
6. IR 只描述观察，不得出现 `SLOT`、`GROUP`、Entity、Relationship、Binding、Field、SchemaKey、required、
   constraint 或 Candidate resolution。
7. IR 不作为 PostgreSQL checkpoint 或公开 API DTO。crash/lease recovery 时可从已规范化 artifact 和 exact
   policy 确定性重算；不得为减少重算而持久化 OCR text。
8. 不保存基于 OCR text 的完整 IR hash。允许持久化的只有已有 artifact identity、contract/capability/policy
   identity、payload-free 数量、耗时、结果 code 和 Profile 已允许的摘要。

### 4. ADDED — v45 compatibility projection

1. 新增一个确定性、无外部副作用的 compatibility projection，把 `DocumentObservationIR/1.0` 转换成当前
   v45 semantic stage 所需的 Document Vision context。
2. projection 必须保持：artifact 顺序、line 顺序、line ID 规则、NFC/空白清理、0..10000 bbox、置信度桶、
   单行/总量上限、Prompt 数据封装和几何 sequence sentinel 输入。
3. projection 不得新增、合并、删除、改名或解释观察；任何不能无损投影的 IR 在 Provider reservation 前以
   稳定 code fail-closed。
4. OBSERVE、HIERARCHY 和 ELEMENT_BINDING 仍按因果顺序执行；LOCAL_MATERIALIZE 仍只消费 verified plan。
   R0 不增加 stage、回边、attempt、repair 或并行 Provider 调用。
5. v45 现有路径在 R0 验收期间作为行为 oracle 保留。只有 equivalence suite 通过后，successor identity 才能
   用于后续离线工作；本 delta 不允许把它加入 live Product Profile catalog。

### 5. ADDED — 三阶段 response-shape catalog

1. 为 OBSERVE、HIERARCHY、ELEMENT_BINDING 建立版本化 `StageResponseShapeCatalog`，覆盖当前 stage response
   的 required、closed object、enum、array、null、数值和大小边界。
2. catalog 为每个 stage 生成 machine-readable JSON Schema 文档和正/负 contract fixtures。生成结果必须
   canonical、byte-stable，并参与 evaluation/build identity。
3. strict Java codec 的 conformance tests 必须证明其接受集合与 catalog 一致；unknown、duplicate、trailing、
   scalar coercion、null primitive、非法 enum 和超限继续 fail-closed。
4. JSON Schema 只描述语法形状。region containment、readingOrder、repeat-group、element coverage、entity tree、
   relationship、binding、evidence、earliest-stage routing 和 Candidate 拓扑继续由现有确定性 validator 权威判断。
5. DashScope v45 的 request 继续使用 `json_object`；R0 不发送 strict JSON Schema 参数，不改变 Prompt，不改变
   provider dialect，也不以 parse success 替代 semantic success。

### 6. MODIFIED — Document Vision identity 与失败边界

1. 新的离线 successor evaluation identity 必须纳入 IR contract、AcquisitionPolicy、adapter/capability、模型
   manifest、预后处理、坐标/投影、order derivation、shape catalog 和 compatibility projection identity。
2. product-v45 的 immutable capability/Profile snapshot 不修改。若未来产品使用新 identity，必须通过新的
   additive Profile 与单独 delta/J1；不能在同一个 capability ID 下静默替换语义。
3. cancel 仍优先于读取 blob、启动 adapter 或预留费用；capability 缺失、identity drift、timeout、malformed
   output、IR contract failure 和 projection failure 都在 Provider reservation 前 fail-closed。
4. 普通失败只返回稳定 diagnostic code，不返回 OCR、动态 observation ID、坐标、路径、Python stderr 或异常
   prose。现有 API/monitor payload-free 约束不变。

### 7. ADDED — R1：不可变分层 gold v2

1. 新增 `renderweave-visual-stage-corpus/2.0`，不原地修改 1.0。v2 继续包含恰好 60 cases、45 DEV +
   15 HOLDOUT，并保留每个 case 的 domain、difficulty、render identity、Entity/Field/Relationship/Binding 和
   Candidate gold。
2. v2 为每个相关 case/variant 增加以下分层标注：
   - 受控 synthetic/CC0 OCR line/token ground truth；
   - layout region 与 region kind；
   - SLOT、GROUP、REPEATED_GROUP 和 ITEM；
   - gold evidence box/polygon 与 owner；
   - reading-order precedence edge；
   - repeat membership 与期望 item count；
   - entity、relationship、cardinality、binding 和最终 Candidate topology；
   - 可评测的 abstention/unresolved 期望。
3. runtime 用户图片、真实业务数据和既有 live payload 不得转存为 v2 gold。语料只使用仓库自制、确定性合成或
   已完成许可审查的 CC0 素材。
4. 标注 schema strict、versioned、closed；annotation version 与源素材/render identity 一起进入
   EvaluationIdentity。任何 gold 变动都产生新 identity，不能改写旧 report。
5. HOLDOUT 针对性修复后，相关 case 必须迁入 DEV 并补充新的 HOLDOUT；不得在保持同一 identity 的情况下
   反复调参。
6. R1 可以在 gold 中保存仓库控制的期望文字以计算 OCR 指标；这不构成放宽 runtime OCR text retention，
   也不得把用户图片文字复制进常规 evidence。

### 8. ADDED — R1 分层指标与报告

| 层级 | 必须报告的指标 |
|---|---|
| OCR | CER、WER、empty-reference insertion/hallucination、完全漏检率 |
| Layout/geometry | per-kind precision/recall、COCO-style AP@[.50:.95] 或经 golden 锁定的等价实现、bbox IoU、evidence recall、错误 evidence 率 |
| Order | precedence-edge precision/recall/F1、cycle rate；R0 线性顺序可确定性派生 edge 与 gold 比较 |
| Repeat | repeated-group recall、ITEM recall、item-count absolute error、membership accuracy |
| Semantic stages | SLOT/GROUP recall、entity/relationship F1、cardinality accuracy、binding accuracy、evidence-owner containment、stage survival、repair yield |
| Candidate | 既有 contract/entity/field/type/edge/evidence/DAG/critical-hallucination、validated-plan topology preservation 100% |
| Calibration/review | confidence bucket accuracy、ECE/Brier 或等价 reliability summary、unresolved precision、到 `REVIEW_REQUIRED` 成功率 |
| Runtime | calls、tokens、estimated/settled cost、stage p50/p95 latency、fixed-code recovery、accepted-stage replay count |

1. OCR 指标使用锁定版本的 JiWER 或由 golden 验证的等价 edit-distance 实现；layout AP/IoU 使用锁定的
   `pycocotools` 或经逐例 golden 证明等价的固定实现。它们只进入隔离评测 toolchain，不进入产品 runtime。
2. 图结构、order、repeat、binding 和 topology 指标由 Java 实现，并由独立 Python verifier 从不可变、
   payload-safe evaluation records 重算。两者任何不一致都使报告失败，不能择优采用。
3. 报告必须同时给出 global、DEV/HOLDOUT、domain、difficulty 和失败 slice；不能用全局平均隐藏 dense text、
   multi-column、repeated list 或 prompt-injection 退化。
4. payload-safe 常规报告可以包含稳定 case ID、identity、计数、比率、耗时、费用、固定 code 和 aggregate
   confidence bin；不得包含图片、OCR text、Prompt、Provider request/response、Candidate 原文、完整 bbox 列表、
   RootDocument 或 chain-of-thought。
5. visual diff 只在受控本地评测模式生成，只接受 synthetic/CC0 case，叠加 gold/predicted box、region、order
   edge 与 owner。它不上传、不进入普通 gate evidence、不作为 Web 产品页面，也不得从用户 live run 生成。
6. 新的 EvaluationIdentity 至少覆盖：input-set、annotation、normalization/render、AcquisitionPolicy、adapter 与
   weight hash、projection/order、stage shape catalog、Provider/Profile（若为 replay 则是 replay identity）、
   Prompt、validator、materializer、evaluator、预算和 decoding mode。

### 9. MODIFIED — 外层编排保持现状

1. 权威编排继续是 PostgreSQL job + lease + checkpoint 的 durable typed state machine。内存调度仍只负责
   wake-up；数据库事务仍是 Candidate apply 的唯一写边界。
2. perception acquisition 位于现有 `MULTISCALE_VIEW / DOCUMENT_VISION` 责任内，不新增一个可被独立跳过或
   任意调用的 Agent state。
3. `OBSERVE → HIERARCHY → ELEMENT_BINDING` 继续串行；validator 只通过 fixed issue code、code-owned
   `earliestStage` 和白名单 action 形成有界 control loop。
4. 模型不能决定目标、模型路由、任意工具、预算或终止条件。模型工具面继续为空；图片/OCR 中的任何命令均是
   untrusted data。
5. 本 delta 不引入 LangGraph、Temporal、AWS Step Functions、Agent SDK、Neo4j、向量数据库或第二套 durable
   workflow/history store。

### 10. REMOVED

本 delta 不从 v45 产品能力、合同或审核流程中移除任何行为。它只禁止以下做法成为 R0–R1 实现捷径：

- 让 RapidOCR/Paddle/docTR 等第三方 DTO 成为 inference 领域合同；
- 在同一 JSON 类型中混合 observation、semantic hypothesis 与 Candidate；
- 为恢复方便而持久化 OCR text 或完整 IR；
- 把 JSON Schema shape conformance 当作图片语义正确；
- 通过修改 v45 Profile/Prompt/pipeline 或借用旧 live 结果证明等价；
- 让 visual diff、图片或 OCR 文字进入常规 evidence。

### 11. 新增验收标准

| AC | 可观察行为 | 最低证据 |
|---|---|---|
| AC-DOIR-001 | 对合法 normalized ArtifactSet + exact AcquisitionPolicy，唯一 seam 产生 strict、bounded、canonical 的 `DocumentObservationIR/1.0`；非法版本、身份、数量、文字或几何 fail-closed | focused contract/property A1 |
| AC-DOIR-002 | source-pixel half-open box 与 v45 0..10000 projection 在边界、奇数尺寸、CMYK/JPEG/PNG 和多图上满足固定 golden；非法 box 不 clamp | Java/Python golden A1/A2 |
| AC-DOIR-003 | IR 中每项 observation 都能定位 exact artifact、adapter/capability、模型/manifest、confidence scale、预后处理、order/projection identity，且不出现 SLOT/GROUP/Schema/Candidate 语义 | contract + architecture test A1 |
| AC-DOIR-004 | OCR text 只存在于受控内存/stage context；checkpoint、DB、普通 log/evidence/report/trace、异常和 `toString` payload scan 为零泄漏 | failure/PG/payload scan A1；independent scan A2 |
| AC-DOIR-005 | RapidOCR compatibility projection 对锁定 corpus 与现有 Document Vision gold 保持 artifact/line 顺序、文本规范化、bbox、confidence bucket、limits 和 fixed failure semantics 等价 | differential golden A1 |
| AC-DOIR-006 | 完整 IMAGE_ONLY scripted replay 经 PostgreSQL durable workflow 到达与 v45 相同终态；accepted stage canonical payload、fixed issue routing、Candidate semantic fingerprint、evidence 顺序和 blocker 语义等价 | real PostgreSQL workflow A1 |
| AC-DOIR-007 | lease expiry/crash recovery 不重放已接受 stage；IR 可重算但不持久化；取消仍先于 blob/OCR/费用 | Testcontainers fault/recovery A1 |
| AC-DOIR-008 | R0–R1 全部 gate、replay 与评测的外部 Provider attempts/reservations/cost 为 0；历史 v45 Profile/run/corpus bytes 不变 | gate summary + diff A1 |
| AC-DOIR-009 | 三阶段 JSON Schema 文档 canonical、byte-stable；shape catalog 与 strict codec 正负例一致，semantic validator 仍能拒绝 shape 合法但空间/拓扑错误的输入 | contract differential A1 |
| AC-DOIR-010 | immutable corpus v2 保持 45 DEV + 15 HOLDOUT，并完整覆盖 OCR/layout/order/repeat/evidence-owner/semantic/Candidate 标注与 identity | corpus verifier A1；independent verifier A2 |
| AC-DOIR-011 | 分层报告按 global/partition/slice 输出规定指标；Java/Python 重算逐项一致，篡改 annotation、identity 或 report 必须失败 | A1 + independent replay A2 |
| AC-DOIR-012 | payload-safe report 不含禁止载荷；visual diff 仅能对受控 synthetic/CC0 case 在本地生成且不进入普通 evidence | negative tests + independent payload scan A2 |

## Testing Decisions / 测试决策

### 1. 测试 seam

主测试 seam 已由用户确认，且是本 delta 唯一新增的高层 seam：

> `normalized ArtifactSet + AcquisitionPolicy → DocumentObservationIR/1.0`

测试从该 seam 的输入和可观察输出断言合同、canonicalization、failure code、redaction 和 identity，不断言
RapidOCR 内部对象、Python 函数、Jackson 字段遍历或具体类协作。adapter process 只在需要验证真实边界时作为
黑盒运行。

最高验收 seam 继续复用完整 IMAGE_ONLY workflow：规范化输入经感知、三个串行语义 stage、本地物化和验证，
最终进入 `REVIEW_REQUIRED`。该 seam 必须证明 v45 行为等价，而不是只证明 IR 能序列化。

### 2. 行为等价定义

1. 对现有 deterministic/scripted replay，比较旧路径与 successor 路径的 terminal state、accepted stage
   canonical payload、earliest-stage/fixed issue routing、Candidate semantic fingerprint、字段/关系顺序、
   evidence canonical order、blocker/warning code 和 provider-reservation summary。
2. run ID、lease token、wall-clock time、数据库自增 sequence 等运行身份不参与 byte-equivalence；领域 payload
   在已有 canonical encoder 的位置必须 byte-equal，其余使用版本化 semantic comparator。
3. 等价不允许删除低置信项、减少 blocker、跳过 validator、增加 normalization 或改变 Prompt 来“改善”结果。
4. external Provider attempts/reservations 必须为 0。scripted/replay stage response 是测试输入，必须与真实
   Provider attempt telemetry 分开统计。
5. 至少覆盖 v45 的 CMYK BGR 解码、强文档序列 sentinel、重复实例字段聚合、ROOT→child MANY reference、
   多图片 artifact 顺序、空/越界/超限失败和 cancellation。

### 3. 局部与受影响测试

1. Contract tests：strict version/member/enum/null/coercion/duplicate/trailing、数量/字节/坐标/ID/provenance 上限。
2. Property tests：source-pixel→0..10000 floor/ceil projection、canonical ordering、重复 ID、随机合法尺寸、边界
   box、round-trip 不扩大出 artifact。
3. Differential tests：当前 Document Vision observation 与 IR compatibility projection 在锁定 fixture 上逐项比较。
4. Failure tests：adapter missing、model missing、manifest drift、timeout、malformed JSON、stderr noise、secret/proxy
   environment、cancel-before-read、invalid IR 和 projection failure。
5. Payload tests：递归扫描 checkpoint、DB row、execution event、report、exception、stdout/stderr capture 和对象
   string representation，确保图片、Base64、OCR text、Prompt/model output 不出现。
6. Shape catalog tests：每个 stage 的 positive/negative vectors 同时喂给 schema validator 和 strict codec；任何
   接受集合差异失败。另有 shape-valid/semantic-invalid 反例证明 semantic validator 不被替代。
7. Corpus tests：case count、DEV/HOLDOUT、annotation closure、owner/edge/repeat graph、identity drift、tamper、
   duplicate 和 deterministic render。
8. Metric goldens：CER/WER、AP/IoU、precedence edge、repeat item、entity/relationship/binding、tree/topology、
   ECE/Brier 的手算小例；空 gold 与空 prediction 必须有明确结果。
9. Independent verifier：Python 不调用 Java scorer，从 versioned records 独立重建指标、identity 和 payload scan；
   注入单点篡改必须被拒绝。
10. Visual diff tests：只接受 allowlisted corpus identity；用户 artifact、绝对路径、远程 URL、live run ID 或
    非许可素材必须 fail-closed。

### 4. 既有测试先例与门控

优先复用已有 Document Vision strict contract、RapidOCR runtime canary、Visual Stage Corpus/Evaluator、Candidate
materializer/validator、真实 PostgreSQL live workflow recovery、evaluation identity/journal 和独立 Python
evidence verifier 的测试风格，不为每个内部 helper 新建 public seam。

验证按局部→受影响→Goal 扩大：

1. inference contract/property/differential focused tests；
2. application adapter 与 Testcontainers PostgreSQL workflow/recovery；
3. `document-vision` runtime canary；
4. server gate；
5. successor 节点退出前执行 full gate与独立 evaluation verifier。

所有命令必须显式清除 live/provider 环境；预期保证为本地 A1，只有独立 Python 重算和 payload scan 的严格输入
范围可形成 A2。仓库无 A3。视觉 diff 需要人工查看时只能报告 `human_review_pending` 或 J0，不能把自动生成
图片冒充 J1 接受。

## Out of Scope / 非目标

1. Template、DesignDSL、Template Agent、Template 设计器或 Template 发布。
2. RootDocument connect/extract、Connector、数据适配、CSV/Excel/数据库/HTTP 数据读取。
3. Schema/Template/StaticSchema 发布、更新、删除、生产 cutover 或任意 SQL/文件/HTTP 能力。
4. 将 Candidate 自动应用为 Draft；现有逐项人工审核与 create-only atomic apply 边界不变。
5. 引入或晋级 PP-StructureV3、Tesseract、docTR、PaddleOCR-VL、Surya、Docling、LayoutParser 或任何新模型。
6. 把 challenger observation 融合进产品 Candidate，或切换 product-v45 Product Profile catalog。
7. 在 runtime IR 中实现 partial-order graph、column/group order、冲突 hypothesis 或多 adapter evidence fusion；
   这些属于 R2–R3 的新合同。
8. 为 Provider 启用 strict JSON Schema、grammar、function calling、tools、remote media 或跨模型自动升级。
9. 增加 `request_crop`、higher-resolution 或其他自适应感知动作。
10. 重写 Prompt 12/7/4、改变 stage 数量、调用/费用/超时边界或放宽 validator。
11. 引入开放式 ReAct/control-loop Agent、通用工具 executor、LangGraph、Temporal、Step Functions、图数据库或
    向量数据库。
12. 把 visual diff 做成 Web 产品页面，或为尚未批准的后续能力创建占位 API、表、页面或导航。
13. 修改既有 corpus 1.0、历史 evaluation report、Profile、Prompt、pipeline、run snapshot、StaticSchema 或
    compiled JSON Schema。
14. 使用真实客户/用户图片扩充 gold，运行付费 live AI，读取 API Key，或继承任何 CLOSED authorization。

## Further Notes / 后续决策门

以下 R2–R6 只记录未来触发条件，不属于本 delta 的实现承诺。任一条件满足后仍须提交独立 spec delta/ADR，
完成风险、许可、预算、数据与回退审查；不能因为写在本节就自动获得实现或 live 权限。

### R2：shadow 感知 bake-off

触发门必须全部满足：

1. R0 `DocumentObservationIR/1.0` 与 R1 corpus v2、分层指标、EvaluationIdentity、Java/Python verifier 已完成
   AC-DOIR-001..012；
2. RapidOCR baseline 在 exact identity 上重跑，能定位至少一个稳定的 OCR/layout slice 缺口，而不是仅凭库宣称；
3. challenger 的代码许可、模型权重许可、包/权重 hash、CPU/GPU/内存、Windows/部署、网络和供应链风险完成
   书面审查；
4. bake-off 全程 shadow，输出只进入新版本 Observation IR 和评测，不影响生产 Candidate、Prompt 或 Product
   Profile；
5. 晋级标准同时要求 holdout 感知指标、最终 stage replay/Candidate 指标、失败率、启动/资源和许可净收益。
   仅 CER 变好不足以晋级。

候选顺序保持研究建议：PP-StructureV3 为第一 challenger，Tesseract 为独立 CPU baseline，再从 docTR 与
PaddleOCR-VL 中选择一路。该顺序不是依赖引入授权。

### R3：reading order 与重复组深化

触发门至少需要可复现实证：

1. 在不少于 3 个 DEV 和 1 个 HOLDOUT case 中，相关 observation 已召回，但当前线性 `readingOrder` 或
   repeat projection 稳定地产生错误 precedence、membership 或下游拓扑；
2. 错误在两个确定性重跑中复现，并能排除 OCR 完全遗漏、Prompt schema failure 和 Candidate materializer；
3. gold precedence edge/repeat annotation 与 scorer 已由独立 verifier 通过；
4. 新设计以 additive IR version 表达 partial-order edge、column/group 和 `PerceptionInvariant`，不得修改
   `DocumentObservationIR/1.0`，也不得由几何规则直接创建字段或数组。

### R4：strict structured output 能力

触发门必须包含：

1. exact Provider/model/endpoint 官方协议证明支持 JSON Schema strict 子集，或自托管 grammar runtime 已锁定；
2. 当前 DEV evidence 显示 shape/codec rejection 是可测瓶颈，而非主要语义遗漏；
3. contract probe 覆盖 required、closed members、enum、数组上限、ref/递归限制、refusal、截断和 unsupported
   schema，并证明失败可读；
4. 新 immutable Profile 在同一 semantic validator 下显著降低 shape rejection，且 holdout 语义、成本和延迟
   不退化；
5. 任何真实 Provider probe 都另需 exact J1，不能复用本 delta 的零调用范围。

### R5：有界自适应感知

触发门必须证明静态 view plan 是系统性瓶颈：

1. 至少 3 个 DEV 和 1 个 HOLDOUT case 因小字/密集区域在静态 view 中不可读而遗漏，且不是 adapter、Prompt
   或 scorer 缺陷；
2. 离线 oracle crop/higher-resolution 能在不增加 critical hallucination 的情况下改善目标 slice；
3. 动作合同只能引用已验证 region/view，使用固定 margin/resolution 枚举，并对 crop 数、总像素、token、
   费用、loop 次数和超时设硬限；
4. 模型只返回声明式 `inspectionRequest`，Java validator 决定是否执行；模型仍无 filesystem、HTTP、SQL、
   publish 或通用 tool executor；
5. crash/recovery、重复请求、超预算和无效 ID 都有 fail-closed 测试与新的 authorization 设计。

### R6：是否引入工作流框架

只有出现真实编排压力才启动框架 ADR，至少满足以下一类事实并有量化维护证据：

- workflow 跨越多个可独立部署/扩缩的服务或 worker pool，需要 durable cross-service activity；
- 状态迁移、长时间人工暂停、取消/补偿或故障恢复已超出现有 PostgreSQL state machine 的可维护边界；
- 现有实现发生过无法通过局部重构、测试或状态表收敛的重复恢复事故。

评估 LangGraph/Temporal 前的 spike 必须证明：immutable Profile/run snapshot 可重放；LLM side effect 有唯一
幂等边界；history/checkpoint 不保存图片、OCR、Prompt 或模型原文；PostgreSQL apply 仍是单一事务权威；旧 run
按旧 pipeline 恢复；成本、取消、lease/fault injection 和 payload scan 不退化。不能运行两套 durable truth。

### ADR-0036 决策记录

本 spec delta 获批后，ADR-0036 已作为独立 `accepted` 决策记录落盘。其核心决策为：

> 外层 durable typed state graph/FSM，内部 provider-neutral Observation IR，语义阶段串行，validator 驱动有界局部 control loop。

ADR-0036 已比较继续使用现有状态机、固定串行 pipeline、静态 DAG、开放式 Agent 和当前迁移
LangGraph/Temporal 的取舍，并明确重新评估条件；它不重复记录 R0–R1 的一次性字段或测试实现细节。

## 影响面

- 用户价值/范围：不新增产品入口；降低未来识别质量改进的盲目性，并保证架构重构不改变 v45 审核体验。
- 实现与数据：新增内部 Observation IR/acquisition policy/compatibility projection/shape catalog、不可变 corpus
  v2、分层 evaluator、独立 verifier 和受控 local visual diff；不新增持久化 payload 或数据库表。
- 验证与发布：先完成 R0 differential 与 PostgreSQL behavior equivalence，再建立 R1 gold/metrics；两者均
  Provider=0。product-v45 继续 `EXPERIMENTAL`，本 delta 不构成发布或质量晋级。
- DAG/预算：R0 → R1 串行；R1 可以并行实现独立 scorer，但只有 Java/Python 一致后汇合。零 paid/live budget，
  不打开 ledger，不读取 Key。
- 恢复影响：源码按未来节点 commit revert；新 contract/corpus 使用 additive version；无数据库 migration、
  无 Provider 费用、无外部副作用。历史 v45 snapshot 和 corpus 1.0 始终可读。

## 决策

- 批准人：yiwer
- 日期：2026-08-13
- 当前结论：`approved`；只批准 R0–R1、T6-5 pending 节点登记及 ADR-0036，不发布 issue、不加标签，
  不修改 v1 权威规格，也不授权实现、live、付费调用或真实数据处理。
- 批准理由：R0 以行为等价方式建立单一感知 seam，R1 才能用分层证据判断后续应改 adapter、view、order、
  Prompt 或 validator；同时保持 v45 的 durable workflow、安全、人工审核和不可变历史边界。

> 本批准只允许把本 delta 作为后续实现和验收基准；不得用它偷绕 AC-021、AC-VR-010、live J1
> 或任何 payload-retention 规则（`RULE-STATE-001`）。
