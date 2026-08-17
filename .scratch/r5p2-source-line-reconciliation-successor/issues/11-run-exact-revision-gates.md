# 11 — 运行 exact-revision release gates

**What to build:** 在 product path 完成后固定一个 clean exact revision，按局部→受影响→Goal 逐级运行发布级验证、payload scan 与 independent terminal replay，并生成 content-addressed evidence manifest；任一 product-path failure 必须如实停止。

**Blocked by:** R5P2-10 — scripted inspection replay and no-inspection product-v45 equivalence must both be green.

**Status:** ready-for-agent

- [ ] 在执行前记录 clean worktree、exact revision、toolchain/runtime identities 与 predecessor evidence digests；dirty revision、identity drift 或缺失 evidence 不得进入 release gate。
- [ ] 在同一 exact revision 依次取得 focused/affected、server、web、eval、document-vision、E2E 与 full gate 绿色 evidence，不以较窄 gate 替代 full。
- [ ] 对 source、fixtures、ordinary evidence、stdout/stderr、exceptions、reports 与 summaries 执行 decoded/raw payload scan，禁止图片、Base64、完整 bbox、OCR/gold、Prompt、Candidate 或 RootDocument 泄漏。
- [ ] 在 exact revision 独立重放 durable workflow、product-v45 compatibility、privacy、Provider-zero 与 predecessor terminal chain；manifest 中每项 evidence 都可按 digest 重算。
- [ ] 任一 action、recovery、equivalence、privacy、gate、payload 或 accounting failure 输出 `R5P2_PRODUCT_PATH_NOT_QUALIFIED` 并关闭 R5P2-12；后续窄测试不能覆盖。
- [ ] 全部 gate 通过时只形成 exact-revision product-path-qualified evidence，不提前输出 final eligibility 或 J1。
- [ ] 若任何修复改变 revision，旧 full/payload/terminal evidence 立即失效，必须在新 exact clean revision 全量重跑。
- [ ] gate 全程 no-network；Provider attempts、reservations、cost、API-key reads 均为 0，不读取或输出任何 API key。

