# 06 — 独立重放 public-process A2

**What to build:** 从 frozen raw fixtures 独立重建 normalization、plans、inspection action、公开 adapter processes、reconciliation、metrics 与 candidate decision；replay 完成前不得读取 producer report 或复制 producer decision。

**Blocked by:** R5P2-05 — complete producer evidence must exist, while remaining unreadable to the replay until reconstruction finishes.

**Status:** ready-for-agent

- [ ] verifier 从 raw fixtures 和 frozen public contracts 重走完整路径，不调用 producer decision engine，不导入 adapter 私有函数；允许复用的只有 frozen OCR binary/model/adapter artifacts。
- [ ] replay 期间 producer report reads、producer decision reads 与 producer metric reads 均为 0，并由 access audit fail-closed enforcement 证明。
- [ ] verifier 通过公开进程协议执行两 runs × 12 cases × 2 branches 的 48 个 fresh process/engine acquisitions，并独立记录 capability probes、artifact/views 与 process calls。
- [ ] 独立计算 normalization、plan、view、process、raw/canonical observation、reconciled input、per-case metric、cohort summary、threshold 和 terminal-input identities。
- [ ] 只有 replay 完成且 evidence sealed 后，comparison runner 才能读取 producer evidence；比较顺序先 identities/accounting，再 metrics/summaries/terminal。
- [ ] producer/A2 在 12/12 case metrics、cohort summaries、stage identities、accounting 与 candidate terminal exact 一致；首个差异仅输出 payload-safe stage code 并 fail-closed。
- [ ] A2 不通过人工解释、复制 producer terminal 或缩窄 comparison 建立独立性；任何 mismatch 固定为 measurement-invalid input，后续节点不得覆盖。
- [ ] verifier 环境 no-network 且最小化；evidence/output 通过 decoded/raw payload scan，Provider attempts、reservations、cost、API-key reads 均为 0。

