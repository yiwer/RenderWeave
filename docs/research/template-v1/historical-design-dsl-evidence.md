# 历史 DesignDSL 语义证据

> 调研对象：`D:\Yiwer\code\hbads-design-v2`
> 调研日期：2026-08-11
> 用途：为 RenderWeave 后续 Template 规格提供历史事实输入；本文不定义 RenderWeave v1，也不提出历史数据迁移或兼容方案。

## 1. 证据口径

本文只使用历史仓库中的规格、源码、测试和计划。标签含义如下：

- **[SPEC]** 已批准或标为 normative 的历史规格陈述；表示历史设计意图。
- **[CODE]** 当前源码直接可见的结构或行为。
- **[TEST]** 当前测试明确锁定的行为。
- **[PLAN]** 计划或完成清单中的状态陈述；不能单独证明代码具备能力。
- **[INFERENCE]** 从多份一手材料合并得到的解释，尚无单一权威声明。
- **[GAP]** 规格、代码、测试或计划之间的缺口、冲突或未知。

历史仓库只能作为参考。本文优先用规格说明“想要什么”，再用代码和测试说明“实际存在什么”；计划清单仅用于判断实现状态，不把勾选项冒充语义证据。

## 2. 结论摘要

1. **[SPEC][CODE] Template 与 Schema 的关系是精确上下文绑定，不是由 Schema 生成 DesignDSL。** Template 创建时在资源元数据上固定 `SCHEMA(schemaKey, versionTag)` 或 `SINGLE_VALUE(valueType)`；DesignDSL 顶层本身没有 Schema identity 字段。
2. **[SPEC] `stele-design-document-v2` 是一个原子文档。** 静态元素树、definitions、顶层 bindings、TemplateUse 都属于同一不可变 Template Revision。
3. **[SPEC][CODE] 历史系统的一等 ValueSource 只有 `literal | context | scope | definition`。** 未发现任意 SQL、HTTP、GraphQL、消息队列或 datasource-plugin 协议；“兼容各种数据源”不能从该历史实现推出。
4. **[SPEC][CODE] 动态语义在服务端消除。** Definition、Expression、Mapping、Binding、TemplateUse、ArrayLoop 与 Context 不进入 Renderer；Renderer 只消费八种封闭静态 scene primitive。
5. **[GAP] 元素属性没有一个单一、封闭、可直接复用的 v2 类型源。** 属性语义散布在 v1 元素规格、DES-ELEM、continuous-field registry、Binding slot registry、Web visual adapter 和 materializer 中；生成的 `V2Element` 只封闭 `id/kind`。
6. **[GAP] 纯内核能力与生产接线不同步。** 代码中已有递归 materializer、两阶段 loop layout 和 scene projector，但生产 `DesignV2SceneEvaluationKernel` 只接收六种 kind，并明确返回 `ARRAY_LOOP_NOT_COMPILED`。
7. **[GAP] 历史“产品闭环完成”不等于全 DSL 闭合。** PC6 计划宣称 create→preview→render→download 已完成；更晚的 2026-08-11 UV 计划又明确记录普通 kind、容器、循环及特殊 lowering 尚未接入生产 kernel。
8. **[SPEC][CODE] 首个 Render Artifact 是 sealed-scene JSON，不是 PNG/PDF。** SVG 是 Web 对 sealed scene 的预览适配；历史证据不足以证明通用图片/PDF 渲染器。

## 3. Template、PublishedSchema 与根输入边界

### 3.1 历史术语与身份

- **[SPEC]** 历史 Schema 规格使用 `PublishedSchema`，其生命周期是“发布创建 → 永久只读”，保存 definition、compiled document、canonical hash 及编译/规范化版本；业务角色没有 UPDATE/DELETE 权限。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\stele-v1.md:119-125,161-172`
- **[SPEC]** Design Template 的 context 是 `SCHEMA(schemaKey, versionTag)` 或 `SINGLE_VALUE(valueType)`，创建时固定；SCHEMA 以同租户精确 PublishedSchema identity 关联，不按 latest 解析。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\stele-design-v1.md:295-301,339-355`
- **[CODE]** `TemplateContext` 是 sealed union；注释明确其在创建后不变。`SchemaTemplateContext` 只保存非空 `schemaKey/versionTag`，`SingleValueTemplateContext` 保存白名单基础类型。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\api\TemplateContext.java:3-11`；`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\api\SchemaTemplateContext.java:3-21`；`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\api\SingleValueTemplateContext.java:3-18`
- **[CODE]** Template context 存在 `design_templates.context`，而 Revision 的 DSL 存在 `design_template_revisions.design_document`；这是两个持久化事实。
  来源：`D:\Yiwer\code\hbads-design-v2\core\app\src\main\resources\db\migration\V004__design_template_registry.sql:1-10,19-35,42-60`

**[INFERENCE]** 对 RenderWeave 的 `StaticSchema` 术语，历史上最接近的语义对应物是不可变 `PublishedSchema`。但不能把名称相似当成合同等同；可复用的历史事实仅是“精确 `{schemaKey, versionTag}` 身份、不可变发布物、无 latest 回退”。

### 3.2 Design 模块如何消费 Schema

- **[CODE]** Design 的 `PublishedSchemaResolver` 明确只通过 Schema public API，按 tenant + exact key/version 查询；接口注释禁止 latest 和结构同构猜测。默认 adapter 构造同时含 `schemaKey` 与 `versionTag` 的查询。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\api\PublishedSchemaResolver.java:7-41`；`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\SchemaApiPublishedSchemaResolver.java:15-23,38-67`
- **[TEST]** resolver 测试检查 query 同时携带 key 与 tag，并扫描接口中不存在 `latest` 方法。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\test\java\cn\hbads\stele\design\internal\SchemaApiPublishedSchemaResolverTest.java:75-94,153-164`
- **[SPEC]** Schema module 拥有 runtime instance parser、contract resolver、constraint validator 和 validated view；Design 只消费 typed、immutable result，不能用 `ResolvedContextTypeIndex` 或通用 JSON Schema validator 代替完整实例验证。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\SCHEMA-INSTANCE-01-published-instance-validation.md:9-19,23-68,119-132,184-201,214-222`
- **[CODE]** `SchemaRootContextAdapter` 的实际顺序是：lossless raw `valueJson` parse 一次 → exact contract resolve 一次 → validate 一次 → 只通过 validated context view 做 value/facts/handoff lookup；不接收 caller proof。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\runtime\SchemaRootContextAdapter.java:14-25,79-149,151-183`

Schema FieldType 到 Design value type 的历史投影为：

| Schema fact | Design fact |
| --- | --- |
| TEXT | STRING |
| NUMBER（含 integerOnly） | DECIMAL；另保留 integer/number contract 区别 |
| BOOLEAN | BOOLEAN |
| DATETIME | DATETIME |
| scalar array | LIST&lt;scalar&gt; |
| object array | compiler-only CONTEXT_COLLECTION，不是一等 Expression value |
| COLOR / IMAGE | 不从字段名或 format 猜测；历史 Schema 没有对应 FieldType |

来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:241-263`；`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\ValueType.java:5-28,31-57`

### 3.3 根输入不是 DSL 内嵌数据

- **[SPEC]** Evaluation input 单独携带 exact root Template Revision、与 TemplateContext 同构的 root context identity、lossless raw `valueJson` 和 root `customData`。identity 必须先与 immutable TemplateContext 逐字段匹配。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:729-779`
- **[SPEC]** SCHEMA 输入走 Schema-owned published-instance validation；SINGLE_VALUE 走 Design-owned 四类型 decoder。Schema 的 canonical hash 来自 resolver，不由客户端随 RootDocument 提交。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\SCHEMA-INSTANCE-01-published-instance-validation.md:203-216`

**[INFERENCE]** Schema 定义“允许从 Context 读取什么及其类型/Presence”，DesignDSL 定义“把哪些值映射到哪些视觉 slot，以及如何组合/循环”；二者是精确绑定的两个事实源，不是一个 JSON 模型。

## 4. DesignDSL v2 的权威结构

### 4.1 原子文档与版本

- **[SPEC]** `format = stele-design-document-v2`；顶层必须含 `definitions[]`、`bindings[]`、`composition.uses[]`，它们与静态 element tree 共享一个 Template Revision。顶层 bindings 是唯一 binding authority。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:15-21,25-37,61-78`
- **[CODE]** server codec 的 exact root field set 是 `artboard, bindings, composition, definitions, elements, format`，并检查 format、必填字段、canonical numeric contract、binding 及 legacy authority。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\document\v2\SteleDesignDocumentV2Codec.java:31-55,57-101`
- **[CODE]** definitions、bindings、uses、Expression inputs、custom fills 在 canonical writer 中按各自稳定 key 排序；Mapping cases 保持 authored order。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\document\v2\SteleDesignDocumentV2Codec.java:198-227`
- **[CODE]** v2 write kernel strict decode 后生成 canonical bytes、content hash、graph digest、ImageRef projection、Template composition projection；trusted read 会重算并核对 suite/hash/graph。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\document\v2\DesignV2RevisionKernel.java:10-55,68-114`
- **[CODE]** Template registry 只有 current pointer 可变，Revision 表只授予 SELECT/INSERT；v2 provenance 绑定 document/canonical/hash/expression 版本，旧 v1/no-format row 保持全 NULL 且不重写。
  来源：`D:\Yiwer\code\hbads-design-v2\core\app\src\main\resources\db\migration\V004__design_template_registry.sql:1-3,68-84`；`D:\Yiwer\code\hbads-design-v2\core\app\src\main\resources\db\migration\V011__design_v2_revision_provenance.sql:1-35`

### 4.2 v1 动态字段在 v2 中失去权威

- **[SPEC][CODE]** v2 element 任意层出现 `bindings/itemLayout/itemTemplate/source/variantRules` 都被当作 unknown field 拒绝；v2 只扫描 root bindings/composition。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:648-650,1198-1206`；`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\document\v2\SteleDesignDocumentV2Codec.java:54-55,346-359`
- **[CODE]** Web authoring codec 同样递归拒绝这些字段。
  来源：`D:\Yiwer\code\hbads-design-v2\web\src\features\design-v2-authoring\v2-authoring-codec.ts:61-67,600-610`

这意味着 `specs/stele-design-v1.md` 中的 element-local `bindings` 和 `repeat.source/itemTemplate/variantRules` 只能作为历史 vocabulary 证据，不能作为 v2 authority。

## 5. DataDefinition、ValueSource、Mapping、Expression 与 Binding

### 5.1 Definition 与 ValueSource

- **[SPEC]** Definition closed union 是 `custom | mapping | expression`；definition ID 在模板内稳定唯一，definition 只能引用同模板、同 evaluation domain 或 lexical ancestor domain。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:212-232`
- **[SPEC][CODE]** ValueSource closed union 是 `literal | context | scope | definition`；Web codec 只接受这些 discriminant，Scope name 仅 `value | index`。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:269-275`；`D:\Yiwer\code\hbads-design-v2\web\src\features\design-v2-authoring\v2-authoring-codec.ts:449-481`
- **[SPEC]** persisted types 是 STRING、DECIMAL、BOOLEAN、DATETIME、COLOR、IMAGE、LIST&lt;scalar&gt;；Presence/ABSENT 是 analyzer effect，不持久化且不能穿越 definition/binding/fill boundary。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-EXPR-02-expression-language-2.0.md:88-119`
- **[GAP]** DES-DATA 的范围摘要提到 “System/Context virtual sources”，但其后封闭 ValueSource union 和实际 codec 没有 `system` variant。System source 的具体 wire、类型和求值语义无法从历史仓库确认。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:25-37,212-232,269-275`
- **[GAP]** 未发现任意外部 connector/adapter 是 DesignDSL ValueSource。历史运行输入是 RootDocument、customData、Context、Scope 和 Definition graph；不能据此声称支持任意外部数据源。

### 5.2 Custom

- **[SPEC]** Custom 固定 invocation domain，必须有 typed default；PUBLIC 必须有唯一 externalKey，可被 root customData 或父 TemplateUse fill 覆盖；PRIVATE 禁止 externalKey，只读 default；missing 与 `false/0/""/[]` 区分，null 非法。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:513-543`
- **[CODE]** Java v2 record 对 Custom 的 exposure/key/default/type/raw-shape 做 exact 校验。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\document\v2\SteleDesignDocumentV2.java:97-157`
- **[GAP]** 同一 Java record 的 typed literal matcher 对 DATETIME 分支返回 `false`，而 Web/OpenAPI 类型允许 DATETIME literal；这至少说明 typed contract 在多个入口间没有完全收口。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\document\v2\SteleDesignDocumentV2.java:186-209,263-283`；`D:\Yiwer\code\hbads-design-v2\web\src\lib\api\schema.d.ts:1763-1783,1893-1905`

### 5.3 Mapping

- **[SPEC]** Mapping cases 按数组顺序 first-match；`otherwise` 必填；operator 白名单是 `IS_ABSENT, EQ, NOT_EQ, GT, GTE, LT, LTE, CONTAINS, STARTS_WITH, ENDS_WITH`，无 regex；Mapping 降低到与 Expression 相同的 typed common IR/evaluator。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:545-567`
- **[CODE]** Web codec 封闭 cases/when/operand/then/otherwise 形状并保留 case order。
  来源：`D:\Yiwer\code\hbads-design-v2\web\src\features\design-v2-authoring\v2-authoring-codec.ts:289-341`

### 5.4 Expression 2.0

- **[SPEC]** wire 固定 `language="stele-design-expression"`、`languageVersion="2.0"`；表达式只能通过结构化 inputs 使用局部 `input.alias`，不能按全局 display name 查找；Java 是唯一生产 evaluator。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-EXPR-02-expression-language-2.0.md:13-40,42-79`
- **[SPEC]** 类型系统显式跟踪 `CONCRETE | MAY_BE_ABSENT`；`exists` refinement、`coalesce`、lazy `if` 等少数构造可消除 Presence，definition output 必须保存期证明 total。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-EXPR-02-expression-language-2.0.md:88-164`
- **[SPEC]** grammar 只含 Boolean/String/Decimal literal、`input.alias`、函数、括号、`! - * + - < <= > >= == != && ||`；无 `/`、`%`、string `+`、成员/索引、隐式 stringify 或隐式 conversion；AST depth 上限 32。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-EXPR-02-expression-language-2.0.md:233-303`
- **[SPEC][CODE]** 函数白名单为 `exists, get, coalesce, if, length, dateTime, divide, round, truncate, concat, formatDecimal, formatDateTime, fractionDigits`。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-EXPR-02-expression-language-2.0.md:305-335`；`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\expression\ExpressionFunctionCatalog.java:7-33,68-80`
- **[CODE]** parser 是 depth-32 recursive descent，优先级与规格一致；evaluator 标记为 pure deterministic，并在 common IR 上执行 lazy/Presence-aware 操作。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\expression\ExpressionParser.java:18-28,52-124,127-220`；`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\ExpressionEvaluator.java:11-20,421-453`
- **[SPEC]** 禁止 Clock、Random、IO、网络、反射、脚本和默认 Locale；runtime 的 `&&/||/if/coalesce` 短路，但所有潜在 edge 仍进入保存期 DAG/domain/budget。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:602-610`；`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-EXPR-02-expression-language-2.0.md:594-622`

### 5.5 Binding 与 slot registry

- **[SPEC]** Binding 形状是 `{bindingId,target:{elementId,slot,memberId?},source}`；target type 由 versioned slot registry 推导，不持久化 expectedType；同一 target 最多一个 binding；literal 与 binding 互斥；Binding 本身不接受 literal 或 inline expression。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:612-650`
- **[CODE]** `stele-binding-slots/2.0` 是单一封闭 target authority。当前登记：`arrayLoop.items`、`barcode.code`、`container.fill`、`element.render`、`ellipse.fill`、`image.resource`、`line.stroke`、`qrcode.payload`、`rect.fill`、`shape.fill`、`text.run.color`、`text.run.content`。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\BindingSlotRegistry.java:11-19,20-128`
- **[CODE]** authority 只有 `REQUIRED_BINDING_ONLY` 与 `REQUIRED_LITERAL_OR_BINDING`；registry 检查 kind/member、重复 binding、literal/binding 冲突和 unbind replacement。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\BindingSlotRegistry.java:148-197,220-228`

## 6. 元素、属性与布局

### 6.1 可确认的元素 vocabulary

- **[SPEC]** 历史静态元素原则是 `element = kind × geometryMode × bindable slots`；Binding 与 kind 正交，视觉效果是属性，文档只保存引擎中立 pt/deg/hex 与语义数据。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\stele-design-v1.md:197-211`
- **[SPEC]** v1 catalog 明列 text、image、rect、ellipse、line、polyline、path、shape、qrcode、barcode 的特殊属性与内容 slot。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\stele-design-v1.md:220-239`
- **[CODE]** v2 authoring kind 集为 `arrayLoop, barcode, ellipse, grid, group, image, line, path, polyline, qrcode, rect, shape, stack, templateHost, text`。
  来源：`D:\Yiwer\code\hbads-design-v2\web\src\features\design-v2-authoring\v2-authoring-codec.ts:44-60`；`D:\Yiwer\code\hbads-design-v2\web\src\lib\api\schema.d.ts:1816-1835`

可确认的公共/增量属性包括：

- id、kind、name、parentId、order；x/y/width/height/rotation；absolute/layout geometry；opacity、blendMode、visible、locked。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\stele-design-v1.md:208-218`
- fixed/hug/fill sizing、min/max、signed margin、layoutGrow/alignSelf/justifySelf。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-ELEM-01-template-element-parity.md:31-70`
- writingMode、text padding/decoration、shrink-to-fit/minFontSize；QR/Barcode 保存语义 payload/code 与确定性样式。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-ELEM-01-template-element-parity.md:72-92,107-114`
- `visible=false` 保留布局位置但不绘制；`render=false` 结构性移除节点及后代。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-ELEM-01-template-element-parity.md:94-105`

### 6.2 容器与布局

- **[SPEC]** group 是自由布局，stack 是单轴堆叠，grid 是网格；table/overlay 是 grid preset，不是独立 kind。布局是确定性纯 pass，派生绝对坐标不持久化。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\stele-design-v1.md:241-249,253-289`
- **[SPEC]** MaterializedScene 前必须冻结 `stele-design-layout/1.0`，覆盖 group/stack/grid、fixed/hug/fill、min/max/margin、text intrinsic、render/visible、rotation 和 artboard mm→pt。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-COMP-01-template-host-and-materialized-scene.md:491-523`
- **[CODE]** `AuthoritativeLocalLayout` 是 pure Java authority；普通 layout 先处理 render，再解析 fixed/hug/fill、min/max/margin；同一类还包含 ArrayLoop 两阶段 packing 与 TemplateHost layout。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\AuthoritativeLocalLayout.java:18-40,42-78,80-180,184-266`

### 6.3 属性闭包缺口

- **[CODE]** server `SteleDesignDocumentV2` 把 artboard、definitions、bindings、composition、elements 大量保留为 `StrictJson.ObjectValue`；`elements` 不是 sealed per-kind Java union。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\document\v2\SteleDesignDocumentV2.java:14-23`
- **[CODE]** 生成 OpenAPI 的 `V2Element` 只声明 `id` 与 kind enum，没有各 kind 属性。Web codec 对 element 只严格检查 id/kind、legacy authority 和 image resource，其他属性按 JSON object 保留。
  来源：`D:\Yiwer\code\hbads-design-v2\web\src\lib\api\schema.d.ts:1816-1835`；`D:\Yiwer\code\hbads-design-v2\web\src\features\design-v2-authoring\v2-authoring-codec.ts:168-186`
- **[CODE]** Web visual adapter 另行维护 common fields、container appearance 和 per-kind field list，并把未知合法字段通过 three-way merge 保留。
  来源：`D:\Yiwer\code\hbads-design-v2\web\src\features\design-v2-authoring\v2-visual-document-adapter.ts:31-45,48-170,277-388`
- **[CODE]** Java 又以独立 continuous-field registry 列举数值 path，而 Binding slot registry 只封闭可绑定子集。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\document\v2\V2ContinuousFieldRegistry.java:8-69,97-170`

**[GAP]** 因而历史仓库没有一个可指认的“完整 v2 Element StaticSchema”。它有多个局部 authority，但缺少统一 per-kind required/optional/default/unknown-field/slot/layout schema。这是最显著的黑盒来源之一。

## 7. ArrayLoop

- **[SPEC]** ArrayLoop elementId 是 loop domain identity；`arrayLoop.items` binding 在父 domain 求值；direct children 构成 item template 并处于 loop domain；nested loop 形成 lexical ancestor chain。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:1129-1156`
- **[SPEC]** object-array 作为 compiler-only context collection；scalar item 暴露 `scope.value`，index/rowIndex 均 1-based；输入数组顺序决定实例顺序。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:1131-1153`
- **[SPEC]** `missingPolicy=ERROR|EMPTY` 必填；loop 同时必须有 `templateLayout` 和 `instanceLayout`，二者各是 stack/grid packing spec。direct child 必须 layout mode、x/y=0、只允许 FIXED/HUG，不允许 FILL 和 placement hints。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:1142-1149,1157-1182`
- **[SPEC]** v2 不保存 `itemTemplate`，不使用 v1 `variantRules`，也不接受 legacy `source/itemLayout/itemTemplate/variantRules`。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:1147-1156,1183-1198`
- **[CODE]** `ArrayLoopStructuralContract` 实际检查 required items binding、missing policy、两份 packing spec、direct-child order/geometry/sizing/hints。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\ArrayLoopStructuralContract.java:10-64,67-123,126-205`
- **[CODE]** 递归 materializer 先求 `element.render`，false 时不求其他 binding/child；ArrayLoop 再求 items、按原序建立 row frame。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\RecursiveDesignMaterializer.java:27-67,88-171,213-245`
- **[CODE]** pure layout/projector 已能建立 row natural box、instance packing 和 `LOOP_INSTANCE` group。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\AuthoritativeLocalLayout.java:114-180`；`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\CompositionSceneProjector.java:187-228`
- **[GAP][CODE]** production evaluation adapter 的 `enterLoopRow` 无条件返回 `ARRAY_LOOP_NOT_COMPILED`。pure seam 存在不等于产品路径已接线。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\DesignV2SceneEvaluationKernel.java:1953-1963`

## 8. 嵌套 Template

- **[SPEC][CODE]** `TemplateUse` 形状为 `useId + hostElementId + exact {templateId,revision,contentHash} + context(current|path) + customFills[]`。不允许 current/latest child reference。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:652-677`；`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\document\v2\SteleDesignDocumentV2.java:82-95`；`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\document\v2\SteleDesignDocumentV2Codec.java:396-443`
- **[SPEC]** child 必须是 strict v2 Revision；父更新不改变旧 child ref，child 更新也不改变旧 parent Revision；fill 只能指向 exact child 的 PUBLIC custom，值在父 lexical frame 求出后传入，子不能读取 parent/root/sibling。模板图必须是 DAG。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:664-725`
- **[CODE]** closure resolver 只从 strict-decoded v2 body 发现 child ref，按 tenant/template/revision 精确读取并核 format/hash；closure graph 独立做 cycle、longest depth 和 digest。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\document\v2\ExactTemplateClosureResolver.java:16-23,39-125`；`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\document\v2\ExactTemplateClosureGraph.java:33-43,47-99`
- **[SPEC]** TemplateHost 是透明 viewport，无 authored children，与 use 一一对应；首版固定 CONTAIN/CENTER/clip，拒绝 HUG。render=false 时不选 child context、不求 fills、不建 child frame。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-COMP-01-template-host-and-materialized-scene.md:140-177`
- **[SPEC][CODE]** TemplateHost lower 为 outer host group + `INSTANCE_ROOT` group，并按 `s=min(W/CW,H/CH)`、center offset 投影 child artboard；renderer 看不到 templateHost discriminant。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-COMP-01-template-host-and-materialized-scene.md:179-248`；`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\CompositionSceneProjector.java:147-186`

## 9. Asset

- **[SPEC]** v2 IMAGE 值必须是 exact `{kind:"assetImage",entryId,entryRevision,assetId,contentHash}`；不接受 URL、普通 String 或 `asset-entry:<id>`。新增/改换 authored ref 才检查 selection assertion，运行时按 immutable assetId + contentHash 重现。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-DATA-01-definitions-bindings-and-template-inputs.md:283-298`
- **[CODE]** Web codec 对 ImageRef 精确检查五字段和 UUID/revision/hash；`image.resource` 是 registry 中的 IMAGE slot。
  来源：`D:\Yiwer\code\hbads-design-v2\web\src\features\design-v2-authoring\v2-authoring-codec.ts:513-567`；`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\BindingSlotRegistry.java:67-74`
- **[CODE]** materialization asset session 是 request-local authority，先 admit closure，再按 canonical ImageRef visit，返回 exact identity + media descriptor，最后产生 admission/visited digest evidence。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\runtime\DesignMaterializationAssetSession.java:6-47`
- **[TEST]** `render=false` image 产生 sealed scene 但不调用 asset visit；rendered structured image 以 canonical exact wire visit 一次。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\test\java\cn\hbads\stele\design\internal\evaluation\DesignV2SceneEvaluationKernelTest.java:613-657`
- **[CODE]** 可交付的 image bytes 仅接受 PNG/JPEG/WebP，并绑定 exact entry/revision/asset/hash/descriptor。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\api\DesignMaterializationAssetContent.java:6-37`

## 10. Evaluator、ResolvedDesignTree 与 MaterializedScene

### 10.1 权威流水线

- **[SPEC]** pipeline 是 exact root/input → context validation → exact closure → ResolvedDesignTree → render-first asset visit → deterministic local layout → composition → request-local MaterializedScene → observable result。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-COMP-01-template-host-and-materialized-scene.md:21-53`
- **[SPEC]** ResolvedDesignTree 不含 definitions、bindings、ValueSource、Expression、Mapping、custom fills 或 ArrayLoop；所有普通 slot 已是 concrete，loop 已展开，render=false 分支已移除。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-COMP-01-template-host-and-materialized-scene.md:55-68`
- **[CODE]** `RecursiveDesignMaterializer` 是 pure deterministic source-tree materializer；先按 authored order + id 遍历、先求 render，再求 binding、loop/host。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\RecursiveDesignMaterializer.java:27-67,88-145`

### 10.2 Renderer 边界

- **[SPEC]** MaterializedScene 不能含 templateHost、templateUse、arrayLoop、definition、binding、expression、mapping 或 customFill；scene kind 只允许 `group|text|image|rect|ellipse|line|polyline|path`。shape/QR/barcode 必须预先 lower 到这些 primitive。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-COMP-01-template-host-and-materialized-scene.md:306-340`
- **[CODE]** Java `MaterializedScene` 是 closed static model，固定 format/fingerprint/layout contract、五种 source kind、八种 node kind，并强制 kind/payload/projection 匹配。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\MaterializedScene.java:15-28,30-128,163-220`
- **[CODE]** Web decoder 同样封闭 scene envelope、八 kind、payload、数值、层级；SVG renderer 只递归消费 transform/clip/appearance/static payload。
  来源：`D:\Yiwer\code\hbads-design-v2\web\src\features\design-scene-preview\sealed-scene.ts:1-22,24-182,184-321`；`D:\Yiwer\code\hbads-design-v2\web\src\features\design-scene-preview\SealedSceneRenderer.tsx:20-46,49-188`
- **[TEST]** Web 测试覆盖八种 primitive，并明确对 `binding` kind、额外 `expression` 字段、payload mismatch 和 broken hierarchy fail closed。
  来源：`D:\Yiwer\code\hbads-design-v2\web\src\features\design-scene-preview\SealedSceneRenderer.test.tsx:19-64,97-120`

### 10.3 草稿预览与权威预览

- **[SPEC]** v2 JSON 是唯一 write authority；v1 PrototypeDocument 只是可丢弃 visual projection，merge 必须保留未知合法字段。local canvas 明确是 draft，不能求值 Definition/Binding/TemplateUse/ArrayLoop 或生成 sealed scene。权威 preview 只消费 server materialization scene。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-EDITOR-02-v2-unified-visual-authoring.md:20-28,54-79`
- **[CODE]** visual adapter 的类型注释把 projection 标为 disposable，并保存 strict v2 source/baseline 做 three-way merge；TemplateHost 在本地画布中临时表示为 group。
  来源：`D:\Yiwer\code\hbads-design-v2\web\src\features\design-v2-authoring\v2-visual-document-adapter.ts:31-45,277-388,528-544`

### 10.4 Render/Artifact 的实际范围

- **[SPEC]** Product MVP 的 Render 只登记已经成功的 materialization，不重新求值；Artifact media type 固定 `application/vnd.stele.materialized-scene+json`。PNG/PDF/外部 renderer 明确在后续。
  来源：`D:\Yiwer\code\hbads-design-v2\specs\changes\DES-PROD-02-v2-authoring-materialization-and-delivery.md:16-29,143-160`
- **[CODE]** 数据库约束也只允许 SEALED scene JSON artifact，并保存 scene/content hash 与 bytes/length。
  来源：`D:\Yiwer\code\hbads-design-v2\core\app\src\main\resources\db\migration\V014__design_render_artifacts.sql:59-97`

## 11. 实现状态、冲突与黑盒清单

### 11.1 明确实现的纵向切片

- **[CODE][TEST]** 生产 kernel 已能物化 nested group/rect/ellipse/text、direct Context bindings、Mapping operator matrix、TemplateHost custom fills 和 structured image visit。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\test\java\cn\hbads\stele\design\internal\evaluation\DesignV2SceneEvaluationKernelTest.java:380-462,464-540,613-657`
- **[CODE]** Template Revision、Instance input、Materialization scene bytes、Render/Artifact 都有 tenant-scoped append-only 持久化结构。
  来源：`D:\Yiwer\code\hbads-design-v2\core\app\src\main\resources\db\migration\V004__design_template_registry.sql:42-84`；`D:\Yiwer\code\hbads-design-v2\core\app\src\main\resources\db\migration\V012__design_instance_inputs.sql:1-68`；`D:\Yiwer\code\hbads-design-v2\core\app\src\main\resources\db\migration\V013__design_materializations.sql:132-193`

### 11.2 不能视为完整实现的部分

- **[CODE][GAP]** production kernel 的 accepted kind 只有 `group, rect, ellipse, text, image, templateHost`；其他 authoring kind 返回 `ELEMENT_KIND_NOT_MATERIALIZABLE`。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\DesignV2SceneEvaluationKernel.java:616-647`
- **[CODE][GAP]** ArrayLoop row adapter 明确返回 `ARRAY_LOOP_NOT_COMPILED`。
  来源：`D:\Yiwer\code\hbads-design-v2\core\design\src\main\java\cn\hbads\stele\design\internal\evaluation\DesignV2SceneEvaluationKernel.java:1953-1963`
- **[PLAN][GAP]** 2026-08-11 的统一编辑器计划禁止用 PC6 历史绿灯冒充 UV 完成，并把 ordinary kinds、Stack/Grid/ArrayLoop、Shape/QR/Barcode/TemplateHost lowering 列为未完成 UV3。
  来源：`D:\Yiwer\code\hbads-design-v2\plans\design-v2-unified-visual-authoring.md:3-20,199-266`
- **[PLAN][CONFLICT]** 较早 Product Closure 计划又把 PC6 空卷 v2 author→input→preview→render→download 标为全绿。该旅程只能证明所用 fixture 的纵向切片，不能证明完整 kind/属性矩阵。
  来源：`D:\Yiwer\code\hbads-design-v2\plans\design-product-closure.md:524-535`
- **[GAP]** Web/OpenAPI/server 对 element property 的 closedness 不一致；没有单一 per-kind schema 可判断每个字段的 required/default/unknown-field 规则。
- **[GAP]** “System source”只有范围级提及，没有可确认的 wire 或 evaluator。
- **[GAP]** 未发现任意外部 datasource adapter 合同。
- **[GAP]** 未发现生产 PNG/PDF renderer；可确认交付物是 sealed scene JSON，SVG 只是 Web consumer。

## 12. 可作为新规格输入的历史事实

以下是历史证据中最稳定、且不依赖具体 UI/框架的语义边界：

1. TemplateContext 与 DesignDSL 分离；Schema 以不可变精确版本绑定，输入先由 Schema authority 完整验证。
2. DSL 的动态事实集中在 root definitions/bindings/composition，不允许 element-local 第二事实源。
3. ValueSource、TypeRef、Presence、evaluation domain、slot registry 都应是封闭且版本化的合同。
4. `render` 是结构求值开关，必须先于其他 slot/children/asset；`visible` 只控制绘制。
5. Loop 需要父域 source、子 lexical domain、稳定 item identity、明确 missing policy，以及 template/instance 两阶段布局。
6. 嵌套模板只引用 exact immutable revision/hash；父子只通过 context handoff 与显式 public fills 传值，closure 必须是 DAG。
7. Asset 在 DSL 中是 immutable exact ref，不是 URL；blob 读取发生在求值后、按实际访问 capability 控制。
8. Evaluator/Layout/Composition 与 Renderer 之间应有 sealed static scene 边界；Renderer 不重新解释 DSL。

历史仓库没有可靠给出的答案是：完整 v2 element/property closed schema、任意 datasource adapter 协议、System source、全 kind 生产 materialization，以及 PNG/PDF 等最终 renderer 合同。这些应在新规格中作为独立待决语义，而不是从历史 UI 或完成清单反推。
