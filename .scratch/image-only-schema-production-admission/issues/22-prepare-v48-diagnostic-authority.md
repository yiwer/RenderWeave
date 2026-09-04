# 22 — IOPA-P1-R07：准备 v48 单例非计分 diagnostic authority

**What to build:** 为 immutable v48 准备一份所有字段都可由所有者核对的单 case、非计分 diagnostic 授权包。准备完成时，case、Profile、manifest、evaluator、normalization、provider route 和全部 caps 都已精确绑定，但系统仍没有 OPEN J1，也没有发生任何外部调用。

**Blocked by:** 21 — IOPA-P1-R06：创建 immutable v48 successor。

**Status:** resolved

- [x] 对获批的 USER_PROVIDED + ORDINARY_DESIGN 回归 case 建立 fresh normalization identity、case identity、manifest identity、evaluator identity 和独立 cycle；不得复用 v46/v47 cycle、manifest、assignment 或 ledger。
- [x] manifest 只含一个非计分 diagnostic case，且明确 certification credit=0、grant=false、next scoring stage=false。
- [x] 推荐 hard cap 不宽于上一轮单例 diagnostic 的 1 run、5 calls、100,000 model tokens、¥3、2 小时；任何扩大必须回到票 18 或新 source decision。
- [x] 生成待所有者签发的 exact J1 内容，完整绑定 authorization/cycle/Profile SHA、manifest/evaluator/normalization identities、provider/model/base URL、数据分类、case SHA、run/call/token/cost caps、per-run cap、effective/expiry 和 approval scope。
- [x] approvedAt/effectiveAt/expiresAt 等人工事实不得由 agent 伪造；所有者回复前 authorization 不得变为 OPEN，历史 CLOSED J1 不得复用。
- [x] zero-call preflight 证明缺失、过期、identity 不匹配、case 不匹配或 cap 超界的 J1 均在 Provider permit 前拒绝，provider attempts 保持 0。
- [x] 预演 terminal close、ledger reconciliation、breaker 和 payload-free evidence 路径，涉及数据库时使用 Testcontainers PostgreSQL。
- [x] 输出简洁的签发提示与核对摘要，不读取或输出 API Key，不包含原始图片、完整模型输入输出或 RootDocument。
- [x] 相关 provider-zero gates 全绿后停在 J1 硬门，不运行 diagnostic。

## Evidence

- 2026-08-18：fresh cycle=`4e1f41b7-7c42-40d8-afd6-9fe3a35cc54d`；normalization=`renderweave-image-only-fresh-normalization/1.0:052e77dabb723f07e76b092e3da8afe1b5a56f7a40dc094451c18c42ee4f9aaa`；manifest=`renderweave-image-only-profile-successor-diagnostic/1.0:7d14e0b85bf07fc67ae20f0399e00be17a86511822fdae15d180a0a1171ecea7`；evaluator=`renderweave-image-only-profile-successor-diagnostic-evaluator/1.0:b2167261ae9d1e3775c91d06d90c57c47c16284d11b685e81aa5073de655f37e`。
- 单 case preparation 绑定 USER_PROVIDED + ORDINARY_DESIGN 与 1 run/5 calls/100,000 tokens/¥3/≤2h；prepared authorization `20260818-iopa-v48-diagnostic-4e1f41b7` 保持 `PENDING_J1`，未创建 OPEN JSON。
- Java preflight、独立 verifier、PowerShell exact runner parse 与 disabled live harness compile 通过；缺失/越界 J1 在 egress 前 fail closed。A1：`.sdlc/evidence/20260818-015529-image-only-v48-successor/`；OPEN=0、Provider usage=0。
