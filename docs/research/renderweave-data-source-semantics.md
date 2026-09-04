# RenderWeave 数据、值来源与词法域语义

研究日期：2026-09-02

## 结论

RenderWeave v1 没有名为 `DataSource` 的领域对象，也没有可由模板浏览或调用的“系统数据源”。外部 CSV、Excel、数据库或 HTTP 只由 Template 边界外的 `Connector` 获取并归一化；一次根求值只接收一个 `RenderInput`，其中只有一个 `rootDocument` 和可省略的根级 `customValues[]`。进入 Template 后，作者能保存的是封闭的 `ValueSource` 描述；Evaluator 实际读取的是经精确 StaticSchema 准入后的 typed context、当前 invocation 的 Custom map、同 Template 的可见 loop frame、命名 Definition，以及只允许作为 Expression input 的封闭 capability。（`CONTEXT.md:100-112`；`.scratch/renderweave-template-v1/issues/06-render-input-and-scope.md:20-26`；`.scratch/renderweave-template-v1/issues/07-value-binding-expression-model.md:34-46`）

`PUBLIC/PRIVATE` 只属于 `CustomDefinition`，控制 invocation 边界能否被根 override 或父 `TemplateUse` fill；它不属于 Mapping/Expression Definition，也不限制 Definition 在自己所属 Template 内的读取。Computed Definition 的可见范围由显式 declaration domain 与词法祖先关系决定，不由 `PUBLIC/PRIVATE` 决定。（`.scratch/renderweave-template-v1/issues/06-render-input-and-scope.md:28-32,38-40`；`.scratch/renderweave-template-v1/issues/07-value-binding-expression-model.md:34-35,48-53`）

当前 Template Designer 原型已经在部分说明文字中承认“数据源”只是 UI 分类，但 fixture 和编辑模型仍有数个会教错产品语义的偏差：`customValues` 展示了错误的对象-map wire、根样例缺失自身声明的 required `/offers`、所有 Definition 都带 visibility、Custom 可选 loop domain、Mapping 也带 visibility、StaticSchema 字段树出现了 Schema v1 不拥有的 `imageRef/color` 类型。这些应在原型成为保存或权威预览入口前修正。（详见“原型偏差”一节。）

## 权威边界

- 当前宪章规定：稳定领域语义由批准的 spec、领域文档、ADR 与当前 ticket 共同决定；Template v1 是 additive effort，不能反向改写 Schema/Inference v1。（`CONSTITUTION.md:3,7,24,48-54`）
- Implementation Authority 明确把 Template/DesignDSL/Rendering 交给该 delta、冻结 checkpoint 与 Tickets 04–19 管辖，并禁止实现以代码或测试静默选择冲突语义。（`specs/changes/20260817-template-v1-implementation-authority.md:26-31,42-49`）
- 本文以当前根 `CONTEXT.md` 和已解决的 Tickets 06、07、11 解释产品语义；Java catalog/Evaluator 代码只用于确认当前实现。Web prototype 是 browser-memory 交互草模，不是领域权威。（`CONTEXT.md:81-88,228-230`；`web/src/prototype/template-designer/AuthoringStudio.tsx:958-962,1012-1016`）

## 术语与功能

| 术语 | 精确定义与功能 | 明确不是 |
|---|---|---|
| 数据 | 泛指外部业务输入、typed context 中的值、Custom 值或 Definition 结果；当前领域模型没有一个总括性的 `Data` 聚合或目录。 | 不等于 `DataSource`，也不自动等于 RootDocument、ValueSource 或 Connector。 |
| Connector | Template 边界外的集成组件，负责读取 CSV、Excel、数据库、HTTP 等外部来源，并把结果归一化成 RenderInput。 | 不是 DesignDSL node/Definition/ValueSource；不能把凭证、SQL、文件或网络能力带入表达式。（`CONTEXT.md:100`；Ticket 06:20） |
| RootDocument | 根为 JSON object 的聚合业务文档；在每次根 Evaluation 入口按根 TemplateSnapshot 的 exact StaticSchemaRef 权威验证。 | 不是多个命名数据根；不是子 Template 可回读的全局句柄。（`CONTEXT.md:20,101-102`；Ticket 06:20,24-26,40） |
| RenderInput | 请求级 strict-JSON envelope：必填单个 `rootDocument`，可省略根级 `customValues[]`。 | 不是 Connector、ValueSource、Schema selector、持久化 Template 内容或 Workspace fixture。（`CONTEXT.md:101`；Ticket 06:20-22,42） |
| AdmittedRenderInput | RenderInput 完成 envelope 检查、exact StaticSchema 验证和 Custom winner/default 消解后的请求级不可变语义值。 | 不是 raw JSON 或通用 map；Evaluator 不能越过它重读原始 RootDocument。（`CONTEXT.md:102`；Ticket 06:24-26） |
| StaticSchema field path | 相对显式 invocation/loop domain、按 exact StaticSchemaRef 静态解析的 RFC 6901 业务字段路径。 | 不是“系统数据源”、任意 JSONPath、数组数字下标/wildcard、`$current/$parent/$root` 或 Schema 外字段。（`CONTEXT.md:110`；Ticket 06:34） |
| ValueSource | DesignDSL 对一个 typed value **来源的描述**，不是值本身。 | 不是 DataSource、RenderInput、Connector、任意 JSON path 或 IO 能力。（`CONTEXT.md:112`） |
| Definition | DesignDSL 顶层封闭 union：`custom | mapping | expression`。Custom 是 invocation 输入；Mapping/Expression 合称 Computed Definition。 | `Computed Definition` 不是 wire kind；没有 inline Mapping/Expression。（`CONTEXT.md:105,113-115`；Ticket 07:32-35） |
| Invocation frame/domain | 每次具体 Template 调用建立的不可变 frame，持有该 Template 的 exact typed context、有效 Custom map、Definition 集与自己的 declaration domain。 | 不是父子 Template 共享 map、动态作用域或持久 session。（`CONTEXT.md:107`；Ticket 06:32） |
| Loop frame/domain | 每个实际 Repeat item 在所属 Template invocation 内建立的不可变 frame，以稳定 `loopId` 定位，保存 typed item 与原集合零基 index，并链接同 Template 的词法祖先。 | 不是 authored Loop node、通用 loop object、`$parent/$root` 句柄或会被 child Template 继承的环境。（`CONTEXT.md:108-109`；Ticket 11:14-21） |

因此，“数据源”若保留为编辑器分组词，只能是 UI 上对“可创作的值来源”的非领域别名，不能出现在 DesignDSL、API、存储模型或用户心智中暗示第二个数据模型。`CONTEXT.md` 对 Connector、RenderInput、CustomDefinition、field path 和 ValueSource 都分别明确写了“不叫 DataSource”。（`CONTEXT.md:100-112`）

## RenderInput、准入与可见性

### 正确的输入形状

概念形状如下；`definitionId` 是 CustomDefinition 的 canonical UUID v4，而不是 displayName：

```json
{
  "rootDocument": { "title": "..." },
  "customValues": [
    {
      "definitionId": "4a000000-0000-4000-8000-000000000007",
      "value": { "assetId": "5a000000-0000-4000-8000-000000000001" }
    }
  ]
}
```

Envelope、assignment 都是 closed strict JSON；未知 member、重复 object key、非 object assignment、缺失 `definitionId`/`value`、非法 UUID 都在创建任何 frame 或执行 expression/capability 前失败。当前 Java parser 也只允许 envelope 的 `rootDocument/customValues` 与 assignment 的 `definitionId/value`，并要求 `customValues` 是 array。（Ticket 06:22,26；`renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/RenderInputEnvelope.java:16-27,62-139`）

RootDocument 经无损原始 JSON 边界进入 RenderWeave validator，调用方不能提交或覆盖 StaticSchemaRef。Schema 未声明字段即使按 validator 规则可存在，也被排除在 closed typed context 外，因此永远不可被 ValueSource/Expression 观察。合法可选字段缺失形成 typed `ABSENT`；显式 JSON `null` 在准入期失败；不存在的 Schema path 是 Template dependency ERROR，不是 runtime ABSENT。（Ticket 06:24-26,34；Ticket 07:14-18）

Java 中 `AdmittedRenderInput` 持有 exact StaticSchemaRef、typed RootDocument、effective Custom map 与命中 PUBLIC 的外部 overrides；`TypedObject` 以 `Optional.empty()` 表示声明可选字段 ABSENT。（`renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/AdmittedRenderInput.java:9-42`）

### Custom override 规则

- 根 `customValues[]` 只能覆盖根 Template 的 `PUBLIC CustomDefinition`，按 `definitionId` 寻址，不能定向 child Template。（Ticket 06:30）
- 重复 definitionId 是 last-wins；所有条目先过 envelope/总预算，再分组。被覆盖 loser 不做声明类型/形状校验。（Ticket 06:30）
- winner 指向 unknown 或 PRIVATE definition 时静默忽略；指向 PUBLIC 时必须 non-null、类型正确并通过值预算。（Ticket 06:30）
- invocation frame 建立时 Custom map 已完整且 concrete：PUBLIC 取 winner 或 default，PRIVATE 永远取 default；Custom 没有 ABSENT。（Ticket 06:28-30；Ticket 07:16）
- imageRef/fontRef 值是封闭 `{assetId}`，不是 UUID string；外部 PUBLIC asset override 还在输入准入期逐 atom 检查 same-scope、ACTIVE、kind 与 caller `asset.read`。（Ticket 07:29,114-115）

### 可见性矩阵

| 值/来源 | 同一 Template 内 | 根调用方 | 父 TemplateUse | Child Template |
|---|---|---|---|---|
| Schema-declared context field | 在声明 domain 及合法后代 domain 可读；optional 可为 ABSENT | 只能通过 RootDocument 提供 | 只能由显式 ContextSelector 选择 typed context/subview | 只看到 selector 交付的 child context |
| PRIVATE Custom | 本 Template 内可读 | override 被静默忽略 | 不能作为 fill target | 使用 child 自己的 default |
| PUBLIC Custom | 本 Template 内可读 | 根 `customValues[]` 可覆盖根 Custom | 可按 child definitionId 显式 fill | 使用该次 fill 或 child default |
| Mapping/Expression Definition | 由 declaration domain/lexical ancestry 决定 | 不可直接 override | 不可跨 Template 引用；只能把其求值结果作为 fill source | child 只能引用自己的 Definition |
| 父 loop item/index | 父 Template 的本 loop 与合法后代 loop 可读 | 不适用 | selector/fill 可在 use 所在父域读取 | 进入 child 后不可见 |

依据：Ticket 06:30-40；Ticket 07:48-53；`CONTEXT.md:105-110,129-130`。

## ValueSource 与 Definition

### 封闭 ValueSource union

v1 只有五类 ValueSource：

1. `literal`：携带 exact `valueType` 与 typed literal。
2. `context`：携带显式 invocation 或 `{loopId}` domain 与非空 StaticSchema field pointer。
3. `loopIndex`：携带 `loopId`，返回原输入零基、非负整数 decimal。
4. `definition`：携带同 Template 的 `definitionId`。
5. `capability`：只有 Clock UTC date/time 与 Random `[0,1)` decimal 三个 exact operation，且只可作为 Expression 的显式 input source。

（Ticket 07:36-45；`renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/DefinitionContractCatalog.java:41-50,62-69`）

Capability 不能直接成为 Binding、Mapping input/result、Custom default、TemplateUse fill、Repeat.items 或静态属性。普通 Binding 仅允许 context、loopIndex、definition；literal 应直接成为静态属性值。（Ticket 07:45；`renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/CanonicalDesignDslAuthority.java:644-717`）

### Definition 的三类职责

| kind | domain | exposure | 功能 |
|---|---|---|---|
| custom | 固定 invocation | 必填 `PUBLIC/PRIVATE` | 带 non-null typed literal default 的 invocation 输入；根 PUBLIC 可 override，child PUBLIC 可 fill。 |
| mapping | 显式 invocation 或 loopId | 无 | 单一 ValueSource 输入；cases authored-order、first-match；required otherwise；产生声明 output type。 |
| expression | 显式 invocation 或 loopId | 无 | exact Expression Profile 下，只通过显式 alias 输入读取值；无隐式环境或任意 IO。 |

Definition 可前向引用，但图必须无环、引用存在且词法合法；definitions 数组顺序不决定求值顺序。非 Custom Definition 只能读取自身 declaration domain 或同 Template 的词法祖先，也只能被自身或后代 domain 消费；不能把 loop 值逃逸到 parent/sibling/child Template。（Ticket 06:28,32；Ticket 07:34-35,48-53）

## 求值与缓存频率

| 对象 | 何时求值 | 频率/缓存边界 |
|---|---|---|
| RenderInput admission | 根 Evaluation 建 frame 前 | 每次根 Evaluation 一次；任何失败都没有 frame、capability 或 partial RenderDocument。（Ticket 06:24-26） |
| 根 Custom | input admission/根 invocation 建立时 | winner/default 一次性冻结成该 invocation 的 immutable map。（Ticket 06:30） |
| Child fills | 每个实际 TemplateUse occurrence，仍在父 lexical frame | 每次 child invocation 分别求值；ABSENT 用 child default，ERROR 终止 Evaluation。（Ticket 06:40） |
| Mapping/Expression Definition | 首次被实际 demand 时 | declaration-frame memoization：invocation-domain 每 invocation 最多一次；loop-domain 每实际 loop frame 最多一次。（Ticket 07:53） |
| Expression input | 其 alias 在实际选中表达式分支首次读取时 | 单次 Expression evaluation 内 memoize；lazy `&&`、`||`、`if`、`coalesce` 未选分支不 demand capability。（Ticket 07:69,73） |
| Binding | consumer node materialize 时 | Binding 自身按实际 consumer 求值；若 source 是 Definition，会命中上述 declaration-frame memo。Definition memo 不合并多个 Asset consumer occurrence。（Ticket 07:53,109,115） |
| Repeat.items | Repeat 自身通过 render 剪枝后，在其父 lexical domain | 每个实际 Repeat occurrence 一次；不能引用自己的 loopId。（Ticket 11:56-60,68-73） |
| Repeat packing dynamic leaves | items 非空后，在父 frame | 每个 Repeat occurrence各一次，所有 items 共用；不是 per-item。（Ticket 11:60） |
| Conditional.condition | render 剪枝后，在节点所在 lexical domain | 每个 actual occurrence 一次；Conditional 不新建 frame。（Ticket 11:61,68-73） |
| Capability state | 全请求准入成功后 | 一个 Evaluation 共享 Clock snapshot/Random nonce；每个 demanded call position 单独派生/记账，同 alias 重读与同 declaration frame Definition memo hit 不重复 demand。（`CONTEXT.md:140-145`） |

当前实现与上述边界一致：`DefinitionEngine` 分离 invocation/frame memo，并用 `definitionId + declarationFrame.memoKey()` 缓存结果；context/loopIndex 从当前 `ResolutionScope` 的 immutable loop-frame map 读取。（`renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/DefinitionEngine.java:25-29,38-67,88-159,208-260,289-297`）`Materializer` 在根创建一个 scope；每个 Repeat item追加 loop frame；每个 child invocation 重建 context/customs、清空 loop frames，并进入新的 TemplateUse runtime path。（`renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/Materializer.java:369-385,480-539,866-901,1823-1909`）

## TemplateUse 隔离

一次 `TemplateUse` 是 same-ownerScope child Template current 的显式调用，不是 frame 继承。允许的 context 只有：

- 与 child 永久 StaticSchemaRef exact 相同的 invocation context；
- 带 exact referenced StaticSchemaRef 的 reference subtree 或 loop item；
- 完整 `system-basic-*@v1` scalar item context；
- child 绑定 `system-empty@v1` 时的显式 empty context。

普通 ContextSelector 必须带 `ERROR | SKIP`；合法 optional subview 为 ABSENT 时分别失败或完全跳过调用。传入的是已经验证且携带 exact StaticSchema proof 的 immutable typed subview，不做 shape guessing，也不重新 JSON serialize/revalidate。（Ticket 06:38；`CONTEXT.md:129-130`）

fills 在父 use 所在的 invocation/loop lexical domain 求值，按 child 当前 PUBLIC definitionId 复制 concrete typed value；省略或 ABSENT 使用 child default，ERROR 失败。重复 target、unknown/PRIVATE target、静态类型不兼容是父 Template dependency ERROR。子 PRIVATE 始终使用自身 default。（Ticket 06:40）

进入 child 后：

- 新建独立 invocation frame；
- 不继承父 Custom map、Definition、RootDocument handle、loop frames 或 sibling state；
- child 只能读取 selector 交付的 context、自己的 Customs/Definitions；
- 同一根 Evaluation 可共享 exact Profile 允许的 CapabilityState，但 child Expression 仍须显式声明 capability input；
- closure 中相同 templateId 的 snapshot 可只冻结一次，但每条 use edge与实际 invocation 都独立，不能共享 invocation 结果。

（Ticket 06:38-40；`CONTEXT.md:97-98,107-108,129-130,142-144`）当前 Materializer 在 child scope construction 时明确使用 `LoopFrames.EMPTY`，并从 child defaults 新建 Custom map再应用父 fills。（`renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/Materializer.java:826-901`）

## Repeat.items 的封闭限制

### 可接受

- source kind 仅 `literal | context | definition`。（Ticket 11:44-46）
- scalar collection 必须是 `list<T>`，其中 `T` 仅 `text | decimal | date | time | boolean`。（Ticket 11:44-45）
- object item 只允许来自已验证 StaticSchema `array(items: reference)` context，并携带 exact item StaticSchemaRef。（Ticket 06:36；Ticket 11:44-48）
- scalar item 被包装为 exact `system-basic-<type>@v1` typed context，暴露 required `/index` 与 `/value`；reference item 保持其业务 StaticSchema context，绝不注入 `/index`。（Ticket 06:36；Ticket 11:47-48）
- 保持输入顺序、重复项与空数组；`loopIndex` 是原输入零基 index，不因 item subtree 被剪枝而重编号。（Ticket 11:26,77-80）

### 不可接受

- `capability` 或 `loopIndex` 直接作为 items；
- `color/imageRef/fontRef` list、nested/heterogeneous list、null item；
- literal/Definition 构造的 object list，或任意 computed JSON object collection；
- shape guessing、动态 filter/map/sort/pagination/key、自动根据 Schema 选择 Template；
- 用数组数字下标或 wildcard 代替 loop domain。

（Ticket 07:27；Ticket 11:16,20-21,44-46,129）

`items` 在 Repeat 的父 lexical domain 求值，不能引用该 Repeat 自己的 loopId；成功取得 collection 后才按 input order逐项建立 loop frame。`ABSENT` 由 required `ERROR | EMPTY` 明确处理，显式空数组总是具体零项；null、类型错误、source ERROR 和预算错误都失败。（Ticket 11:56-67）

Java catalog 将 Repeat item 标量集合硬限制为上述五种类型；admission 仅接受 literal/context/definition，literal/definition 必须可静态证明为允许的 `list<T>`，context 的 scalar/reference array 类型证明留给 StaticSchema dependency resolution。（`renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/NodeContractCatalog.java:160-170`；`renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/CanonicalDesignDslAuthority.java:1500-1581`）

## 当前 Web 原型的语义偏差与风险

这些是 prototype/in-memory model 的偏差，不表示 Java 产品路径已经违反语义。

### P0：输入 fixture 不是合法 RenderInput

1. 原型把 `customValues` 展示为以 `brandName/brandIcon/unknownKey` 为 key 的 object；权威 wire 是 assignment array，每项必须是 `{definitionId,value}`，且 definitionId 必须是 UUID v4。（`web/src/prototype/template-designer/model.ts:754-760`；`web/src/prototype/template-designer/SharedParts.tsx:664-675` 对照 Ticket 06:22,30）
2. `brandIcon` 的样例值是 UUID string；`imageRef` 的 typed value 必须是 `{assetId}` object。（`model.ts:754-758` 对照 Ticket 07:29）
3. 说明称 PRIVATE `brandName` 和 unknown key 会静默忽略，但当前示例会更早因 envelope shape/definitionId 不合法整体拒绝，根本到不了 winner visibility 消解。（`SharedParts.tsx:667-675` 对照 `RenderInputEnvelope.java:91-139`）
4. 原型的 Schema inventory 把 `/offers` 声明为 required，但 `rootDocumentSample` 完全没有 `/offers`；按 exact StaticSchema admission，该样例应整体失败而不是进入编辑预览。（`model.ts:680-690,734-751` 对照 Ticket 06:24-26）

### P1：Definition 的 exposure/domain 建模错误

共享 `DesignerDefinition` 强制所有 CUSTOM/EXPRESSION/MAPPING 都有 `visibility`，fixture 也把 Computed Definitions 标成 PUBLIC/PRIVATE；新建 Definition 表单不区分 kind，允许 Custom 选择 loop domain、允许 Expression 选择 visibility；Mapping draft 同样携带 visibility。（`web/src/prototype/template-designer/model.ts:107-115,656-665`；`web/src/prototype/template-designer/AuthoringStudio.tsx:536-553,830-850,857-889`）

权威语义是：只有 Custom 有 exposure 且 domain 固定 invocation；Mapping/Expression 有显式 invocation/loop domain但没有 exposure。若沿用当前原型模型做 serializer，会产生不存在的 wire member或非法 Custom domain。（Ticket 06:28,32；Ticket 07:34-35；`DefinitionContractCatalog.java:17-24`）

### P1：把 StaticSchema 字段错误标成 Template-only ValueType

原型把 `/brand/logo` 标成 `imageRef`、`/brand/accentColor` 标成 `color`，并称它们是 StaticSchema 字段。（`AuthoringStudio.tsx:579-586`）但 Template v1 不扩展 StaticSchema：Schema 事实类型仍是五种 scalar、reference 与 array，不增加 color/image/font；`color/imageRef/fontRef` 是 Template ValueType，可来自 typed literal/Custom/Definition 等 Template 值路径。（Ticket 07:25-30）

### P1：DataSource/“系统”命名仍制造第二套概念

原型主 tab、dialog 和 ARIA label 大量使用“数据源”“系统数据源”“映射数据源”；虽然页面注释明确说它只是 UI 分类，这一命名仍把 StaticSchema fields、Definitions 与 Mapping 混成一个像领域对象的目录。（`AuthoringStudio.tsx:103-110,728-792,1018-1057`）这与 `CONTEXT.md:100-112` 对 DataSource/system-source 的排除形成明显心智风险。建议改为“值来源”，分组改为“Schema 字段 / Definitions / Mappings”；Connector 若未来出现，应位于 RenderInput 准备流程，而不是此 DesignDSL 来源树。

### P2：展示语法与 exact wire 混杂

- 原型用 `loop-context(<loopId>, /value)`、`context(invocation, /x)` 与 `definition(id)` 作为“ValueSource 预览”，并用固定字符串 `loop tags` 表示 domain。（`AuthoringStudio.tsx:536-550,556-588,745-754,807-813`）它们不是 DesignDSL exact object wire；若只是 presentation alias，应明确标为摘要，并在保存前机械归一到 stable loopId domain。（Ticket 06:34；Ticket 07:36-44）
- 原型 Mapping operator 是 `EQUALS`，权威 token 是 `EQ`；PATTERN_MATCH 是受限 regex substring 语义，不是 glob。（`AuthoringStudio.tsx:519-524,891-899` 对照 Ticket 07:57-61）
- Mapping source picker 只从顶层 `schemaSourceTree.filter(...)` 生成 invocation 字段，但同一 editor 又允许选择 loop domain；它没有把 source domain 与 declaration domain 的 lexical proof绑定，可能构造“loop-domain Definition 读取错误 context”的草模状态。（`AuthoringStudio.tsx:631-633,868-889` 对照 Ticket 07:48-53）

### P2：Repeat 部分总体对齐，但措辞仍可能暗示 Template 自动选择

Repeat prototype source catalog 当前只列 scalar text list、exact reference array 与 list-producing Definition，并显式记录 item StaticSchemaRef；投影逻辑还会核对 child Template exact schema，整体符合 Repeat.items 的主要边界。（`web/src/prototype/template-designer/model.ts:163-175,257-303`；`web/src/prototype/template-designer/repeat-layout.ts:107-133,239-253`）

不过 Demo 标题使用 `LIST → TEMPLATE → LAYOUT` 和“标签模板”，容易让人误解每个 Repeat 必须/自动选择一个 Template。权威模型是直接设计一个 authored item subtree；TemplateUse 只是 subtree 中可选、显式的 child node，Schema 从不自动选择视觉 Template。（`AuthoringStudio.tsx:328-331,399-407` 对照 Ticket 11:50-54）原型实际的 scalar demo 使用直接 Text/Rect children，因此这里主要是文案风险，不是行为错误。（`model.ts:1939-1975`）

### 实现注释漂移

`NodeContractCatalog` 顶部注释仍说 visual/repeat/conditional/templateUse “尚未进入 catalog”，但同文件已注册全部 kind，且 `FUTURE_KINDS` 为空。这不是运行时语义错误，但会误导后续维护者，应单独修正文档注释。（`renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/NodeContractCatalog.java:7-13,16-76`）

## 原型收敛建议

1. 先修正 RenderInput fixture：单一 RootDocument + assignment-array customValues，使用真实 definitionId 与 typed asset-ref object，并确保样例满足所有 required Schema fields。
2. 把 Definition UI/state 改为 discriminated union：Custom 固定 invocation + exposure；Mapping/Expression 才显示 domain，且不显示 exposure。
3. 将“数据源/系统数据源”改为“值来源/Schema 字段”，在 UI 上把外部 Connector 与 DesignDSL ValueSource 明确分层。
4. StaticSchema field tree 只展示 Schema v1 真正拥有的 field types；color/imageRef/fontRef 通过 Template literal/Custom/Definition 展示。
5. ValueSource picker 内部保存 exact source object与 stable loopId；人类可读摘要只作为 projection。Mapping/Repeat picker必须按 source kind、type、presence 与 lexical domain过滤。
6. Repeat 文案改为“collection → item subtree → packing”；只有作者显式插入 TemplateUse 时才显示 child Template 选择。

## 主要来源定位

- `CONSTITUTION.md:3-11,23-24,46-54`
- `CONTEXT.md:20,81-85,93-115,125-149,228-230`
- `specs/changes/20260817-template-v1-implementation-authority.md:26-49`
- `.scratch/renderweave-template-v1/issues/06-render-input-and-scope.md:20-44`
- `.scratch/renderweave-template-v1/issues/07-value-binding-expression-model.md:23-53,64-78,97-116`
- `.scratch/renderweave-template-v1/issues/11-repeat-and-conditional-structure.md:33-80,118-130`
- `renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/DefinitionContractCatalog.java:7-89`
- `renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/NodeContractCatalog.java:7-13,16-76,98-101,160-170,191-208`
- `renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/CanonicalDesignDslAuthority.java:644-717,1270-1385,1500-1581`
- `renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/RenderInputEnvelope.java:16-139`
- `renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/AdmittedRenderInput.java:9-42`
- `renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/DefinitionEngine.java:25-29,38-159,208-297,497-563,740-762`
- `renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/Materializer.java:369-385,480-539,806-910,1671-1683,1823-1909`
- `web/src/prototype/template-designer/AuthoringStudio.tsx:103-110,504-633,728-905,910-1071`
- `web/src/prototype/template-designer/model.ts:107-115,163-175,257-303,656-690,734-760,1889-1996`
- `web/src/prototype/template-designer/repeat-layout.ts:107-133,239-325`
- `web/src/prototype/template-designer/SharedParts.tsx:644-680`
