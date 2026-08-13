# 定义封闭的节点、属性与 BindingPolicy 模型

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 07, 08

## Question

首版叶元素与结构容器的 kind 集合是什么；每个 kind 的静态属性、可绑定目标、类型、默认值、子节点能力和验证规则由哪个全局单一合同权威定义；如何以只追加 BindingPolicy 扩展可绑定属性而不重复旧系统中属性语义分散的问题？

## Inherited constraints

- Node kind 与属性模型对所有 Template 全局固定；Template 只能实例化和填写，不能定义属性 shape、ValueType、validation、bindability 或 enum catalog，也不开放运行时插件注册。
- `BaseValueType` 精确为 text/decimal/boolean/date/time/color/imageRef/fontRef；enum 与受限同质一维 `list<T>` 是派生类型，具体消费者可进一步限制可接纳的`T`。对象/复杂数组是 authored property tree 容器，不是任意 JSON ValueType。
- 全局 `BindingPolicyCatalog` 由 Node 定义者维护，前端/客户端消费、服务端保存与 Evaluation 权威执行；单条 Policy 以 nodeKind + propertyPathPattern 唯一定位已有 Property Identity，并由 NodeContract 唯一派生 targetType 与 propertyValidation。
- Catalog 只追加允许项：已有 Policy 不可修改/删除，新 Policy 不得与已有 target set 重叠且不得使既有 Template 失效；Policy 不绑定 DesignDSL version，Template 不保存 policyId/Catalog revision。
- 没有匹配 Policy 的属性不可绑定；不存在 STATIC_ONLY/STATIC_OR_BINDING/BINDING_REQUIRED 模式。每个可绑定属性仍必须拥有合法 authored static baseline，Binding 只是可选 overlay。
- Binding 位于宿主 Node 的 `bindings[]`，targetPropertyRef 不含 nodeId/slotId；支持 property、property.member、property[index]、property[index].member、property.member[index]，最多一次 member 与一次 fixed nonnegative index。
- target 的容器与叶子必须已经存在并匹配唯一 Policy；Binding 不创建 member 或扩展数组。重复 target 与 ancestor/descendant overlap 是 hard error，bindings 顺序无语义。
- overlay 后的 concrete value 必须精确匹配 targetType 并重新通过同一 propertyValidation；ABSENT/ERROR/类型或约束失败中止 Evaluation，不回退 static baseline。
- 本票据必须给出每个 Node kind/property/member/array-item 的唯一全局合同，并明确哪些 property path pattern 进入首批 BindingPolicyCatalog；不能把合同分散在 Web 控件、Evaluator switch 与 Renderer payload 中。
- `renderweave-design/1.0` 的顶层只通过必填 `designRoot` 承载全部 authored Node/结构内容；本票据必须冻结 DesignRoot exact shape、root kind/children 规则与其和画板的关系，不能增加平行顶层 elements/bindings/editor state。
- Global Node Property Identity 是永久 `nodeKind + propertyPathPattern`；一经引入，其 ValueType、结构角色、default 与 validation 跨所有 DSL version 不可改变。破坏性演进必须使用新 node kind/propertyId 与新 dslVersion，不能靠新版本复用旧 identity。
- Node 自身使用 client-generated canonical lowercase UUID v4 `nodeId`；所有 Node kind/property/member 对 unknown field 与 null 失败封闭，服务端不生成/修复 nodeId 或 opaque-preserve 未知属性后保存。
- canonical writer 不展开/删除 Node defaults，也不创建 bindable baseline；可绑定 target leaf 必须 authored 存在。票据须声明哪些 child/member arrays 有 z-order/layout 语义并保序；完全 set-like 的 flat node collection 才可按 nodeId canonical sort。
- Node/property wire 或已有 default/validation 的任何语义变化都需要新 dslVersion；只追加 BindingPolicy 不改变 wire/version，但只能授权已存在、永久同义的 property identity。

## Answer

### 1. 单一合同权威与永久身份

- `renderweave-design/1.0` 的全部 Design Node wire、Property、derived ValueType、default、propertyValidation、array item 与 ContentModel 由唯一全局 `NodeContractCatalog` 定义。Web、服务端保存、Evaluator 与 lowering 都必须消费该合同，不能分别维护控件 schema、validator switch 或 Renderer payload 映射作为第二权威。
- `NodeContractCatalog` 按 exact `dslVersion` 解释，但 Global Node Property Identity 永久为 `(nodeKind, propertyPathPattern)`。既有 identity 的 ValueType、结构角色、default 与 validation 不得在任何后续 DSL version 中改变；破坏性变化必须使用新 property 名或新 node kind，并同时引入新 dslVersion。
- 可复用的 CommonNode、Placement、Fill、Stroke 等 fragment 只可作为 Catalog 的声明/生成便利；权威验证与诊断必须能展开到具体 node kind + path，不能让 fragment 成为另一个可漂移合同。
- `BindingPolicyCatalog` 是独立的全局只追加授权集合。单条 Policy 只声明一个既有 `(nodeKind, propertyPathPattern)` 可作为 Binding target；target type、default 与 validation 始终从 NodeContract 派生，Policy 不复制这些字段，也不限制 ValueSource kind。
- Catalog 中已有 target 不可修改、删除或以重叠 pattern 重复声明；新增 Policy 只单调扩大未来 authoring 能力，不改变 wire、content hash 或现有 Binding 语义。Catalog 可以拥有运维/分发所需的单调管理版本，但 DesignDSL 不保存 policyId、Catalog revision 或自报 target type。
- 本模型不引入 Slot、SlotRegistry、Template-local property definition、运行时 Node plugin 或通用 `properties` bag。前端可用 NodeContract 与 BindingPolicy 生成控件，但 UI 配置不是合同权威。

### 2. DesignRoot、Node wire 与 ContentModel

- `designRoot` 必须直接承载唯一 Canvas Node。最小合法形态为：

```json
{
  "nodeId": "00000000-0000-4000-8000-000000000001",
  "kind": "canvas",
  "widthMm": 210,
  "heightMm": 297,
  "bindings": [],
  "children": []
}
```

- 首批 exact node kind 为：

```text
canvas | group | frame | stack | grid |
text | image | rect | ellipse | line | polygon | polyline | path |
qrCode | barcode | repeat | conditional | templateUse
```

- kind与property/member名使用lowerCamelCase；enum token使用UPPER_SNAKE_CASE。`repeat/conditional/templateUse`是票据11/12在DesignDSL 1.0正式冻结前并入本Catalog的exact structural kind，不能伪装成普通visual property、Frame或sidecar composition表。
- 每个 Node 必填 `nodeId`、`kind`、`bindings`；`displayName` 可选。`nodeId` 是 authoring client 生成的 canonical lowercase UUID v4，并在单份 DesignDSL 的整棵 `designRoot` 树内唯一；服务端不生成、修复或重写。
- `displayName` trim 后为 1–128 Unicode code points，不做 Unicode normalization；它是 authored revision/content-hash 事实，但不是求值 property，也不可 Binding。
- 每个非 Canvas Node 必填 `placement`，可选 `render`、`visible`、`opacity`、`transform`。字段直接位于 Node，不放入通用 `properties` object。
- `render` 省略时为 `true`；求值后为 `false` 时整个 subtree 不参加 layout、资源解析或输出。`visible` 省略时为 `true`；求值后为 `false` 时 subtree 保留 layout，但不绘制。`opacity` 省略时为 `1`，合法范围 `[0,1]`；具体父子 opacity 合成顺序由布局/Renderer 票据冻结。
- Canvas、Group、Frame、Stack、Grid必填`children`，即使为空；其child可以是任意当前已定义的non-Canvas Node，包括repeat/conditional/templateUse，且容器可递归嵌套。Repeat/Conditional必填非空children并使用票据11专用ContentModel；TemplateUse是禁止children的结构leaf并使用票据12调用合同；visual leaf同样禁止children。Canvas只能作为designRoot，不能出现在children中。
- `children` 顺序同时是 authored layout traversal 与 paint z-order：先出现者先绘制，后出现者覆盖在上方。不存在 `zIndex`、`order` 或 flat node collection 作为第二排序权威。
- Group 是纯结构容器：不拥有 fill、stroke、cornerRadii、padding、clip 或显式 box size；空 Group 合法且 intrinsic size 为 `0 × 0`。需要显式空白 box、appearance、padding 或 clip 时使用 Frame。
- 所有 Node/property/member/union 对 unknown field、unknown kind 与 JSON null 失败封闭；optional 只用 member omission 表达。

### 3. ValueType、单位与闭合复合对象

- property leaf 只能使用既定基础 ValueType `text | decimal | boolean | date | time | color | imageRef | fontRef`、精确 enum 或受限同质一维 `list<T>`。复杂对象和数组只作为 authored property tree 容器，不是任意 JSON ValueType，也不能整体成为 Binding target。
- geometry、placement、padding、gap、corner radius、shape stroke 与 vector coordinate 使用 mm；`fontSizePt`、固定 `lineHeight.valuePt`、绝对 `letterSpacingPt` 与 Text stroke 使用 pt；ratio/scale 无单位；rotation 使用 degree。
- 单位关系永久为 `1in = 25.4mm`、`1pt = 1/72in`。DPI 不属于 Canvas、Template 或 DesignDSL；Render Request 省略 DPI 时默认 `96px/in`，最终像素尺寸与舍入由票据 16 冻结。
- optional composite object 一旦出现，其成员必须全部出现且合法；禁止 partial object 与 null。Canonical writer 不补对象/default，也不删除显式等于 default 的 authored value。

| 对象 | exact shape 与约束 |
|---|---|
| `Fill` | `{color}` |
| `StrokeMm` | `{color,widthMm,cap,join}`；`widthMm > 0` |
| `StrokePt` | `{color,widthPt,cap,join}`；`widthPt > 0`，只供 Text |
| `PaddingMm` | `{topMm,rightMm,bottomMm,leftMm}`；全部 `>= 0` |
| `CornerRadiiMm` | `{topLeftMm,topRightMm,bottomRightMm,bottomLeftMm}`；全部 `>= 0` |
| `BleedMm` | `{topMm,rightMm,bottomMm,leftMm}`；全部 `>= 0` |
| `Transform` | `{rotationDeg,scaleX,scaleY,originX,originY}`；scale 均非零，origin 均在 `[0,1]` |
| `PointMm` | `{xMm,yMm}`；坐标可为负 |
| `AssetRef` | imageRef/fontRef的原子语义值，wire均为闭合`{assetId}`且assetId是服务端生成的canonical lowercase UUID v4 |

- Stroke `cap` 为 `BUTT | ROUND | SQUARE`，`join` 为 `MITER | ROUND | BEVEL`；MITER 使用固定 miter limit `4`，不增加 authored property。Fill/Stroke 缺失分别表示不填充/不描边；Padding/CornerRadii 缺失表示全零；Transform 缺失表示 identity。
- v1 appearance 合同是上述单一 solid Fill 与单一 Stroke 的闭合集；不含 gradient、multiple fills/strokes、shadow、dash pattern、stroke alignment 或 blend mode。
- `rotationDeg` 接受任意 decimal，不作 360-degree canonical normalization；负 scale 表示 flip。Transform 不支持 translate 或 skew。
- final box 上相邻 corner radii 之和超出边长时，按 CSS border-radius 的共同比例缩小规则统一归一化四角，不失败也不逐角裁剪。

### 4. Placement 与 size capability

- `placement` 是必填、closed、shallow union；所有可绑定叶子都是 `placement.<member>`，不再嵌套 size/margin 对象。父 ContentModel 固定 placement variant：Canvas/Frame/Group/Conditional child 使用 `ABSOLUTE`，Stack child 使用 `STACK`，Grid child 使用 `GRID`，Repeat child 使用 `PACK`；不匹配是 hard error，编辑器 reparent 必须显式转换。
- 每个 non-Canvas placement 必填 `widthMode` 与 `heightMode`。ABSOLUTE/STACK/GRID各轴允许`FIXED | HUG_CONTENT | FILL`并继续受node-kind capability限制；PACK只允许`FIXED | HUG_CONTENT`。FIXED必填对应`widthMm/heightMm > 0`，HUG/FILL禁止对应尺寸字段；FILL要求父轴definite，任何HUG/FILL dependency cycle是hard error。
- 除 Group 外，placement 可选 `minWidthMm/minHeightMm >= 0` 与 `maxWidthMm/maxHeightMm > 0`；同轴 `min <= max`，FIXED 值必须落在范围内，HUG/FILL 的 clamp 顺序由票据 10 冻结。Group 禁止全部 min/max。
- `ABSOLUTE` exact members 为 `type: "ABSOLUTE"`、必填 `xMm/yMm`、两轴 mode/conditional size、可选 min/max；width FILL 时可选 `rightInsetMm`，height FILL 时可选 `bottomInsetMm`，二者省略为 `0`，其他 mode 禁止对应 inset。坐标、inset 可为负，原点为父 content box 左上角。
- `STACK` exact members 为 `type: "STACK"`、两轴 mode/conditional size、可选 min/max、四个可选 `margin*Mm`（省略为 `0`，允许负值）、可选 `alignSelf: START | CENTER | END` 与可选 `fillWeight > 0`（省略为 `1`）。alignSelf 只覆盖 cross axis；cross-axis FILL 时显式 alignSelf 非法。fillWeight 只在 owning Stack 当前 direction 对应的 main-axis mode 为 FILL 时合法，direction/weight 经 Binding 后必须重新做 aggregate validation。没有 order、grow、shrink 或 flex basis。
- `GRID` exact members 为 `type: "GRID"`、两轴 mode/conditional size、可选 min/max、必填零基非负整数 `row/column`、可选正整数 `rowSpan/columnSpan`（省略为 `1`）、四边 margin，以及可选 `horizontalAlignSelf/verticalAlignSelf: START | CENTER | END`（省略为 START）。对应轴 FILL 时显式 align-self 非法；禁止 absolute inset 与 stack alignSelf。
- `PACK` exact members 为`type: "PACK"`、两轴FIXED/HUG_CONTENT mode与conditional size、可选min/max；只允许Repeat direct child。它禁止FILL、margin、x/y/inset、row/column/span、alignSelf、fillWeight与普通Stack/Grid hint；itemLayout在STACK/GRID间切换无需改写child placement。
- margin 仅存在于 STACK/GRID placement，允许负值且不折叠；ABSOLUTE/PACK placement 不存在 margin。padding 与 gap 必须非负。多个 Stack main-axis FILL child 按 fillWeight 分配剩余空间；精确 water filling、rounding 与 min/max 冲突处理由票据 10 冻结。

| node kind | width/height mode capability |
|---|---|
| `group` | 两轴只能 HUG_CONTENT |
| `frame/stack/grid/text` | 两轴均可 FIXED/HUG_CONTENT/FILL |
| `repeat/conditional/templateUse` | 两轴均可 FIXED/HUG_CONTENT/FILL；作为Repeat direct child时受PACK限制为FIXED/HUG_CONTENT |
| `image` | FIXED/FILL；最多一轴 HUG_CONTENT，另一轴必须 FIXED/FILL |
| `rect/ellipse/qrCode/barcode` | 两轴只能 FIXED/FILL |
| `line/polygon/polyline/path` | 两轴均可 FIXED/HUG_CONTENT/FILL |

### 5. Canvas 与 layout containers

- Canvas exact properties 为必填 `widthMm > 0`、`heightMm > 0`、`bindings`、`children`，可选 `backgroundColor`（省略为透明色 `#00000000`）与完整 `bleed`。Canvas 禁止 placement、transform、render、visible 与 opacity；Canvas size 与 bleed 不可 Binding，backgroundColor 可 Binding。
- Frame、Stack、Grid 都可选 `fill`、`stroke`、`cornerRadii`、`padding`、`clipContent`；`clipContent` 省略为 `false`。Group 没有这些 property。
- Frame、Stack、Grid 的 stroke 完全向 LayoutBox 内部绘制；ContentBox 先扣 inward stroke 再扣 padding，`clipContent` 使用 inner-border rounded shape 而不是 padding 边界。Rect/Ellipse 的 stroke 同样向内，但没有 child ContentBox；Text stroke 是 glyph stroke，不是容器 border。
- Stack 可选 `direction: ROW | COLUMN`（默认 COLUMN）、`gapMm >= 0`（默认 `0`）、`justifyContent: START | CENTER | END | SPACE_BETWEEN | SPACE_AROUND | SPACE_EVENLY`（默认 START）与 `alignItems: START | CENTER | END`（默认 START）。Stack child 未写 alignSelf 时继承 parent alignItems；不提供 STRETCH，FILL 是唯一 stretch authority。
- Grid 必填非空有序 `rows` 与 `columns`，可选 `rowGapMm/columnGapMm >= 0`（默认 `0`）。Track 是 closed union：

```json
{"type":"FIXED","valueMm":10}
{"type":"FRACTION","weight":1}
{"type":"AUTO"}
```

- FIXED `valueMm > 0`，FRACTION `weight > 0`；type 不可 Binding，实际存在的 valueMm/weight 可 Binding。Grid 不提供 named track、minmax、implicit track 或 template areas。child span 必须在 explicit grid bounds 内；overlap 合法，children order 决定 paint order。
- Repeat/Conditional的exact结构字段、ValueSource、ABSENT policy、词法frame、item/instance packing及lowering以票据 11为权威。Repeat children是item subtree并统一PACK placement；Conditional children统一ABSOLUTE。二者没有appearance/padding/clip；Repeat的`itemLayout/instanceLayout`只使用专用closed STACK/GRID RepeatPackingSpec，不是普通Grid implicit-track扩展。
- TemplateUse的exact字段、logical TemplateRef、ContextSelector、fills、same-scope invocation与compositionViewport lowering以票据12为权威。它没有children/appearance/padding/clip/fit，HUG intrinsic size为child Canvas trim；FIXED/FILL只改变host box并使用静态CONTAIN/CENTER/clip，不把父约束反馈给child reflow。

### 6. Text 与 Run

- Text exact properties 为必填非空有序 `runs`，以及可选 `writingMode`、`horizontalAlign`、`verticalAlign`、`lineBreak`、`overflow`、`lineHeight`、`maxLines`、`padding`、`stroke`、`fitMode`、`minScale`。
- 每个 Run 是 closed object，必填 `text`、`fontRef`、`fontSizePt > 0`、`color`、`decoration`，并且 `letterSpacingPt` 与 `letterSpacingFactor` 必须且只能出现一个。Run 没有 runId；index 是 revision-local identity，数组重排若要保持 Binding 意图必须同步重写 target index。
- `decoration` 为 `NONE | UNDERLINE | LINE_THROUGH`。weight/italic 只能由 exact FONT AssetRef 表达，不提供独立 run property；Renderer 不得使用隐式 family、环境字体或 fallback，任一 required glyph 缺失必须使该次 Render 失败。编辑器可以有全局/作用域创建 preset，但创建时必须把 exact fontRef 快照写进每个 Run。
- `letterSpacingPt` 是绝对附加 advance；`letterSpacingFactor` 是 `fontSizePt × factor` 的附加 advance。二者接受负 decimal，不裁剪；只施加在相邻排版单元之间，不加在行首、行尾或强制换行两侧。
- Run text 按数组顺序无分隔拼接；Run 边界只改变样式。空 Run 合法；整个空文本必须表示为一个 `text:""` Run，不增加 top-level text/plainText alias 或另一种纯文本 kind。
- text 只允许 LF `\n` 作为强制换行控制符；CR、TAB 与其他 C0 control 非法。Unicode scalar 原值保留且不做 NFC/NFKC normalization。
- `writingMode` 为 `HORIZONTAL_TB | VERTICAL_RL`（默认 HORIZONTAL_TB）；VERTICAL_RL 中字符沿上到下前进，wrapped columns 从右到左。v1 不提供 VERTICAL_LR 或独立 RTL mode。
- `horizontalAlign` 为 `LEFT | CENTER | RIGHT | JUSTIFY | SPACE_EVENLY`（默认 LEFT）；`verticalAlign` 为 `TOP | CENTER | BOTTOM | JUSTIFY | SPACE_EVENLY`（默认 TOP）。它们作用于 Text 内部 box 的物理轴，不替代 parent placement。
- `lineBreak` 为 `NONE | WORD | CHAR`（默认 WORD）；`overflow` 为 `VISIBLE | CLIP | ELLIPSIS | FAIL`（默认 CLIP）。旧内容需要越界可见时必须显式写 VISIBLE。
- `lineHeight` 是 closed union `{type:"FACTOR",factor}` 或 `{type:"FIXED",valuePt}`，数值都必须 `> 0`；字段省略时默认 FACTOR `1.2`。type 不可 Binding，实际数值 member 可 Binding。
- `maxLines` 若存在必须是 `>= 1` 的整数；横排表示最大 rows，竖排表示最大 columns；`overflow: VISIBLE` 与 maxLines 同时 authored 是 hard error。`fitMode` 为 `NONE | SHRINK_TO_FIT`（默认 NONE）；SHRINK_TO_FIT 必须携带 `0 < minScale <= 1`，NONE 禁止 minScale。fitMode 不可 Binding，已存在 minScale 可 Binding；先统一缩放全部 Run，达到 minScale 后再应用 overflow。v1 不另设 autoSizeText/overScale；box HUG_CONTENT 与 fitMode 分别承担 intrinsic sizing 和文字缩放。
- Text HUG_CONTENT 包含排版内容、padding 与 glyph stroke bounds；Text ContentBox 只扣 padding。shaping、mixed-run line box、vertical layout、ellipsis 与 pixel rounding 由票据 10、16 冻结。

### 7. Image、vector、QR 与 Barcode

- Image 必填 `imageRef`，可选 `fit: CONTAIN | COVER | FILL`（默认 CONTAIN）与 `sampling: LINEAR | NEAREST`（默认 LINEAR）。v1 不含 URL/base64、crop、focal point、filter、alt、corner property 或缺乏稳定物理语义的 fit NONE；corner/clip 使用 Frame。
- Image 单轴 HUG_CONTENT 只用已解析图片的 pixel aspect ratio 推导另一轴，不使用 Asset DPI；双轴 HUG 非法。默认 Render DPI 也不改变 authored Image 物理语义。
- Rect 可选 fill、stroke、cornerRadii；Ellipse 可选 fill、stroke；二者至少出现 fill 或 stroke。Line 必填 `start`、`end` 与 stroke，且 start/end 不得相同。
- Polygon 必填至少三个有序 Point、可选 fill/stroke且至少出现一个；首尾不得重复、相邻点不得重复，并至少存在三个不共线点。Polygon 隐式闭合。Polyline 必填至少两个有序 Point 与 stroke，相邻点不得重复。
- Path 必填非空有序 `commands`，可选 fill/stroke且至少出现一个；`fillRule: NONZERO | EVEN_ODD` 省略为 NONZERO。PathCommand exact union 为：

```text
MOVE_TO  {type,xMm,yMm}
LINE_TO  {type,xMm,yMm}
QUAD_TO  {type,cxMm,cyMm,xMm,yMm}
CUBIC_TO {type,c1xMm,c1yMm,c2xMm,c2yMm,xMm,yMm}
CLOSE    {type}
```

- 首条 command 必须 MOVE_TO，至少有一个 drawing command；CLOSE 只能关闭当前开放 subpath、不可连续出现，CLOSE 后继续 drawing 必须先 MOVE_TO。允许多个 subpath。Fill 对开放 subpath 作隐式闭合，stroke 只有显式 CLOSE 才闭合。
- vector coordinate 是 local、pre-transform mm，可为负。HUG bounds、FIXED/FILL scaling、stroke bounds、negative-origin mapping 与 min/max 应用顺序属于票据 10；本合同不预设 viewBox 或额外 scale property。
- QR Code 必填非空 `content`，可选 `errorCorrectionLevel: L | M | Q | H`（默认 M）、`foregroundColor`（默认 `#000000FF`）与 `backgroundColor`（默认 `#FFFFFFFF`）。内容按 UTF-8 编码，quiet zone 固定四个 module，容量超限失败；final content box 必须为正方形，不自动拉伸或在矩形中隐式居中。Color 只做类型验证，不承诺可扫描对比度。
- Barcode 必填 `format: EAN_8 | EAN_13 | UPC_A | CODE_128` 与 `value`，可选 `foregroundColor`（默认 `#000000FF`）与 `backgroundColor`（默认 `#FFFFFFFF`），不绘制 human-readable text。EAN/UPC value 必须包含并通过完整 check digit；CODE_128 接受 1–128 个 printable ASCII characters。quiet zone 与编码选择是固定 Renderer 语义，不开放 authored property。

### 8. 首批 BindingPolicyCatalog

- 以下表中的 kind group 只是规格缩写；实际 Catalog 必须展开成逐个 `(nodeKind, propertyPathPattern)` entry，运行时不存在 node-kind wildcard。Array Policy 只能使用 `[*]`；具体 Binding target 才使用 fixed nonnegative index。
- 只有原子 semantic value、derived enum 或 scalar leaf 可成为 target。Fill、Stroke、Placement、Run、Point、Track、PathCommand、AssetRef 内部 member、任意 array/object 整体都不可替换；imageRef/fontRef 虽以 object wire 表达，在 ValueType 中仍是不可拆分原子值。

| kind scope | 首批授权 propertyPathPattern |
|---|---|
| `canvas` | `backgroundColor` |
| 每个 non-Canvas kind | `render`, `visible`, `opacity`, `transform.rotationDeg`, `transform.scaleX`, `transform.scaleY`, `transform.originX`, `transform.originY` |
| 每个 non-Canvas kind 的 ABSOLUTE variant | `placement.xMm`, `placement.yMm` |
| 每个 non-Canvas kind 的 STACK/GRID variant | `placement.marginTopMm`, `placement.marginRightMm`, `placement.marginBottomMm`, `placement.marginLeftMm` |
| 每个 non-Canvas kind 的 STACK variant | `placement.alignSelf` |
| 每个 non-Canvas、non-Group kind 的 STACK variant | `placement.fillWeight`；只在 owning Stack 当前 main-axis mode 为 FILL 时可求值 |
| 每个 non-Canvas kind 的 GRID variant | `placement.row`, `placement.column`, `placement.rowSpan`, `placement.columnSpan`, `placement.horizontalAlignSelf`, `placement.verticalAlignSelf` |
| 每个 non-Canvas、non-Group kind | `placement.widthMm`, `placement.heightMm`, `placement.minWidthMm`, `placement.minHeightMm`, `placement.maxWidthMm`, `placement.maxHeightMm` |
| 每个 non-Canvas、non-Group kind 的 ABSOLUTE variant | `placement.rightInsetMm`, `placement.bottomInsetMm` |
| 每个 non-Canvas、non-Group kind 的 PACK variant | `placement.widthMm`, `placement.heightMm`, `placement.minWidthMm`, `placement.minHeightMm`, `placement.maxWidthMm`, `placement.maxHeightMm`；只允许Repeat direct child且mode必须为FIXED/HUG_CONTENT |
| `frame`, `stack`, `grid` | `fill.color`, `stroke.color`, `stroke.widthMm`, `stroke.cap`, `stroke.join`, `cornerRadii.topLeftMm`, `cornerRadii.topRightMm`, `cornerRadii.bottomRightMm`, `cornerRadii.bottomLeftMm`, `padding.topMm`, `padding.rightMm`, `padding.bottomMm`, `padding.leftMm`, `clipContent` |
| `stack` | `direction`, `gapMm`, `justifyContent`, `alignItems` |
| `grid` | `rowGapMm`, `columnGapMm`, `rows[*].valueMm`, `rows[*].weight`, `columns[*].valueMm`, `columns[*].weight` |
| `repeat` | `itemLayout.direction`, `itemLayout.gapMm`, `itemLayout.columns`, `itemLayout.columnGapMm`, `itemLayout.rowGapMm`, `instanceLayout.direction`, `instanceLayout.gapMm`, `instanceLayout.columns`, `instanceLayout.columnGapMm`, `instanceLayout.rowGapMm`；全部按当前packing variant匹配，optional baseline必须先materialize |
| `text` | `runs[*].text`, `runs[*].fontRef`, `runs[*].fontSizePt`, `runs[*].color`, `runs[*].letterSpacingPt`, `runs[*].letterSpacingFactor`, `runs[*].decoration`, `writingMode`, `horizontalAlign`, `verticalAlign`, `lineBreak`, `overflow`, `lineHeight.factor`, `lineHeight.valuePt`, `maxLines`, `padding.topMm`, `padding.rightMm`, `padding.bottomMm`, `padding.leftMm`, `stroke.color`, `stroke.widthPt`, `stroke.cap`, `stroke.join`, `minScale` |
| `image` | `imageRef`, `fit`, `sampling` |
| `rect` | `fill.color`, `stroke.color`, `stroke.widthMm`, `stroke.cap`, `stroke.join`, `cornerRadii.topLeftMm`, `cornerRadii.topRightMm`, `cornerRadii.bottomRightMm`, `cornerRadii.bottomLeftMm` |
| `ellipse` | `fill.color`, `stroke.color`, `stroke.widthMm`, `stroke.cap`, `stroke.join` |
| `line` | `start.xMm`, `start.yMm`, `end.xMm`, `end.yMm`, `stroke.color`, `stroke.widthMm`, `stroke.cap`, `stroke.join` |
| `polygon` | `points[*].xMm`, `points[*].yMm`, `fill.color`, `stroke.color`, `stroke.widthMm`, `stroke.cap`, `stroke.join` |
| `polyline` | `points[*].xMm`, `points[*].yMm`, `stroke.color`, `stroke.widthMm`, `stroke.cap`, `stroke.join` |
| `path` | `commands[*].xMm`, `commands[*].yMm`, `commands[*].cxMm`, `commands[*].cyMm`, `commands[*].c1xMm`, `commands[*].c1yMm`, `commands[*].c2xMm`, `commands[*].c2yMm`, `fill.color`, `stroke.color`, `stroke.widthMm`, `stroke.cap`, `stroke.join`, `fillRule` |
| `qrCode` | `content`, `errorCorrectionLevel`, `foregroundColor`, `backgroundColor` |
| `barcode` | `format`, `value`, `foregroundColor`, `backgroundColor` |

- 未授权且明确不可授权的首批target包括nodeId/kind/displayName/children/bindings、placement.type、widthMode/heightMode、Canvas size/bleed、lineHeight.type、fitMode、Track.type、PathCommand.type、Repeat items/absentPolicy/loopId/packing kind、Conditional condition/absentPolicy、TemplateUse useId/templateRef/contextSelector/contextAbsentPolicy/fills、array length/order与任何container/object whole value。
- target leaf、其 container 与 concrete index 必须已经存在于 authored static property tree，并匹配唯一 Policy；省略但有 NodeContract default 的 member 仍不能 Binding，编辑器必须先 materialize 合法 baseline。Binding 不创建 member、切换 union variant、扩展 array 或改变 topology。
- overlay 结果必须精确匹配该 Property Identity 的 ValueType/enum，并再次通过同一 propertyValidation 与 aggregate validation；ABSENT、ERROR、type/range/domain failure 都中止 Evaluation，不作 coercion、clamp 或 static fallback。

### 9. Canonical order、验证与 Asset readiness

- `children`、`runs`、`points`、`commands`、Grid `rows/columns` 都有语义顺序并保序；`bindings` 是 set-like，保存前按 bindingId canonical sort。当前 Node wire 没有可按 nodeId 排序的 flat collection。
- Node validation 的权威 phase 顺序为：closed JSON/field type → property/composite constraints → ContentModel 与 parent/placement compatibility → whole-tree nodeId/aggregate constraints → Binding target existence/overlap/Policy → external dependency readiness。结构阶段不安全时不执行后续 IO。
- duplicate Binding target、ancestor/descendant overlap、missing/越界 target、union variant mismatch 与无 Policy 都是不可确认 hard error；服务端不得自动删 Binding 或生成 baseline。
- NodeContract 对 imageRef/fontRef 只验证 exact ValueType shape 与 Asset identifier syntax；Asset current 是否存在、ACTIVE、kind matching 属于 Template dependency/readiness。结构合法但 authored AssetRef 无效时可按既定二阶段流程保存为 INVALID，但不能 Render。
- 所有静态可发现的authored AssetRef atom都以独立canonical pointer进入current-only dependency projection，包括被Binding覆盖的Node baseline、Definition/Mapping/default及asset-ref list中尚未demand的值。由RenderInput或运行时definition透传的imageRef/fontRef不进入反向索引；只有最终materialized Node property消费才形成OccurrencePath+ConsumerPropertyRef resolve occurrence，失败使当次Render零输出但不会据此自动把Template current标记INVALID。
- Canvas physical size及Text/Vector/QR等aggregate规则由票据10定义；票据15已冻结Evaluator只做static/post-binding/量化后可判定preflight，依赖final geometry的唯一权威是Engine按exact Layout Profile的measure/arrange。任一阶段不得改变本票据已冻结的property identity、unit、default或failure-closed语义；Node、Run、Point、PathCommand、Track等更低聚合数量和深度预算由票据19冻结。

### 10. 证据与明确延期

- 历史证据证明旧设计器采用 nested children、Canvas/Stack/Grid 与 node-local property binding，同时也暴露了属性合同分散和字体 fallback 漂移；本答案选择全局 NodeContractCatalog 与显式 fontRef 消除这些第二权威。
- 历史文字能力的迁移目标是已证实的 vertical writing、双轴 distributed alignment、padding、text stroke 与 shrink-to-fit 的语义覆盖，不承诺旧环境 fallback、历史 bug 或 pixel-identical output。
- 票据10已冻结layout allocation、HUG measurement、vector scaling/bounds、transform/clip/opacity顺序及text shaping，并通过受控修正增加永久`placement.fillWeight`、收紧margin variant、inward stroke box model与`VISIBLE + maxLines`约束；票据11/12又在DesignDSL 1.0正式冻结前受控加入repeat/conditional/templateUse、PACK、RepeatPacking及TemplateUse invocation相关Property Identity。票据16冻结DPI-to-pixel rounding、字体/图片解码与输出。除这些显式扩展外，后续票据只能收紧派生算法，不能改动已冻结authored wire与永久Property Identity。
- 本票据只冻结探索规格与下游约束，不创建 Node Catalog、validator、Editor、Evaluator、Renderer、API 或持久化实现。

研究依据：

- [`docs/research/2026-08-11-element-and-layout-container-content-models.md`](../../../docs/research/2026-08-11-element-and-layout-container-content-models.md)
- [`docs/research/2026-08-12-design-node-layout-binding-source-facts.md`](../../../docs/research/2026-08-12-design-node-layout-binding-source-facts.md)
- [`docs/research/2026-08-12-design-layout-draw-text-capability-parity.md`](../../../docs/research/2026-08-12-design-layout-draw-text-capability-parity.md)
