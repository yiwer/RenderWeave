# 01 — 冻结 VisualEvidenceAcquisition 与 DocumentObservationIR/1.0 合同

**Parent:** N8 / R0

**Anchor:** approved successor spec、ADR-0036、commit 19e22854e0be236d0068336a32969356a6befaf8

**What to build:** 建立 additive 的深 Module 合同，使调用方通过唯一主 seam
normalized ArtifactSet + AcquisitionPolicy -> DocumentObservationIR/1.0
获得 canonical IR 或稳定、payload-free 的失败。本 ticket 是有意安排的 prefactor；不接生产 RapidOCR adapter。

**Blocked by:** None — can start immediately.

**Status:** planned

## Acceptance criteria

- [ ] ArtifactSet 只接纳已完成 v1 规范化的图片身份、顺序、媒体类型、尺寸和受控内存字节。
- [ ] AcquisitionPolicy/1.0 精确绑定 contract、capability、engine/model manifest、预后处理、坐标、投影、顺序、canonicalization、文字暴露政策和全部硬上限 identity。
- [ ] DocumentObservationIR/1.0 是 strict、closed、bounded、canonical；artifact、ordinal 和 observation ID 唯一并稳定排序。
- [ ] box 使用 source-pixel、左上原点、half-open 语义，合法区域必须非空且位于所属 artifact。
- [ ] observation 只表达 TEXT_LINE、native confidence、provenance、canonical order 和 EPHEMERAL_UNTRUSTED text。
- [ ] IR 不出现 SLOT、GROUP、Entity、Relationship、Field、Schema、Candidate 或发布语义。
- [ ] toString、异常和 diagnostic 只暴露数量、长度、identity 和固定 code。
- [ ] IR 不成为数据库、checkpoint 或公开 API DTO。

## Required gate/evidence

- [ ] focused contract、boundary、canonicalization 和 property tests 通过。
- [ ] architecture test 证明 observation 与 semantic hypothesis、verified plan、Candidate 类型隔离。
- [ ] payload、toString 和 exception negative scan 通过。
- [ ] 形成 A1，覆盖 AC-DOIR-001、003、004 的合同部分。

## Guardrails

- Provider attempts、reservations、cost 必须为 0；不读取 API Key。
- 不修改历史 Profile、Prompt、pipeline、run snapshot、corpus 或 evidence。
- 不增加 Web/API、数据库 migration、Template、RootDocument connect、数据适配或发布能力。
- 不实现 R2–R6、开放式 Agent、通用工具执行器、LangGraph 或 Temporal。
