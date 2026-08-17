# 03 — 锁定 source-line reconciliation policy

**What to build:** 实现 evaluation-memory-only 的 `FrozenSourceLineReconciliationPolicy/1.0`，用无质量含义的 self-describing lines 冻结 source projection、跨视图 geometry clustering、complete-link、原始 representative selection、重复行保护和跨语言确定性。

**Blocked by:** R5P2-01 — authority lock must be green. This ticket can run in parallel with R5P2-02.

**Status:** ready-for-agent

- [ ] baseline 与 successor observation 使用同一 `renderweave-r5p-source-projection/1.0` 投影到同一 source artifact 的 canonical `0..10000` 坐标；不同 source artifact 或同一 view 内的 lines 永不聚类。
- [ ] candidate 必须同时满足 intersection/smaller-area `>= 5000 bps`、vertical-intersection/smaller-height `>= 8000 bps`，以及较小 box 中心位于较大 box 的闭开区间；覆盖 4999/5000、7999/8000 与 center-edge RED/green 边界。
- [ ] cluster 使用 complete-link；A-B、B-C 合格但 A-C 不合格时不得通过 transitive chaining 合成一个 cluster。
- [ ] representative 的冻结顺序为更高 view/source pixel density、更高 native confidence、更小 projected area、更小 view ordinal、更小 line ordinal；所有比较使用 checked integer/rational arithmetic。
- [ ] 代表项只保留某个原 observation 的 text、box 与 confidence；禁止拼接、投票、合成 text、gold/model 修正或人工过滤。
- [ ] 不同 source boxes 上的相同文字、相邻 source lines 与 repeated list 保持分离；同一 source line 的跨视图 OCR variants 只保留一个原始 representative。
- [ ] metric input 稳定排序为 source top、left、bottom、right、canonical text、view ordinal、line ordinal，并覆盖 NFC、ASCII/Unicode whitespace、ISO control 和 overflow cases。
- [ ] Java/Python golden vectors 对 projection、cluster membership、representative、stable order 与 policy identity exact 一致；policy、阈值或 tie-break 改动必须产生新 spec/identity。
- [ ] reconciliation 不修改 `DocumentObservationIR/1.0`，不进入 Candidate、Evidence、checkpoint 或 product store；Provider attempts、reservations、cost、API-key reads 均为 0。

