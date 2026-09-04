# 27 — IOPA-P1-R12：实现 mixed-field bounded correction 与 breaker

**What to build:** 当票 26 的 rejection envelope 精确表示一组全部可行动的 region field failures 时，同一授权 run 能按票 24 批准的 Prompt/pipeline contract 做受限纠正并继续到 `REVIEW_REQUIRED` 或 immutable failure；unclassified、未知成员、无信息重复和预算不足必须在下一次外传前停止。

**Blocked by:** 26 — IOPA-P1-R11：贯通 bounded mixed-rejection envelope。

**Status:** resolved

- [x] correction eligibility 只接受批准的 mixed primary code，且 detail set 的每个成员均属于新 Prompt 明确覆盖的 allowlist；任一未知、未列或缺失成员整体 fail closed。
- [x] retry task 只携带 bounded fixed codes 和既有安全上下文，不包含字段值、坐标、局部 ID、历史 response 或其他 payload 回显。
- [x] rejected attempt 必须先 append-only 落账，再以 canonical detail set 计算同 run/stage breaker；相同 set 到批准阈值后不得产生下一次 reservation。
- [x] 不同 canonical set 按批准合同分别计数，但 Profile 与当次 authorization 的 calls/tokens/cost/time hard caps 始终优先，额度不是消费目标。
- [x] scripted-provider 正向 tracer 证明 mixed rejection 经 bounded correction 后可完成 OBSERVE、HIERARCHY、BINDING 并到达 Candidate `REVIEW_REQUIRED`；Candidate 保持 unapplied，StaticSchema unpublished。
- [x] 负向 tracers 证明 unclassified、未知成员、相同 set breaker、变化 set 的总预算边界和 Provider permit 前拒绝。
- [x] Testcontainers PostgreSQL 验证 reservation、attempt、rewind/checkpoint、settlement、terminal close 与 0 unsettled reservations。
- [x] v48、v47 与历史 pipeline retry/breaker semantics 保持不变；diagnostic 不计入 5/20/60，不产生 grant 或 next-stage credit。
- [x] 局部、受影响和 provider-zero gates 通过；不读取 Key、不创建 OPEN authorization、不调用真实 Provider。

## Evidence

- A1 dedicated gate：`.sdlc/evidence/20260818-110846-image-only-v49-correction/`，result=`PASS`。
- 独立 verifier 冻结 mixed primary、七码 Prompt-covered allowlist、canonical-set breaker threshold=3、v48
  immutable hash、v49 Profile/Prompt absence、OPEN=0 与 Provider/key usage=0。
- Java inference 47/47；Testcontainers PostgreSQL 11/11。正向 tracer 从 mixed OBSERVE rejection 经只含 fixed
  codes 的 retry task 到 `REVIEW_REQUIRED`；负向覆盖 unclassified、同集合第三次熔断、崩溃接管、异集合隔离与
  total-call cap，并重放 v47/v48 历史 retry/breaker semantics。
- 本票未创建 v49 Profile/Prompt 15 或 authorization，未调用真实 Provider，未 apply Candidate、未发布
  StaticSchema、未部署生产、未 commit/push。
