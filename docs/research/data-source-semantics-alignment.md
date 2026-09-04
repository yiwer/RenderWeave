# Template v1 数据语义对齐提案

> 日期：2026-09-02
> 状态：讨论稿；在产品词汇确认前，不修改 `CONTEXT.md`，也不继续固化数据源原型。

## 一句话结论

RenderWeave 目前没有名为 `DataSource` 的正式领域对象。Template 编辑器真正操作的是：

1. 一次渲染请求带来的实际数据 `RenderInput`；
2. 由 StaticSchema 验证后形成的 typed context；
3. 描述“一个值从哪里取得”的 `ValueSource`；
4. 模板内命名输入或计算结果 `Definition`；
5. 决定值在哪个运行时词法帧可见和计算的 invocation / loop domain。

因此，“数据源”适合保留为作者界面的统称，例如“可用数据”或“值来源”，但不应悄悄新增一个与上述对象平行的持久化模型。

`design-layout-draw` 则确实把“动态数据源”实现成模板级命名资源目录，并按 `sysSource / definedSource / mappingSource / joinSource` 分类。它的 UI 组织可以借鉴，但不能把其领域模型逐字段搬进 RenderWeave。

## 1. 先把不同层次拆开

```text
Excel / DB / HTTP
       │
       ▼
Connector（Template 边界外，v1 尚不实现）
       │ 归一化
       ▼
RenderInput.rootDocument（一次请求的一份实际值）
       │ 按 Template 固定的 exact StaticSchema 验证
       ▼
Invocation context（本次模板调用可读的 typed context）
       │ Repeat.items 取得 list
       ▼
Loop context（某个 Repeat 的某一个实际 item）
       │
       └── ValueSource / Definition / Binding 读取并产生最终属性值
```

这五层回答的是不同问题：

| 概念 | 回答的问题 | 是否是“数据源目录项” |
|---|---|---|
| Connector | 原始数据从哪个外部系统取得、如何归一化 | 否；位于 Template 外 |
| RenderInput | 这一次渲染实际传入了什么 | 否；请求级值 |
| StaticSchema | 允许有哪些字段、类型和约束 | 否；它是不可变类型合同，不包含业务值 |
| ValueSource | 某个属性或结构条件从哪里取得一个 typed value | 否；它是 DesignDSL 中的封闭引用描述 |
| Definition | 模板内有哪些具名输入或计算结果 | 可以作为 UI 中的“可用值”，但正式名称仍是 Definition |
| invocation / loop domain | 该值在哪个词法帧求值、可见和缓存 | 否；它是作用域，不是来源类别 |

权威词汇直接规定 Connector、RenderInput、StaticSchema field path 与 ValueSource 都“不叫 DataSource”：[`CONTEXT.md`](../../CONTEXT.md#L100-L113)。

还需注意一个类型边界：Schema v1 的事实类型仍是五种 scalar、reference 与 array；`color / imageRef / fontRef` 是 Template ValueType，不是可以随意放进 StaticSchema 字段树的新 Schema 类型。

## 2. 调用域是什么

### 定义

一次**具体 Template 调用**会建立一个 invocation frame。可以把 Template 类比为强类型函数，把 invocation frame 类比为这次函数调用的局部环境。

它持有：

- 该 Template 自己永久绑定的 StaticSchema 所验证出的 typed context；
- 该次调用最终生效的 CustomDefinition 值；
- 该 Template 自己的 Definitions。

根 Template 有一次根 invocation。每个实际 `TemplateUse` 都会再建立一个相互隔离的子 invocation，即使多次调用的是同一 Template revision，也不是同一个 frame。

### 能看到什么

调用域中的值可以被同一 Template 内的普通节点及其后代 Repeat 使用。它不能自动看到调用者 Template 的 RootDocument、Custom、Definition 或 loop frame。

子 Template 要取得数据，父侧必须显式完成两件事：

- 用 `ContextSelector` 选出与子 Template StaticSchema 精确匹配的 typed context；
- 用 `fills[]` 给子 Template 的 PUBLIC CustomDefinition 显式赋值。

同名不会自动继承，子 Template 也不能回读父 frame。权威定义见 [issue 06 第 32–40 行](../../.scratch/renderweave-template-v1/issues/06-render-input-and-scope.md#L32) 和 [issue 12 第 18–26、48–68 行](../../.scratch/renderweave-template-v1/issues/12-nested-template-composition.md#L18)。

### 计算频率

- CustomDefinition 永远属于 invocation domain，每次 invocation 冻结一个具体值；它不能选择 loop domain。
- MappingDefinition / ExpressionDefinition 若声明为 invocation domain，则在每次 invocation 中按需计算并最多 memoize 一次。
- “每次 invocation 一次”不等于“整次根渲染一次”：循环中十次调用同一子 Template，会产生十个子 invocation。

### 建议的作者界面名称

主界面不必使用抽象的“调用域”。建议显示为：

- `模板范围`，辅助说明“每个模板实例计算一次”；
- 高级详情或 DSL 预览再显示正式 token `invocation`。

## 3. 循环域是什么

### 定义

Repeat 先在它的**父词法域**求值 `items`，取得一份静态可证明 item 类型的 collection。然后针对每个实际 item 建立一个不可变 loop frame；这个 frame 就是该 Repeat 的循环域实例。

每个 loop frame 持有：

- 当前 typed item；
- 原输入集合中的零基 index；
- 同一 Template 内的词法祖先 frame 链；
- 一个由 authored Repeat 固定的 `loopId`，用于持久引用这个作用域。

`loopId` 标识“哪一个 Repeat 的作用域”，运行时的每个 item 则形成该作用域的一个 frame occurrence。

### 能看到什么

- Repeat 的 item subtree 可以读当前 loop frame；
- 嵌套 Repeat 可以显式读当前项或同一 Template 中的祖先项；
- 不能读 sibling、descendant 或已离开词法范围的 loop frame；
- 进入 `TemplateUse` 的子 Template 后，父 loop frame 不会被继承。

持久 DSL 必须保存明确的 `loopId`，不能保存含义随节点位置漂移的 `$current / $parent / $root`。编辑器可以显示“当前循环项”作为便捷名称，但保存前要归一到具体 loopId。见 [issue 06 第 34–40 行](../../.scratch/renderweave-template-v1/issues/06-render-input-and-scope.md#L34) 和 [issue 11 第 56–62 行](../../.scratch/renderweave-template-v1/issues/11-repeat-and-conditional-structure.md#L56)。

### 标量项与对象项

- `list<text>` 等标量列表的每个 item 会被包装成相应的只读 `system-basic-*@v1` typed context，提供 `/value` 与 `/index`。
- StaticSchema `array(items: reference)` 的对象项保持被引用业务 Schema 的字段，例如 `/name`、`/price`；不会向业务对象注入 `/index`，索引应使用 `loopIndex(loopId)`。
- 任意计算出的 JSON object 不能仅因形状相似就冒充一个 StaticSchema context。

### 计算频率

- Repeat `items`：每个实际 Repeat occurrence 在父域求值一次，不能引用它自己的 loopId。
- loop-domain Mapping / Expression：每个实际 loop frame 按需计算并最多 memoize 一次。
- 节点 Binding：在具体 consumer materialize 时求值。

### 建议的作者界面名称

主界面建议显示：

- `每个循环项`，并带具体容器名，例如“每个优惠项”；
- 来源选择器中显示“当前优惠项”“外层线路项”，而不是让作者手填 loopId；
- 高级详情再显示正式 `loop <loopId>`。

## 4. 一个完整例子

假设 `poster@v1` 的调用数据为：

```text
/title
/offers[] -> offer@v1
               /name
               /price
               /badges[] -> text
```

在根 Template invocation 中：

- 标题元素读取 `context(invocation, /title)`；
- `offersRepeat.items` 读取 `context(invocation, /offers)`。

进入 `offersRepeat` 的某个 item 后：

- 价格读取 `context(loop offersLoopId, /price)`；
- 海报标题仍可读取 `context(invocation, /title)`；
- 原序号读取 `loopIndex(offersLoopId)`；
- 内层 `badgesRepeat.items` 读取 `context(loop offersLoopId, /badges)`。

进入 `badgesRepeat` 的某个 item 后：

- 徽章文字读取 `context(loop badgesLoopId, /value)`；
- 仍可显式读取祖先优惠项的 `/name`；
- 仍可读取本 Template invocation 的 `/title`。

如果 item subtree 中放入一个 `TemplateUse(offer-card)`：

- 父侧 `ContextSelector` 可以把完整当前 `offer@v1` item 交给子 Template；
- 子 Template 随即建立自己的 invocation frame，在子内部以 `context(invocation, /price)` 读取价格；
- 子内部不能再引用父侧 `offersLoopId`。

## 5. `design-layout-draw` 的“数据源”模型

该参考项目存在两代并存痕迹：旧的元素 `dataSource` 字段，以及较新的动态源目录和属性绑定 API。

### 旧绑定

`dataSource` 是元素上的业务字段名，`DynamicAssignment` 在出图前按真实公交数据替换元素值；循环也是公交线路、站点等业务专用展开。参见其 [`docs/domain.md`](../../../design-layout-draw/svg-edit-web/docs/domain.md#L37-L45) 与 [`bus/core/index.ts`](../../../design-layout-draw/svg-edit-web/src/views/Editor/bus/core/index.ts#L211)。

### 新动态源目录

动态源以模板为范围，通过后端 API 查询或维护，并由 `dynSourceName + dynSourceType` 引用，`propertyType` 用于兼容性过滤：

| 参考项目类别 | 实际功能 | RenderWeave 最接近的概念 |
|---|---|---|
| `sysSource` | 后端按 templateId 返回的只读业务字段目录 | StaticSchema field path 的编辑器投影，但二者并不天然等同 |
| `definedSource` | 作者创建的模板级具名 typed value，带默认值，并可编辑、删除、排序、暴露 | CustomDefinition 最接近 |
| `mappingSource` | 从一个或多个源按规则产生新值，可返回 literal 或引用另一个同型源 | MappingDefinition 最接近 |
| `joinSource` | 把静态片段和动态值拼接成文本 | ExpressionDefinition 的 `concat` 或专用表达式构建器最接近 |

四类枚举和有限类型集合见 [`src/types/index.ts`](../../../design-layout-draw/svg-edit-web/src/types/index.ts#L2-L46)。自定义源有独立 CRUD 与排序 API，映射源也有独立 CRUD；映射引用具有同模板、同类型、无环等约束，见 [`mapping.types.ts`](../../../design-layout-draw/svg-edit-web/src/api/rid/template/mapping.types.ts#L8-L64)。

### 元素绑定

元素属性绑定独立按 `templateId + elementId + applyProperty` 保存，目标可以绑定上述某个 `dynSourceName + dynSourceType`。数组型属性有单独 collection binding 结构。参见 [`rightPanel/bind/index.vue`](../../../design-layout-draw/svg-edit-web/src/views/Editor/layouts/panel/rightPanel/bind/index.vue#L193-L220) 与该文件 [第 327–410 行](../../../design-layout-draw/svg-edit-web/src/views/Editor/layouts/panel/rightPanel/bind/index.vue#L327)。

### 循环

循环容器保存 `loopTemplateId`；当选择 SINGLE_VALUE 模板时，额外保存一个 `loopArraySource {dynSourceName,dynSourceType,propertyType:"text_arr"}`。当前 UI 只合并系统源和自定义源，并过滤 `text_arr`，尽管 TypeScript union 还写有 mapping/join。参见 [`wpfBox.ts`](../../../design-layout-draw/svg-edit-web/src/views/Editor/core/shapes/wpfBox.ts#L6-L20) 与 [`loopAttr.vue`](../../../design-layout-draw/svg-edit-web/src/views/Editor/layouts/panel/rightPanel/attrs/loopAttr.vue#L345-L416)。

这套循环没有 RenderWeave 的显式 invocation/loop lexical domain。它主要依赖模板类别、子模板和后端业务展开，当前项的可见性不是由持久化 loopId 作用域图表达。因此可借鉴其“分类、搜索、类型过滤、卡片管理、属性绑定入口”，不能借用其隐式作用域。

## 6. 两边的关键差异

| 问题 | `design-layout-draw` | RenderWeave Template v1 |
|---|---|---|
| 数据源是否一等资源 | 是，模板内有命名动态源目录和 CRUD | 否；正式对象是 context path、Definition 与 ValueSource |
| 实际业务数据 | 后端业务模型与 DynamicAssignment 隐式提供 | 单一 RenderInput 经 exact StaticSchema 准入 |
| 类型系统 | 少量 `text/text_arr/number/color/boolean/image` | 封闭 ValueType + exact StaticSchema 类型证明 |
| 当前循环项 | 业务展开/子模板隐式传递 | 稳定 loopId 明确标识词法域 |
| 嵌套模板 | 依赖 templateType、exposedDynSources 等业务协议 | ContextSelector + PUBLIC Custom fills，子 invocation 严格隔离 |
| 外部 DB/Excel/API | 与模板动态源概念容易混在一起 | 明确由 Template 外 Connector 负责 |

## 7. 对当前原型的校正

当前原型的主要问题不是视觉，而是把多个层次拍成了同一种“数据源卡片”：

1. 当前 `customValues` 示例是按名字组织的 object map，但正式 wire 是 `{definitionId,value}` assignment array；`imageRef` 值还必须是 `{assetId}`，不能直接是 UUID string。该样例会在建立任何 frame 前被拒绝。
2. 根样例缺失原型自己声明为 required 的 `/offers`；按 exact StaticSchema admission，不能进入成功预览。
3. 把 `/tags` 下的 `/value`、`/index` 和 `/offers` 下的 item 字段提前放进全局系统树。实际上 loop frame 只在某个具体 Repeat 的 item subtree 中存在；同一数组甚至可以被多个 Repeat 使用，每个 Repeat 有不同 loopId。
4. 允许 CustomDefinition 选择循环域。权威语义规定 Custom 永远是 invocation-domain；只有 Mapping / Expression 可以声明 invocation 或具体 loopId domain。
5. 给 Mapping / Expression 展示 PUBLIC/PRIVATE。正式 exposure 只属于 CustomDefinition；Computed Definition 的边界不是公开参数。
6. StaticSchema 字段树把 `/brand/logo`、`/brand/accentColor` 标成 `imageRef/color`，但 Schema v1 没有这两种事实类型；它们只能出现在合法的 Template typed value 路径上。
7. 把 `SYSTEM / DEFINITION` 当成 Repeat 的完整领域分类。Repeat.items 真正接受的是类型兼容且词法可达的 list-valued ValueSource，来源可以是 context、definition 或 literal；context 还可能来自祖先 loop，而不只有 invocation。
8. `context(invocation, /x)`、`loop-context(...)` 等可以作为人类可读摘要，但不是 exact DesignDSL object wire；当前 Mapping 的 `EQUALS` 也应归一为权威 token `EQ`。
9. `LIST → TEMPLATE → LAYOUT` 暗示 Repeat 必须选择循环模板。权威结构是 collection → authored item subtree → packing；`TemplateUse` 只是 item subtree 中可选的显式节点。
10. “系统数据源”容易让人误以为它连接了外部系统。当前展示的其实只是 Template exact StaticSchema 的只读字段投影，实际值来自 RenderInput。

## 8. 建议采用的统一产品语言

建议采用“RenderWeave 语义 + `design-layout-draw` 的管理体验”的混合方案，但不新增 DataSource 聚合。

### 左侧板面

标题建议为 `数据` 或 `可用数据`，包含：

1. `模板字段`：当前 Template StaticSchema 的只读树；只列 invocation context 可寻址字段。
2. `自定义输入`：CustomDefinition；始终为“模板范围”，有默认值与 PUBLIC/PRIVATE。
3. `派生值`：MappingDefinition / ExpressionDefinition；可选择“模板范围”或某个有效的“每个循环项”。
4. `当前循环项`：不是全局页签，只在当前选中节点位于 Repeat subtree 时作为上下文分组出现；可以同时显示当前和祖先 Repeat。

Connector、Connection、Feed 等外部数据接入未来放在独立产品面，不混入 Template 属性绑定。

### 属性绑定选择器

先按当前节点的词法位置和目标属性类型过滤，再分组显示：

- 当前循环项；
- 外层循环项（仅嵌套循环时）；
- 模板字段；
- 自定义输入；
- 派生值；
- 循环序号。

作者选择人类可读项，编辑器写入稳定的 `context(domain,pointer)`、`definition(definitionId)` 或 `loopIndex(loopId)`。

### Repeat.items 选择器

只展示“词法可达 + 输出为合法 list 类型”的候选：

- 模板字段中的 list；
- 祖先循环项中的 list（嵌套 Repeat 时）；
- 输出合法 scalar list 的 Custom / Mapping / Expression Definition；
- 高级模式下的固定 literal list。

不建议先让作者理解 `SYSTEM / DEFINITION` 两个技术类别再选值。可以保持二级视觉分组，但第一问题应是“循环哪个列表”，不是“它属于哪类 DataSource”。

## 9. 需要与产品方确认的三个命题

1. Template 编辑器中的“数据源”是否只是“所有可绑定 typed value”的作者界面统称，而不是要新增一个有独立 ID、存储、CRUD、生命周期的 DataSource 聚合？
2. 是否接受以下对应：系统数据 → `模板字段`；定义数据 → `自定义输入`；映射/表达式 → `派生值`；当前循环项 → 仅在具体 Repeat 词法位置出现的运行时上下文？
3. 是否接受主界面把“调用域 / 循环域”翻译成“模板范围 / 每个循环项”，正式 domain 与 loopId 只放进高级详情和持久 DSL？

若第 1 条答案是否定的，也就是确实需要 `design-layout-draw` 式一等 DataSource 资源目录，则这是 Template v1 的产品语义变更，需要另行冻结身份、存储范围、输入注入、跨 Template 传递、权限、类型兼容、删除引用与 Connector 边界，不能只靠改原型命名完成。

## 参考依据

- RenderWeave 领域词汇：[`CONTEXT.md`](../../CONTEXT.md#L100-L130)
- RenderWeave 独立研究报告：[`renderweave-data-source-semantics.md`](renderweave-data-source-semantics.md)
- 参考项目独立研究报告：[`design-layout-draw-data-source-semantics.md`](design-layout-draw-data-source-semantics.md)
- RenderInput 与 scope：[issue 06](../../.scratch/renderweave-template-v1/issues/06-render-input-and-scope.md#L14-L44)
- ValueSource / Definition：[issue 07](../../.scratch/renderweave-template-v1/issues/07-value-binding-expression-model.md#L32-L53)
- Repeat scope：[issue 11](../../.scratch/renderweave-template-v1/issues/11-repeat-and-conditional-structure.md#L37-L62)
- TemplateUse isolation：[issue 12](../../.scratch/renderweave-template-v1/issues/12-nested-template-composition.md#L43-L68)
- `design-layout-draw` 数据绑定：[`docs/domain.md`](../../../design-layout-draw/svg-edit-web/docs/domain.md#L37-L45)
- `design-layout-draw` 动态源类型：[`src/types/index.ts`](../../../design-layout-draw/svg-edit-web/src/types/index.ts#L2-L46)
- `design-layout-draw` 循环选择：[`loopAttr.vue`](../../../design-layout-draw/svg-edit-web/src/views/Editor/layouts/panel/rightPanel/attrs/loopAttr.vue#L345-L416)
