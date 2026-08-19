# 04 — 冻结三个语义阶段的 response-shape catalog

**Parent:** N8 / R0

**Anchor:** approved successor spec、ADR-0036、commit c12f23d76a6fc76a6a38042ff89bbd166e6012b5

**What to build:** 为 OBSERVE、HIERARCHY、ELEMENT_BINDING 建立 canonical、byte-stable、机器可读的 shape catalog、JSON Schema 文档和正负 fixtures。

**Blocked by:** None — can start immediately.

**Status:** planned

## Acceptance criteria

- [ ] catalog 覆盖 required、closed members、enum、array、null、数字、长度和数量边界。
- [ ] unknown member、duplicate key、trailing token、scalar coercion、null primitive、非法 enum 和超限均拒绝。
- [ ] JSON Schema validator 与现有 strict Java codec 对固定 fixtures 的接受集合一致。
- [ ] shape-valid 但空间、顺序、ownership、relationship 或 binding 错误的反例仍被语义 validator 拒绝。
- [ ] catalog、生成文档和 fixtures 输出 canonical、byte-stable，并进入 successor evaluation identity。
- [ ] DashScope 请求继续使用 json_object；不发送 strict JSON Schema，不修改 Prompt 或 provider dialect。

## Required gate/evidence

- [ ] 三阶段正负 contract differential 通过。
- [ ] byte-stability golden 通过。
- [ ] shape-valid/semantic-invalid negative suite 通过。
- [ ] focused + server contract gate 形成 A1；覆盖 AC-DOIR-009。

## Guardrails

- Provider attempts、reservations、cost 必须为 0；不读取 API Key。
- JSON Schema 只权威描述语法形状，不能替代现有 strict codec、semantic verifier、Candidate validator 或 RenderWeave validator。
- 不启用 Provider strict structured output、function calling、grammar 或 tools。
- 不修改历史 Prompt、Profile、pipeline 或 run snapshot。
