# 定义几何与布局容器语义

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 09

## Question

画板、自由布局、Group、Stack、Grid 等容器如何拥有和计算子节点几何；fixed/hug/fill、min/max、padding/margin、gap、writing mode、transform、overflow/clip、排序和派生坐标持久化规则应是什么？

## Inherited constraints

- 所有 authored geometry/layout property 必须先按全局 Node 属性合同形成合法静态 baseline；哪些具体路径允许 Binding 只能由只追加 BindingPolicyCatalog 显式列出，不由 Template 自定义。
- Binding 只覆盖已存在 property leaf，最多一次 member 与一次 fixed array index；overlay 结果必须在进入 layout 前成为 concrete exact ValueType 并重新通过该 geometry property 的全局约束，失败不得回退 baseline。
- RenderEngine/布局阶段只接收已求值的静态 RenderDocument，不得重新解释 Binding、Expression、ABSENT 或 DesignDSL property policy。
- DesignRoot/Node children、画板与输出相关数组若以 authored order 表达 z-order/layout/output，就必须在 Canonical DesignDSL 中保序；若顺序完全由显式 order/placement 字段决定，票据须声明其 set-like canonical sort key，不能同时存在两个 authority。
- geometry decimal 在 Canonical DesignDSL 中按 arbitrary-precision plain token 写出，等价 lexeme 不构成作者事实；每项范围/default/单位仍由永久 Node Property Identity 定义，canonical writer 不自动补 default 或裁剪非法数值。
- 票据 09 已冻结唯一 Canvas root、nested children z-order、Group/Frame/Stack/Grid ContentModel、mm 几何与 pt typography、closed ABSOLUTE/STACK/GRID placement、FIXED/HUG_CONTENT/FILL capability matrix、min/max、margin、padding、gap、Track、Transform 与 Text sizing authored wire；除最终确认的 fillWeight、margin variant、inward stroke 与 `VISIBLE + maxLines` 四项受控修正外，本票据只定义派生算法，不能改名、增加 alias 或重解释其他既有 Property Identity/default/validation。
- Canvas/Frame/Group child 必须是 ABSOLUTE，Stack/Grid child 分别为 STACK/GRID；reparent conversion 是 authoring 责任。Group 两轴只 HUG 且 direct child FILL 非法；多个 Stack main-axis FILL child 按省略为 `1` 的正 fillWeight 分配剩余空间，精确 min/max 冲突、rounding 与 cycle detection 由本票据冻结。
- 本票据必须冻结 HUG measurement、FILL allocation、Grid AUTO/FRACTION、negative margin、absolute inset、min/max clamp、transform/origin、opacity、clip、paint bounds 与 layout bounds 的权威顺序；不得通过隐式 STRETCH、zIndex、percent、viewBox 或 DPI 补充未授权 authored 属性。
- Text 已冻结 horizontal/vertical writing、双轴 alignment、lineBreak/overflow、lineHeight、padding/stroke、maxLines 与 uniform SHRINK_TO_FIT 输入；本票据必须给出 shaping/line box/wrap/ellipsis/vertical column 与 HUG 测量语义，并保留“无环境字体 fallback”的失败封闭规则。
- Vector local coordinates 使用 pre-transform mm；本票据必须冻结 HUG bounds、FIXED/FILL scaling、stroke 是否计入 bounds、负坐标到 local box 的映射与 degenerate/final geometry 处理。Image 单轴 HUG 只使用 pixel aspect ratio，不读取 Asset DPI；QR final content box 必须为正方形。

## Answer

### 1. 权威边界、布局 Profile 与派生产物

- 首个精确布局合同标识为 `renderweave-layout/1.0`。它必须显式写入 RenderDocument，并由 DesignDSL/RenderDSL compatibility table 选择；Render Request 和调用方不能任意选择、升级或要求 fallback。任何可观察布局算法变化都引入新的 Layout Profile；若同时改变 authored wire、Property Identity、default 或 validation，则还必须引入新的 `dslVersion`，不能只换布局 Profile 掩盖作者语义变化。
- Evaluator负责把DesignDSL的Binding、Definition、Repeat、Conditional、TemplateUse与logical AssetRef全部物化，按实际消费位置产生一对一RenderResource manifest和有序静态Node tree。TemplateUse/child Canvas降低为不含Template句柄的静态compositionViewport；RenderDocument仍保留Stack/Grid/Placement/viewport等布局规则，不携带final x/y、行列断点、glyph或派生box。
- RenderEngine 是布局权威：按本 Profile 完成资源准备、measure、arrange、最终约束下的 shaping/reflow 与 draw，内部产生 `LaidOutScene`。LaidOutScene、派生坐标、glyph、box 和布局树都不写回 DesignDSL、Template revision 或 RenderDocument，也没有独立产品生命周期。
- 浏览器画布只能用同一合同提供非权威反馈；正式输出与权威预览必须进入同一 RenderEngine。现有 `busbox-render-engine` 的通用 box、HUG/FILL、Stack/Grid、clip 与文字能力不足以直接成为该合同，后续必须扩展为明确支持 RenderWeave Layout Profile 的引擎；适配器可以改 wire/单位，但不得预布局、静默模拟或降级语义。
- RenderDocument 必须展开所有 NodeContract default，按最终物化顺序保存 children，并为每个实际 occurrence 携带票据15按最终树先序分配的opaque occurrenceId；完整OccurrencePath与可选source-node诊断关联只在Rendering请求级sidecar。它不含 Binding、Definition、动态结构判别、逻辑 AssetRef、编辑器状态、Template身份或缺省值省略信息。

### 2. 物化、可见性与资源时序

- `render:false`、未命中的 Conditional 分支和零项 Repeat 在 measure、layout、动态 Asset 解析与输出前移除整个子树；它们不占尺寸，也不产生 Stack gap。Stack gap 只存在于最终 materialized children 中相邻两项之间，展开后的 authored occurrence order 同时决定 layout traversal 与 paint order。
- `visible:false` 保留完整资源解析、测量和布局，但跳过自身与全部 descendants 的绘制。`opacity:0` 也保留资源、测量、布局与所有失败语义，只允许绘制阶段优化；不得因不可见而掩盖缺失字体、图片解码或布局错误。静态 authored AssetRef 的 readiness/index 检查仍独立于运行时剪枝。
- Engine 先准备本次物化树所需的精确字体与图片尺寸，再执行自底向上 measure、自顶向下 arrange；得到最终宽度约束后再做必要的文字 reflow/shaping 和绘制。任一资源、测量、布局、shaping 或绘制前置失败都使整个请求失败，不返回部分 Scene、LayoutTrace 或 RenderOutput。

### 3. 单位、降低与确定性数值

- authored geometry、padding、margin、gap、shape stroke 与 vector coordinate 使用 mm；`fontSizePt`、固定 line height、绝对 letter spacing 与 Text glyph stroke 使用 pt；scale/ratio/weight 无单位，rotation 使用 degree。DPI 只在 raster Render Request 中生效，省略为 96 DPI，不参与物理布局、字体度量、Image HUG 或 shrink-to-fit。
- lowering 把所有物理量转换为 RenderDocument pt：`pt = mm × 360 / 127`。每个 decimal 字段只在 lowering 边界统一量化一次为小数点后六位、`HALF_EVEN`；原生 pt、unitless 与 degree 也执行同样六位量化，`-0` 规范为 `0`，整数保持精确。量化后非有限、超界或违反合同的值直接失败，不 clamp；rotation 不作 360 度归一化。
- Layout Profile 使用 IEEE-754 binary64、固定求值顺序并禁止影响结果的 fast-math/FMA contraction；中间结果不再次量化。FILL、FRACTION、AUTO deficit 和 distributed-space 分配都按稳定 authored order 计算前 `n-1` 项，最后一项接收剩余量；水位算法每轮至少冻结一个达到 min/max 的项，同值按 authored order，微小负残差在 Profile tolerance 内归零，超出 tolerance 是数值错误。布局阶段不做 pixel snapping。
- conformance 以 box、断行、transform、paint/clip 顺序、错误码和 Profile tolerance 比较，不要求两个实现的内部浮点 bit pattern 相同；DPI-to-pixel 与最终像素舍入留给票据 16。

### 4. 统一布局语言与 box model

- `IntrinsicSize`：Node 在给定轴约束下由内容得到的自然尺寸；轴约束是 `UNBOUNDED | AT_MOST(value) | EXACT(value)`，measure 必须是对 Node、已解析资源和约束的纯函数。
- `LayoutBox`：arrange 后、Node transform 前的 border box，不含 margin；所有 placement width/height 都指它。`MarginExtent` 是 Stack/Grid 用于排布的 LayoutBox 加 signed margin 区间，不是可绘制 box。
- `ContentBox`：Frame/Stack/Grid 的 LayoutBox 先向内扣除 inward stroke，再扣除 padding 的非负区域；Rect/Ellipse 无子内容。Text 没有容器 border，其 ContentBox 只由 LayoutBox 扣除 Text padding，Text stroke 是 glyph stroke。任何扣除结果小于零都 floor 为零，不自动缩放或报错。
- `PaintBounds` 是应用完整 world transform 后、尚未受自身/祖先/surface clip 影响的保守世界坐标 AABB；`EffectivePaintBounds` 是与全部有效 clip 和 surface 相交后的保守 AABB。`ClipRegion` 保留实际 path/rounded shape 语义，不能用 AABB 替代。`LayoutOverflow` 只表示未裁剪内容超出自己的 LayoutBox/ContentBox，不反馈布局，也不是 Template readiness 问题。
- `LaidOutScene` 是 Engine 内含最终 box、world transform、clip、shaped glyph、paint item 与资源句柄的绘制场景；不是 RenderDSL、持久化 Artifact 或跨上下文产品合同。
- FIXED authored width/height 必须 `> 0`；HUG/FILL 的最终 LayoutBox 可以为零。零尺寸 Node 仍存在于树中：未裁剪 descendants 或 `VISIBLE` 内容可以越界；Image clip 为空因而不绘制，Rect/Ellipse 没有自绘制面积，QR/Barcode 的正尺寸要求仍直接失败。零尺寸不等于 `render:false`。

### 5. Size mode、约束测量与 transform

- FIXED 使用 authored size；HUG_CONTENT 使用 constraint-sensitive IntrinsicSize；FILL 使用父容器给出的 definite offer。min/max 只 clamp HUG/FILL，FIXED 必须在进入布局前已满足 min/max。HUG 收到父 offer 或 max 时使用 `AT_MOST`；Text 在 intrinsic measure 前先按 inline-axis constraint wrap/reflow，非 reflow shape 先求自然尺寸再 clamp。max 小于内容只形成 overflow，不隐式缩小内容。
- FILL 必须获得 definite offer；任何 HUG/FILL、Frame child-inset、Stack main FILL 或 Grid AUTO/FILL 依赖环都是 hard error，不以零猜测、不做一般固定点迭代。Stack 先解主轴，再以最终主轴重新测量 cross-axis HUG，一次完成且不反算主轴；Grid 固定 columns-first：先求 columns，再以最终列宽测量 row contribution 并求 rows，不从 rows 反推 columns。混合 Text writing mode 也不改变该顺序。
- arrange 后应用 Node transform，普通 Node transform 不影响 parent HUG、Stack/Grid 分配或 sibling 位置。唯一范围例外是 Group 与 HUG Frame 对 direct ABSOLUTE child 使用其变换后 LayoutBox 的范围；仍不使用 PaintBounds，也不把容器自己的 transform 反馈给自身布局。
- Node 局部点的精确 transform 为 `p' = origin + RotateClockwise(rotationDeg) × Scale(scaleX, scaleY) × (p - origin)`：先 scale 后顺时针 rotation，origin 是未变换 LayoutBox 的归一化坐标；负 scale 表示 flip。父 transform 作用于完整 descendant subtree。v1 无 translate/skew authored transform。

### 6. Canvas、Absolute、Frame、Group 与绘制栈

- Canvas trim box 固定为 `(0,0,width,height)`；bleed 向四边扩展最终 surface。Canvas background 填满包含 bleed 的整个 surface，trim 不形成 clip，children 可以绘制进 bleed，但任何内容都受 surface hard clip。v1 不自动生成 crop marks。
- Canvas作为根输出surface时继续使用上述bleed语义；作为child Template来源时自身不进入RenderDocument。compositionViewport先在child原始trim约束内layout并强制clip到trim，忽略child bleed，再把完整artboard等比CONTAIN/CENTER到host并强制host clip；letterbox透明，父约束不使child reflow。
- ABSOLUTE child 的 FIXED/HUG 左上角是 parent ContentBox 原点加 `x/y`；对应轴 FILL 的尺寸为 `max(0, parentContentSize - start - endInset)` 后再应用 min/max。负 x/y/inset 合法，允许越界。Absolute 没有 margin。
- HUG Frame 保持稳定 local ContentBox 原点 `(0,0)`；逐轴需求为 direct child 变换后 LayoutBox 的最远正端 `max(0,maxEnd)`，负端只算 overflow，再加 padding 与 inward stroke。空 Frame 的 HUG size 等于 padding 加 stroke；依赖 HUG 轴的 child FILL/inset 形成 cycle 并失败。
- Group 取 direct children 变换后 LayoutBox 的二维 union，以 union min point 作为派生 local origin并在绘制时平移归一到 `(0,0)`；Group placement 放置归一化 union 的左上角，随后再应用 Group 自身 transform。空 Group 为 `0 × 0`。编辑器在 group/ungroup/reparent 时若要保持 world position，必须改写 authored placement；Engine 不写回。
- Frame/Stack/Grid 的 fill 使用归一化 outer rounded shape；stroke 完全向内。inner-border shape 是 outer shape inset stroke 后、半径取 `max(0,r-stroke)` 的结果；`clipContent:true` 只用该 inner-border rounded shape clip descendants，padding 不是 clip boundary。过厚 stroke 可使 inner shape、ContentBox 与 descendant clip 为空，但 self stroke 仍绘制。
- 确切绘制栈为：继承 ancestor transform/clip → 应用 node transform → 建立 subtree opacity isolation → 绘制 self fill/content → 若 `clipContent` 则压入 descendant clip → 按 materialized children order 绘制 → 释放 descendant clip → 绘制 inward self stroke → 合成 opacity layer。Image 总是自裁剪到 LayoutBox；Text 按 overflow 自裁剪；Rect/Ellipse/QR/Barcode 在自身 box 内生成；自由 Vector 与 Group 不自动自裁剪。所有 self/ancestor/surface clip 求交，opacity 覆盖整个 subtree 且不改变 bounds。

### 7. Stack 的精确算法

- `ROW` 物理方向固定为左到右，`COLUMN` 为上到下，不受 BiDi 影响。主轴 cursor 从零开始，依次加 leading margin、child size、trailing margin，并仅在 materialized 相邻 child 间加固定 gap；signed negative margin 允许 cursor 回退、重叠或 outset，不折叠。
- definite 主轴先扣除 gap、signed margin、FIXED 与已测 HUG；剩余 `max(0,available-used)` 分给当前主轴为 FILL 的 child。`placement.fillWeight` 是 STACK placement 的可选 decimal，必须 `> 0`、省略为 `1`，只在 owning Stack 当前 direction 对应轴为 FILL 时合法并参与比例分配；方向或权重经 Binding 后必须重新做该 aggregate validation。
- FILL 使用按 weight 的 iterative min/max water filling：达到 bound 的 child 冻结，剩余空间按未冻结权重重分；所有 min 总和超过空间时允许布局溢出而不缩减 min；全部 max 截断后的剩余空间交给 justifyContent。算法最多执行 `fillChildCount + 1` 轮。
- 主轴占用量为 `max(0, ΣchildSize + ΣsignedMargins + gap×(n-1))`，free space 为 `max(0,available-occupied)`。START/END/CENTER 的 leading extra 分别为 `0/F/F÷2`；SPACE_BETWEEN 在 `n>1` 时每个内部 gap 加 `F÷(n-1)`，单 child 等同 START；SPACE_AROUND 每项槽宽 `F÷n`、首尾半槽，单 child 居中；SPACE_EVENLY 槽宽 `F÷(n+1)`、端点与内部均一槽。出现 overflow 时 `F=0`，全部退回 START 分布。
- cross axis 先用 signed margins 得到 available interval；cross FILL 取 `max(0,available)` 后应用 min/max，FIXED/HUG 使用自身 size。`alignSelf` 覆盖 `alignItems`，START/CENTER/END 按原始区间定位；child 大于区间时仍遵守所选对齐，不强制 START。transform 不影响 sibling。
- HUG Stack 主轴模拟同一 cursor、margin、gap 与非 FILL child size，以稳定 origin `0` 和最远正端求尺寸；负 leading extent 只算 overflow。HUG 主轴出现 FILL 是 cycle。cross HUG 取所有 child MarginExtent 的最远正端；最后加 padding/stroke 并应用 min/max。空 HUG Stack 等于 padding 加 stroke。

### 8. Grid 的精确算法

- definite 轴按 FIXED → AUTO → FRACTION 顺序求 track。AUTO contribution 为 non-FILL child 的 constraint-sensitive size 加 signed margins后取 `max(0,...)`；单轨取最大值。跨多轨 child 的 deficit 只平均增加其跨度内 AUTO tracks，约束按 `(spanLength,startIndex,materializedOrder)` 稳定排序；没有 AUTO track 时允许 overflow。跨越任一 AUTO track 的该轴 FILL child 形成 cycle。
- FIXED/AUTO 加 gap 已超出 available 时 FRACTION 全部取零且不缩小既有 track；否则 FRACTION 按正 weight 分配剩余空间。没有 FRACTION 时多余空间固定留在物理右/下端，v1 没有 grid-track justify。row/column 是零基，track 物理顺序为左到右、上到下。
- HUG 轴只允许 FIXED/AUTO，出现 FRACTION 是 hard error；无贡献 AUTO 为零，HUG size 是 explicit tracks 加相邻 gap、padding 和 inward stroke。零宽 track 仍保留其两侧声明 gap。双轴 HUG 在无 FRACTION 时合法，并始终执行 columns-first；空 Grid 仍保留 explicit track/gap 尺寸。
- child spanned cell 包含跨度内部 gaps；signed margins 形成可用 interval。FILL 填满 `max(0,available)` 再 clamp，FIXED/HUG 依 horizontal/vertical align-self 定位，oversized child 仍遵守对齐。child min 不扩大 FIXED/FRACTION track。Grid overlap 合法，只由 children order 决定 paint order。
- AUTO/span deficit 是单调增加、有限稳定过程，不作 track convergence；每条 span constraint 至多处理一次，残差遵循统一数值规则。容量上限由票据 19 冻结，超预算直接失败而不近似。

### 9. Text shaping、排版与 overflow

- 每个 Run 的 exact FONT Asset 是唯一字体来源。字体度量来自 OpenType design metrics 按 `fontSizePt` 缩放，不使用 hinted pixel metrics；DPI、系统字体与平台 fallback 不能改变 layout/shrink。Layout Profile 固定 Unicode、BiDi、vertical orientation、shaping engine/features 版本；script由Unicode推断、language固定为`und`，v1没有authored locale/language/feature/axis。首批字体是single-face、non-variable、monochrome TrueType `glyf`/CFF；缺少所需glyph直接失败而不绘制`.notdef`。underline/strike只有实际使用且对应`post`/`OS/2` metrics无效时失败。
- 横排 inline axis 为 X、block axis 为 Y；竖排 inline axis 为 Y、wrapped columns 从右到左。横排只有在 definite/AT_MOST content width 下软换行，HUG width 仅由 LF 断行；竖排对 definite content height 做对应规则。`NONE` 禁止软换行但 LF 始终强制换行；`WORD` 使用 Profile 固定的 Unicode break opportunity，超长不可分单元直接 overflow、不回退 CHAR；`CHAR` 只在 extended grapheme cluster 边界断开。Run 边界本身不产生换行机会。
- 原始 whitespace 完全保留，不 collapse/trim。横排 base direction 为 LTR，但按固定 Unicode BiDi Algorithm 处理 strong RTL；竖排使用固定 vertical shaping/orientation。只接受既有 LF 合同，每个 LF 都创建下一行/列，连续与尾随 LF 保留；完全空文本仍有唯一空行/列。
- shaping cluster 不跨越不兼容 Run；Run 边界本身不增加 advance。绝对或 factor letter spacing 只加在相邻排版单元之间，跨 Run 时使用前一个 cluster 所属 Run 的 spacing；不进入 grapheme 内部、行/列边缘或 LF 两侧。factor spacing 在 shrink 后以缩放后的 font size 计算。
- FACTOR line height 取该行各 Run `fontSize × factor` 的最大值；FIXED 使用固定 advance。混合 Run 以真实字体 metrics 共用 baseline，glyph ink 可越过 line advance。LF 产生的空行使用该位置的 style；完全空文本使用唯一 Run。横排空行 width 为零、block axis 占一份 line advance，竖排对调。HUG intrinsic 取所有 line/column advance 与 glyph ink（含 Text glyph stroke）的 union，再加 Text padding。
- horizontalAlign/verticalAlign 始终是物理轴：横排分别控制 inline/block，竖排分别控制 block/inline。JUSTIFY 只扩展 Profile 定义的机会，最后一行及 hard-LF 行回退 LEFT/TOP；SPACE_EVENLY 在 line/column 内含相同端点空间，单单元居中；block-axis JUSTIFY/SPACE_EVENLY 在行或列之间分配。可用空间为负时不分配，按普通 overflow 对齐。
- SHRINK_TO_FIT 不改变 LayoutBox 或 padding，只等比缩放 font size、line advance、letter spacing、decoration 与 Text stroke，每个候选 scale 都重新 shape/reflow。目标是完整未截断内容在两轴与 maxLines 内 fit；以固定 32 次二分搜索 `[minScale,1]`，选择最大的已证明 fit 下界。minScale 仍不 fit 时再执行 overflow。
- Text ContentBox 只扣 padding；Text stroke 属于 glyph ink而非容器 border。HUG 将 glyph stroke 计入 intrinsic，FIXED/FILL 中 glyph stroke可以越过 ContentBox。`VISIBLE` 不做 self clip，且与 authored `maxLines` 组合是 hard error；`CLIP` 布局全部内容后裁到 ContentBox，maxLines 还增加第 N 行/列边界；`ELLIPSIS` 在最终可见行以 U+2026 替代被省略内容，优先用最后一个可见 Run style，无可见 glyph 时用第一个被省略 Run style，ellipsis 自身必须 shape 并 fit，否则该行为空；`FAIL` 在 shrink 后仍有任意 overflow 或超出 maxLines 时使请求失败。ellipsis 不移动 hard LF，空行与尾随 LF 也计入 maxLines。

### 10. Image、Vector、QR 与 Barcode

- Image的intrinsic只使用RenderResource technical descriptor及Engine对exact bytes复核后的一致orientation逻辑pixel宽高比，不读取Asset DPI或EXIF physical resolution；descriptor不一致、像素尺寸缺失/为零或解码失败直接失败。单轴HUG由另一definite轴和比例推出并只clamp HUG轴；clamp改变比例后由fit处理。CONTAIN居中并留下透明bars，COVER居中裁切，FILL非等比拉伸；Image总是裁到LayoutBox。票据16冻结先orientation/sRGB归一化、half-integer pixel center、nearest/bilinear premultiplied采样及edge clamp；sampling只影响绘制，绝不反馈layout。
- Line/Polygon/Polyline/Path 的 HUG 使用 local geometry 加 cap/join/miter-limit 后的 stroke-inclusive bounds，并把最小点归一到 local box `(0,0)`。FIXED/FILL 将几何/centerline bounds 非等比映射到 LayoutBox，但 authored physical-mm stroke 不随这次 geometry mapping 缩放，因而可绘制到 box 外；Node transform 则缩放/旋转完整 fill、stroke 与 subtree。某个 source extent 为零时该轴不缩放、只在目标轴居中。v1 没有 viewBox/aspect-fit，且自由 Vector 不自动 clip。
- QR final box 必须为严格正方形且两轴为正，quiet zone 位于 box 内；Barcode 两轴也必须为正但允许任意矩形，quiet zone 同样在 box 内。二者都不支持 HUG，不再隐式把 QR 居中到矩形。票据16进一步冻结完整world transform只允许translation/正uniform scale/rotation，以及整数module pitch、quiet zone、QR UTF-8+ECI 26/mask/version和EAN/UPC/Code128编码；这些device规则只在final geometry与DPI后执行，不反馈layout。

### 11. 校验、错误、诊断与一致性

- Template save 的静态 baseline 必须完成全部结构和可判定布局约束，包括 size mode/min/max、HUG/FILL cycle、Stack direction/fillWeight、Grid span/track/FRACTION-on-HUG、Absolute inset 与 QR square；这些是不可确认的 hard error。Binding、Repeat 与 TemplateUse 展开后，每个实际 occurrence 以 concrete value 再执行同一 aggregate validation。运行输入导致的失败只使本次请求失败，不把 TemplateReadiness 改为 INVALID。
- Engine 以同一 Layout Profile 做防御性验证，而不是定义第二套含义。preflight 可按 stable occurrence/property/code 收集有界问题；进入资源/布局运行阶段后按 materialized authored DFS 顺序 fail-fast。失败时不返回 Scene、Trace、Output 或 partial success。普通 overflow、signed overlap 与 paint outset 不是 warning/error，只在授权 LayoutTrace 中作为 flag。
- `LayoutTrace` 是可选、请求级、容量受限且绑定 exact Layout Profile 的诊断结果；每个 occurrence 可含 LayoutBox/ContentBox、world transform、PaintBounds/EffectivePaintBounds、有效 clip 的 kind/AABB、paint index 与 overflow flags。它不得包含原始文本、RenderInput、完整 DesignDSL 或 Asset bytes，也不参与 revision、hash、RenderOutput identity 或缓存键；API 授权和 exact occurrence identity 由后续票据冻结。
- shared conformance corpus 必须覆盖 boxes、wrap/break、transform、paint/clip order、visibility/resource failure、stable errors 与数值 tolerance；Engine 是权威，浏览器实现只能以 corpus 证明反馈一致。任何行为修正如改变可观察结果必须发布新 Layout Profile，不能热替换 `renderweave-layout/1.0`。

### 12. 对票据 09 的受控修正与明确排除

- 本票据只对票据 09 作四项受控收紧：STACK placement 新增永久 `fillWeight` Property Identity 及首批只追加 BindingPolicy；margin 仅属于 STACK/GRID placement；Frame/Stack/Grid stroke 明确完全向内并参与 ContentBox；Text 的 `VISIBLE + maxLines` 明确为 hard error。除此之外不改票据 09 已冻结的 kind、wire、default、ValueType 与 Property Identity。
- 首版明确不支持 percent/viewport/breakpoint、anchor、Stack wrap/baseline/flex grow-shrink-basis、Grid implicit/auto-placement/named area/minmax/track alignment、通用 aspect-ratio property、zIndex/order、scroll/pagination/multi-Canvas flow、Vector viewBox/fit、layout pixel snapping或浏览器式自动修复。新增 authored wire 必须进入新 DesignDSL Profile；只改变派生算法也必须进入新 Layout Profile。
- 本票据只冻结探索规格、领域语言与后续约束，不创建 LayoutEngine、RenderDSL model、NodeContract、validator、Web canvas、API、数据库或任何产品实现。
