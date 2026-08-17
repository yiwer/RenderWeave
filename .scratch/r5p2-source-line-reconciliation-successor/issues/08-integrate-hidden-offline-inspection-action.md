# 08 — 贯通 hidden offline OBSERVE inspection action

**What to build:** 在 measurement 明确允许后，新增 additive closed OBSERVE inspection union decoder，并绑定 hidden/experimental/offline-only Prompt、Profile、pipeline、response shape、action policy 与 complete plan identity；旧/default/live routes 保持不变。

**Blocked by:** R5P2-07 reaching `R5P2_ACTION_IMPLEMENTATION_ALLOWED` — every negative disposition closes this ticket.

**Status:** ready-for-agent

- [ ] OBSERVE response 只能是完整既有 grounding 或完整 `InspectionRequest/1.0` 二选一；mixed variant、unknown member、duplicate key、trailing token、null/coercion 与 numeric enum 均拒绝。
- [ ] inspection request 只表达一至两个 known-view regions 与冻结 margin/resolution presets；free text、path、URL、tool/model、budget、loop、priority 或 termination instruction 不可表达。
- [ ] semantic-invalid request 返回固定 payload-safe reason code，不执行 permissive repair/default；既有 grounding decoder 与 historical response bytes 保持不变。
- [ ] successor identities 精确绑定 closed action policy、完整 plan successor、最多一轮和两个 inspected views、固定 transform/budgets/failure codes；不得产生开放式工具能力或产品 OCR merge。
- [ ] inspection Prompt/Profile/pipeline/response-shape catalog 条目明确为 hidden、experimental、offline-only，产品 create/default selection 无法选择，catalog ordering 与 snapshots 确定性稳定。
- [ ] historical Prompt/Profile/pipeline、product-v45 identities 与既有 view-plan bytes 不变；不得复活或修改 R5P-07..12。
- [ ] no-network scripted adapter 可以执行该 action；live-capable route 在 request construction/reservation 前因无 fresh exact J1 fail-closed。
- [ ] codec/schema parity、snapshot/catalog/admission negative tests 按 TDD 通过；Provider attempts、reservations、cost、API-key reads 均为 0。

