# ADR-0037：账本丢失后建立 successor Goal authority epoch

- 状态：accepted
- 日期：2026-08-13
- 关联：P6/T6-5/N7、N7-03、ADR-0029、ADR-0036

## 背景

视觉评测 Goal 的 `goal-budget.json`、`goal-budget.guard.json` 与 `goal-budget.lock` 已不可从当前工作区、其他
worktree、回收站、归档或 IDE Local History 精确恢复。旧 v1/v4 状态以每次 Provider attempt 的 reservation
为事实源；直接创建空文件会清零历史消费，按汇总数字伪造 418 个 UUID、run、时间戳和 usage 又会制造不存在的
历史事实。ADR-0029 同时要求无法证明实际 usage 的 reservation 不得释放，而应继续按保守上界计费。

固定 revision `e3230398b7d6978d93527813af29df98fa7b35e6` 上四份独立提交材料一致记录最后已验证状态：418 个
reservations，其中 412 SETTLED、6 个无法结算的历史 reservation、0 BREACHED；Flash、Plus、Max 分别为
157/179/82 attempts，1,148,324/1,087,500/491,919 exposed tokens，¥0.560618/¥4.159620/¥10.289316。
用户在确认原始文件不可恢复后批准改用 successor re-anchor，而不是继续磁盘恢复。

## 决策

1. 保留 Goal ID，但新增 `n7-closeout-successor-20260813` authority epoch。状态和 guard 分别使用 additive
   `goal-budget/2.0` 与 `goal-guard/5.0`；旧 v1/v4 reader、历史 evidence 和迁移语义继续可用且不改写。
2. 不重建虚假的逐条 reservation。上述 418 条只进入 immutable historical baseline；其中 6 条记为
   `QUARANTINED_CHARGED` 语义：继续包含在 exposed attempts/tokens/cost 中，不宣称 SETTLED、不产生退款，且不再
   作为可恢复或可重放的活动 RESERVED 阻塞 successor epoch。
3. successor epoch 拥有独立、显式的新增分配：每模型最多 500,000 exposed tokens、180 attempts；Max 成本上限
   ¥18，Plus/Flash 各 ¥10。容量检查只针对该 epoch 的新 reservations；审计必须同时报告 epoch usage 与包含
   historical baseline 的 lifetime usage，不能把前者冒充后者。
4. re-anchor manifest 固定 anchor revision、四个 Git blob SHA-256、精确 baseline、epoch limits 和用户决定；
   state 与 guard 同时绑定其规范化 SHA-256。materializer 只允许在三个目标文件全部不存在时执行一次，任何部分
   状态、重复执行、manifest/source drift 或独立审计失败均 fail-closed，且不得读取密钥或调用 Provider。
5. `goal-budget.lock` 仍只是空内容的文件锁载体。其重新创建不表达历史或授权；两个 JSON 才承载预算事实。
6. successor epoch 不继承任何历史 J1。每张 live ticket 仍必须在 clean fixed revision 上绑定 fresh
   EvaluationIdentity、exact Profile snapshot、case assignment、attempt/token/cost、数据分类和不超过 24 小时的
   exact J1，并先通过 NOT_OPEN negative probe。re-anchor 本身 Provider attempts/reservations/cost 全为 0。

## 未选择的方案

- **继续等待文件级恢复**：已无可验证的 Local History revision，继续等待不能提高账本完整性。
- **创建空 v1 ledger**：会隐式返还全部历史消费并允许重复调用。
- **按汇总值生成 418 个 synthetic reservations**：会伪造身份、调用顺序、usage 与结算事实。
- **让 6 个未知项永久保持活动 RESERVED**：虽然保守，但会让任何 successor admission 永久不可达；终态
  quarantine 在不退款、不声称实际 usage 的前提下消除调度歧义。

## 后果与验证

- lifetime 审计仍保留所有已知最坏暴露；新 epoch 的额度来源、消费和关闭可以独立重放。
- 无法再进行旧 418 条 reservation 级取证；报告必须明确其 A1/A2 上限及 `CONSERVATIVE_REANCHOR` 来源。
- Java guard 与独立 Python verifier 必须对 baseline、manifest、epoch cap、tamper、partial state、重复执行和
  legacy v1/v4 兼容形成正负测试。N7-03 只有在实际 operational files 被一次性 materialize、独立重建为
  `GOAL_READY` 且 Plus proposal 仍为 `PROPOSED/NOT_OPEN` 后才可关闭。
- 本决策不修改 Prompt 12/7/4、pipeline 4.28、Profile、validator、corpus、Candidate 或产品认证状态。
