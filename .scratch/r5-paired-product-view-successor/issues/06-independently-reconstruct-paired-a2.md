# R5P-06 — 独立重放 paired A2 并作出单一离线决定

- Status: planned
- Spec: `specs/changes/20260815-r5-paired-product-view-successor.md`
- Blocked by: R5P-05 complete producer evidence
- Provider boundary: attempts/reservations/cost/API-key reads must remain 0

## What it delivers

独立进程从 raw repository fixtures 实际重走 normalization、static plan、bounded action、paired acquisition、
source projection、evaluation-only coalescing、layered metrics 和 Provider-zero accounting，形成唯一离线终态。

## Acceptance criteria

- [ ] verifier 不调用 producer decision engine，也不以重复 producer JSON 公式冒充 A2。
- [ ] normalization provenance、full-plan coverage、view bytes/identity、metrics 与 resource accounting 均独立重建。
- [ ] seen 四例全部满足 per-case target improvement 与 hallucination non-increase，且 `transit-board-v3` 显式通过。
- [ ] confirmation 四例全部满足逐例门；aggregate line recall gain ≥500 bps、character errors 下降、order/repeat 不回退超过 100 bps。
- [ ] measurement 无效只输出 `R5P_MEASUREMENT_INVALID`；质量失败只输出 `R5P_PAIRED_VIEW_NOT_QUALIFIED`。
- [ ] Java/Python 对 unknown/duplicate/trailing/coercion/overflow/decoded payload markers fail-closed 等价。

## Required gate/evidence

- Cross-implementation actual replay A2、tamper matrix 与 payload scan。
- 只有 terminal `R5P_ACTION_IMPLEMENTATION_ALLOWED` 解锁 R5P-07..12。
- 其他 terminal 必须关闭后续 product/workflow/live work。

