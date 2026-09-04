# 29 — IOPA-P1-R14：准备 fresh v49 diagnostic authority

**What to build:** 在 v49 Provider-zero gates 全绿后，为同一已知失败 ordinary-design regression probe 生成 fresh、非计分、严格绑定的 diagnostic preparation 与 one-shot live seam，并停在新的 exact J1 门前；默认测试和 gate 即使环境存在凭据也不得外传。

**Blocked by:** 28 — IOPA-P1-R13：创建 immutable v49 successor。

**Status:** resolved

- [x] 对获批 artifact 做 fresh static normalization verification，只保存 case ID、SHA-256、media type、bytes、dimensions 和 normalization identity，不保存文件名、路径、图片或 OCR/模型 payload。
- [x] 创建 fresh diagnostic cycle/manifest/evaluator identity，只绑定 exact v49 Profile SHA、单 case、`USER_PROVIDED + ORDINARY_DESIGN`、non-scoring 与 certification credit=0。
- [x] proposed authorization caps 不宽于 1 run、5 Provider calls、100,000 model tokens、¥3、每 run 5 calls/¥3、有效窗口≤2h；状态必须为 `PENDING_J1/PROPOSED`，不得创建 OPEN record。
- [x] strict authorization/preflight 逐字段绑定 Profile/manifest/evaluator/normalization/provider/model/base URL/data/case/caps/scope/timing，任一漂移或过期在 Provider boundary 前拒绝。
- [x] one-shot live runner 默认禁用；只有未来 exact OPEN J1、唯一匹配 artifact 和显式 gate 同时满足时才可进入 stage/permit，且禁止 run 级自动重跑。
- [x] fake/scripted Provider + Testcontainers 证明 `REVIEW_REQUIRED` 与 negative terminal 两条 closure path，authorization/ledger 均 CLOSED、unsettled reservations=0。
- [x] preparation、runner 和 evidence contract 明确禁止计入 5/20/60、grant、Candidate apply、StaticSchema publish 或 production deployment。
- [x] 独立 verifier、payload scan、fast 与 server 受影响 gates 通过；Provider attempts/reservations/cost/API-key reads=0，OPEN authorization=0。
- [x] 输出可供所有者签发 fresh exact J1 的 identity/caps 摘要，但 preparation、ticket 或测试文字本身不构成授权。

## Evidence

- fresh cycle=`432fdfeb-c5ab-4cff-92f4-e066a0d98c8c`；normalization=
  `renderweave-image-only-fresh-normalization/1.0:3096deba42aeab03be175074e6717ccf6898d4a628950d19eaa6891674d62375`；
  manifest=`renderweave-image-only-profile-successor-diagnostic/1.0:8ff24a6161223f9e1c8bfb586ffd89421a1ee0ad393622e72870848509f0c8e2`；
  evaluator=`renderweave-image-only-profile-successor-diagnostic-evaluator/1.0:b2167261ae9d1e3775c91d06d90c57c47c16284d11b685e81aa5073de655f37e`。
- proposed authorization=`20260818-iopa-v49-diagnostic-432fdfeb`，状态=`PENDING_J1`，caps=1 run / 5 calls /
  100,000 tokens / ¥3 / per-run 5 calls + ¥3 / ≤2h；本 ticket 未创建 OPEN JSON。
- ADR-0043 将 v49 normalization identity 变成 exact authorization/preflight 字段，同时保持 v47/v48 CLOSED JSON
  bytes 不变；漂移/缺失负测、strict codec、默认禁用 one-shot runner 与 scripted closure contract 全绿。
- A1 dedicated gate `.sdlc/evidence/20260818-115742-image-only-v49-diagnostic-preparation/` PASS（verifier 3/3、
  inference 50/50、Testcontainers 7/7）；fast `.sdlc/evidence/20260818-115934-fast/` PASS；server
  `.sdlc/evidence/20260818-120001-server/` PASS（20/13/389/287，0 failures/errors）。
- Provider attempts/reservations/model tokens/cost/key reads=0，OPEN=0，Candidate 未 apply、StaticSchema 未 publish、
  未 deploy/commit/push；ticket 30 为唯一下一 live frontier。
