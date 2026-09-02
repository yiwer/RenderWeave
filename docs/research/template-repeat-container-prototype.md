# Repeat 容器浏览器原型方案

- 调研日期：2026-09-02
- 范围：T220 浏览器内存原型；不修改生产 DesignDSL、Template API、Evaluator、RenderServer 或 Renderer
- 事实优先级：当前 `CONTEXT.md`、冻结 Template v1 Ticket 11/12、当前 Java 合同与测试；历史仓库只用于交互借鉴
- 后续决定：本文的三步语义分析继续有效；“三个始终可见的编号阶段”只是早期 UX 建议，已被最终 T220
  决定取代。最终界面只保留“循环列表”和“循环模板”两个紧凑主控，item/instance packing 与预览折叠为高级设置。

文中的源码短名分别指向以下精确路径：

- 冻结 Ticket 11：`.scratch/renderweave-template-v1/issues/11-repeat-and-conditional-structure.md`
- 冻结 Ticket 12：`.scratch/renderweave-template-v1/issues/12-nested-template-composition.md`
- 当前 T220：`.scratch/renderweave-template-v1-implementation/issues/220-redesign-template-editor-authoring-rail.md`
- `NodeContractCatalog.java`：`renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/NodeContractCatalog.java`
- `CanonicalDesignDslAuthority.java`：`renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/CanonicalDesignDslAuthority.java`
- `TemplateSemanticDependencyValidator.java`：`renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/TemplateSemanticDependencyValidator.java`
- `TemplateSemanticDependencyValidatorTest.java`：`renderweave-template/src/test/java/cn/hbads/renderweave/template/internal/TemplateSemanticDependencyValidatorTest.java`
- `Materializer.java`：`renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/Materializer.java`
- `ValueDescriptor.java` / `ArrayValue.java`：`renderweave-schema/src/main/java/cn/hbads/renderweave/schema/definition/`
- Schema v1：`specs/renderweave-v1.md`
- 当前原型 `model.ts` / `SharedParts.tsx`：`web/src/prototype/template-designer/`
- hbads 历史 `editor-prototype-model.ts` / `computeLayout.ts`：`D:/Yiwer/code/hbads-design-v2/web/src/features/design-prototype/editor/`
- design-layout-draw 历史 `loopAttr.vue`：`D:/Yiwer/code/design-layout-draw/svg-edit-web/src/views/Editor/layouts/panel/rightPanel/attrs/loopAttr.vue`

## 结论

用户提出的三步心智模型基本正确，但第二步需要换一个精确名称：

1. **选择循环集合**：先选择一个静态可证明的数组来源，并立即显示它推导出的“循环项上下文”。
2. **设计单项内容**：Repeat 的 `children[]` 才是唯一的单项子树。作者可以直接在其中设计元素，也可以显式插入一个与循环项 StaticSchema 精确兼容的 `TemplateUse`；子模板不是 Repeat 的必填字段，也不能由 Schema 自动推断。
3. **配置两级排布**：`itemLayout` 排列单个循环项内的直接子节点，`instanceLayout` 排列全部保留下来的循环项实例。两者都只使用 Repeat 专用的简化 STACK/GRID 合同。

这不是命名微调，而是避免把当前权威中明确不存在的 `itemTemplate` 再引入原型。当前领域定义明确规定：Repeat 不按 StaticSchema 自动选择视觉 Template；单项子树是有序 `children[]`，嵌套模板由子树中的显式 TemplateUse 选择（`CONTEXT.md:127-130`；`.scratch/renderweave-template-v1/issues/11-repeat-and-conditional-structure.md:46-54`）。

语义上仍可用这三步解释 Repeat，但最终 T220 不再把它们做成三个始终可见的问题卡。右侧只保留两个紧凑主控，画布继续投影单项编辑态和实例结果；这既保留语义顺序，也避免面板说明过密。

## 对原始设想的校正

| 原始设想 | 结论 | 原因 |
|---|---|---|
| Repeat 一定循环数组 | 正确 | `items` 必须静态证明为五种 scalar 的 `list<T>`，或 StaticSchema 的 `array(items: reference)`；非集合失败（冻结 Ticket 11:42-48；`CanonicalDesignDslAuthority.java:1523-1582`）。 |
| scalar 数组项对应系统预设 StaticSchema | 正确 | text/decimal/date/time/boolean 分别形成 `system-basic-*@v1` 上下文，含必填 `/index` 与 `/value`（`CONTEXT.md:17,108-110`；`specs/renderweave-v1.md:271-282`）。 |
| `Array<StaticSchema<T>>` | 概念接近，但术语需改 | RenderWeave Schema DSL 没有泛型 StaticSchema；精确形态是 `ArrayValue(items = ReferenceValue(exact StaticSchemaRef))`。数组不能再嵌套数组（`ValueDescriptor.java:3-13`；`ArrayValue.java:5-16`；`TemplateSemanticDependencyValidator.java:786-807`）。 |
| 第二步必须选择“基于项类型的模板” | 不正确 | Repeat 不保存 `itemTemplate`。单项内容是 `children[]`；TemplateUse 是可选子节点，可以有零个、一个或多个（冻结 Ticket 11:50-54；Ticket 12:36-39）。 |
| 没有数组字段的 Template 不能使用 Repeat | 不是当前权威 | `items` 还允许 scalar-list literal、声明为 scalar list 的 Definition，以及嵌套 Repeat 中来自祖先 loop domain 的合法 context source。Reference collection 只能来自带 exact item StaticSchemaRef 的 context（冻结 Ticket 11:44-48,56-62；`CanonicalDesignDslAuthority.java:1523-1573`）。若产品要限制为“仅当前 Template Schema 字段”，需要单独产品 delta。 |
| 横向、纵向、多列、多行 | 基本正确 | STACK 的 ROW/COLUMN 对应横向/纵向；GRID 只有正整数 `columns`，行数由 surviving instance 数量按 row-major 派生，不存在独立 authored rows（冻结 Ticket 11:89-102；`CanonicalDesignDslAuthority.java:1584-1610`）。 |
| 额外操作可以包含筛选、排序、分页等 | v1 不允许 | filter/sort/distinct/reverse/limit/offset/key、动态模板推断、逐项 packing、masonry 与 pagination 都被明确排除（冻结 Ticket 11:124-130）。 |

因此原型不应把“没有匹配子模板”显示成 Repeat 不可用；它只意味着“使用子模板”这一种单项内容方式不可用，作者仍可直接设计 item subtree。

## 原型交互

### 总体布局

下面保留早期三阶段草图作为语义说明，不代表最终 T220 控件布局：

```text
1 循环集合       /tags · array<text> · optional
                 → item context: system-basic-text@v1

2 单项内容       [直接设计] [使用子模板]
                 2 个 authored children · PACK

3 循环排布       单项内部: STACK / ROW / 1.5mm
                 所有实例: GRID / 3 列 / 1.5×1.5mm
```

每一阶段有 `未完成 / 可用 / 需修复` 状态。后续阶段可以浏览，但前一阶段未完成时禁用会产生错误语义的提交控件，并说明缺少什么；不要把面板整体隐藏。

Structure 树只显示一份 authored item subtree：

```text
↻ tagLoop                         ×4 · GRID 3列
  ├─ T tagChip                    PACK
  └─ ⧉ tagPillUse                PACK · system-basic-text@v1
```

实例永远不进入 `children[]`、图层树、undo history 或 authored ID namespace。历史 hbads 原型中“真实模板节点只放一次，其他实例是虚拟投影”的做法值得借用（`D:/Yiwer/code/hbads-design-v2/web/src/features/design-prototype/editor/editor-prototype-model.ts:670-706,728-748`；`computeLayout.ts:1689-1722`），但其旧 `itemTemplate` 字段和单一 `itemLayout` 语义不能复制。

### 阶段 1：选择循环集合

主入口显示“模板数据字段”，从当前 Template 永久绑定的 exact StaticSchemaRef 出发枚举可达数组路径：

- 可以沿 reference 字段继续向下枚举，例如 `/brand/badges`。
- 到达数组后停止；不允许 `/items/0/name` 或 wildcard。
- scalar array 只接受 text/decimal/date/time/boolean。
- reference array 必须携带 exact item StaticSchemaRef。
- 每个候选显示 path、是否 required、集合类型和推导出的 item context。

当前 validator 正是沿 reference 字段解析 path，最后把 scalar array 变成 `list<T>`、reference array 变成 `REFERENCE_LIST(exact ref)`；数字下标穿越数组会失败（`TemplateSemanticDependencyValidator.java:739-809`；`CONTEXT.md:110`）。

建议候选卡：

```text
/tags       可选   array<text>
循环项上下文       system-basic-text@v1
可读字段           /index decimal · /value text

/offers     必填   array<reference offer-card@v2>
循环项上下文       offer-card@v2
可读字段           /name · /price · /badge …
```

Stage 1 同时放置 `缺失集合` 策略：

- `EMPTY`：合法 optional source 在运行时为 typed ABSENT 时产生零项。
- `ERROR`：ABSENT 使本次预览失败。
- 显式空数组在两种策略下都是合法零项；null、错误类型和 source ERROR 都不能由 EMPTY 吞掉（冻结 Ticket 11:64-73；`Materializer.java:490-518`）。

“高级合法来源”可以折叠显示 literal/Definition/祖先 Loop；为快速迭代，第一版可以只实现 invocation StaticSchema 字段，但必须标注“原型当前只展示模板字段”，不能宣称这就是完整 Repeat 合同。

如果没有合格数组字段：

- 不禁用容器目录中的 Repeat。
- 插入后显示 `集合待选择` 的本地 invalid EditorSession 状态。
- 如果没有任何 literal/Definition/ancestor-loop 候选，Stage 1 显示明确空态；保存/权威预览不可用。

### 阶段 2：设计单项内容

提供两个 authoring 入口，而不是给 Repeat 增加一个持久化 `itemTemplateId`：

#### A. 直接设计

这是默认方式。作者直接向 Repeat 拖入 Text、Frame、Stack、Grid、Conditional、嵌套 Repeat 或其他 non-Canvas 节点。它们成为有序 `children[]`，直接 child 使用 PACK placement；PACK 每轴只允许 FIXED/HUG_CONTENT，不允许 FILL、margin、x/y、grid cell 或 alignSelf（`CONTEXT.md:133-135`；`NodeContractCatalog.java:240-247,262-274`）。

快速创建建议：

- scalar item：提供“添加文本项”动作，生成一个 Text，Binding source 指向当前 `loopId` 的 `/value`。
- reference item：提供“添加卡片框架”动作，生成一个 Frame，再让作者从 item schema 字段逐行绑定；这只是显式用户动作，不是 Schema 自动生成最终视觉模板。
- 所有 descendant 数据选择器都明确显示当前 loop domain 与 `loopId`；scalar 的 `/index`、`/value` 与 reference item 的业务字段不可混用。

#### B. 使用子模板

这是向 `children[]` 插入 TemplateUse 的快捷动作。候选进入本轮“可成功数据预览”列表需满足：

- same ownerScope；
- lifecycle 为 ACTIVE；
- permanent StaticSchemaRef 与 Stage 1 推导的 item context **完全相等**；
- 当前 readiness 为 READY。

Schema 失配、DELETED、INVALID 或 STALE 模板保留在“不可用”区并显示原因，不用 shape/name 推断。生产权威允许部分依赖 ERROR 经二阶段确认保存父 Template 为 INVALID；本轮没有保存流程，因此 disabled 只是原型 guard，不能解释为新的 DesignDSL hard rule。TemplateUse 的 ContextSelector 使用当前 loop domain、相同 `loopId` 和空 pointer，把完整 item context 传入 child：

```json
{
  "kind": "context",
  "domain": { "kind": "loop", "loopId": "…" },
  "pointer": "",
  "contextAbsentPolicy": "ERROR"
}
```

精确 Schema equality 与 empty selector 的验证已经由当前语义 validator 执行（`TemplateSemanticDependencyValidator.java:405-502`；其测试见 `TemplateSemanticDependencyValidatorTest.java:279-306`）。TemplateUse 进入 child 后建立隔离 invocation，child 不能回读父 loop frame（冻结 Ticket 12:18-25,56-68）。

子模板候选目录需要从当前的展示字符串升级为最小结构事实：

```ts
interface PrototypeTemplateCandidate {
  templateId: string;
  name: string;
  ownerScope: string;
  staticSchemaRef: string;
  lifecycle: 'ACTIVE' | 'DELETED';
  readiness: 'READY' | 'INVALID' | 'STALE';
}
```

早期原型的 `NestedTemplateEntry` 只有 `context` 字符串和 `READY | DRAFT`，不足以证明兼容性（`web/src/prototype/template-designer/model.ts:149-155,270-274`）。`DRAFT` 也不是当前 Template readiness 术语，现已改为 ACTIVE/DELETED 与 READY/INVALID/STALE 的组合。

Stage 1 改变 source 后，不得静默删除或替换现有 item subtree。重新推导 item context，并逐项标红失配 Binding/TemplateUse；提供显式“保留并修复”或“替换为新 starter subtree”动作。

### 阶段 3：两级排布与额外行为

属性栏分成两个并列的小卡：

1. `单项内部 itemLayout`：排列一次 item evaluation 后 surviving 的 direct children。
2. `所有实例 instanceLayout`：排列全部 surviving item container。

两者使用同一个 closed union：

```ts
type RepeatPackingSpec =
  | { kind: 'STACK'; direction: 'ROW' | 'COLUMN'; gapMm: number }
  | { kind: 'GRID'; columns: number; columnGapMm: number; rowGapMm: number };
```

交互预设：

- 横向：STACK / ROW
- 纵向：STACK / COLUMN
- 多列：GRID / columns=N
- 多行：不是新模式；显示 `派生行数 = ceil(surviving / columns)`

这里不能复用普通 Grid 的 `12, auto, 1*, 2*` 轨道编辑器。Repeat GRID 只有正整数 columns、row-major、各列/行取该轨最大 item 尺寸、START 对齐、不拉伸；也没有 ordinary Stack/Grid 的 justify、align、margin、FILL、manual cell、track 或 responsive wrap（冻结 Ticket 11:89-102；`NodeContractCatalog.java:168-171`）。

“额外行为”第一版只放与权威一致的内容：

- `absentPolicy`（放 Stage 1）；
- 当前样本项选择、样本数量与预览截断提示（editor-only，不进入 DesignDSL）；
- 原输入 index 与 surviving ordinal 的对照诊断；
- v1 不支持列表：筛选、排序、去重、反转、分页、key、逐项布局覆盖。

不要复制 design-layout-draw 的筛选、去重、squeeze overflow 和 `loopTemplateId` 合同。它的分组下拉、搜索、loading/empty 状态可借鉴，但它先选模板、再按模板类型开放 `text_arr` 数据源，并直接持久化 `loopTemplateId/loopArraySource`（`D:/Yiwer/code/design-layout-draw/svg-edit-web/src/views/Editor/layouts/panel/rightPanel/attrs/loopAttr.vue:120-165,345-417,472-626`），与 RenderWeave 当前 Repeat/TemplateUse 权威不同。

## 浏览器内存模型

不要把原型 UI 步骤字段伪装成 DesignDSL。建议分成“author facts”“catalog proof”“editor projection”三层：

```ts
interface PrototypeRepeatAuthorFacts {
  nodeId: string;
  loopId: string;
  items: PrototypeRepeatSource | null;
  absentPolicy: 'ERROR' | 'EMPTY';
  itemLayout: RepeatPackingSpec;
  instanceLayout: RepeatPackingSpec;
  children: DesignerNode[];
}

interface PrototypeRepeatSourceProof {
  sourceId: string;
  displayPath: string;
  sourceType: 'SCALAR_LIST' | 'REFERENCE_LIST';
  optional: boolean;
  itemValueType?: 'text' | 'decimal' | 'date' | 'time' | 'boolean';
  itemStaticSchemaRef: string;
  status: 'PROVEN' | 'MISSING' | 'TYPE_MISMATCH';
}

interface PrototypeRepeatEditorProjection {
  phase: 'SOURCE_REQUIRED' | 'CONTENT_REQUIRED' | 'NEEDS_REPAIR' | 'READY';
  activeSampleIndex: number;
  previewLimit: number;
  outcome: 'PROJECTED' | 'EMPTY' | 'ABSENT_ERROR' | 'INVALID';
}
```

`itemStaticSchemaRef` 是由 `items` 静态证明派生的只读事实，绝不单独 authored。`phase`、sample index、preview limit 与 outcome 只属于 EditorSession。Repeat 的 author facts 仍精确对应 `loopId/items/absentPolicy/itemLayout/instanceLayout/children`（`NodeContractCatalog.java:98-101`）。

当前 T220 fixture 已有一个正确的 scalar Repeat 起点：`/tags : list<text>` 推导 `system-basic-text@v1`，并有两个 PACK children、独立 itemLayout 与 instanceLayout（`web/src/prototype/template-designer/model.ts:432-486`）。需要替换的是当前新增 Repeat 的简化默认值：它只创建 `items` 与部分 itemLayout，没有 instanceLayout，也没有来源证明或配置阶段（同文件 `1188-1194`）。

## 实时投影算法

第一轮只做 browser-only、fixed-PACK 子集，避免越过 T220 已明确延期的通用 HUG intrinsic measurement。

1. **证明 source**：从 in-memory StaticSchema catalog 解析选中字段，得到 scalar-list 或 reference-list item context；失败则不生成实例。
2. **解析样本**：从浏览器内存 sample RootDocument 取得数组。ABSENT 按 policy 变成 EMPTY 或 error；非数组/error 一律失败。
3. **逐项建立 frame**：按原输入顺序建立 `{loopId, inputIndex, typed item}`。scalar 的可见 context 为 `{index,value}`；reference item 不注入 index。
4. **投影单项子树**：复用同一 authored `children[]`，只实现当前 demo 所需的 loop-field text 替换与 TemplateUse thumbnail/card 投影。不要复制节点或生成 authored UUID。
5. **itemLayout**：对 surviving direct children 使用 PACK 的 fixed authored boxes，按 STACK/GRID 计算每个 item natural box。
6. **survival**：若一个 item 的所有 direct children都被结构剪枝，则该 item 不进入 instanceLayout，但它的 `inputIndex` 不重编号。
7. **instanceLayout**：按 surviving 顺序排布 item boxes。GRID 使用 `effectiveColumns=min(columns,n)`、row-major、列宽/行高最大值和 gaps。
8. **外层 box**：第一轮继续使用当前 T220 DraftBox 作为 FIXED host，内容锚定左上；空间不足显示 overflow，不能缩放 item 或偷偷改 columns。权威 HUG 的 natural box 应来自 instance packing，但要等通用 intrinsic seam 明确后再加入原型。
9. **画布投影**：生成 `VirtualRepeatOccurrence {repeatNodeId,inputIndex,box,childBoxes}`，只供绘制和 hit-test。点击虚拟实例选中 Repeat，并更新 editor-only `activeSampleIndex`；Structure 不新增行。

生产 Materializer 也是先解析 items、为每个 item 建 Loop frame并展开同一 child list，再生成 item packing container，最后做 instance packing；空集合或零 surviving instance 完全移除 Repeat（`Materializer.java:475-590`）。原型算法应保持这条顺序，但不声称执行完整 Binding/Conditional/TemplateUse/Evaluator 语义。

画布需要区分两种视觉层：

- **数据预览态**：绘制全部虚拟实例，选中某实例只改变样本焦点。
- **单项编辑态**：仅突出一份 authored subtree，并以浅色 ghost 显示其他实例；顶部 breadcrumb 显示 `tagLoop / item[2] / system-basic-text@v1`。

零项时 authored Repeat 仍可在编辑器被选中，但只能用独立 editor chrome 显示“0 项”；真实 output projection 不保留空白 Repeat box。该区别遵循冻结 Ticket 11 的零项语义（`:75-80`），也避免把编辑器可选框冒充渲染内容。

## 建议的三个 Demo

### Demo A：scalar 标签

- Root Template Schema：`campaign-card@v3`
- source：optional `/tags : array<text>`
- item context：`system-basic-text@v1`
- item subtree：Text 绑定 loop `/value`；可切换为显式 `标签胶囊` TemplateUse
- itemLayout：STACK ROW，gap 1mm
- instanceLayout：GRID 3 columns，column/row gap 1.5mm
- samples：4 个标签、空数组、ABSENT

此 Demo 可以直接演进当前 fixture；当前 `schemaFields` 已有 `/tags array[text]`（`model.ts:598-605`），`tagLoop` 也已表达正确的双层 packing。

### Demo B：reference 商品卡

- 给 browser-only catalog 增加 `campaign-card@v3./offers : array<reference offer-card@v2>`
- item context：exact `offer-card@v2`
- item subtree：一个 `优惠卡` TemplateUse，candidate 的 permanent schema 必须 exact match
- itemLayout：STACK ROW（单 child 时明确显示“当前无可见差异”）
- instanceLayout：先 STACK COLUMN，再切 GRID 2 columns
- samples：3 个不同宽高卡片，用于证明 Grid 的列宽/行高 max 与 authored order

### Demo C：无兼容模板与非法状态

- source：`/tags array<text>`，但 catalog 暂时移除全部 `system-basic-text@v1` child templates
- Stage 2 清楚显示：`没有兼容子模板；仍可直接设计单项`
- 再把 source 切到 `/offers`，保留原 scalar subtree，展示 `NEEDS_REPAIR`，不自动清除
- 切换 EMPTY/ERROR 并使用 ABSENT sample，展示零项与 preview error 的不同

Demo 数据、StaticSchema catalog 和 child Template catalog 全部是 browser memory fixture，刷新消失；T220 已明确禁止调用 Template API 或 RenderServer（`.scratch/renderweave-template-v1-implementation/issues/220-redesign-template-editor-authoring-rail.md:9-18,111`）。

## 非法与空状态

| 状态 | 原型行为 | 权威含义 |
|---|---|---|
| 未选择 source | Stage 1 blocking，仍可保留本地节点 | EditorSession 可暂存 invalid；缺失 required wire 不能保存。 |
| path 不存在 | 保留选择并标红，不猜路径 | dependency ERROR，可确认保存为 INVALID；不存在 path 不是 runtime ABSENT（`CONTEXT.md:104,110`）。 |
| context source 指向 scalar/reference 而非 array | `TYPE_MISMATCH`，零实例 | StaticSchema 驱动的 `TEMPLATE_REPEAT_ITEMS_TYPE_MISMATCH` 是 dependency ERROR（`TemplateSemanticDependencyValidator.java:223-236`）。literal/Definition 的非 list 或非法 item type 则在 canonical admission 阶段 hard fail（`CanonicalDesignDslAuthority.java:1538-1560`）。 |
| scalar array | 推导 exact `system-basic-*@v1` | `/index` 与 `/value` 可供 descendant 使用；当前测试覆盖 decimal（`TemplateSemanticDependencyValidatorTest.java:167-190`）。 |
| reference array | 推导 item 的 exact StaticSchemaRef | 业务 item 不注入 index；index 只能用 `loopIndex`。 |
| literal/Definition 产生 reference list | 不提供候选 | v1 reference collection 只允许从 verified context 取得（冻结 Ticket 11:44-48）。 |
| 空 `children[]` | Stage 2 blocking；画布显示 editor placeholder | EditorSession 可暂时为空，服务端不能保存（冻结 Ticket 11:37-40）。 |
| 没有 compatible child Template | “使用子模板”空态；“直接设计”仍可用 | Repeat 不要求 TemplateUse。 |
| child Template Schema 失配 | candidate disabled；现有 use 标为需修复 | exact mismatch 是 dependency ERROR（`TemplateSemanticDependencyValidator.java:485-500`）。 |
| source 从 scalar 改为 reference | 保留 subtree并计算 impact | 不自动改 Binding、TemplateUse 或 loopId。 |
| optional source ABSENT + EMPTY | output 零 occurrence，editor placeholder保留 | 成功零项，不 warning。 |
| optional source ABSENT + ERROR | preview error，零 partial output | Evaluation fail。 |
| 显式空数组 | output 零 occurrence | 合法具体值，与 ABSENT 不同。 |
| 某 item 全部 children 被剪枝 | 不参加 instance packing；inputIndex 保留 | surviving ordinal 可以不同于 inputIndex（冻结 Ticket 11:75-80）。 |
| 全部 item 不 survive | output 中移除整个 Repeat | 不保留 FIXED/FILL 空白 box。 |
| HUG child intrinsic | 第一轮显示“尚未模拟”，Demo 固定 PACK | T220 的通用 intrinsic HUG 仍延期；不要从 DOM scroll/font metrics 猜测（T220:79-80,205-206）。 |

## 实现顺序与验收

建议作为四个很小的 browser-only tracer bullets 实现：

1. **来源证明**：in-memory schema catalog、array candidate enumeration、item context chip、EMPTY/ERROR 与空态。
2. **单项内容**：direct subtree / compatible TemplateUse 两入口、exact schema filtering、source change impact。
3. **投影内核**：pure `projectRepeatLayout(...)`，fixed PACK、两级 STACK/GRID、虚拟 occurrence、不改 tree/boxes author facts。
4. **画布与诊断**：三个 Demo、数据预览/单项编辑态、Structure `×N`、inputIndex vs surviving ordinal。

最低纯函数测试：

- scalar/ref array 分别推导 system-basic/exact business Schema；non-array fail closed；
- source 枚举可穿越 reference，但不能穿越 array index/wildcard；
- Template candidate 必须 exact StaticSchemaRef，相同字段 shape 不算兼容；
- itemLayout 与 instanceLayout 独立切换，修改其一不改另一份 author facts；
- STACK ROW/COLUMN、GRID columns/row-major、不同 item 尺寸、零项、ABSENT 两策略；
- virtual occurrences 不进入 `tree`、`boxes` authored state、selection IDs 或 undo snapshot；
- source change 不删除 subtree，失配进入 NEEDS_REPAIR；
- Structure 始终只出现一份 item subtree。

最低浏览器检查：

- 完成三阶段后 Demo A 实时从横排切纵排、再切三列 Grid；
- 切换 `/tags` 与 `/offers` 时 item context、compatible templates 和修复状态同步更新；
- 点击第 3 个虚拟 occurrence 只改变 active sample，不产生新图层；
- 空数组与 ABSENT+EMPTY 显示 editor-only 空态，ABSENT+ERROR 显示 preview error；
- 页面不发出 Template/RenderServer 请求，控制台无错误。

## 证据边界

当前 T220 已有静态 Repeat 说明，能展示 scalar source、两级布局和“子节点就是循环内容”，但还没有来源证明、兼容模板选择或实例实时布局（`web/src/prototype/template-designer/SharedParts.tsx:1594-1608`）。本方案是在该原型上补齐交互，不改变其 browser-memory-only 边界。

hbads-design-v2 的旧 ArrayLoop 可以借用三项体验：真实 item subtree 只编辑一次、虚拟实例不进历史/ID、虚拟 hit 映射回 authored template + row index（`editor-prototype-model.ts:670-706,728-748`）。它不能作为语义源，因为它持久化 `source/itemTemplate/itemLayout/variantRules`，并用同一 `itemLayout` 同时安排模板与实例（`editor-prototype-model.ts:690-700`；`computeLayout.ts:1603-1677`），与当前 RenderWeave 的 `children[] + itemLayout + instanceLayout` 合同冲突。

生产 Materializer 已实现 Repeat 展开顺序，但本轮明确不接它。原型只验证用户心智、信息架构和确定性 browser projection，不据此宣称 Authoritative Preview 或 RenderServer 行为已验证。
