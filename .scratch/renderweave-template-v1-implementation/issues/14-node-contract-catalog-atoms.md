# 实现 DesignDSL NodeContractCatalog 与 Node/Property Identity 原子

Type: task
Status: in_progress
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
