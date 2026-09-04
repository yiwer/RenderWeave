# 36 — IOPA-P1-R21：冻结 v51 parent-containment provenance successor

**What to build:** 把 v50 immutable containment negative terminal 转成一个 successor-only、Provider-zero 的分类合同，
先证明结构性失败属于哪一类，再允许任何 repair 决策。

**Blocked by:** None — ticket 35 terminal 是不可变输入。

**Status:** resolved

- [x] 冻结 v50 CLOSED authorization、terminal、live summary 与 post-close digests；OPEN=0、unsettled=0。
- [x] 确认 v50 已启用 unique compatible parent + unique ROOT ancestor normalization，不能重复开旧 seam。
- [x] 仅从 in-memory typed regions 生成 allowlisted category/count；不记录 ID、box、payload。
- [x] classified primary 对 v51 首次 terminal，不向模型 correction，不发第 2 个 permit。
- [x] v51 relative diff 只允许 profileId + pipelineVersion；Prompt 16 bytes 不变。
- [x] 后续 diagnostic 必须 fresh cycle/normalization/manifest/exact J1，non-scoring；v50 不重跑。

## Decision frontier

### Q1 — 先分类还是直接 repair

**推荐：先分类。** fixed code 无法证明扩大 box、删除 branch 或改 repetition 的唯一正确性。

### Q2 — 分类证据边界

**推荐：只输出固定类别与计数。** 不输出 region/local IDs、坐标或模型片段。

### Q3 — 调用行为

**推荐：classified primary 首次即 terminal。** provenance 不应触发 LLM 自纠重试。

### Q4 — successor identity

**推荐：hidden v51 / pipeline 4.33 / Prompt 16 unchanged。** 语义变化必须新 Profile，避免 patch v50。

## Answer

所有者持续 Goal 指示所有决策按推荐并落 ADR；本票采用 Q1–Q4，ADR-0046 与 approved delta 为实现合同。本票本身
不创建 OPEN J1、不授权 live。
