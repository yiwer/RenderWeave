# 实现 DesignDSL Conditional 原子

Type: task
Status: resolved
Claimed by: Codex `/root`
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

## 实现记录（2026-08-19）

- `NodeContractCatalog`：NodeKind/KIND_BY_NAME 增 CONDITIONAL（`FUTURE_KINDS` 清空——v1 全部 kind
  已 admission，未来 wire kind 必须带新 dslVersion）、CONDITIONAL_MEMBERS（condition/absentPolicy）、
  CONDITIONAL_ABSENT_POLICY_TOKENS（FALSE|ERROR）、allowsChildren(true)、sizeModes FIXED/HUG/FILL、
  expectedVariant(CONDITIONAL)=ABSOLUTE。
- `CanonicalDesignDslAuthority.validateConditionalMembers`：condition 结构 ValueSource
  （literal → valueType 必须 "boolean"；definition → output 必须 "boolean"（dangling 同拒）；
  context 形状校验、类型证明延后依赖解析；loopIndex（decimal）/capability（date/time/decimal）
  静态非 boolean → /condition/kind 拒绝）+ absentPolicy 必填 FALSE|ERROR；children 必填非空且
  统一 ABSOLUTE（child 用 PACK → variant mismatch hard error）；无 appearance/box 成员。
- `BindingPolicyCatalog` 只追加 conditional 的 common/placement entries（ticket 09 §8），
  `BindingPolicyCatalogTest` 同步钉住（condition/absentPolicy 永不授权）。
- 冻结向量：manifest `renderweave-template-canonical-kernel-v1/8`，211 cases（197 原样 + 15 新：
  4 admit 冻结 exact canonical bytes/hash——literal/definition/context condition、conditional-in-
  stack（STACK placement）；11 reject 冻结精确 code/stage/pointer——缺 condition、缺/非法
  absentPolicy、literal/definition 非 boolean、dangling definition、loopIndex/capability
  condition、空 children、child PACK、appearance 越位）；`reject-future-kind-conditional` 如实改写
  为 `reject-conditional-missing-condition`（conditional 已 admission，FUTURE_KINDS 已空）。
  Python independent（211/211，A2）镜像全部校验。
- 验证：Template module 30 tests 全绿；`template` gate 绿（Java=211/211 Python=211/211）；
  Profile 保持 NOT_REGISTERED；plan §12 已更新为 211/211。
- 边界（诚实）：false 剪枝物化、true-frame lowering、runtime ABSENT/ERROR 传播属 Evaluator；
  condition context 的 boolean 类型证明延后到 StaticSchema 依赖解析。
- 收口：删除临时 ManifestBuilder18/组装脚本 → `template`/`fast` gates → NOTES/map/plan 同步 →
  Ticket 18 resolved/automated_verified → 单一 verified commit（不 push，待用户授权）。

## Resolution（2026-08-19）

- 实现并验证完成：`template` gate 绿（Java=211/211 Python=211/211，evidence
  `.sdlc/evidence/20260819-202947-template/`）、`fast` 绿（`.sdlc/evidence/20260819-203018-fast/`）、
  Profile 保持 NOT_REGISTERED；临时文件已删除；NOTES/map/plan 已同步；单一 verified commit 形成，
  worktree clean，未 push（待用户另行授权）。DesignDSL v1 全部 kind 已 admission（FUTURE_KINDS
  空）。T20（Template 依赖投影，T12b 的 blocker）为唯一 unblocked frontier（T12b/T13 仍阻塞），
  single-writer 下一轮只 claim T20。
