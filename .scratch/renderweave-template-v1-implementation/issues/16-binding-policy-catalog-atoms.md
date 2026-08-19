# 实现 DesignDSL Binding 与 BindingPolicyCatalog 原子

Type: task
Status: resolved
Claimed by: Codex `/root`
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

## 实现记录（2026-08-19）

- `NodeContractCatalog`：BINDING_MEMBERS（bindingId/targetPropertyRef/source）、
  TARGET_PROPERTY_REF_MEMBERS（rootPropertyId/selectors）、MEMBER/INDEX_SELECTOR_MEMBERS、
  SELECTOR_KINDS、`wireName(kind)`（qrCode/barcode 非纯小写）。
- `CanonicalDesignDslAuthority`：
  - `validateBindings`：bindingId（UUID v4 + Template 全树唯一，canvas 与全部 node 共享
    namespace）、每 binding rejectUnknown、targetPropertyRef 解析、source 校验、同 node 重复
    target hard error；canonical 按 bindingId 排序。canvas 与 node 的 bindings 非空不再
    KERNEL_SCOPE_UNSUPPORTED，改为全量 admission。
  - `validateTargetPropertyRef`：rootPropertyId 必须是 authored member（NodeContract default 的
    optional member 未 materialize 时不可 Binding）；≤2 selectors 且至多一个 member + 一个
    index（重复/未知 kind hard error）；index 为非负整数且必须在 authored array 范围内；
    member 必须存在于解析后的容器；policy pattern（index→`[*]`）必须命中
    `BindingPolicyCatalog` 唯一 entry；返回解析后的 target identity 供重复检测
    （`property[index].member` 与 `property.member[index]` 归一为同一 identity）。
  - `validateBindingSource`：source kinds 只允许 context/loopIndex/definition（literal 属静态树、
    capability 仅 Expression input，二者在 /kind 拒绝）；definition 引用必须存在。
  - 测试 harness 新增 CANVAS_BINDINGS input kind（Java primary 与 Python independent 同步），
    支持 canvas bindings 向量。
- Python independent：独立重展开 BindingPolicyCatalog（ticket 09 §8 逐 kind 表，无 wildcard）并
  镜像全部 binding 校验（含 selector 解析与 policy 命中）。
- 冻结向量：manifest `renderweave-template-canonical-kernel-v1/6`，176 cases（152 原样 + 24 新：
  6 admit 冻结 exact canonical bytes/hash——bindings sorted by bindingId、context source、loopIndex
  source（Repeat PACK child）、runs[*].text、canvas backgroundColor、rows[*].valueMm；18 reject 冻结
  精确 code/stage/pointer——缺/重/非法 bindingId、unknown member、缺 target/source、literal/
  capability source、dangling definition、root 缺失、无 policy、member 缺失、index 越界/非数组、
  重复 target、extra selectors、双 member、未知 selector kind、负 index）；
  `reject-bindings-nonempty` 如实改写为 `reject-binding-missing-binding-id`（bindings [{}] →
  /bindings/0/bindingId）。
- 验证：Template module 30 tests 全绿；`template` gate 绿（Java=176/176 Python=176/176）；Python
  independent 镜像 Java 检查顺序（含独立 catalog 重展开）。Profile 保持 NOT_REGISTERED；
  plan §12 已更新为 176/176。
- 边界（诚实）：Binding overlay 的 typed static tree 求值、targetType/propertyValidation 重验、
  ABSENT/ERROR 传播属 Evaluator（本票只做存在性 + policy + identity + source shape）；Target
  type 由 NodeContract 派生，catalog 不携带类型字段（ticket 09 §8 冻结）。
- 收口：删除临时 ManifestBuilder16/组装脚本 → `template`/`fast` gates → NOTES/map/plan 同步 →
  Ticket 16 resolved/automated_verified → 单一 verified commit（不 push，待用户授权）。

## Resolution（2026-08-19）

- 实现并验证完成：`template` gate 绿（Java=176/176 Python=176/176，evidence
  `.sdlc/evidence/20260819-201717-template/`）、`fast` 绿（`.sdlc/evidence/20260819-201748-fast/`）、
  Profile 保持 NOT_REGISTERED；临时文件已删除；NOTES/map/plan 已同步；单一 verified commit 形成，
  worktree clean，未 push（待用户另行授权）。T19（TemplateUse）以本票为前置解锁，T18/T19 成为
  unblocked frontier，single-writer 下一轮只 claim 其一。
