# 实现 DesignDSL TemplateUse 原子

Type: task
Status: resolved
Claimed by: Codex `/root`
Blocked by: 03, 14, 15, 16

## Question

如何在 DesignDSL admission/canonicalization 中实现 TemplateUse 结构原子（旧 map ticket 12 的冻结规格）：
TemplateUse 是禁止 children 的结构 leaf，按 logical TemplateRef 调用同 ownerScope child Template current
（不钉死 revision、无 latest/cross-scope/dynamic 选择）；`useId` 命名空间唯一；显式 ContextSelector 选择
exact StaticSchema typed context 或 `system-empty@v1` 显式 empty context，并声明 `ERROR | SKIP` absent
policy；按 child 当前 PUBLIC definitionId 的显式 typed fills（重复 target/不存在/PRIVATE/类型不兼容是
hard error，fill source ABSENT 用 child default，fill 求值 ERROR 是依赖 ERROR）；固定 consumer order
（render → contextSelector/absentPolicy → placement → visible → opacity → transform → fills（按
targetDefinitionId 稳定）→ child invocation）中可静态判定的部分；TemplateRef closure 边（same-scope、
DAG、readiness 检查属 closure/recheck authority——本票只冻结 authored use 边的 admission 原子与 canonical
向量，不实现反向索引/STALE 消费（投影票）与 compositionViewport lowering（Rendering 侧））。
权威输入：旧 map ticket 12（nested-template-composition）与 08 冻结规格。

## 实现记录（2026-08-19）

- `NodeContractCatalog`：NodeKind/KIND_BY_NAME 增 TEMPLATE_USE（FUTURE_KINDS 只余 conditional）、
  TEMPLATE_USE_MEMBERS（useId/templateRef/contextSelector/fills）、TEMPLATE_REF_MEMBERS
  （templateId）、CONTEXT_SELECTOR_MEMBERS/EMPTY_SELECTOR_MEMBERS/SELECTOR_DOMAIN_MEMBERS、
  CONTEXT_ABSENT_POLICY_TOKENS（ERROR|SKIP）、USE_FILL_MEMBERS（targetDefinitionId/source）、
  `wireName(TEMPLATE_USE)="templateUse"`、allowsChildren(false)、sizeModes FIXED/HUG/FILL。
- `CanonicalDesignDslAuthority.validateTemplateUseMembers`：
  - useId（UUID v4 + Template 全树唯一 namespace，threaded）；
  - templateRef `{templateId}` closed（UUID v4；无 revision selector，extra member 即
    MEMBER_UNKNOWN——"只跟随 current" 的 wire 级强制）；
  - contextSelector closed union：`{kind:"context",domain:{kind:"invocation"}|{kind:"loop",
    loopId},pointer?,contextAbsentPolicy}`（selector 专用 domain 对象形态 per ticket 12 §58；
    pointer 可为空 = 整个 typed context，非空走 RFC 6901 校验；contextAbsentPolicy 必填
    ERROR|SKIP）或 `{kind:"empty"}`（禁任何 extra member，含 contextAbsentPolicy）；
  - fills `{targetDefinitionId,source}`：target UUID + TemplateUse 内唯一（duplicate hard
    error），source 复用 binding source 规则（context/loopIndex/definition；dangling
    definition hard error）；canonical 按 targetDefinitionId 排序；
  - child 侧存在性/PUBLIC/类型/StaticSchema 兼容是依赖 ERROR（child current lookup），不在
    admission 判定——边界如实登记。
- `BindingPolicyCatalog` 只追加 templateUse 的 common/placement entries（ticket 09 §8
  "每个 non-Canvas kind" 展开；conditional 待 T18），`BindingPolicyCatalogTest` 同步钉住。
- 冻结向量：manifest `renderweave-template-canonical-kernel-v1/7`，197 cases（176 原样 + 21 新：
  5 admit 冻结 exact canonical bytes/hash——templateUse、empty selector、loop-domain selector
  （Repeat PACK child）、fills sorted、templateUse binding；16 reject 冻结精确
  code/stage/pointer——缺/重 useId、缺/非法 templateId、templateRef extra member、未知 selector
  kind、缺/非法 absent policy、empty selector 带 policy、非法 domain、duplicate fill target、
  literal/capability/dangling fill source、children 越位、非法 pointer）。Python independent
  （197/197，A2）镜像全部校验。
- 验证：Template module 30 tests 全绿；`template` gate 绿（Java=197/197 Python=197/197）；
  Profile 保持 NOT_REGISTERED；plan §12 已更新为 197/197。
- 边界（诚实）：child current 存在/ACTIVE/readiness、DAG/cycle、PUBLIC fill 目标与类型兼容、
  StaticSchemaRef 匹配、closure snapshot 与 STALE 消费属 closure/recheck authority 与投影票；
  compositionViewport/contain lowering 属 Rendering；OccurrencePath 属 Evaluator。
- 收口：删除临时 ManifestBuilder19/组装脚本 → `template`/`fast` gates → NOTES/map/plan 同步 →
  Ticket 19 resolved/automated_verified → 单一 verified commit（不 push，待用户授权）。

## Resolution（2026-08-19）

- 实现并验证完成：`template` gate 绿（Java=197/197 Python=197/197，evidence
  `.sdlc/evidence/20260819-202430-template/`）、`fast` 绿（`.sdlc/evidence/20260819-202501-fast/`）、
  Profile 保持 NOT_REGISTERED；临时文件已删除；NOTES/map/plan 已同步；单一 verified commit 形成，
  worktree clean，未 push（待用户另行授权）。T20（Template 依赖投影，T12b 的 blocker）以本票为
  前置解锁；T18/T20 为 unblocked frontier，single-writer 下一轮只 claim 其一。
