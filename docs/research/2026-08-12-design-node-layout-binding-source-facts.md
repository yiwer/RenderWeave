# Design Node、布局与 Binding 一手源码事实

- 调研日期：2026-08-12
- 只读来源：`D:\Yiwer\code\design-layout-draw`、`D:\Yiwer\code\hbads-design-v2`、`E:\rust-app\busbox-render-engine`
- 用途：RenderWeave Template v1 票据 09 的事实输入
- 性质：研究记录，不是 DesignDSL 决策或实现授权

## 1. 结论摘要

1. `design-layout-draw` 的真实在线设计/渲染模型以嵌套 `children[]` 树运行；`WpfFrame/WpfBox` 同时承载外观与 `canvas/stack/grid` 布局算法。它没有把 Group、Stack、Grid 建成三个永久 kind。
2. 该项目的真实 Text 作者数据是单一 `text` 加整框字体/排版属性，并非 `runs[]`。`runs[]` 是 `hbads-design-v2` 后来的富文本原型和 busbox 新 RenderDSL 引入的模型，不能反推为本系统产品需求。
3. 旧编辑器确实允许 Binding 覆盖内容、外观、几何和部分布局属性；但没有把 `id/tag/children` 当作 Binding target。事实更接近“Node 合同定义属性，Binding 目录选择其中哪些可绑定”，而不是“Binding 目录定义任意 JSON 字段的意义”。
4. 父布局解释子项 placement 是两个项目的共同规律：Stack/Grid 算法属于 parent，margin/alignment/grid row/column 等属于 child 在该 parent 下的参与信息。
5. `hbads-design-v2` 的后期原型改善了 kind/属性面板分类，但 persisted v2 element codec 仍以开放 `JsonRecord` 接受大部分字段；它正是“UI/codec/evaluator 多权威”的反例，不能直接复制。
6. busbox 的 `haibo.dsl/1.0` 是严格的静态 RenderDSL：Canvas 根、Stack/Grid 容器、Text/Image/Rect/Ellipse/Line/Polygon/Polyline/Path/QRCode/Barcode 叶子。它证明目标 Renderer 能力，不应反向成为 DesignDSL 作者模型。

## 2. `design-layout-draw` 的作者树与元素模型

### 2.1 根和容器

- CanvasKit 自有领域文档把 `WpfFrame` 定义为递归布局容器，并把 `WpfRect/WpfText/WpfImage/WpfEllipse/WpfPolygon/WpfQrCode/WpfBarCode` 定义为绘制元素：`canvaskit/docs/domain.md:14-26`。
- 布局领域只有三种算法：Stack、Grid、Canvas；`layoutType` 和 `layoutDirection` 位于布局元素上：`ts-layout-wpf/docs/domain.md:15-36`。
- `WpfBox` 的同一个 class 同时拥有 `layoutType/layoutDirection/spacing`、Grid tracks/placement、margin/min/max、外观、循环和嵌套模板字段：`svg-edit-web/src/views/Editor/core/shapes/wpfBox.ts:24-105,123-191,208-265`。
- `WpfFrame` 根也拥有相同布局算法选择，并额外保存 FrameScale：`svg-edit-web/src/views/Editor/core/shapes/wpfFrame.ts:22-82,99-163`。
- 真实 fixture 根为 `WpfFrame`，以 `children[]` 嵌套 `WpfBox` 与视觉元素；根和 Box 都直接保存 layout、geometry 与 appearance：`canvaskit/public/template/test/common.json:1-95`。

这说明旧模型把“节点角色”和“布局算法”组合在一个宽结构中。它可以表达现有模板，但也使不适用字段、运行时派生字段和编辑器字段混在同一 JSON object。

### 2.2 真实 fixture 统计

对 `canvaskit/public/template/test/*.json` 的 10 个 JSON 文件按 `children[]` 递归统计：

| tag | 数量 |
| --- | ---: |
| WpfFrame | 7 |
| WpfBox | 2,936 |
| WpfText | 1,681 |
| WpfEllipse | 606 |
| WpfImage | 490 |
| WpfRect | 199 |
| WpfPolygon | 3 |

直接父子边几乎全部通过 `WpfBox`：Box→Box 2,926、Box→Text 1,675、Box→Ellipse 606、Box→Image 488、Box→Rect 199、Box→Polygon 3。Frame 只直接承载少量 Box/Text/Image。

许多 leaf 也序列化了空 `children[]`：Text 1,484/1,681、Image 490/490、Ellipse 605/606、Rect 178/199。这来自宽泛 UI object 序列化，不能证明这些 leaf 在产品语义上应允许 children。

### 2.3 Text 是单值而非 runs

- 10 个 fixture 的 1,681 个 `WpfText` 全部包含 `text/fontFamily/fontSize/fill/width/height`；没有 `runs`。
- Text class 明确声明单一 `text`、fontFamily、fontSize、fontWeight、italic、letterSpacing、lineHeight、textAlign、verticalAlign、textWrap、textOverflow、padding 与 stroke：`svg-edit-web/src/views/Editor/core/shapes/wpfText.skia.ts:196-250`。
- 注册的默认重绘属性也是单值字段：`svg-edit-web/src/views/Editor/core/shapes/wpfText.skia.ts:81-106`。

因此 `runs[]` 只是一种候选富文本模型。若 RenderWeave v1 不需要同框混合样式，单一 `text` 能更贴近已有真实作者数据，并显著简化 Binding target。

## 3. `design-layout-draw` 的 Binding 事实

旧属性面板的 Bind 组件接收 `applyProperty` 和 `limitType`，数组成员另用 `arrayEffect.index`：`svg-edit-web/src/views/Editor/layouts/panel/rightPanel/bind/index.vue:138-145,243-267,323-409`。

源码调用点出现的 distinct `applyProperty` 包括：

```text
width, height,
marginTop, marginRight, marginBottom, marginLeft,
text, fontSize, letterSpacing.value,
fill, stroke, strokeWidth, url,
spacing, rowDefinitions, columnDefinitions,
rowStart, rowSpan, columnStart, columnSpan, fixedCount,
FrameScale, BoxScale, BoxScaleX, BoxScaleY,
render, embeddedTemplate
```

代表性证据：

- width/height：`attrs/baseAttr.vue:65-89`
- text/fontSize/letterSpacing：`attrs/textAttr.vue:309-413`
- render：`attrs/renderAttr.vue` 与 Bind 组件注释 `bind/index.vue:323-327`
- Grid/Stack 与 scale：`attrs/gridAttr.vue:231-580`、`attrs/layoutType.vue:149-151`、`attrs/renderAttr.vue:64-118`

这个 UI 证明旧产品期望“内容、视觉、几何、布局属性都可能被动态覆盖”。但检索不到 `id/tag/children` 作为 `applyProperty`；这些字段仍是作者树和身份结构。

## 4. `hbads-design-v2` 的改进与反例

### 4.1 后期原型模型

- 原型区分 `group/stack/grid/arrayLoop` 容器与十类内容节点，并把 Artboard 单列：`web/src/features/design-prototype/editor/editor-prototype-model.ts:640-838`。
- common base 混合保存 geometry、parentId/order、name/locked、opacity/visible/render、bindings 和 parent-layout hints：同文件 `426-480`。
- 属性面板明确按 geometry、parent layout child、parent Grid placement、own layout、content、appearance、layer、conditional render、binding、operations 分区：`property-inspector/property-inspector-model.ts:107-151`。
- Group 固定 free layout；Stack/Grid 各自拥有 layout spec；container 自身也拥有 fill/stroke/corner/padding/clip：`editor-prototype-model.ts:632-669`。
- `render=false` 从结构投影移除节点与后代，`visible=false` 只抑制绘制并保留布局：`editor-prototype-model.ts:446-451`，对应 UI 解释位于 `property-inspector/CommonProperties.tsx:425-462`。

### 4.2 runs 的来源

- Text 原型把 `runs[]` 定义为每段独立 text/font/color/stroke，并允许绑定 `runs.N.text` 与 `runs.N.color`：`editor-prototype-model.ts:484-515`、`element-contract.ts:124-181,265-294`。
- 属性面板当前主要编辑 primary run；例如文字颜色、描边等更新 run 0：`property-inspector/ElementProperties.tsx:1429-1480`。

这证明 runs 可以支持混合字体和分段 Binding，但也显示它提高了编辑器、Binding 和文本排版复杂度；它不是从旧真实 fixture 必然得出的需求。

### 4.3 属性权威仍未闭合

- BindingSlotRegistry 集中列出 arrayLoop items、render、Text run、Image、fill/stroke、QR/Barcode 等 target：`core/design/src/main/java/cn/hbads/stele/design/internal/evaluation/BindingSlotRegistry.java:13-106`。
- 但 v2 authoring codec 的 element 仍是 `JsonRecord & {id,kind}`；除 image slot 特例与少量 legacy authority 外，没有逐 kind 封闭 property shape：`web/src/features/design-v2-authoring/v2-authoring-codec.ts:16-30,168-186`。
- 这使 Prototype TS union、BindingSlotRegistry、codec、materializer 和 Renderer 各自只拥有部分属性事实。历史研究已把它认定为主要缺口：`docs/research/template-v1/historical-design-dsl-evidence.md:24-28,292-305`。

## 5. busbox RenderDSL 的边界

- `haibo.dsl/1.0` 的 Element union 是 Stack、Grid、Text、Image、Rect、Ellipse、Line、Polygon、Polyline、Path、QRCode、Barcode：`E:\rust-app\busbox-render-engine\crates\haibo-dsl\src\elements.rs:19-33`。
- Canvas 是独立唯一根，含 width/height/bleed/background/children：同文件 `270-278`。
- Stack/Grid 才允许 children；其他 Element 的 `children()` 返回空：同文件 `87-94`。
- placement 是 `ABSOLUTE/STACK/GRID` 封闭 union，表示 child 在不同 parent layout 下的参与方式：同文件 `111-199`。
- Text 使用 `runs[]` 和 request-scoped font chain：同文件 `350-435`。
- 整个 wire 对具体 struct 使用 `deny_unknown_fields`，并有独立深度、元素、文字、轨道、向量和资源预算：`crates/haibo-dsl/src/elements.rs:99-100`、`crates/haibo-dsl/src/validate.rs:14-30`。

busbox 因此是静态目标合同。DesignDSL 可以在 Evaluation 中展开 Repeat/TemplateUse、消除 Binding、展平 Group，再产生符合它的 RenderDocument；不需要让作者节点与 Render element 一一对应。

## 6. 对票据 09 的问题修正

下面是由事实触发、但仍需产品决策的问题；本记录不替用户作答：

1. Text v1 是单一 `text + style`，还是 `runs[]` 富文本？若用 runs，是否真的需要多 run 编辑和逐 run Binding？
2. Group/Stack/Grid 是永久不同 kind，还是一个 Container kind 加 `layoutMode`？旧项目选择后者，hbads 原型与 busbox 选择前者。
3. Container 是否同时拥有 fill/stroke/corner/padding/clip，还是纯结构容器需与 Rect/Decorator 组合？两个历史系统都倾向容器兼有外观。
4. Node 合同需把字段至少分为：身份/判别、作者树结构、作者 metadata、可求值属性。BindingPolicy 可以授权任何有封闭 ValueType 的属性，包括会影响布局或结构展开的属性；但不能凭空把 identity/children/bindings 变成属性。
5. 哪些编辑状态属于作者事实？两个旧编辑器都保存 name/visible/locked；但 RenderWeave 已决定排除 EditorSession，需要重新判断 locked 是否属于 revision。
6. 新模型采用嵌套 children 树后，是否仍需要显式 order/zIndex？旧 tree 依赖 child order/zIndex，hbads flat prototype 同时保存 parentId/order，曾产生双重权威风险。
