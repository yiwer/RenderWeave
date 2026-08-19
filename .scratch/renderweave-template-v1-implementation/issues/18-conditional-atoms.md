# 实现 DesignDSL Conditional 原子

Type: task
Status: open
Blocked by: 03, 14, 15

## Question

如何在 DesignDSL admission/canonicalization 中实现 Conditional 结构原子（旧 map ticket 11 的冻结规格）：
Conditional 是只有 true branch 的结构 Design Node（false 时整个 subtree 在后续 Binding/layout/Asset/
output 前剪枝，true 时降低为无外观 frame）；`condition` 是直接结构 ValueSource 且必填
`ERROR | FALSE` absent policy（accepted ABSENT、显式空集与 runtime ERROR 是三个不同状态）；必填非空
`children[]` 统一 ABSOLUTE placement；固定 consumer order（render → condition → placement → visible →
opacity → transform → children DFS）中可静态判定的部分；admission 的 unknown/null/ContentModel 与
condition 类型 hard error、canonical 与向量，而不实现 runtime 求值（剪枝物化属 Evaluator）？
Repeat/TemplateUse 原子与 Editor UI 不进入本票。

权威输入：旧 map ticket 11（repeat-and-conditional-structure）与 08 冻结规格。
