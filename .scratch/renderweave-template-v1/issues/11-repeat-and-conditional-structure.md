# 定义循环与条件结构语义

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 06, 07, 09, 10

## Question

Repeat/Loop 如何绑定集合、建立 item/index/key 作用域、复制直接子树、进行实例/模板两级布局并限制数量；空集、缺失、单项、嵌套循环、条件显示与条件不渲染如何区分和失败？

## Inherited constraints

- 每个实际 item 创建由单 Template 内唯一稳定 loopId 定位的不可变 Loop frame，公开 typed item 与零基 index，并只能读取同 Template 的 invocation/词法祖先 frame。
- scalar item 必须形成对应精确 `system-basic-*@v1` typed context；只有已验证 StaticSchema `array(items: reference)` 的对象 item 才携带精确引用 Schema context。任意动态 JSON object 不能冒充 context。
- iterable 必须拥有静态封闭 item 类型；StaticSchema field path 不允许用数字下标或 wildcard 穿越数组，访问元素必须通过 Loop domain。
- nested loop 对当前或祖先项的引用必须显式使用 loopId；不存在 `$current/$parent/$root` 漫游，子 Template invocation 也不继承父 loop frame。
- loop-scoped Computed Definition 的 evaluation domain 在 DesignDSL 中固定；它可以读取本域和词法祖先，不能读取 sibling/descendant 或按消费者动态改变求值域。
- 合法 iterable source 的 ABSENT、空集合、运行时错误以及 iteration/output 上限仍由本票据分别冻结，不能把不存在的 Schema path 当作 ABSENT。
- `loopIndex` 是唯一 loop metadata ValueSource，返回零基非负整数 decimal；没有通用 loop object 或 v1 loop key，item 字段只能经显式 loopId context domain 读取。
- v1 scalar list 只允许五种 StaticSchema scalar item；reference array 必须携带精确 item StaticSchemaRef，任意 object/list nesting 或动态 JSON collection 禁止。
- 普通 Binding 要求 CONCRETE；本票据若让 Repeat/Conditional 结构 source 接受 MAY_BE_ABSENT，必须在全局 Node 属性合同中声明封闭结构类型与明确的 ABSENT policy，不能改变普通 visual property 规则。
- Binding 永远是可选 overlay 且必须保留合法静态 baseline；因此 iterable/condition 若使用统一 Binding 模型，也必须定义未绑定时可执行的 authored 静态值。存在 Binding 但 ABSENT/ERROR 时不得隐式回退该 baseline。
- Loop subtree 中 node-local Binding 的词法 domain 由节点位置与显式 loopId 静态确定；移动节点造成越界是 hard error，definition 或 Binding 值不得逃逸到 parent/sibling/child Template。
- 每个 Loop 同时携带独立 client-generated canonical UUID v4 loopId；它与 nodeId 分属 namespace，服务端只校验唯一性/引用，不生成或修复。copy subtree 时客户端必须成组 remap loopId 与全部 domain refs。
- 输入 scalar/reference collection 与实际 iteration order 是语义顺序并保持 authored/runtime order；definitions/bindings 的 canonical sorting 不能重排 Loop items、instances 或 conditional branch order。
- Repeat/Conditional wire、missing policy、结构 type 或既有 property identity 的变化需要新 dslVersion；既有 identity 不能在新版本中复用为不同语义。
- 票据 09 的首批 visual kind 精确为 canvas/group/frame/stack/grid/text/image/rect/ellipse/line/polygon/polyline/path/qrCode/barcode；Repeat/Conditional 不是这些 kind 的 property hack，也不能通过 Binding 改 children/topology。本票据必须为它们定义独立 exact structural NodeKind、ContentModel 与永久 Property Identity，并在最终 DesignDSL 1.0 合同冻结前并入同一个 NodeContractCatalog。
- 结构节点仍须遵守 nodeId/bindings/common non-Canvas wire、parent-selected placement、children semantic order 与 closed/unknown/null failure；若某结构字段需要不同于普通 Binding 的 ABSENT policy，必须成为明确的 structural contract，而不是放宽视觉 property overlay。
- 结构求值必须形成确定的 materialized child sequence：false Conditional、零项 Repeat 与 `render:false` subtree 在 layout/resource 前完全移除且不产生 Stack gap；`visible:false`/`opacity:0` occurrence 仍保留资源、measure、layout 与失败语义。Repeat instance order 直接进入 RenderDocument 的 layout/paint order，不能在 lowering 或 canonicalization 中重排。
- 本票据必须冻结可组合到嵌套 invocation path 的请求级OccurrencePath，使票据15能在Rendering诊断sidecar中把RenderDocument opaque occurrenceId回接source node、票据10的LayoutTrace能稳定定位实际实例；该path不是authored ID、revision事实、Engine输入或跨请求持久身份。

## Answer

### 1. 领域概念与 exact structural Node

- 作者结构节点精确命名为 `repeat` 与 `conditional`；`Loop` 只指 Evaluation 中某个实际 item 的运行时 Loop frame，不作为 authored NodeKind。Repeat 同时拥有普通 `nodeId` 与独立 `loopId`；Conditional 只有 `nodeId`。两者在 `renderweave-design/1.0` 正式冻结前并入同一个全局 NodeContractCatalog，而不是普通 Node 的 property hack。
- Repeat 必填 `kind/nodeId/loopId/items/absentPolicy/itemLayout/instanceLayout/placement/bindings/children`，可选既有 `displayName/render/visible/opacity/transform`。Conditional 必填 `kind/nodeId/condition/absentPolicy/placement/bindings/children`，也可选相同 common fields（没有 loopId）。`displayName` 只用于作者识别并进入 content hash，不求值且不可 Binding。
- 两种结构节点的 `children` 都是必填、非空、有序数组；EditorSession 可以暂时为空，但服务端不能保存。全部 object/union closed，unknown member/kind 与 JSON null 失败；`bindings` 即使为空也必须存在。Repeat/Conditional 没有 fill、stroke、cornerRadii、padding 或 clipContent，需要视觉 box 时显式嵌套/外包 Frame。
- `items` 与 `condition` 是必填结构 ValueSource，不是普通 property Binding，也不要求 authored static baseline；它们直接复用既有 ValueSource union、类型证明、domain 与错误语义。`absentPolicy`、children topology 和结构 source 都不能被 Binding 改写。

### 2. Collection、item type 与 scalar item context

- Repeat `items` 的静态类型只能是 `list<T>` 或精确 StaticSchema `array(items: reference)` collection。`list<T>` 的 T 只允许 StaticSchema 五种 scalar：text、decimal、date、time、boolean；保持输入顺序、重复项与空数组，禁止 null、异构、嵌套 list、color/imageRef/fontRef list、任意 object collection 或按 shape 猜测 context。
- 合法 source 可以是 literal、context 或 definition；context 可以指向已证明类型的 scalar/reference array，Definition 可以产生声明的 scalar `list<T>`。Capability 与 loopIndex 不能直接作为 items source，Expression 1.0 也不能构造/filter/map 任意集合。Reference collection 只来自带精确 item StaticSchemaRef 的已验证 context，不能由通用 literal/Definition 构造 object list。
- T 唯一从 items ValueSource 的静态类型证明派生；Repeat 不保存 `itemType`、`itemTemplate` 或 runtime shape hint。一个 StaticSchema 可以对应多个 Template，Schema 只证明数据类型，不参与视觉 Template 选择。
- scalar item frame精确符合正式预置的一等只读StaticSchema：`system-basic-text@v1`、`system-basic-decimal@v1`、`system-basic-date@v1`、`system-basic-time@v1`、`system-basic-boolean@v1`。每份都有必填`/index: decimal(min=0,multipleOf=1)`与对应类型的必填`/value`；descendant context ValueSource显式选择loopId并读取字段，TemplateUse可用空pointer把整个context传给绑定同一系统Schema的child。
- reference-array item frame携带被引用的exact StaticSchemaRef并按其字段路径读取，未知字段不可见且绝不注入index。`loopIndex`返回原输入数组的零基非负整数decimal，并与scalar context的`/index`表示同一值；v1不提供loop key、packedIndex、row、column或通用loop object。

### 3. Item subtree 与显式子 Template 选择

- Repeat 的 `children[]` 是唯一 item subtree：同一份 authored subtree在每个 Loop frame 中分别求值，不能保存每项展开副本。`itemLayout` 排列单个 item 的 surviving direct children，`instanceLayout` 排列所有 surviving item instances；旧称 `templateLayout` 明确弃用，因为它不选择 Template。
- item subtree 可包含任意 non-Canvas Node，包括普通元素、Frame/Stack/Grid、嵌套 Repeat、Conditional，以及票据 12 定义后的 TemplateUse。Canvas 始终只可作为 DesignRoot。
- 若每项需要嵌套 Template，作者必须在 item subtree 中显式放置一个或多个 TemplateUse，由每个 TemplateUse 自己选择 `{templateId}` 并用 ContextSelector 选择当前 loop item。同一 StaticSchema 下不存在“默认/最近/第一个 Template”推断；目标 Template 永久 StaticSchemaRef 与所选 item context 的兼容性由票据 12 权威验证。

### 4. 词法作用域与求值频率

- Repeat 自己的 render、items、placement/common 与 packing Binding 全部在其父 lexical domain 求值，不能引用自己的 loopId；嵌套 Repeat 的父 domain 可以是祖先 Loop frame。items 每个实际 Repeat occurrence只求值一次。
- items 成功得到 collection 后，Evaluator 按原输入顺序为每个 item 建立一个不可变 Loop frame。Repeat descendants 可以显式读取自身或任意同 Template 词法祖先 loopId；不能读取 sibling、descendant或词法范围外 frame。嵌套 Repeat 的 items可读取祖先 loopId，但不能读取它自己的 loopId。
- `itemLayout/instanceLayout` 的动态叶子在父 frame每个 Repeat occurrence各求值一次，全部 item共用；不允许 per-item columns/gap/direction。每个 item中的 PACK placement、普通 Binding、Conditional与嵌套 Repeat在自己的 Loop frame求值。
- Conditional 不建立新 frame；condition 在节点所在 lexical domain每个实际 occurrence只求值一次，children保留相同 domain。loop-domain Computed Definition继续每 Loop frame惰性 memoize，invocation-domain Definition每 invocation memoize。
- TemplateUse 的 selector/fill可以在当前 Loop frame求值；一旦进入 child Template即建立隔离 invocation frame，child不能读取父 loopId、Definition或RootDocument handle。

### 5. ABSENT、惰性结构求值与稳定 consumer order

- Repeat `absentPolicy` 必填且精确为 `ERROR | EMPTY`。EMPTY只把合法 MAY_BE_ABSENT collection的 runtime typed ABSENT变为零项；ERROR下 ABSENT使本次 Evaluation失败。显式空数组始终为具体零项；null、错误类型、ValueSource ERROR与预算错误在两种 policy下都失败。
- Conditional `condition` 必须静态为 boolean，`absentPolicy` 必填且精确为 `ERROR | FALSE`。FALSE只把 typed ABSENT当 false；ERROR policy的 ABSENT以及任何 null、错误类型、ValueSource ERROR或预算错误都使 Evaluation失败。EMPTY/FALSE成功处理 ABSENT时不产生 warning。
- node-local bindings数组顺序无求值含义。固定 runtime consumer order 为：
  - Repeat：`render → items → placement → visible → opacity → transform → itemLayout → instanceLayout → children DFS`；
  - Conditional：`render → condition → placement → visible → opacity → transform → children DFS`。
  composite 内 leaf按 NodeContractCatalog 冻结的声明顺序 demand，不能按 JSON member order或bindingId排序决定行为。
- render最先求值；false立即剪枝且不求结构 source、其余 Binding、descendants、Asset或capability。Repeat items得到零项时也立即剪枝，不求其余 common/packing Binding或children；Conditional condition为false时同理。前置剪枝后未 demand的 ERROR/capability不会发生。
- visible:false 与 opacity:0 永远不是求值剪枝：只要结构已物化，仍完整解析资源、求值descendants并参加layout/failure。静态保存仍验证全部 authored branch、source、baseline、domain与ContentModel，不能用literal false/empty隐藏非法结构。

### 6. Instance survival、顺序与失败原子性

- 每个 item按 children authored order深度优先物化。若该 item的全部 direct children都因render:false、false Conditional或零项 Repeat被剪枝，则该 item instance不进入instanceLayout，也不产生gap/cell。visible:false、opacity:0或任何仍materialized的零尺寸/空Node都使instance继续存在。
- loopIndex与OccurrencePath中的inputIndex始终是原 collection index，不因item subtree被全部剪枝而重编号；instanceLayout只对surviving sequence紧凑排列。因此视觉packing ordinal可与loopIndex不同，首版不把packing ordinal作为ValueSource。
- 零项/零surviving instance使整个Repeat移除，即使Repeat authored placement为FIXED/FILL，也不保留空白box。Conditional false完全移除；Conditional true即使全部descendant被剪枝仍物化为无外观Frame，FIXED/FILL继续占box，HUG为`0 × 0`。
- collection长度和可提前判定预算在建frame前检查；随后严格按input order与children DFS执行。首个实际 demanded runtime ERROR立即停止后续item、capability与Asset resolution，整次Evaluation零RenderDocument；不跳过失败项、不返回前N项或partial output。

### 7. PACK placement 与结构 ContentModel

- 新增永久 shallow placement variant `PACK`，只允许Repeat direct child使用。exact members为`type:"PACK"`、必填`widthMode/heightMode`，每轴只允许`FIXED | HUG_CONTENT`；FIXED必填对应`widthMm/heightMm > 0`，HUG禁止该size。除Group外可选既有min/max，约束与Ticket 09/10相同；Group仍只能双轴HUG且无min/max。
- PACK禁止FILL、margin、x/y/inset、row/column/span、alignSelf、fillWeight及所有普通Stack/Grid placement hint。itemLayout在STACK/GRID间切换不改写children placement；gap是唯一item-subtree sibling间距authority。
- Repeat/Conditional自身作为普通父容器child时，继续使用父ContentModel要求的ABSOLUTE/STACK/GRID placement，并支持FIXED/HUG_CONTENT/FILL；若它们本身是Repeat direct child则使用PACK而只能FIXED/HUG。reparent必须由authoring client显式转换placement。
- Conditional direct children统一使用ABSOLUTE placement；其true box遵循无padding/stroke/clip的Frame规则：HUG逐轴使用Ticket 10对transformed direct-child LayoutBox的正端范围，FIXED/FILL使用父offer，HUG/FILL dependency cycle失败。

### 8. RepeatPackingSpec 与两级布局

- `itemLayout` 与 `instanceLayout` 都使用同一个closed `RepeatPackingSpec` union：

```json
{"kind":"STACK","direction":"ROW","gapMm":0}
{"kind":"GRID","columns":3,"columnGapMm":0,"rowGapMm":0}
```

- STACK必填kind/direction，gapMm可省略并默认0；direction精确为ROW|COLUMN。GRID必填kind与正整数columns，两个gap可省略并默认0；columns受容量上限约束。gap必须非负。kind永远静态；direction/gap/columns可按BindingPolicy覆盖已存在baseline并重新验证。
- STACK对surviving sequence使用pre-transform LayoutBox：ROW宽为尺寸和加相邻gap、高取最大；COLUMN对调。物理方向固定左到右/上到下，cross axis START；零尺寸但materialized的项仍参与序列与相邻gap，transform不影响packing。
- GRID令`effectiveColumns = min(columns,n)`，按surviving sequence零基row-major放置。每列宽取该列所有item LayoutBox最大宽、每行高取最大高；item在cell左上START且不拉伸。位置按track prefix sum加gap，自然尺寸为有效track和相邻gap之和；不完整末行不创建placeholder，但共享前序行确定的列宽。transform不参与track测量，paint order仍按sequence。
- itemLayout先分别得到每个item container的HUG natural box；instanceLayout再排列这些box。Repeat HUG取instance packing自然尺寸；Repeat FIXED/FILL不拉伸item、不重排、不动态改columns，内容锚定左上，空间过小形成overflow、过大只在右/下留空。结构容器不clip，overflow继续服从ancestor/surface规则。
- RepeatPackingSpec不是普通Stack/Grid Node alias：首版没有justify、align、margin、FILL、reverse、wrap、FRACTION/AUTO/FIXED authored track、manual cell、masonry或implicit responsive columns。

### 9. BindingPolicy 扩展

- Repeat/Conditional继承当前node kind合法的common及父placement leaf Policy。PACK对相应non-Group kind只授权已存在的widthMm/heightMm/min*/max*叶子；mode/type不可Binding。
- Repeat新增`itemLayout.direction/gapMm/columns/columnGapMm/rowGapMm`与对应`instanceLayout.*`的variant-specific Policy；实际Catalog逐kind/path展开且union target不重叠。optional gap若要Binding，作者必须先materialize static baseline。
- items、condition、absentPolicy、loopId、children、packing kind、placement type/mode与任何结构/object整体永不成为Binding target。items/condition已经是ValueSource，不再包一层Binding或保存expectedType。

### 10. Lowering、合成容器与 OccurrencePath

- Repeat/Conditional必须在Evaluator中完全消除，RenderEngine看不到ValueSource、Loop frame或动态结构。true Conditional降低为无外观/无padding/无clip的普通Frame；false无节点。每个surviving Repeat item先降低为HUG普通Stack/Grid承载item children，Repeat外层再降低为普通Stack/Grid承载item containers并继承Repeat自身placement/visible/opacity/transform。
- PACK在lowering中映射为对应生成父的STACK或GRID placement并保留FIXED/HUG、size与min/max。Repeat GRID按concrete surviving count生成显式AUTO rows/columns与零基GRID placement；STACK生成普通STACK placement。Evaluator只生成静态layout rules，不计算final coordinate、track size、line break或glyph。
- 合成外层/item containers没有fill、stroke、padding、corner或clip。Repeat transform/visible/opacity只作用一次于外层subtree，不复制到每个item；item内children按authored order绘制，不同item按surviving sequence绘制，发生overlap时后者覆盖前者。Conditional common视觉控制只作用于true subtree。
- 请求级OccurrencePath以前置root Template invocation segment开头，每层Repeat追加`{loopId,inputIndex}`；实际TemplateUse再追加`{useId,childTemplateId,resolvedRevision}`后进入child source nodes。合成role至少区分`repeat-container`、`repeat-item`、`conditional-frame`、`template-use-viewport`与`canvas-background`。重复值仍是不同occurrence，array reorder会改变path。
- 合成container不生成/持久化UUID：Repeat外层与Conditional frame关联源nodeId及role，item container由loop segment+role定位。RenderDocument只携带opaque occurrenceId；请求级诊断sidecar回接OccurrencePath与可选sourceNodeId，错误、LayoutTrace和paint诊断经权限投影使用该path。它不含item值、不是author identity、revision事实、业务key、Engine输入或跨请求稳定身份。

### 11. Validation、copy/reparent 与 canonical事实

- 空children、duplicate/invalid loopId、unknown union、非法PACK/FILL、错误parent placement、非法packing、词法越界/cycle、可证明的source类型不为collection/boolean及不兼容known type都是不可确认hard error。StaticSchema path或Definition dependency事实不存在仍按既有dependency ERROR处理，可经二阶段确认保存INVALID；INVALID/STALE不能Render。runtime input的ABSENT/source/预算错误只影响该请求，不自动改变TemplateReadiness。
- 同Template复制Repeat subtree时，client必须为副本全部nodeId/bindingId/loopId生成新UUID并原子改写副本内部domain refs。若副本依赖subtree外但domain属于原Repeat的Computed Definition，client必须一并复制/重定向或拒绝；移动/删除导致loopId词法不可达也必须先修复，否则保存hard error。server不猜测改成invocation/ancestor domain；whole Template copy/restore仍保留local IDs。
- children、literal list与Mapping cases保持语义顺序；bindings仍按bindingId canonical sort。loopId、items/condition source、absentPolicy、itemLayout/instanceLayout与全部authored subtree进入Canonical DesignDSL/content hash；runtime item、survival/剪枝结果、OccurrencePath、合成container、派生cell/track与resolved Template current不进入。

### 12. Capacity、diagnostics、版本与排除项

- 每个input item即使其item subtree最终全部被剪枝，也消耗collection、frame、operation与nested traversal预算；只有surviving instance/descendant进入materialized-node、layout与paint-item预算。nested Repeat使用请求级累计上限，具体collection length、loop depth、frame/occurrence/node/operation与generated-track数由Ticket 19冻结；超限fail closed，不截断、分页或近似。
- ABSENT被EMPTY/FALSE接受时静默。source问题定位到结构nodeId+property path；item descendant问题增加OccurrencePath中的loopId/inputIndex以及安全的source node/definition/binding identity。Problem不得回显item值、完整文本、RootDocument片段或展开subtree，达到全局上限按Ticket 19明确截断。
- Repeat/Conditional/PACK wire、ABSENT policy、词法/结构剪枝、OccurrencePath构成与lowering属于DesignDSL Profile；可观察变化需要新dslVersion。lowering后的Stack/Grid/Frame measure/arrange属于RenderDocument指定的Layout Profile；其算法变化需要新Layout Profile，同时影响两层则两者都升级。本票据是在`renderweave-design/1.0`正式冻结前对Ticket 09 draft作受控扩展，不是发布后修改identity。
- 首版明确排除Repeat filter/sort/distinct/reverse/limit/offset/key、dynamic template inference、per-item packing、masonry、available-width wrap、pagination、多Canvas/多图片输出、失败项跳过及partial success。不同视觉Template只能由item subtree中显式Conditional+TemplateUse组合；数据变换由受支持的上游Connector或未来Expression Profile产生有序collection。
- 历史系统证明items在父域、item frame、输入保序与两阶段packing是可行seam，但其索引为一基；原取证commit的production kernel明确返回`ARRAY_LOOP_NOT_COMPILED`，2026-08-13复核的最新历史HEAD虽已接线`arrayLoop/templateHost`，仍使用Java预布局与旧MaterializedScene/Asset语义。本合同不继承任一时点的旧wire、missing/layout字段或Scene生命周期。
- 本票据只冻结探索规格、领域语言和下游约束，不创建NodeContract实现、Evaluator、RenderDocument model、Editor、API、数据库或任何产品代码。
