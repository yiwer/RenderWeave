# 实现 DesignDSL visual leaf Node kinds 与 BindingPolicyCatalog 基础登记

Type: task
Status: resolved
Claimed by: Codex `/root`
Blocked by: 03, 14

## Question

T14 增量 1（容器：canvas/group/frame/stack/grid 的 NodeContractCatalog、递归 admission/canonical、57
vectors、Java primary + Python independent A2）已 resolve；本票把 DesignDSL canonical kernel 的
admission 继续扩展到 visual leaf Node 全切片：text/image/rect/ellipse/line/polygon/polyline/path/
qrCode/barcode 的 exact property 树（含 Text runs/PathCommand/QR/Barcode 语义复合对象、StrokePt 与
StrokeMm 的 pt/mm 单位差异、text 的 FIXED/HUG_CONTENT 与 rect/ellipse/qr/barcode 的 FIXED/FILL mode
capability）、ContentModel（visual leaf 无 children）、placement 规则（leaf 双轴 FIXED/FILL/HUG 按
kind capability 表），并把 `FUTURE_KINDS` 中已 admission 的 kind 从
`DESIGN_KERNEL_SCOPE_UNSUPPORTED` 移除；同时登记 BindingPolicyCatalog 基础
（`(nodeKind, propertyPathPattern)` 追加式登记、bindability 判定随 T16 消费），不实现 Binding 原子。

权威输入：旧 map ticket 09（Node/Property/ContentModel/Placement/Text/Image/vector/QR/Barcode）的冻结
规格；T14 的 `NodeContractCatalog`（NodeKind/PlacementVariant/SizeMode、member 集、token 集、
expectedVariant/sizeModes、FUTURE_KINDS fail-closed）；kernel manifest
`renderweave-template-canonical-kernel-v1/2`（57 cases）。

## 边界

- 本票只做静态 admission/canonicalization（runtime 求值/lowering 属 Evaluator/Rendering）；Repeat/
  Conditional/TemplateUse 结构 kind、Definition/Binding 原子与 Expression 求值不进入本票；
  PACK placement 仍随 T17；acceptance 语义完整前 `renderweave-design/1.0` Profile 不登记 available；
  Ticket 19 open。
- vectors：manifest 升 `renderweave-template-canonical-kernel-v1/3`，Java primary + Python independent
  同步扩展，`template` gate 断言同步（case 数随冻结集），Profile=`NOT_REGISTERED` 不变。

## Handoff notes（2026-08-19，T14 容器增量 resolve 后登记）

T14 已交付：`NodeContractCatalog`（internal 唯一权威）+ `CanonicalDesignDslAuthority` 递归容器
admission/canonical（seenNodeIds 唯一、Canvas 不可为子、FUTURE_KINDS/bindings 非空 fail closed、
placement 按 parent 期望 variant、appearance/stack/grid 成员、tracks、复合对象 Fill/StrokeMm/
PaddingMm/CornerRadiiMm/Transform、children 保序）；manifest v2 57 vectors（5 admit 冻结 canonical
bytes + 19 reject 精确 pointer）；Python independent 镜像 Java 检查顺序；`template`/`fast`/`server`
gates 绿。本票从 T14 的第二个增量拆出（T14 原文允许"同票或随后续票"）。

## 实现记录（2026-08-19）

- `NodeContractCatalog`：NodeKind/KIND_BY_NAME 增 10 个 visual leaf kind（text/image/rect/ellipse/
  line/polygon/polyline/path/qrCode/barcode）、FUTURE_KINDS 只余 conditional/templateUse、
  各 kind member 集与 token 集（TEXT_MEMBERS/RUN_MEMBERS/LINE_HEIGHT_MEMBERS/IMAGE_MEMBERS/
  RECT/ELLIPSE/LINE/POLYGON/POLYLINE/PATH/QRCODE/BARCODE_MEMBERS、POINT_MM_MEMBERS、五个
  PathCommand member 集、writingMode/align/lineBreak/overflow/decoration/fitMode/imageFit/
  sampling/fillRule/errorCorrection/barcodeFormat token 集）、`allowsChildren`（leaf 全部 false）、
  `sizeModes` 表（rect/ellipse/qrCode/barcode 仅 FIXED/FILL；image FIXED/HUG/FILL 但双轴 HUG 非法）。
- `CanonicalDesignDslAuthority` leaf admission：visual leaf 无 children member（出现即
  DESIGN_MEMBER_UNKNOWN）；text = 必填非空有序 runs（Run：text 只允许 LF 控制符、fontRef AssetRef、
  fontSizePt>0、color RGBA、decoration、letterSpacingPt XOR letterSpacingFactor）+ writingMode/
  horizontalAlign/verticalAlign/lineBreak/overflow/lineHeight（FACTOR|FIXED union）/maxLines
  （VISIBLE+maxLines hard error）/padding（PaddingMm）/stroke（StrokePt：widthPt>0）/fitMode
  （SHRINK_TO_FIT 必带 0<minScale<=1，NONE 禁 minScale）；image = imageRef AssetRef + fit/sampling；
  rect/ellipse = fill/stroke 至少其一（rect 另 cornerRadii）；line = start/end 不得相同 + 必填
  stroke；polygon = ≥3 点、首尾/相邻不重复、至少三个不共线（cross product）；polyline = ≥2 点 +
  必填 stroke；path = 必填非空 commands（首条必须 MOVE_TO、至少一个 drawing command、CLOSE 不可
  连续、CLOSE 后 drawing 必须先 MOVE_TO、fillRule NONZERO|EVEN_ODD）+ fill/stroke 至少其一；
  qrCode = 必填非空 content + errorCorrectionLevel/前景/背景色；barcode = 必填 format
  （EAN_8/EAN_13/UPC_A/CODE_128）+ value（EAN/UPC 长度+全数字+check digit 算术验证，CODE_128
  1–128 printable ASCII）。
- `BindingPolicyCatalog`（新 internal 只追加注册表）：ticket 09 §8 首批授权逐 kind 展开（无
  node-kind wildcard）：canvas backgroundColor；每个 non-Canvas kind 的 render/visible/opacity/
  transform.*/ABSOLUTE x/y/STACK+GRID margin/alignSelf/GRID row/column/span/alignSelf；non-Canvas
  non-Group 的 fillWeight/widthMm/heightMm/min/max/inset；frame/stack/grid appearance 全叶子；
  stack direction/gap/justify/align；grid rowGap/columnGap/rows[*]/columns[*]；repeat
  itemLayout/instanceLayout 十项；text/image/rect/ellipse/line/polygon/polyline/path/qrCode/
  barcode 全叶子；nodeId/kind/displayName/children/bindings/placement.type/widthMode/heightMode/
  structural 字段永不授权。bindability 判定消费属 T16。
- 冻结向量：manifest `renderweave-template-canonical-kernel-v1/5`，152 cases（116 原样 + 36 新：
  12 admit 冻结 exact canonical bytes/hash——text runs、image FIXED/HUG、rect/ellipse/line/polygon/
  polyline/path/qrCode/barcode（EAN_13 与 CODE_128）；24 reject 冻结精确 code/stage/pointer——runs
  空/letterSpacing XOR/控制符/overflow+maxLines/fitMode+minScale/image 双 HUG/rect 缺 fill+stroke/
  padding 越位/rect HUG/line 同点/缺 stroke/polygon 数量/相邻重复/共线/polyline 单点/path 首命令/
  CLOSE 连续/无 drawing/qrCode 空/barcode check digit/format/leaf children）；`reject-visual-leaf-kind`
  如实改写为 `reject-future-kind-conditional`（text 已 admission，conditional 仍 fail closed）。
- 新增 `BindingPolicyCatalogTest`（3 tests：冻结首批 entries、identity/结构永不授权、逐 kind 展开
  无 wildcard）；Template module 30 tests。
- 验证：`template` gate 绿（Java=152/152 Python=152/152）；Python independent 镜像 Java 检查顺序
  （leaf 全部校验含 EAN check digit 与 polygon cross product）。Profile 保持 NOT_REGISTERED；
  plan §12 已更新为 152/152。
- 边界（诚实）：text shaping/vector HUG bounds/QR 容量/布局与像素语义属 Evaluator/Rendering
  （ticket 10/16）；Asset current 存在性/kind 属 Template dependency readiness；Binding 消费属 T16。
- 收口：删除临时 ManifestBuilder14b/组装脚本 → `template`/`fast` gates → NOTES/map/plan 同步 →
  Ticket 14b resolved/automated_verified → 单一 verified commit（不 push，待用户授权）。

## Resolution（2026-08-19）

- 实现并验证完成：`template` gate 绿（Java=152/152 Python=152/152，evidence
  `.sdlc/evidence/20260819-200756-template/`）、`fast` 绿（`.sdlc/evidence/20260819-200827-fast/`）、
  Profile 保持 NOT_REGISTERED；临时文件已删除；NOTES/map/plan 已同步；单一 verified commit 形成，
  worktree clean，未 push（待用户另行授权）。T16（Binding/PolicyCatalog 消费）与 T18（Conditional）
  为 unblocked frontier，single-writer 下一轮只 claim 其一。
