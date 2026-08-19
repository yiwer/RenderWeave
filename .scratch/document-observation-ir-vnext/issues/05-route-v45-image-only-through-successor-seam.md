# 05 — 让一条 v45 IMAGE_ONLY tracer run 通过 successor seam 到达审核

**Parent:** N8 / R0

**Anchor:** approved successor spec、ADR-0036、commit c12f23d76a6fc76a6a38042ff89bbd166e6012b5

**What to build:** 将一条代表性的 scripted IMAGE_ONLY 流程从旧 DocumentVisionPreprocessor 调用边界切换到 VisualEvidenceAcquisition，经串行三阶段和本地物化到达 REVIEW_REQUIRED。

**Blocked by:** 02 — 让 RapidOCR/OpenVINO 通过主 seam 产出 source-pixel IR；03 — 以 compatibility projection 精确复现 v45 Document Vision context。

**Status:** planned

## Acceptance criteria

- [ ] worker 只依赖主 seam，不依赖 RapidOCR 或 Python DTO。
- [ ] exact policy、capability、IR 和 projection identity 被绑定到 additive successor evaluation identity。
- [ ] cancel 仍先于 blob read、OCR process 和费用 reservation。
- [ ] OBSERVE、HIERARCHY、ELEMENT_BINDING、LOCAL_MATERIALIZE 的因果顺序、fixed issue、earliestStage 和白名单回边不变。
- [ ] 代表性 replay 的终态、Candidate semantic fingerprint、evidence 顺序和 blocker 语义与 v45 oracle 等价。
- [ ] OCR text 和完整 IR 不进入 checkpoint；恢复时允许确定性重算 acquisition。
- [ ] 不增加 stage、attempt、repair、并行 Provider 调用或新状态机。
- [ ] 历史 Profile、Prompt、pipeline、run 和 corpus bytes 不变。

## Required gate/evidence

- [ ] focused worker/application contract tests 通过。
- [ ] Testcontainers PostgreSQL tracer 到达 REVIEW_REQUIRED。
- [ ] checkpoint、execution event、DB row 和异常 payload scan 通过。
- [ ] server gate 形成 A1；Provider attempts、reservations、cost 为 0。

## Guardrails

- 外层事实源继续是现有 PostgreSQL durable typed state machine。
- Provider 语义阶段继续串行，validator 继续拥有 fixed issue/action 和有界回边。
- 不引入开放式 Agent、通用工具执行器、LangGraph、Temporal 或第二套 durable history。
- 不改变人工审核、create-only atomic apply 或 Candidate validator 权威。
