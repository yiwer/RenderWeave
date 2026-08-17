# 01 — RapidOCR corpus v2 零 Provider exact re-anchor

**Parent:** N7 / T6-5

**Anchor:** `specs/renderweave-v1.md`、approved visual-recognition vNext delta、approved DocumentObservationIR successor delta、ADR-0036、commit `b50d04e710f3a176b5e95336f912460809939d89`

**What to build:** 通过唯一主 seam
`normalized ArtifactSet + AcquisitionPolicy -> DocumentObservationIR/1.0`
使用冻结的 RapidOCR/OpenVINO identity 对 corpus v2 的 60 cases 做两次彼此隔离的确定性实际运行，形成可按层定位感知缺口、但不含载荷的 shadow diagnostic。不得用 gold、perfect replay、预制 observation 或 Provider 结果代替实际 OCR acquisition。

**Blocked by:** None — can start immediately.

**Status:** planned

## Acceptance criteria

- [ ] 运行前精确校验 IR contract、AcquisitionPolicy、adapter/capability、engine/model manifest、pre/post-process、坐标投影、reading-order derivation 和 corpus v2 identity；任一漂移 fail-closed。
- [ ] 两轮各处理恰好 60 cases、45 DEV + 15 HOLDOUT，并隔离进程、临时目录和内存缓存。
- [ ] 两轮均经真实图片规范化与 RapidOCR acquisition 产生 `DocumentObservationIR/1.0`；不得调用 synthetic `perfect` 或以 gold annotation 生成实际值。
- [ ] 在受控内存内比较 OCR text、source-pixel geometry、native confidence、canonical order 和 repeat/item observability；OCR text 及其内容 hash 均不得持久化。
- [ ] 报告提供 global、DEV、HOLDOUT、domain、difficulty、dense/small-text 及既有失败 slice 的 OCR、layout、order、repeat 指标和两轮一致性指标。
- [ ] 报告对 R2、R3、R4、R5 分别输出 `requiredEvidencePresent`、`triggered` 和固定 reason codes；证据不足必须明确为 `NOT_TRIGGERED_EVIDENCE_ABSENT`，不能用“待观察”代替判定。
- [ ] 报告固定声明 `shadowDiagnostic=true`、`certificationEligible=false`，不得替换 corpus 1.0、AC-021 或任何历史 evaluation identity。
- [ ] Provider attempts、reservations、cost 均为 0，不读取 API Key。

## Required gate/evidence

- [ ] 两轮实际 acquisition 的 A1 运行证据与 exact identity lock。
- [ ] Java/Python 或等价独立实现重算 case accounting、slice 指标和 determinism verdict，形成严格输入范围内的 A2。
- [ ] Document Vision runtime canary、payload scan、identity-tamper 和 second-run-drift 负例通过。
- [ ] 常规报告只包含 identity、计数、指标、耗时、资源摘要和固定 code。

## Guardrails

- OCR text 必须保持 `EPHEMERAL_UNTRUSTED`；图片、Prompt、OCR/模型/Candidate 原文不得进入常规日志或证据。
- 不修改 Prompt 12/7/4、任何 Profile、validator、pipeline、corpus 1.0 或 corpus v2 annotations。
- 不实现 R2–R6，不增加开放式 Agent、通用工具执行器、LangGraph 或 Temporal。
- Template、RootDocument connect、数据适配和发布能力全部 Out of Scope。

