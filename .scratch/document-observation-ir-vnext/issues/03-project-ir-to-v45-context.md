# 03 — 以 compatibility projection 精确复现 v45 Document Vision context

**Parent:** N8 / R0

**Anchor:** approved successor spec、ADR-0036、commit 19e22854e0be236d0068336a32969356a6befaf8

**What to build:** 提供确定性、无副作用的 compatibility projection，把合法 DocumentObservationIR/1.0 转换为 v45 三阶段消费的 Document Vision context，同时保留旧路径作为行为 oracle。

**Blocked by:** 01 — 冻结 VisualEvidenceAcquisition 与 DocumentObservationIR/1.0 合同。

**Status:** planned

## Acceptance criteria

- [ ] source-pixel 到 0..10000 严格使用既有 left/top floor、right/bottom ceil 规则。
- [ ] 覆盖奇数尺寸、边界 box、多图片、CMYK、PNG 和 JPEG；非法投影不 clamp。
- [ ] artifact/line 顺序、line ID、NFC、空白、文字上限、confidence bucket 和 sequence sentinel 与 v45 等价。
- [ ] projection 不新增、合并、删除、重命名或解释 observation。
- [ ] 无损投影不成立时，以稳定 code 在 Provider reservation 前 fail-closed。
- [ ] locked fixture 上旧路径与新 projection 逐项相等。
- [ ] product-v45 Profile、Prompt、pipeline 和 capability snapshot 均不修改。

## Required gate/evidence

- [ ] projection property tests 和 boundary goldens 通过。
- [ ] 旧路径与新路径 differential suite 通过。
- [ ] Java 与独立 Python 的坐标投影重算一致。
- [ ] 形成 A1；独立投影重算在严格输入范围内形成 A2；覆盖 AC-DOIR-002、005。

## Guardrails

- Provider attempts、reservations、cost 必须为 0；不读取 API Key。
- OCR text、完整 IR 和 text-derived hash 不持久化。
- compatibility projection 不能成为新的语义 validator 或 Candidate materializer。
- 不改变 stage 数量、Prompt 数据语义、repair、费用或超时边界。
