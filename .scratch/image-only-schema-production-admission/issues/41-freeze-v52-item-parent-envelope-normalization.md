# 41 — IOPA-P1-R26：冻结 v52 ITEM parent-envelope normalization recovery

**What to build:** 把 v51 immutable `ITEM_ZERO_COMPATIBLE` terminal 转成 successor-only、Provider-zero 的最窄恢复：
仅对已经通过 parent kind、repeatGroup equality 与 exact parent reference 检查，但 parent box 未包含 ITEM box 的
`ITEM -> REPEATED_GROUP` 链接，把该 exact repeated-group evidence box 扩为自身与其 direct ITEM children 的确定性并集；
完整 plan 任一约束失败则原子回滚。

**Blocked by:** None — ticket 40 immutable terminal 是输入，不是可修改 blocker。

**Status:** resolved

- [x] 冻结 v51 Profile/CLOSED J1/live/terminal/post-close digests；OPEN=0、unsettled=0、harness closed-only。
- [x] 只采用 payload-free 分类事实：失败链接为 ITEM，当前 parent 已是相同 repeatGroup 的 REPEATED_GROUP，但没有任何
  已包含该 ITEM 的兼容 parent；不推断原始 ID、box 值、图片内容或模型文本。
- [x] normalization 只允许扩大 exact current parent 的 direct image evidence 到 parent + direct linked ITEM boxes 的并集；
  不移动/裁剪 ITEM、不换 parent、不改 kind/multiplicity/repeatGroup/readingOrder/ownership/semantic values。
- [x] 每个 parent 只处理一次，数量 bounded；artifact 必须一致；任何 duplicate/orphan/cycle/root/ancestor/coverage/plan
  invariant 失败都回滚整个 normalization，不做部分提交。
- [x] v52 才 opt in；v51 及更早 Profile/pipeline/prompt/terminal bytes 不变。v52 pipeline=`4.34`，Prompt 16 unchanged。
- [x] fresh v52 diagnostic 使用同一 regression probe 与新 cycle/identities/exact J1；caps 不宽于 1/5/100k/¥3/2h，
  non-scoring、0 credit、无自动 rerun。
- [x] 本 source decision Provider usage=0，不创建 OPEN authorization、不 apply/publish/deploy/commit/push。

## Decision frontier

### Q1 — 直接重接 parent 还是规范化 exact parent envelope

**推荐：只规范化 exact current parent envelope。** v51 分类与 classifier 实现共同证明 parent kind、reference 与
repeatGroup equality 已成立；`zero compatible` 反而否定了安全重接到其他 parent 的依据。

### Q2 — evidence 计算

**推荐：取 current parent 与其 direct linked ITEM children 的闭包并集。** 该计算只扩张抽象容器，不改叶子视觉证据；
不使用启发式 padding、OCR、token 文本或未关联 sibling。

### Q3 — 原子边界

**推荐：对全部候选 parent 一次性构造后运行完整 RenderWeave grounding validator，任一失败全量回滚。** 不递归扩大
ancestor，不放宽 containment，也不让局部成功掩盖其他结构错误。

### Q4 — successor/J1

**推荐：hidden v52 / pipeline 4.34 / Prompt 16 unchanged；先 Provider-zero，再 fresh one-shot diagnostic。** 只有
`REVIEW_REQUIRED` 才进入 5-case，失败继续按附录 A 新开 source ticket。

## Answer

所有者持续 Goal 要求推荐决策落 ADR；本票采用 Q1–Q4，ADR-0047 与 approved delta 是权威合同。该接受不是 paid
wildcard J1，live 仍须精确实例化短时 JSON。
