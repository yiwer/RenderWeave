# 实现 DesignDSL NodeContractCatalog 与 Node/Property Identity 原子

Type: task
Status: resolved
Claimed by: Codex `/root`
Blocked by: 03

## Question

如何把 DesignDSL canonical kernel 的 admission（`DesignDslAuthority.admit(rawUtf8)`）扩展到
`renderweave-design/1.0` 的容器与 visual leaf Node 全切片：NodeContractCatalog（closed NodeKind 集合、
per-kind property tree、ValueType/单位、ContentModel、placement variant capability、Node Property
Identity 永久性）如何成为唯一权威并被 admission/canonicalization 消费；Canvas/group/frame/stack/grid 与
text/image/rect/ellipse/line/polygon/polyline/path/qrCode/barcode 的 exact property、闭合复合对象
（Fill/StrokeMm/StrokePt/PaddingMm/CornerRadiiMm/BleedMm/Transform/PointMm/AssetRef）、必填 placement
（ABSOLUTE/STACK/GRID，PACK 随 Repeat 票）、children/ContentModel 与 unknown/null 失败封闭、canonical
展开与 exact vectors 如何落成可验证增量，而不登记 partial Profile available？Repeat/Conditional/
TemplateUse 结构 kind、Definition/Binding 原子与 Expression 求值不进入本票；本票只做静态 admission/
canonicalization（runtime 求值与 lower 属 Evaluator/Rendering 侧）。

权威输入：旧 map tickets 08（envelope/身份/canonical/失败封闭）与 09（Node/Property/ContentModel/
Placement/Text/Image/vector/QR/Barcode）的冻结规格；kernel 现状（33 vectors、`DESIGN_KERNEL_SCOPE_
UNSUPPORTED` 拒绝非空 children/definitions/bindings）。

## Handoff notes（2026-08-19，交给下一 session 继续）

T14 已 claim，尚未实现。kernel 现状（`CanonicalDesignDslAuthority.validateAndNormalize`）：
strict parse（`StrictJsonParser`，九项 raw/token 预算）→ 根 envelope 校验（dslVersion/expressionProfile/
displayName/description/definitions 空/designRoot 单 Canvas）→ Canvas 校验（nodeId UUID v4/widthMm/
heightMm/backgroundColor RGBA/bleed）→ bindings/children 空才放行 → metadata trim → canonical writer
（member 名 UTF-8 字节序排序 + decimal plain notation + domain hash）；所有 Node/复合对象 unknown/null
失败封闭。

首个增量建议（容器优先，保持 A2 双语言可重放）：
1. 新增 internal `NodeContractCatalog`（唯一权威）：closed NodeKind 集合（首批容器
   canvas/group/frame/stack/grid）+ 每 kind 的 property tree/ValueType/单位/ContentModel/placement
   variant capability + Node Property Identity（`nodeKind + propertyPathPattern` 永久化登记）；public
   surface 不变（仍只 `DesignDslAuthority.admit`）。
2. admission 扩展：递归校验容器 children（ContentModel：Canvas 只能根、Group/Frame/Stack/Grid 必填
   children 且可嵌套、Group 无 appearance/padding/clip/box size、Frame/Stack/Grid 的 fill/stroke/
   cornerRadii/padding/clipContent、Stack direction/gap/justifyContent/alignItems、Grid 必填非空
   rows/columns + FIXED/FRACTION/AUTO track、Canvas backgroundColor 可 Binding 而 size/bleed 不可）；
   非 Canvas Node 必填 placement（ABSOLUTE/STACK/GRID：widthMode/heightMode FIXED/HUG_CONTENT/FILL、
   min/max、ABSOLUTE x/y/inset、STACK margin/alignSelf/fillWeight、GRID row/column/span/alignSelf；
   PACK 随 T17）；闭合复合对象（Fill/StrokeMm/StrokePt/PaddingMm/CornerRadiiMm/BleedMm/Transform/
   PointMm/AssetRef）逐 member 校验且 optional 出现即完整；ValueType/单位/枚举（color RGBA、mm/pt/
   degree、cap/join、clipContent 等）与 mode capability 表（group 双轴 HUG、rect/ellipse/qr/barcode
   双轴 FIXED/FILL 等随 visual leaves 增量）。
3. canonical：children 保持 authored 顺序（paint z-order 语义）、bindings 按 bindingId 排序（本增量
   恒空）、不展开 default、不重排树；`DESIGN_KERNEL_SCOPE_UNSUPPORTED` 改为仅剩
   definitions/bindings 非空与 visual leaf/Repeat/Conditional/TemplateUse kind。
4. vectors + 双语言：manifest 版本升级（`renderweave-template-canonical-kernel-v1/1` → 新版本），Java
   primary + Python independent 同步扩展（容器/placement/复合对象/ContentModel 的 admit/reject 与
   canonical bytes 精确断言）；`template` gate 断言 Profile=`NOT_REGISTERED` 不变。
5. 验证：module TDD red/green、`template` composite（Java=Python=N/N）、`fast`、随后 `full`；
   Template save 纵切（T06）经同一 authority 自动接纳新 DSL，无需改 controller。

第二个增量（同票或随后续票）：visual leaf kind（text/image/rect/ellipse/line/polygon/polyline/path/
qrCode/barcode + Text runs/PathCommand/QR/Barcode 语义）与 BindingPolicyCatalog 基础登记（
`(nodeKind, propertyPathPattern)` 追加式、bindability 判定随 T16 消费）。

诚实边界：本票只做静态 admission/canonicalization；runtime 求值/lowering 属 Evaluator/Rendering；
Repeat/Conditional/TemplateUse 结构 kind、Definition/Binding 原子不进入本票；acceptance 语义完整前
`renderweave-design/1.0` Profile 不登记 available；Ticket 19 open。

## Increment 1 进度（2026-08-19 晚，实现完成）

容器增量已实现并双语言验证，待收口：
- `NodeContractCatalog`（internal 唯一权威）：NodeKind/PlacementVariant/SizeMode 枚举、KIND_BY_NAME、
  FUTURE_KINDS、COMMON/CONTAINER/APPEARANCE/STACK/GRID member 集、FILL/STROKE_MM/TRANSFORM member 集、
  PADDING/CORNER_RADII 有序 member 列表、ABSOLUTE/STACK/GRID placement member 集（含 widthMm/heightMm）、
  token 枚举集、expectedVariant(parentKind)、sizeModes(kind)（group 仅 HUG_CONTENT）、allowsChildren。
- `CanonicalDesignDslAuthority`：`validateChildren`/`validateNonCanvasNode` 递归（seenNodeIds 防重复
  nodeId、Canvas 不可为子、FUTURE_KINDS→KERNEL_SCOPE_UNSUPPORTED、bindings 非空→KERNEL_SCOPE_UNSUPPORTED、
  displayName trim、render/visible/opacity/transform、必填 placement 按 parent 期望 variant、appearance
  成员校验、stack/grid 专属成员、children 规范化保序）；`validatePlacement`（ABSOLUTE x/y/inset、
  STACK margin/alignSelf/fillWeight 主/交叉轴 FILL 规则、GRID row/column/span/alignSelf、FIXED 必填
  widthMm/heightMm、HUG/FILL 禁 widthMm、min/max 与 FIXED 交叉检查、group 禁 min/max）；复合对象
  Fill/StrokeMm/PaddingMm/CornerRadiiMm/Transform 逐 member；grid tracks 必填非空 + FIXED/FRACTION/AUTO；
  padding/cornerRadii 用有序列表保证确定性 reject pointer。
- 验证结果：manifest `renderweave-template-canonical-kernel-v1/2`，57 vectors（33 原 + 1 重写
  reject-empty-object-child + 24 新：5 admit 含 canonical bytes/hash 冻结 + 19 reject 精确 pointer），
  Java primary 27/27 module tests、Python independent 57/57（A2）、`template` gate 绿
  （Java=57/57 Python=57/57，static 0 diff）、`fast` 绿。Profile 保持 NOT_REGISTERED。
- 剩余（本票收口）：删除临时 VectorProbe/ManifestBuilder/ReplayRunner 与 .scratch 辅助脚本、更新
  NOTES/tracker、`full` gate、evidence 汇总（A1 + A2）、resolve ticket + verified commit；第二个增量
  （visual leaves + BindingPolicyCatalog 基础）另行开始。

## T14 收口 checklist

- [x] NodeContractCatalog + 递归容器 admission/canonical 实现
- [x] 5 admit + 19 reject 新 vectors + reject-empty-object-child 重写，manifest v2（57 cases）
- [x] Python independent 扩展（节点/placement/复合对象/ContentModel 校验，57/57）
- [x] `template` gate 绿（Java=57/57 Python=57/57，static 0 diff，evidence
  `.sdlc/evidence/20260819-191833-template/`）
- [x] `fast` gate 绿（`.sdlc/evidence/20260819-192312-fast/`）
- [x] `server` gate 绿（BUILD SUCCESS，`.sdlc/evidence/20260819-191804-server/`）
- [x] 删除临时文件（VectorProbe/ManifestBuilder/ReplayRunner/.scratch 脚本）
- [x] NOTES/tracker/plan §12 已同步（plan §12 已改 57/57；frontier/status/map 已更新）
- [x] evidence 汇总写入 NOTES/issue，Ticket 14 resolved/automated_verified（容器增量）
- [x] 单一 verified commit，worktree clean

Resolution（2026-08-19）：容器增量交付并验证（Java primary 27/27 module tests、Python independent
57/57 A2、template/fast/server gates 绿，Profile 保持 NOT_REGISTERED）。visual leaf 增量按票内
"同票或随后续票" 拆分为新票 [14b](14b-visual-leaf-kinds.md)（open，Blocked by 03/14）；
T14b/T15/T16 成为 unblocked frontier，single-writer 下一轮只 claim 其一。提交后未 push（待用户另行
授权）。
