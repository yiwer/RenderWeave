# 实现 DesignDSL Repeat 原子

Type: task
Status: resolved
Claimed by: Codex `/root`
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

## 实现记录（2026-08-19）

- `NodeContractCatalog`：NodeKind 增 REPEAT、KIND_BY_NAME 增 "repeat"、FUTURE_KINDS 移除 repeat
  （conditional/templateUse 仍 fail closed）；REPEAT_MEMBERS（loopId/items/absentPolicy/itemLayout/
  instanceLayout，无 appearance/box）、ABSENT_POLICY_TOKENS（ERROR|EMPTY）、REPEAT_ITEM_TYPES（五种
  StaticSchema scalar）、STACK/GRID RepeatPackingSpec member 集、PACK_PLACEMENT_MEMBERS、
  expectedVariant(REPEAT)=PACK、sizeModes(REPEAT)=FIXED/HUG/FILL（PACK 模式另限 FIXED/HUG）。
- `CanonicalDesignDslAuthority`：
  - loopIds 预收集 pass（best-effort 树遍历收集 repeat loopId）→ definitions 校验可用真实 loopIds
    → T15 的 loop domain/loopIndex 悬空拒绝自然解锁（`admit-repeat-loop-domain-definition` 向量证明
    loop-domain mapping + loopIndex source 现在可 admission）；树校验另持 seenLoopIds 做 loopId
    Template 内唯一（duplicate → /loopId hard error）。
  - Repeat admission：loopId（UUID v4 + 唯一）、items 结构 ValueSource（literal/context/definition；
    capability/loopIndex 直接拒绝；literal 与 definition source 静态类型证明必须为五种 scalar 的
    `list<T>`，context 的类型证明延后到 StaticSchema 依赖解析）、absentPolicy、itemLayout/
    instanceLayout RepeatPackingSpec（STACK 必填 direction + 可选 gapMm；GRID 必填正整数 columns +
    可选 gap）、children 必填非空、item subtree 全部 PACK placement。
  - PACK placement 从 KERNEL_SCOPE_UNSUPPORTED 变为真实 variant：members 仅 type/widthMode/heightMode/
    widthMm/heightMm/min*/max*（无 FILL/margin/x/y/inset/alignSelf/fillWeight/row/column），每轴
    FIXED|HUG_CONTENT（group 仍双轴 HUG），FIXED 必填正 size、HUG 禁 size；min/max 复用既有规则。
  - definitions 校验同时产出 definitionId→output type 映射，供 Repeat items definition source 的
    静态类型证明。
- 冻结向量：manifest `renderweave-template-canonical-kernel-v1/4`，116 cases（94 原样 + 22 新：5 admit
  冻结 exact canonical bytes/hash（PACK children、items definition source、loop-domain definition、
  nested repeat 经 ancestor loop context、GRID/STACK packing spec）+ 17 reject 冻结精确
  code/stage/pointer（缺/重 loopId、空 children、absentPolicy、items 类型/kind、PACK FILL/xMm、
  packing spec 非法、PACK 越位））；`reject-pack-placement` 的 expected 从
  KERNEL_SCOPE_UNSUPPORTED 如实更新为 VALUE_INVALID（PACK 已是真实 variant，父 variant 不匹配）。
- 验证：Template module 27 tests 全绿；`template` gate 绿（Java=116/116 Python=116/116）；Python
  independent 镜像 Java 检查顺序（预收集 loopIds、REPEAT 成员、PACK placement、packing spec）。
  Profile 保持 NOT_REGISTERED；plan §12 已更新为 116/116。
- 边界（诚实）：loop frame 物化/求值、itemLayout/instanceLayout 布局语义、system-basic-* context
  shape、词法祖先 domain 证明、packing 容量上限（Ticket 19）与 lowering 属 Evaluator/Rendering；
  items context source 的类型证明延后到 StaticSchema 依赖解析（dependency ERROR 两阶段流程）。
- 收口：删除临时 ManifestBuilder17/组装脚本 → `template`/`fast` gates → NOTES/map/plan 同步 →
  Ticket 17 resolved/automated_verified → 单一 verified commit（不 push，待用户授权）。

## Resolution（2026-08-19）

- 实现并验证完成：`template` gate 绿（Java=116/116 Python=116/116，evidence
  `.sdlc/evidence/20260819-195735-template/`）、`fast` 绿（`.sdlc/evidence/20260819-195806-fast/`）、
  Profile 保持 NOT_REGISTERED；临时文件已删除；NOTES/map/plan 已同步；单一 verified commit 形成，
  worktree clean，未 push（待用户另行授权）。T18（Conditional）以本票/15 为前置，解锁为 unblocked
  frontier（T14b/T16/T18），single-writer 下一轮只 claim 其一。
