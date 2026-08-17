# 02 — 建立完整 branch 的公开进程 acquisition

**What to build:** 建立 evaluation-only 的完整 branch acquisition seam，并以 fake/self-describing adapter 锁定公开进程协议：每次 branch 使用一个 fresh process、一个 fresh engine 和一个包含完整有序 `ArtifactSet` 的 request；进程、probe 与 artifact/view 必须分账。

**Blocked by:** R5P2-01 — authority lock must be green.

**Status:** ready-for-agent

- [ ] 以 contract RED tests 锁定 `R5P2PairedProductViewEvaluation` 的唯一输入边界：完整 raw fixture set、完整 frozen assignment、完整 baseline/successor plans、exact acquisition 与 frozen reconciliation policy；散装 view、任意 crop、预计算 observation、gold hint 或 caller-supplied threshold 均拒绝。
- [ ] 每个完整 branch 精确启动一个 fresh adapter process 与一个 fresh OCR engine，并通过一次公开 protocol request 提交该 branch 的全部 artifacts；逐 view request 与跨 branch engine reuse 均失败。
- [ ] adapter 私有 `_engine`、`_artifact`、`_preprocess` 等路径在 producer 和 verifier harness 中均不可达，且由 import/call negative tests 证明。
- [ ] response 独立严格校验 JSON、capability、artifact count/order/identity、bytes、dimensions、line limits、text bytes、bbox、confidence、canonical text、timeout 与 exit status；duplicate、unknown、coercion、missing、reordered、oversize 和 trailing content 均 fail-closed。
- [ ] raw fixture 必须先通过产品 `InputNormalizer` 与 scoped BlobStore provenance，再物化完整 static/successor plan；scene、oracle、gold 或绕过产品 normalization 的输入不可进入 acquisition。
- [ ] branch process calls、capability probe processes 和 artifact/view counts 使用不同字段与 identities；把任一计数冒充另一计数的 tamper case 必须失败。
- [ ] 子进程只继承最小运行环境，清空 live/credential 变量并保持 no-network；Provider attempts、reservations、cost、API-key reads 均为 0。
- [ ] fake/stateful conformance suite 按 red → green → refactor 完成局部与 node gate，并产出 payload-safe A1；独立 protocol vectors 可被 A2 重放。

