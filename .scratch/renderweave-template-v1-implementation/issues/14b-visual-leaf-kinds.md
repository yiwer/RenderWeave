# 实现 DesignDSL visual leaf Node kinds 与 BindingPolicyCatalog 基础登记

Type: task
Status: open
Claimed by: (none)
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
