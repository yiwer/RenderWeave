# 实现 DesignDSL Repeat 原子

Type: task
Status: open
Blocked by: 03, 14, 15

## Question

如何在 DesignDSL admission/canonicalization 中实现 Repeat 结构原子（旧 map ticket 11 的冻结规格）：
Repeat 是同时拥有 nodeId 与独立 loopId 的结构 Design Node，必填非空 `children[]`（item subtree，全部
统一 PACK placement，PACK 只允许 FIXED/HUG_CONTENT 且禁止 FILL/margin/inset/alignSelf/fillWeight）；
`items` 是直接结构 ValueSource，静态可证明为 `list<T>` 或 exact reference-array 类型（缺类型证明是 hard
error）；`itemLayout`/`instanceLayout` 使用专用 closed STACK/GRID RepeatPackingSpec；固定 consumer order
（render → items → placement → visible → opacity → transform → itemLayout → instanceLayout → children
DFS）中可静态判定的部分；scalar item 的 `system-basic-*@v1` 一等 StaticSchema context 语义、loopId 在单
Template 全部循环节点内唯一、nested Repeat 的 lexical 边界与 loopIndex 规则；admission 的 unknown/null/
ContentModel/PACK 不匹配 hard error 与 canonical/向量，而不实现 runtime 求值（loop frames 物化属
Evaluator）？Conditional/TemplateUse 原子与 Editor UI 不进入本票。

权威输入：旧 map ticket 11（repeat-and-conditional-structure）与 08（identity/canonical）冻结规格。
