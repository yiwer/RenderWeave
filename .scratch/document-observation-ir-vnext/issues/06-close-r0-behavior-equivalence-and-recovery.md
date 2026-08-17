# 06 — 完成 R0 全矩阵行为等价与 durable recovery 门

**Parent:** N8 / R0

**Anchor:** approved successor spec、ADR-0036、commit 19e22854e0be236d0068336a32969356a6befaf8

**What to build:** 形成 N8/R0 的正式完成门，证明 successor 架构不是只会序列化 IR，而是完整保持 product-v45 行为。此 ticket 完成后才允许任何 R1 ticket 开始。

**Blocked by:** 04 — 冻结三个语义阶段的 response-shape catalog；05 — 让一条 v45 IMAGE_ONLY tracer run 通过 successor seam 到达审核。

**Status:** planned

## Acceptance criteria

- [ ] 旧路径与 successor 的 terminal state、accepted-stage canonical payload、fixed issue routing、Candidate semantic fingerprint、字段/关系顺序、evidence canonical order、blocker/warning 和 reservation summary 等价。
- [ ] 覆盖 CMYK BGR、多图顺序、强 document sequence、重复实例字段聚合、ROOT 到 child 的 MANY reference、空/越界/超限和 cancellation。
- [ ] PostgreSQL lease expiry 或 crash 后可以重算 IR，但不得重放已接受的 Provider stage。
- [ ] recovery 后仍到达与 v45 相同的 REVIEW_REQUIRED。
- [ ] checkpoint、DB、日志、evidence、report、stdout/stderr 和对象字符串均无图片、OCR、Prompt 或模型原文。
- [ ] product-v45 Profile、Prompt、pipeline、run snapshot、corpus 1.0 和既有 evidence bytes 不变。
- [ ] Provider attempts、reservations、cost 全为 0。

## Required gate/evidence

- [ ] contract、property 和 differential suites 通过。
- [ ] Testcontainers PostgreSQL fault/recovery 通过。
- [ ] document-vision canary 通过。
- [ ] server + full gate 通过。
- [ ] 独立 replay 与独立 payload scan 通过。
- [ ] 形成 A1；独立 replay/scan 在严格输入范围内形成 A2；关闭 AC-DOIR-001..009 的 R0 门。

## Guardrails

- 本 ticket 不关闭 N7、AC-021、AC-VR-010 或任何产品质量晋级门。
- product-v45 继续 EXPERIMENTAL。
- 不授权 live、费用、真实数据、Profile catalog 切换或数据库 migration。
- R2–R6 保持 Further Notes，不因 R0 完成而激活。
