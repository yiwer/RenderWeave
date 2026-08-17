# 01 — 锁定 R5P2 authority 与历史负面终态

**What to build:** 为全新 `R5P2` namespace 建立 fail-closed authority lock，固定 approved successor spec、代码基线、历史 R5P 负面终态与 immutable evidence、corpus identity、route closure 和 zero-provider 边界；不得重写、复活或把历史 R5P 解释为 PASS。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 先写 tamper RED tests，再使 authority 精确接受 approved spec SHA-256 `e33269e1faa04f21239a0e79d4346fc90439f142b26111b3764164f53ba7d902` 与基线 revision `4b756c52cbc2fd389d8ca34f4c4a65b1bc9615db`；任一 byte、identity 或 revision 漂移均 fail-closed。
- [ ] 固定历史有效终态 `R5P_MEASUREMENT_INVALID`，并证明 R5P-07..12 继续关闭；新路线不能 amend、删除、覆盖、重命名、supersede 或重跑历史 R5/R5P artifacts。
- [ ] 绑定 producer report identity `renderweave-r5p-paired-product-view-report/1.0:2f15a068bd6c5eb8416a1d7da7c8fd679278a8f734cd78d2d35ade6ab01ff783` 及 SHA-256 `df622da5089f069ed4b6bd2a929fec6539839af6375d3669d18f896397082625`。
- [ ] 绑定 independent evidence identity `renderweave-r5p-independent-replay-evidence/1.0:2ccd12203e15ac572d72036530973ad181e76f0a08ebd4b84b2d4b14aaca5281` 及 SHA-256 `1086bbee024a126d7c665995a44461faee36e4a7ee541e73f8bccd2f2fc393d6`，并固定 historical evaluation identity `renderweave-r5p-paired-view-evaluation/1.0:c8ad69263640ca49cd93ca24c6b558c6f913ff89a40c84052634c7cd79f66b65`。
- [ ] 绑定 corpus identity `renderweave-visual-stage-corpus/2.0:c596621eb680e7e10d42d2e1d1f926995cec9716cc6ef83a96a50ad53adc285c` 与 identity-lock SHA-256 `cf54fd985e89a024fdc0742a737c21442c49718fdf58b0bb05b87e2cffd2247d`。
- [ ] route-closed runner 只承认 R5P2 identities；旧 R5/R5P identity、缺失 predecessor、未知字段、重复键、trailing content 或不完整 authority 均不能解锁后继。
- [ ] A1 authority/tamper evidence 与独立重算 A2 只含 payload-safe identities、digests 和固定 reason codes；Provider attempts、reservations、cost、API-key reads 均为 0。
