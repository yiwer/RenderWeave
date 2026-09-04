# 42 — IOPA-P1-R27：实现 v52 ITEM parent-envelope normalization

**What to build:** 按 ticket 41 / ADR-0047 实现 successor-only deterministic repeated-group envelope normalization，
含 bounded/atomic/compatibility/telemetry tests 与 PostgreSQL second-permit proof。

**Blocked by:** 41 — IOPA-P1-R26。

**Status:** resolved

- [x] 新 normalization policy 只对 exact `ITEM -> REPEATED_GROUP` same-repeatGroup non-containment 生效。
- [x] parent evidence 只取自身与 direct linked ITEM boxes 的无 padding 并集；跨 artifact/非 image/invalid shape fail closed。
- [x] 多 parent bounded、一次性 full-plan validation；失败完全回滚，v51 及更早行为不变。
- [x] payload-free telemetry 只含固定 outcome/count，不含 ID、box、path 或 payload。
- [x] unit/property/integration 与 Testcontainers PostgreSQL gates 全绿，Provider usage=0。

## Resolution evidence — 2026-08-18

- A1 gate：`.sdlc/evidence/20260818-151531-image-only-v52-successor/metadata.json`，result=`passed`。
- Payload-free summary SHA-256：`c9d592ce4861d4caeff3c389c8dda61356c0ff84d3a71bf11c4db8b3236a7d7a`。
- 实现身份：`renderweave-image-only-v52-implementation/1.0:293fec9792df98131d72acffdc22ed4b4d65e0d8edaea1b46743e9e7da2b7405`。
- Inference 58/58、Testcontainers PostgreSQL 3/3；8-parent hard bound、ancestor escape atomic rollback、second-permit 路径均已重放。
- Provider attempts/reservations/modelTokens/cost/API-key reads 均为 0；Candidate/StaticSchema/production 均未变更。
