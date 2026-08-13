# 元素与布局容器内容模型调研：HTML/CSS、WPF 与在线设计工具

- 调研日期：2026-08-11
- 资料范围：HTML Living Standard、W3C CSS、Microsoft WPF/XAML，以及 Figma、Canva、Webflow、GrapesJS 的公开官方规范、文档、类型或源码
- 结论性质：调研快照，不是 RenderWeave 规范变更或实现授权

## 1. 结论摘要

“某容器允许哪些元素”不是一个单层问题。被调研系统至少存在下面四种彼此独立的规则：

1. **作者树/文档树准入**：数据或标记是否允许保存为父子关系，例如 HTML content model、WPF `Content`/`Children` 类型、Figma 节点重挂接规则。
2. **语义约束**：即使父节点一般可含子节点，特定组合仍可能禁止，例如 HTML 链接不能嵌套链接、Figma `ComponentSet` 只能含 `Component`、Canva Group 至少两个成员。
3. **布局参与**：节点已是子节点，不代表它参与父布局。例如绝对定位的 DOM 子节点不成为 Flex/Grid item；Figma 的“ignore auto layout”节点仍保留父子关系。
4. **设计器/API 能力**：底层模型可表达，不等于当前工具允许拖放、重挂接、解组或经某个 API 创建。Canva 的固定画布 API 与文档光标 API 就是不同能力面。

因此不存在可跨 HTML、WPF 和在线设计工具复用的扁平 `parentKind -> childKinds[]` 全表。更稳健的抽象是：**封闭节点联合 + 内容模型（槽位、顺序、基数、类别）+ 双向准入约束 + 父布局解释的 child placement + 设计器/渲染器能力配置**。

本调研对 RenderWeave 最重要的结论是：

- 作者树、派生布局树和设计器可编辑树必须分开；不能因预览“能显示”就认定作者结构合法。
- `Group`（选区/变换/坐标分组）与 `LayoutContainer`（对子项重排、测量和分配空间）应是不同概念。
- 容器规则需要表达 `empty`、单子项、多子项、具名槽位、顺序和最小/最大基数，而不只是 `allowedChildren`。
- Grid 行列、Dock、Flex grow 等信息属于“子项在特定父布局中的放置关系”；不应成为对所有节点都有效的通用属性。
- 设计器的 `canDrop` 只能是权威 validator 的预检接口，保存时仍须验证整棵树。

## 2. 范围、口径与证据边界

### 2.1 本报告所说的“允许”

| 层次 | 回答的问题 | 典型事实源 | 失败含义 |
|---|---|---|---|
| 结构准入 | 父节点是否有子内容，接受一个、多个还是具名槽位 | HTML 元素 content model、XAML content property、节点类型定义 | 文档/DSL 结构不合法 |
| 语义准入 | 这个具体父子组合、祖先关系和基数是否合法 | 元素专属限制、组件/实例规则、循环规则 | 结构可解析但语义不合法 |
| 布局参与 | 已准入的直接子节点是否成为布局 item，使用什么位置数据 | CSS formatting context、WPF Panel、Auto Layout | 结构合法，但属性可能无效或节点脱离布局流 |
| 工具能力 | 当前编辑器或公开 API 能否创建、拖入、重排或编辑 | Designer/Plugin/Apps API | 当前入口不支持；不必然说明持久化格式不支持 |
| 目标能力 | 某 renderer/export target 是否能忠实输出 | 渲染器 capability profile | 对特定目标不支持；不必然说明 DSL 本身非法 |

HTML 的完整元素索引是官方的 [WHATWG element index](https://html.spec.whatwg.org/dev/indices.html#elements-3)，其中逐项列出 Categories、Parents 和 Children；该索引明确属于便于开发者使用的汇总，元素章节中的规范性 content model 才是最终权威。WPF 是可扩展类系统，在线设计工具又只公开产品模型的子集，所以后两类不存在稳定、封闭且覆盖所有自定义扩展的全量表。

### 2.2 与 RenderWeave 当前范围的关系

[RenderWeave v1 规格](../../specs/renderweave-v1.md)明确把 Template 设计、动态值、循环容器、Workspace 和图片渲染列为非目标，并以 AC-025 禁止占位实现；[领域上下文](../../CONTEXT.md)也把 `Design Node` 定义为未来某个 DesignDSL 版本封闭定义的元素/容器，而不是任意 HTML/CSS/SVG DOM 或运行时插件 kind。因此第 8 节仅是未来 DesignDSL/spec delta 的设计输入，不建议在 v1 创建接口、表、页面或占位类型。

## 3. HTML：元素内容模型决定“可放什么”

### 3.1 HTML 不是“所有标签都可任意嵌套”

HTML Living Standard 规定每个元素都有 content model；元素内容是其 DOM children，作者只能在元素定义明确允许的位置使用 HTML 元素。[WHATWG content models](https://html.spec.whatwg.org/multipage/dom.html#content-models)还定义了 metadata、flow、sectioning、heading、phrasing、embedded、interactive、palpable 和 script-supporting 等重叠类别。类别用于复用规则，但元素仍可附加自己的顺序、祖先、后代和属性状态限制。

下表覆盖主要内容模型家族；需要逐元素核对时应直接查 [完整 Parent/Children 索引](https://html.spec.whatwg.org/dev/indices.html#elements-3)及对应元素章节。

| 内容模型家族 | 典型父元素 | 直接内容允许范围 | 关键限制/说明 |
|---|---|---|---|
| 文档骨架 | `html`、`head`、`body` | `html` 组织 `head` 与 `body`；`head` 接受 metadata；`body` 接受 flow | 标签可省略不等于槽位不存在；文档仍有结构顺序 |
| 通用 flow 容器 | `body`、`article`、`aside`、`blockquote`、`div`、`header`、`footer`、`main`、`nav`、`section`、`search`、`dialog` | 通常为 flow content | `address`、`form`、`header`、`footer` 等还带禁止后代或上下文限制，不能只看类别集合 |
| 仅 phrasing 内容 | `p`、`pre`、`h1`–`h6`、`span`、`abbr`、`b`、`code`、`em`、`strong`、`time` 等 | 文本和 phrasing content | `p` 中不能放一般块级 flow 元素；CSS 把子元素设为 `display:block` 也不会改变 HTML 合法性 |
| transparent | `a`、`ins`、`del`、`map`、`object`、`canvas` 等 | 由其所在父上下文“透传”得到 | 规则是非局部的；元素仍可能有负向限制，例如链接相关的 interactive/nested-link 约束。见 [transparent content models](https://html.spec.whatwg.org/multipage/dom.html#transparent-content-models) |
| nothing / 空内容 | `area`、`base`、`br`、`col`、`embed`、`hr`、`img`、`input`、`link`、`meta`、`source`、`track`、`wbr` 等 | 不允许元素子节点和非空白文本 | “nothing”与 HTML 语法中的 void element 是相关但不同的概念，[规范明确区分二者](https://html.spec.whatwg.org/multipage/dom.html#the-nothing-content-model) |
| 列表 | `ol`、`ul`、`menu` | `li` 与 script-supporting elements；`li` 内为 flow | 父级和 item 是不同角色，不能把任意 flow 直接放到列表根 |
| 名值列表 | `dl` | 由 `dt`/`dd` 组成的 name-value groups，也允许用 `div` 对组进行结构化包装及 script-supporting elements | 不只是无约束的 `dt`、`dd`、`div` 数组；合法性还取决于组顺序。见 [`dl` 定义](https://html.spec.whatwg.org/multipage/grouping-content.html#the-dl-element) |
| 表格 | `table` | 有序的 caption、column group、header/body/footer row groups 或直接 rows、script-supporting elements | `thead`/`tbody`/`tfoot` → `tr`；`tr` → `th`/`td`；cell → flow。详见 [`table` content model](https://html.spec.whatwg.org/multipage/tables.html#the-table-element) |
| 有序/具名槽位 | `details`、`fieldset`、`figure`、`picture` | `details`：一个 `summary` 后跟 flow；`fieldset`：可选 `legend` 后跟 flow；`figure`：可选首/尾 `figcaption` 与 flow；`picture`：`source*`、一个 `img`、script-supporting elements | 这类模型需要 sequence、choice、cardinality 和位置规则，集合白名单无法完整表达。见 [`details`](https://html.spec.whatwg.org/multipage/interactive-elements.html#the-details-element)、[`figure`](https://html.spec.whatwg.org/multipage/grouping-content.html#the-figure-element)、[`picture`](https://html.spec.whatwg.org/multipage/embedded-content.html#the-picture-element) |
| 媒体及回退 | `audio`、`video` | 条件化的 `source`、`track` 与 transparent fallback content | 是否存在 `src` 等属性会改变合法组合，应按具体元素定义校验 |
| 表单选择结构 | `select`、`optgroup`、`option`、`datalist` | option/optgroup 结构，当前标准也为 customizable select 定义条件化模型 | 内容模型会随属性状态和标准演进，不能把旧静态标签表当永久事实源。见 [`select`](https://html.spec.whatwg.org/multipage/form-elements.html#the-select-element) |
| 模板与 foreign content | `template`、SVG `svg`、MathML `math` | `template` 的模板内容存于关联 `DocumentFragment`；SVG/MathML 子结构由各自规范定义 | DOM `children` API 形状不足以描述序列化内容；见 [`template`](https://html.spec.whatwg.org/multipage/scripting.html#the-template-element) 与 element index 的外部规范链接 |

### 3.2 三个容易误判的边界

1. **可被浏览器解析不等于 conforming。** HTML parser 有错误恢复和树构建算法，源标记可能被自动闭合、重排或放入不同父节点；最终“看起来能显示”不能作为 authoring validator 的通过条件。权威行为见 [HTML tree construction](https://html.spec.whatwg.org/multipage/parsing.html#tree-construction)。
2. **直接子限制与后代限制都存在。** `table > tr > td` 是直接子层级；链接、form 等又可能限制整个后代链。因此 validator 需要 direct-child、ancestor/descendant predicate 两类规则。
3. **类别不是继承层次。** 一个元素可同时属于 flow、phrasing、interactive 等多个类别，类别成员还可能取决于属性；应建模成可组合集合与条件，而不是单继承类树。

## 4. CSS：决定 box tree 与布局参与，不授权 DOM 嵌套

CSS Display 从 document element tree 派生 formatting box tree；一个元素可能生成零个、一个或多个 box，布局还可能产生没有对应元素的 anonymous box。[CSS Display](https://www.w3.org/TR/css-display-3/)明确指出 `display` 不改变元素语义：HTML 中原本非法的父子组合，不会因为改成 Flex/Grid 就合法。

`display` 的 outer type 决定元素自身的 principal box 如何参与外部 flow；inner type 决定其后代 box 使用 flow、flow-root、table、flex、grid 或 ruby 哪种 formatting context。这说明“容器采用何种布局”与“容器接受什么文档节点”本来就是两条轴。

| CSS 布局/box 规则 | 哪些已有子内容参与 | 不参与或被派生的内容 | 对作者树准入的影响 |
|---|---|---|---|
| `flow` / `flow-root` | 子 box 进入 block-and-inline layout | 混合 inline/block 时可生成 anonymous block boxes；float/position/overflow 会改变 formatting context | 无元素 kind 白名单，不改变 HTML content model |
| `flex` / `inline-flex` | 每个 **in-flow** child 成为 flex item；直接文本序列被包成 anonymous block flex item | 纯空白文本不渲染；绝对定位 child 保持 DOM 子关系但不参与 flex layout | 不授权 `p > div` 等非法 HTML。见 [Flex Items](https://www.w3.org/TR/css-flexbox-1/#flex-items) 与 [absolutely-positioned flex children](https://www.w3.org/TR/css-flexbox-1/#abspos-items) |
| `grid` / `inline-grid` | 每个 **in-flow** child 成为 grid item；直接文本序列形成 anonymous grid item | 纯空白文本不渲染；绝对定位 child 不成为常规 grid item | 不限制标签名。见 [Grid Items](https://www.w3.org/TR/css-grid-2/#grid-items) |
| `table` 与 `table-*` | 只有具有 table internal role 的 boxes 以预期层级参与 | 错误的 box 父级会生成 anonymous table/row-group/row wrapper | 这是 box-tree fix-up，不会把错误 HTML 变成 conforming。见 [layout-internal display types](https://www.w3.org/TR/css-display-3/#layout-specific-display) |
| `ruby` 与 `ruby-*` | ruby base/text 等内部角色 | 可产生对应 anonymous/internal boxes | 同上，不决定 HTML/SVG/MathML 的内容模型 |
| `display: contents` | 元素自己的 box 消失，其 children/pseudo-elements 继续生成 box，布局上近似“提升” | DOM 元素、selector matching、事件和继承语义仍存在 | 只改 box tree，不改作者树。见 [box suppression](https://www.w3.org/TR/css-display-3/#box-generation) |
| `display: none` | 无 | 元素及后代均不生成 box/text sequence | 节点仍可存在于 DOM；“不可见”不等于“不允许” |

CSS 的启示不是给 DSL 加一个无限开放的 `display` 字符串，而是：保存的作者节点应保持稳定；布局引擎可以派生 anonymous/layout boxes，但这些派生节点不应被当作作者节点回写。

## 5. WPF/XAML：由 ContentProperty 与属性类型定义内容

### 5.1 XAML 子元素其实是属性赋值

在 WPF XAML 中，除根对象外，嵌套 object element 实质上是父对象隐式 collection property 的成员，或父对象 XAML content property 的值；`ContentPropertyAttribute` 指定省略属性元素时接收内容的属性。详见 [XAML Syntax In Detail](https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/xaml-syntax-in-detail#xaml-content-properties)。所以 XML 外观相似不代表所有 WPF 元素都有同样的 child list。

### 5.2 WPF 主要内容模型家族

[Microsoft WPF content model](https://learn.microsoft.com/en-us/dotnet/desktop/wpf/controls/wpf-content-model)给出以下基础家族：

| 家族 | 内容属性与允许值 | 典型派生类 | 直接结论 |
|---|---|---|---|
| `ContentControl` | `Content: object`，一个任意对象 | `Button`、`CheckBox`、`Label`、`ListBoxItem`、`ScrollViewer`、`ToolTip`、`UserControl`、`Window` 等 | 只能有一个 content value；若需多个视觉子项，先包入 `Panel` |
| `HeaderedContentControl` | 一个任意 `Header` + 一个任意 `Content` | `Expander`、`GroupBox`、`TabItem` | 是两个具名槽位，不是一个无差别 children 数组 |
| `ItemsControl` | `Items`/`ItemsSource`，多个任意对象 | `Menu`、`ComboBox`、`ListBox`、`ListView`、`TabControl`、`TreeView`、`StatusBar` 等 | item 可以是 string、数据对象或 UIElement；ItemTemplate/container generation 决定其视觉呈现 |
| `HeaderedItemsControl` | 任意 `Header` + 多个任意 items | `MenuItem`、`ToolBar`、`TreeViewItem` | 同时具有单 header 槽和 item collection |
| `Panel` | `Children: UIElementCollection`，多个 `UIElement` | `Canvas`、`DockPanel`、`Grid`、`StackPanel`、`WrapPanel`、`UniformGrid`、`VirtualizingStackPanel` 等 | 所有 Panel 的类型准入大体相同，排列语义由具体 Panel 决定 |
| `Decorator` | `Child: UIElement`，一个子项 | `Border`、`Viewbox`、`AdornerDecorator`、`BulletDecorator` 等 | 典型 single-child decorator；多个子项必须先包一层 Panel |
| 文本输入控件 | `TextBox.Text: string`；`RichTextBox.Document`；`PasswordBox.Password: string` | `TextBox`、`RichTextBox`、`PasswordBox` | 不是通用视觉 children 容器 |
| `TextBlock`/文档查看器 | `TextBlock.Text` 或 `Inlines`; viewers 接受 `FlowDocument`/`IDocumentPaginatorSource` | `TextBlock`、`FlowDocumentReader` 等 | 文本有自己的 Inline/Block 内容模型，不能用 UIElement children 规则代替 |

“任意 object”只说明属性类型和 XAML 可承载值，并不保证任意对象都产生可见 UI；数据对象通常需模板或由控件转换呈现。反过来，Panel 只接受 `UIElement`，不能把任意业务对象直接当 `Children`。

### 5.3 Panel 的 child kind 相近，但 placement 语义不同

[WPF Panel overview](https://learn.microsoft.com/en-us/dotnet/desktop/wpf/controls/panel)说明派生 Panel 广泛使用 attached properties：值存放在 child 上，却由 parent 定义并解释。这正是“parent-specific child layout data”的一手案例。

| Panel | Children 类型 | 布局含义 | child 侧的父布局数据 |
|---|---|---|---|
| `Canvas` | 多个 `UIElement` | 绝对坐标；可重叠；默认可绘制到父边界之外 | `Canvas.Left/Top/Right/Bottom`、`Panel.ZIndex` |
| `DockPanel` | 多个 `UIElement` | 按边停靠，最后一个 child 默认填充剩余区域 | `DockPanel.Dock`；父级 `LastChildFill` 又使顺序有语义 |
| `Grid` | 多个 `UIElement` | 行列、跨行跨列、共享坐标空间 | `Grid.Row/Column/RowSpan/ColumnSpan`；`RowDefinitions`/`ColumnDefinitions` 是具名属性集合，不是 `Children` |
| `StackPanel` | 多个 `UIElement` | 单方向顺序排列 | 主要由父级 `Orientation` 控制；child 的尺寸/对齐仍参与 measure/arrange |
| `WrapPanel` | 多个 `UIElement` | 顺序排列，空间不足后换行/列 | 父级方向和 item 尺寸共同决定换行 |
| `UniformGrid` | 多个 `UIElement` | 等尺寸 cell | 父级 rows/columns；子项按顺序占位 |
| `VirtualizingStackPanel` | 多个生成的 `UIElement` | Stack 布局并为 ItemsControl 提供虚拟化 | 类型上是 Panel，但正确用途和生命周期受 items host 语义约束 |

### 5.4 文本内容模型是另一棵严格树

[TextElement Content Model Overview](https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/textelement-content-model-overview)把可允许子内容分为专门集合：

- `InlineCollection` 允许 `Inline`，用于 `Paragraph`、`Span`、`TextBlock`。
- `BlockCollection` 允许 `Block`，用于 `FlowDocument`、`Section`、`ListItem`、`TableCell`、`Floater`、`Figure`。
- `ListItemCollection` 为 `List` 提供 item 角色；WPF 文档表格还有 Table → row group → row → cell → Block 的专门层级。
- `InlineUIContainer`/`BlockUIContainer` 是把单个 UIElement 桥接进文本流的显式容器；一般 UIElement 不能随意混进 Inline/Block 集合。

### 5.5 XAML 作者树、逻辑树、视觉树和 Designer 也不同

WPF 控件模板会在运行时产生未出现在作者逻辑树中的视觉节点；官方 [Trees in WPF](https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/trees-in-wpf)明确区分 logical tree 与 visual tree。比如 Button 的 authored `Content` 在逻辑树中，而模板生成的 Border 等在视觉树中。

Visual Studio XAML Designer 提供 Toolbox、画板和 active Panel 上的拖放/绘制操作，[Designer 文档](https://learn.microsoft.com/en-us/visualstudio/xaml-tools/working-with-elements-in-xaml-designer?view=visualstudio)描述的是编辑体验；它不能替代 XAML content property、属性类型和运行时模板规则。换言之，“Toolbox 能拖进去”是 authoring affordance，不是完整框架类型判定。

## 6. 在线设计软件与框架

### 6.1 横向结论

| 产品 | 公开模型 | 哪些节点可含 children | 主要准入方式 | 布局与分组 | 公开能力边界 |
|---|---|---|---|---|---|
| Figma | 场景/图层树 | 具有 `ChildrenMixin`/child APIs 的节点 | 容器能力 + 运行时专属限制 + 环/实例限制 | Frame 是布局层级；Group 更像图层文件夹；Auto Layout 独立 | Plugin API/REST/UI 操作面并不完全相同 |
| Canva Apps SDK | 固定画布元素及受限编辑 API | Apps SDK Group 有封闭 child union | 组成员白名单 + 最小基数 + API/设计类型能力 | Group 是坐标/变换分组，不是 Flex/Grid | SDK 只是 Canva 内部完整模型的公开子集 |
| Webflow | HTML-like 元素树 | `children` capability 为 true 的元素 | 父 capability + 专用结构规则 + HTML 语义 | CSS Flex/Grid/normal flow 与元素树分离 | Designer UI、Extension API、发布清理是不同面 |
| GrapesJS | 可扩展 Component tree | 基础 Component 默认宽松，可由类型覆盖 | child `draggable` 与 parent `droppable` 双向 predicate | 布局主要由输出 CSS 和集成配置决定 | 插件可注册类型，因而无永久封闭的全局矩阵 |

### 6.2 Figma

Figma Plugin API 只有组合了 child 能力的节点才暴露 `children`/`appendChild`。官方 [`appendChild`](https://developers.figma.com/docs/plugins/api/properties/nodes-appendchild/)列出的接收者包括 `PageNode`、`FrameNode`、`GroupNode`、`ComponentNode`、`ComponentSetNode`、`InstanceNode`、`SectionNode`、`BooleanOperationNode` 以及 Slides/Slot/TransformGroup 等产品节点；Rectangle、Text 等普通原子节点没有通用 child 容器能力。

但 `appendChild(child: SceneNode)` 的宽签名不是“任意 SceneNode 均可放入任意容器”。同一官方页面列出运行时约束：

- document root 只能含 Page，其他父节点不能含 Page；
- `ComponentSet` 只能含 `Component`；
- 不得产生 parenting/component/component-set cycle，也不得形成 component inside component；
- Instance 或 Instance 内部、内部只读节点等不能经该操作重挂接。

这说明 validator 不能从一个广义 API 参数类型推导精确内容模型。

[`FrameNode`](https://developers.figma.com/docs/plugins/api/FrameNode/)被官方定义为类似 HTML `div` 的 layout hierarchy container；`GroupNode` 更像图层文件夹。Frame 有独立尺寸、裁剪、约束和 Auto Layout，而 Group 的作用更偏向选区与变换。Figma 的 [`layoutMode`](https://developers.figma.com/docs/plugins/api/properties/nodes-layoutmode/)为 `NONE | HORIZONTAL | VERTICAL | GRID`，只支持指定容器类型；Slot 不支持 GRID，切换模式还会改变 child 位置和容器尺寸。由此应分别建模“可包含”和“如何布局”。

设计器层还会增加锁定、实例编辑和重挂接体验规则；因此 REST 响应存在 `children`、Plugin API 暴露 `appendChild`、用户 UI 实际可拖动三者不能互相替代。

### 6.3 Canva

Canva 的公开 Apps SDK 给出本调研中最清晰的封闭组规则。官方 [Grouping elements](https://www.canva.dev/docs/apps/grouping-elements/)规定 Group 可含：

- Embed
- Image
- Shape
- Text
- Video

App element、Group、Table 不能作为该 Group 的成员；Group 至少有两个元素，App 不能创建 locked group。成员按数组顺序绘制，后者在前；成员使用相对 group 的 `top`/`left`/`width`/`height` 等坐标，Text 对 width/height 有专门例外。结合 [positioning model](https://www.canva.dev/docs/apps/positioning-elements/)，Canva Group 更像由成员包围盒决定的坐标/变换容器，而不是自动重排的 Flex/Grid 容器。

工具能力还取决于文档上下文。Canva [Elements support matrix](https://www.canva.dev/docs/apps/elements/#creating-elements)显示 `addElementAtPoint` 面向绝对定位设计，支持 Group 和 Shape；`addElementAtCursor` 面向 Canva Docs 的文本流，不支持 Group/Shape。这不是 group content model 的变化，而是 API/设计类型 capability 的变化。

Canva 另有 [Design Editing API](https://www.canva.dev/docs/apps/design-editing/)，其可读写元素词汇与 `addElementAtPoint` 并不完全相同。报告中的白名单只代表公开 Apps SDK 写入面，不能宣称等于 Canva 内部全部文档元素或终端用户 UI 的全部能力。

### 6.4 Webflow

Webflow Designer API 把元素能力公开成布尔 flags。官方 [Element properties & methods](https://developers.webflow.com/designer/reference/element-properties-methods)中，`children` 表示元素能否包含 child elements，`textContent` 则是另一项能力；DivBlock、Section、Container 是 `children` 的示例。只有检查 capability 后，才应调用 `append`/`prepend`/`getChildren`。

通用 [`element.append`](https://developers.webflow.com/designer/reference/append)接受 ElementPreset、Component 或 tag string，并将常见 HTML tag 映射到 Webflow preset。其签名没有静态展开每个 parent-child pair，因此还需专用语义：

- Slot 的 [`append`](https://developers.webflow.com/designer/reference/slot-instance-element/append)只允许插入本站组件库的 component instance。
- Webflow UI 的 [Link Block](https://help.webflow.com/hc/en-us/articles/33961262636819-Link-block)可含除其他 link 外的元素，体现负向后代约束；含 link 的 Div 也不能直接转换成 Link Block。
- [Custom Element](https://help.webflow.com/hc/en-us/articles/33961250668691-Custom-element)若含非法 HTML child，画布会提示，发布时 Webflow 还会移除无效 HTML。运行/发布时修复不应被误作作者结构合法。

Webflow 的 Flex/Grid/Quick Stack 最终仍建立在 HTML/CSS 布局上：DOM-like tree 决定结构，CSS 决定直接子 box 的 layout participation。Designer UI、Designer Extension API 和导出/发布规则必须分别标注版本与能力面。

### 6.5 GrapesJS

GrapesJS 把 HTML-like 模板表示成可嵌套 Component tree，wrapper 充当根；内置 component types 覆盖 table/row/cell、link、image、video、svg、text/textnode 等，同时允许插件注册自定义类型。见官方 [Components module](https://grapesjs.com/docs/modules/Components.html)。因此它本质上是可扩展框架，不可能给出跨所有集成永久有效的封闭 child matrix。

其 child admission 值得借鉴：

- child 的 `draggable` 决定它接受哪些 destination；
- parent 的 `droppable` 决定它接受哪些 source；
- 两者都可为 Boolean、selector string 或 predicate function，基础默认值为 true。见 [Component API](https://grapesjs.com/docs/api/component.html)。
- [`Components.canMove(target, source)`](https://grapesjs.com/docs/api/components.html#canmove)同时检查两端并返回结构化 reason：source 不接受 destination，或 target 不接受 source。

GrapesJS 的布局主要来自组件生成的 HTML/CSS 及集成者开放的 Style Manager 属性，而不是核心中固定的 Flex/Grid node kind。其模型适合参考设计器预检接口，却不适合作为 RenderWeave 持久化 DSL 可任意运行时扩展的先例。

## 7. 跨系统规律

| 规律 | HTML/CSS | WPF | 在线工具 | 对 DSL 的含义 |
|---|---|---|---|---|
| 内容模型需要基数/顺序/槽位 | `details`、`picture`、`table` | Content、Header、Items、Child | Canva min 2、Webflow Slot | 不能只存 child kind set |
| 容器一般准入仍有例外 | flow category + negative descendants | 基类属性类型 + 派生控件语义 | Figma ComponentSet/Instance、Link Block | 类别规则须允许 parent-specific exception |
| 布局只解释直接子项 | Flex/Grid items | Panel measure/arrange | Figma Auto Layout、Canva group coordinates | placement 应绑定 parent-child 关系 |
| 作者树与派生树不同 | DOM vs box tree | logical vs visual tree | 组件实例/内部节点/画布投影 | 只保存作者事实，派生树不回写 |
| “组”不必产生 reflow | 普通 wrapper 可有或无 layout box | 装饰/逻辑组合与 Panel 不同 | Figma/Canva Group | Group 与 layout container 分 kind/role |
| 编辑器规则不是事实源 | 浏览器可修复非法 markup | Toolbox/Designer 只是入口 | API/UI capability 不一致 | `canDrop` 与 authoritative save validation 分层 |
| 扩展性影响闭集 | 标准按版本演进、custom elements | 自定义 Control/Panel | GrapesJS plugins | RenderWeave 应以 DSL version 冻结闭集和迁移策略 |

## 8. 对 RenderWeave Schema/validator 的建议（非规范性，面向未来 DesignDSL）

以下建议只面向未来 DesignDSL 规格设计。

### 8.1 用封闭 tagged union 定义节点，不开放任意 kind

每个 DesignDSL version 应拥有封闭 `NodeKind` 联合，并为每个 kind 注册不可变、版本化的 `NodeKindSpec`。不要允许插件在运行时扩展持久化语义；这与 [CONTEXT.md](../../CONTEXT.md) 对 `Design Node` 的边界一致，也能让编译、校验、迁移和 Renderer capability 可审计。

节点规格至少需要表达下列内容模型原语：

| 原语 | 含义 | 对应先例 |
|---|---|---|
| `empty` | 无 child | HTML void/nothing、原子图形 |
| `single` | 0..1 或恰好 1 个，带 accepted kinds/categories | WPF Decorator/ContentControl |
| `collection` | 有序 children，带 min/max、accepted sets | Panel、Figma Frame、Canva Group |
| `slots` | 多个具名槽，每槽独立基数和 accepted sets | Header + Content、组件 Slot |
| `sequence/choice` | 不同 slot/kind 的相对顺序和互斥分支 | HTML table/details/picture |
| `descendantConstraints` | 非仅直接父子的负向/循环规则 | nested link、component cycles、TemplateRef DAG |

HTML transparent model 会把合法性依赖传播到外层上下文，复杂度和错误定位都较高。除非产品需求明确，早期 DesignDSL 可不引入 transparent container；若以后引入，应给它显式、可版本化的规则，而不是“任意 child”。

### 8.2 把节点角色拆开

建议至少在领域语义上区分：

- `Root/Page/Scope`：限定顶层可出现的 kind；
- `Leaf`：无 children；
- `Group`：选区、层叠和共同变换，尺寸可由成员包围盒派生，不自动 reflow；
- `LayoutContainer`：测量直接子项并使用 absolute/stack/flex/grid 等明确布局模式；
- `SingleChildDecorator`：边框、裁剪、变换等单子项包装；
- `Definition/Instance/Slot`：若未来引入组件复用，实例默认受控，只有显式 slot 可写入。

Items/repeat/loop container 虽可参考 WPF `ItemsControl`，但 [v1 规格](../../specs/renderweave-v1.md)已明确把循环容器列为非目标；不要为其预建 v1 占位。

### 8.3 child placement 由父布局判别

WPF attached property、CSS Flex/Grid item 和 Figma Auto Layout 都说明：同一 child 在不同 parent layout 下需要不同数据。建议把 placement 视为边/关系数据，或至少由 parent layout mode 判别的 child 子对象：

| 父 layout mode | 允许的 child placement 示例 | 不应接受的字段 |
|---|---|---|
| `absolute/freeform` | x/y/width/height/rotation/z/anchors | grid row/column、flex grow |
| `stack/flex` | grow/shrink/basis、alignSelf、order、是否脱离 flow | absolute-only 坐标（除显式 out-of-flow 分支） |
| `grid` | row/column/span、self alignment、order | Canvas.Left/Dock 等其他布局字段 |
| `group` | 相对 transform/z；边界由 children 派生或显式策略 | padding/gap 等 reflow 属性，除非 Group 被明确定义成 layout container |

这能避免“任何节点都带一大包可空布局属性”，也能让 validator 把不适用字段报为结构错误，而不是静默忽略。

### 8.4 validator 分层，但只有一个语义权威

建议同一权威 rule engine 产生分层问题：

1. **结构层 ERROR**：未知 kind/version、错误内容原语、槽位缺失、基数、顺序、parent-child pair、非法 placement shape、重复 node id、深度/节点数预算。
2. **图与语义层 ERROR**：祖先/后代禁配、parenting cycle、definition/reference cycle、受控 instance 被直接修改、跨作用域引用。
3. **依赖层 ERROR/WARNING**：StaticSchema field path、AssetRef、TemplateRef 等未来依赖按领域保存策略处理；不得让确认机制绕过结构、版本、循环和安全规则。
4. **布局有效性 WARNING 或目标化 ERROR**：属性虽可表达但不生效、out-of-flow、overflow/clip 风险、某 target 不支持某 mode。严重级别应由明确的 target capability profile 决定，而非设计器猜测。
5. **编辑器能力信息**：当前 UI 不支持 drag/reparent/ungroup，可作为 capability/affordance，不应改变 DSL 的权威合法性。

问题项应有稳定 code、severity、指向 authored node/slot/placement 的 JSON Pointer、规则参数和目标 profile；不要只返回布尔值或让客户端解析自然语言。

### 8.5 `canDrop` 是预检，保存必须全树重验

可以提供无副作用的 `canMove/canDrop(source, target, slot, index, capabilityProfile)`，同时检查：

- child 是否可移动到该 parent/slot；
- parent/slot 是否接受该 child；
- 插入位置是否满足 sequence/cardinality；
- 是否形成 cycle 或突破 depth/node budgets；
- 当前设计器是否有该操作能力。

它应复用与保存相同的 rule catalog，并返回类似 GrapesJS 的结构化 reason。由于并发、祖先关系和依赖可能在预检后变化，save 仍必须对完整 DesignDSL 做权威验证；客户端预检结果不能作为豁免令牌。

### 8.6 不复制浏览器的隐式修复到事实源

CSS anonymous boxes、HTML parser recovery 和 Webflow 发布清理都能让错误结构“最终有画面”，但会模糊作者意图。RenderWeave 的事实源更适合：

- 结构错误直接拒绝保存，或在保存前执行显式、确定性、可预览的 normalization；
- normalization 结果成为用户实际提交的 DesignDSL，不在 Renderer 内偷偷改树；
- layout/visual tree 始终是可丢弃派生产物，不生成新的作者 node identity；
- EditorSession 可承载尚未保存的无效导入内容，但不能把局部画布可显示当作通过权威验证。

### 8.7 建议的验证测试矩阵

- 按 DSL version 生成全部 parent kind × child kind × slot 的表驱动正反例。
- 对 min/max、sequence/choice、首尾槽位和 negative descendant constraints 做边界测试。
- 用 property-based tests 覆盖 parenting cycle、最大深度、最大节点数、移动前后 invariants。
- 对每个 layout mode 测试 placement 判别：合法字段、无效字段、out-of-flow 与 mode 切换。
- 保证 Designer `canDrop` 与服务器 full validation 对同一快照没有“预检允许、结构层拒绝”的规则漂移；并单测预检后并发变化会在 save 被重新发现。
- 用 golden cases 比较 authored tree、derived layout tree 与 Renderer capability report，确保派生匿名节点不泄漏回 DesignDSL。

## 9. 尚需产品规格明确的决策

在形成 DesignDSL delta 前，至少需要确定：

1. 首要目标是自由二维画板、响应式网页布局，还是二者都要；这决定 absolute/group 与 flow/flex/grid 的优先级。
2. Group 是否允许嵌套、最小成员数是多少、空 Group 是删除还是保留；Figma 与 Canva 的选择不同。
3. 是否需要 definition/instance/slot；若需要，哪些 instance 区域可写、引用图如何限制。
4. 同一 DesignDSL 是否面向多个 Renderer target；若是，需要什么 capability profile 与降级策略。
5. 非结构性布局 warning 能否保存，以及权威预览与本地草稿预览怎样标示差异。
6. 导入非法树的 normalization 是拒绝、显式修复建议，还是允许保留到未保存 EditorSession。

这些是产品语义决策，不能仅从 HTML/WPF/Figma 的既有行为机械复制。

## 10. 一手来源索引

### 标准与框架

- WHATWG：[HTML content models](https://html.spec.whatwg.org/multipage/dom.html#content-models)、[完整元素 Parent/Children 索引](https://html.spec.whatwg.org/dev/indices.html#elements-3)、[HTML parser tree construction](https://html.spec.whatwg.org/multipage/parsing.html#tree-construction)
- W3C CSSWG：[CSS Display Level 3](https://www.w3.org/TR/css-display-3/)、[Flexbox Level 1](https://www.w3.org/TR/css-flexbox-1/)、[Grid Layout Level 2](https://www.w3.org/TR/css-grid-2/)
- Microsoft：[WPF content model](https://learn.microsoft.com/en-us/dotnet/desktop/wpf/controls/wpf-content-model)、[XAML syntax](https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/xaml-syntax-in-detail)、[Panel](https://learn.microsoft.com/en-us/dotnet/desktop/wpf/controls/panel)、[TextElement content model](https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/textelement-content-model-overview)、[WPF trees](https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/trees-in-wpf)

### 在线设计软件与框架

- Figma：[Node types](https://developers.figma.com/docs/plugins/api/nodes/)、[`appendChild`](https://developers.figma.com/docs/plugins/api/properties/nodes-appendchild/)、[`FrameNode`](https://developers.figma.com/docs/plugins/api/FrameNode/)、[`GroupNode`](https://developers.figma.com/docs/plugins/api/GroupNode/)、[`layoutMode`](https://developers.figma.com/docs/plugins/api/properties/nodes-layoutmode/)、[官方 Plugin typings](https://github.com/figma/plugin-typings/blob/master/plugin-api.d.ts)
- Canva：[Grouping elements](https://www.canva.dev/docs/apps/grouping-elements/)、[Elements capability matrix](https://www.canva.dev/docs/apps/elements/)、[Positioning elements](https://www.canva.dev/docs/apps/positioning-elements/)、[Design Editing API](https://www.canva.dev/docs/apps/design-editing/)
- Webflow：[Element properties](https://developers.webflow.com/designer/reference/element-properties-methods)、[`append`](https://developers.webflow.com/designer/reference/append)、[Element presets](https://developers.webflow.com/designer/reference/element-presets)、[Slot append](https://developers.webflow.com/designer/reference/slot-instance-element/append)、[Link Block](https://help.webflow.com/hc/en-us/articles/33961262636819-Link-block)、[Custom Element](https://help.webflow.com/hc/en-us/articles/33961250668691-Custom-element)
- GrapesJS：[Components module](https://grapesjs.com/docs/modules/Components.html)、[Component API](https://grapesjs.com/docs/api/component.html)、[`Components.canMove`](https://grapesjs.com/docs/api/components.html#canmove)、[base Component source](https://github.com/GrapesJS/grapesjs/blob/dev/packages/core/src/dom_components/model/Component.ts)

## 11. 可信度与限制

- HTML/CSS 部分来自 Living Standard/W3C 规范，可视为相应版本的权威规则；HTML 元素索引是官方汇总，但遇到 `*`、条件模型或冲突时以元素规范正文为准。
- WPF 部分覆盖标准基类内容模型和常见 Panels；自定义 Control、Panel、MarkupExtension、Template 可扩展对象/视觉树，不能从本表推出全部第三方控件规则。
- Figma、Canva、Webflow 属于专有产品。本报告只陈述其截至调研日公开 Plugin/Apps/Designer API 与 Help Center 能力，不把公开 SDK 子集推断为内部完整文件格式。
- GrapesJS 允许插件注册类型；本文描述核心默认机制与官方内置类型，不代表某个具体集成的最终准入规则。
- Living docs 与在线 API 会变化；若据此制定 RenderWeave 规范，应在 decision record 中冻结引用日期、目标版本和采纳/不采纳的具体语义。
