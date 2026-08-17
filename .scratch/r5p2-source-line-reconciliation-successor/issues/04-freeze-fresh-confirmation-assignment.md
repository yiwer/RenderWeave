# 04 — 冻结 diagnostic 与全新 confirmation assignment

**What to build:** 在读取任何 R5P2 paired result 前冻结 `FrozenR5P2Assignment/1.0`、八例 historical diagnostic、四例从未观察 paired treatment 的 sealed confirmation、一次性 raw fixtures、统一 request policy，以及 holdout access audit。

**Blocked by:** R5P2-02 and R5P2-03 — branch-process and reconciliation conformance must both be green.

**Status:** ready-for-agent

- [ ] historical diagnostic 精确固定为 `transit-board-v3`、`restaurant-menu-v3`、`hospital-schedule-v3`、`transit-board-v5`、`transit-board-v2`、`invoice-lines-v3`、`school-timetable-v4`、`building-directory-v5`；它们仅能 veto，不能贡献 fresh confirmation 或 HOLDOUT claim。
- [ ] sealed confirmation 精确固定为 `weather-forecast-v3` DEV/DENSE_TEXT、`warehouse-inventory-v2` DEV/MULTI_COLUMN、`event-agenda-v4` DEV/LOW_CONTRAST、`product-catalog-v5` HOLDOUT/NOISY。
- [ ] `weather-forecast-v3` 固定 case identity `renderweave-layered-case/2.0:b8276787bbfa99b49851308a7c963fcd41e5bbd311ad1db9169885fc766ee890` 与 selection rank SHA-256 `19f8156bddc9fd7a08e8324e6e3e165060207060fe49c972e51613dabcd1068d`。
- [ ] `warehouse-inventory-v2` 固定 case identity `renderweave-layered-case/2.0:9ca1319537a06892e7920a973b66a9a893f0dea1fea31c4bcb21b7efaf4bf456` 与 selection rank SHA-256 `25fc1c7bc6c9f070c90893c9839c5c9859db0e5fd9492b0bcb4ad9be02251535`。
- [ ] `event-agenda-v4` 固定 case identity `renderweave-layered-case/2.0:8691ed1f501c6597fecbd5c93ac433f42100f5025c7339dcff5474299a9c314f` 与 selection rank SHA-256 `2552e253b354d65e1e8c5d570f696104c9bf62715e6db11cf2e4453bad15417e`。
- [ ] `product-catalog-v5` 固定 case identity `renderweave-layered-case/2.0:2c0e17e489cf1ef4f1f55be50ff9ca2d070114bf58395125a811a30de3444b14` 与 selection rank SHA-256 `5d7decfb23a7b8090ddc032ead18c40c1d6c7fd2852557c56447cfa58351c3bc`。
- [ ] 只读取 frozen corpus metadata 重算 selection，得到规格冻结的 case identities、family uniqueness 与 rank SHA-256；读取 OCR、paired metrics、gold、Candidate 或模型结果进行 selection 必须失败。
- [ ] 任一 confirmation case/family 曾进入旧 R5/R5P paired assignment/evidence、四例 family 不唯一、partition/difficulty 不符或 case substitution 时，assignment admission fail-closed。
- [ ] 四例共用 upper region `[200,200,9800,2500]`、lower region `[200,2500,9800,9800]`、`TIGHT_0000_BPS` 与 `INSPECT_LONG_EDGE_2400`，不允许 case-local override。
- [ ] 每个 canonical corpus raster 只生成一次 raw submitted fixture；baseline/successor 复用同一 bytes，不得分别 render。
- [ ] 在第一次 OCR acquisition 前提交 fixture bytes/dimensions、render identity、normalization fingerprint、regions、runtime/capability、reconciliation policy、thresholds、assignment identity 与 access state；结果后 mutation 产生 `R5P2_MEASUREMENT_INVALID`。
- [ ] HOLDOUT 在 freeze commit 前 gold/metric reads=0；freeze 后只允许 official producer 与 independent replay 各自按固定顺序访问，exploratory run 或额外 accessor 均使 measurement invalid。
- [ ] manifest/hash/access audit 可由 A2 独立重算，只包含 payload-safe identity 与计数；Provider attempts、reservations、cost、API-key reads 均为 0。
