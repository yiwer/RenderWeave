# R5P-01 — 锁定 successor authority 与历史终局

- Status: planned
- Spec: `specs/changes/20260815-r5-paired-product-view-successor.md`
- Blocked by: None
- Provider boundary: attempts/reservations/cost/API-key reads must remain 0

## What it delivers

建立全新、payload-safe 的 R5P authority lock，精确绑定 approved successor spec、基线 revision、N7-04
`FAIL/CLOSED`、N7-05 permanently blocked、旧 R5 authority 与 closed runner。该 ticket 只建立新的离线权威，
不修改任何历史 terminal record。

## Acceptance criteria

- [ ] authority 精确绑定 spec identity、基线 revision 和旧 R5 authority digest。
- [ ] N7-04/N7-05 及旧 R5 ticket、authorization、contract、assignment、evaluation、evidence identity 均列入禁止复用集合。
- [ ] changed digest、OPEN historical authorization、fabricated PASS、renamed old ticket 或 reopened runner 全部 fail-closed。
- [ ] 旧 runner 仍稳定返回 route closed，不产生 local acquisition 或 Provider work。
- [ ] 常规输出只包含 identities、digests、固定 codes 和零使用计数。

## Required gate/evidence

- Authority/contract/tamper A1。
- 不调用 production decision engine 的只读独立重建 A2。
- Terminal code: `R5P_AUTHORITY_LOCKED`。

