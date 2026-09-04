# 20 — IOPA-P1-R05：实现受限 region correction vertical slice

**What to build:** 当 successor OBSERVE 收到票 18 明确批准、票 19 已可靠分类的 region fixed code 时，运行能够在同一授权 run 内执行受限纠正，并完整走到 REVIEW_REQUIRED 或不可变失败；无信息重复、未知 code、breaker 和预算边界都必须 fail closed，不能靠耗尽全部 calls 碰运气。

**Blocked by:** 19 — IOPA-P1-R04：贯通 payload-free region rejection taxonomy。

**Status:** resolved

- [x] correction 只接受票 18 allowlist 中的 fixed code，并只改变获批的 Prompt/pipeline 行为；generic、未知或未批准 code 不获得隐式 retry 权限。
- [x] 每次 rejected attempt 必须先 append-only 持久化，再根据同 run、同 stage 的历史计算下一步；跨 stage 不累计，成功推进后不延续旧拒绝计数。
- [x] 在等价拒绝阈值、run call/token/cost hard cap 或任何 fail-closed 条件到达时，不得 reserve 或签发下一张 Provider permit。
- [x] fake-provider 成功场景证明一次或多次受限纠正后可继续到 Candidate 的 REVIEW_REQUIRED 边界，但 Candidate 保持 unapplied、StaticSchema 保持 unpublished。
- [x] fake-provider 失败场景分别证明：等价 fixed code 熔断、generic/未知 code 停止、混合 code 按冻结合同处理、预算不足时在外传前停止。
- [x] reservation、attempt、ledger reconciliation 与 terminal lifecycle 使用 Testcontainers PostgreSQL 验证，所有失败路径最终 unsettled reservation 为 0。
- [x] N9/R1 evaluator identity、评分阈值和 certification credit 语义保持不变；该 slice 不得把 diagnostic 结果计入 5/20/60。
- [x] telemetry 与 A1 测试证据仅包含 identities、fixed codes、计数、费用/token 聚合和状态，不包含任何受保护 payload。
- [x] 相关局部、server 与 provider-zero runtime gates 通过，provider attempts 为 0；不读取 Key、不创建 OPEN authorization。

## Evidence

- 2026-08-18：Prompt 14 与 `VisualObservationCorrectionPolicy` 显式冻结 allowlist；generic、unknown、unlisted 或 mixed OBSERVE code 在 rejected attempt 落账后立即 terminal，无下一次 reservation。
- Testcontainers 场景证明：field-specific evidence code 经一次 bounded correction 到 `REVIEW_REQUIRED`；allowlisted truncation 同 run/stage/code 第三次熔断；unlisted version code 仅 1 attempt；历史 4.29/4.28 语义保持。
- A1：`.sdlc/evidence/20260818-015529-image-only-v48-successor/`；Candidate 未 apply，StaticSchema 未发布，certification credit=0，Provider usage=0。
