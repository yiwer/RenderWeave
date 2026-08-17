# 03 — 证明 zero-provider live admission 并准备 exact J1 envelope

**Parent:** N7 / T6-5

**Anchor:** ticket 02 qualification protocol、现有 Goal guard 与 authorization lifecycle、commit `b50d04e710f3a176b5e95336f912460809939d89`

**What to build:** 建立每一张后续 live ticket 都必须独立通过的 fail-closed admission gate，并先产生 Plus 5-case canary 的 exact J1 请求信封；本 ticket 不调用 Provider，也不把历史授权解释为当前授权。

**Blocked by:** 02 — 冻结 R2–R5 disposition 与 N7 qualification protocol，且其结论必须为 `CONTINUE_N7_CURRENT_BEHAVIOR`。

**Status:** planned

## Acceptance criteria

- [ ] 从权威 reservation/Goal ledger 重建 Plus、Max、Flash 的累计 attempts、exposed tokens、cost、非终态 reservation 和 breached 状态；不得采用计划文档中的历史余额快照。
- [ ] 证明全部既有 live ledger 已 CLOSED；存在 RESERVED、OPEN、identity mismatch 或账本不一致时 fail-closed。
- [ ] 在 clean fixed revision 上完成 zero-provider full、Document Vision、identity、secret、payload、process 和 lease audit。
- [ ] `PROPOSED`、`NOT_OPEN`、过期、超预算、错误 case、错误 Profile snapshot、错误 evaluation identity 和不精确 J1 均在 journal mutation、reservation 和 Provider 前拒绝。
- [ ] 定义可为每个 live ticket实例化的 `G-LIVE(ticket)`：clean revision、zero-provider audit、fresh EvaluationIdentity、exact immutable Profile snapshot、fresh exact J1、negative probe 和 OPEN ledger 缺一不可。
- [ ] Plus 5-case exact J1 envelope 精确绑定 provider/model/Profile、Prompt 12/7/4、pipeline 4.28、corpus、evaluator、case IDs、数据分类、attempt/token/费用上限、最长有效期和 batch≤5。
- [ ] 本 ticket Provider attempts、reservations、cost 均为 0，不读取或输出 API Key。

## Required gate/evidence

- [ ] Clean fixed-revision full 与 Document Vision A1 evidence。
- [ ] Goal guard independent reconstruction、NOT_OPEN negative probe、identity drift 和 J1 mismatch 负例形成 A2。
- [ ] Exact J1 envelope 在获得人工批准前保持 `PROPOSED/NOT_OPEN`。

## Guardrails

- 历史 CLOSED J1、当前会话中的宽泛许可或未绑定字段的授权都不能满足 `G-LIVE(ticket)`。
- 每张 live ticket 必须取得自己的 fresh identity 和 exact J1；canary 授权不能自动扩到 qualification/final。
- 常规证据 payload-free；不改变产品能力、Profile 或 pipeline。

