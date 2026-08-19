# 实现 DesignDSL Binding 与 BindingPolicyCatalog 原子

Type: task
Status: open
Blocked by: 03, 14

## Question

如何在 DesignDSL admission/canonicalization 中实现 Binding 全切片（旧 map tickets 07/09 的冻结规格）：
Binding 只位于宿主 Design Node 的 `bindings[]`（wire 只含 bindingId、closed targetPropertyRef 与 source，
不引入 nodeId/slotId）；targetPropertyRef 最多一次 member + 一次固定非负 index selector，禁止动态 path/
wildcard/任意 JSON Pointer/创建缺失 property；全局只追加、不允许 target 重叠的 BindingPolicyCatalog 以
`(nodeKind, propertyPathPattern)` 逐条展开授权（array 只允许 `[*]`），Template 不能携带 policyId/Catalog
revision/自报 target type；admission 先形成完整 typed static property tree 再应用 node-local、互不重叠的
Binding overlay，并重新执行 exact leaf 与 aggregate validation（ABSENT/ERROR/类型/约束失败绝不回退
baseline）；canonical 按 bindingId 排序与 exact vectors，而不实现求值（Evaluator）与编辑器 UI？
Repeat/Conditional/TemplateUse 的 Bindings 交互随各自原子票。

权威输入：旧 map tickets 07/09 冻结规格；kernel 现状。
