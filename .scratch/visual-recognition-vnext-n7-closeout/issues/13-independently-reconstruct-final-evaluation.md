# 13 — 独立重建 final evaluation A2

**Parent:** N7 / T6-5

**Anchor:** ticket 12 CLOSED final evidence、AC-VR-001..010、v1 AC-015..021

**What to build:** 由不依赖 live runner 自报结论的 verifier，从 immutable corpus、assignment、journal、reservation 和 fixed revision 独立重建 final 60-case 技术证据。

**Blocked by:** 12 — 最佳 Profile final 60-case 与 HOLDOUT 完成，且全部 live ledger CLOSED。

**Status:** planned

## Acceptance criteria

- [ ] 独立校验 revision、Profile、Prompt 12/7/4、pipeline 4.28、corpus、evaluator、workflow/build 和 EvaluationIdentity。
- [ ] 重建恰好 60 个唯一 assignments、全部 terminal states、attempts、tokens、cost、latency、reservation settlement 和 ledger closure。
- [ ] 独立重算 global、DEV、HOLDOUT、domain、difficulty、stage 和 Candidate metrics，并逐项与 live report 对比。
- [ ] 显式映射 AC-VR-001..010 和 AC-021；v1 要求的 global/mode-slice/HOLDOUT 证据若没有可合法复用的 exact identity，结论必须为 `INCOMPLETE`，不得从 IMAGE_ONLY 结果外推。
- [ ] 检测 duplicate/missing assignment、identity drift、tampered journal、stale ledger、incorrect price 和 over-budget。
- [ ] 扫描常规 evidence，确认无图片、Prompt、OCR/模型/Candidate 原文、Provider request ID、secret 或 chain-of-thought。
- [ ] verifier 自身 Provider attempts、reservations、cost 为 0。

## Required gate/evidence

- [ ] Independent A2 PASS/FAIL report。
- [ ] Cross-implementation metric recompute、tamper probes、payload scan 和 clean final revision verification。
- [ ] 自动 evidence 与后续人工 J1、policy decision 保持分离。

## Guardrails

- A2 只决定技术证据是否成立，不修改 Profile 或作生产 policy 决策。
- verifier 不读取 API Key，不调用 Provider，不访问未授权载荷。
- 不用 corpus v2 shadow report补足 corpus 1.0 或 AC-021 evidence 缺口。

