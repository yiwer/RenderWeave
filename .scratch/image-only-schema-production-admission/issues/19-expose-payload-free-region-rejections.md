# 19 — IOPA-P1-R04：贯通 payload-free region rejection taxonomy

**What to build:** 让 successor-only 的 OBSERVE 路径在 region 输出被拒绝时，能够从 synthetic Provider response 一直给出稳定、可行动的 fixed code，并把同一 code 安全传递到 attempt、运行决策和 terminal/evidence 摘要；操作者可以判断失败类别，但不能看到图片、Prompt、RootDocument 或模型 payload。

**Blocked by:** 18 — IOPA-P1-R03：冻结 v48 bounded region-recovery contract。

**Status:** resolved

- [x] 只按票 18 批准的分类合同增加 opt-in successor diagnostic policy；legacy/v46/v47 的对外 code、资源 bytes、hash 和历史 terminal 保持不变。
- [x] synthetic contract fixtures 覆盖每个获批 region failure family、合法 region 输出及不可分类 fallback；同一输入必须稳定产生同一 fixed code。
- [x] fixed code 从解析/验证边界贯通到 rejected attempt、breaker 输入、run terminal 和 payload-free evidence summary，且各处不得退化成异常消息字符串匹配之外的非确定语义。
- [x] 对未知、混合或无法安全区分的失败 fail closed 到批准的 generic code，不把异常 cause、字段值、局部 ID、坐标或 payload 片段写入日志和证据。
- [x] 外部错误表面只包含允许的 fixed code、stage、计数和 identity；常规日志与证据不包含原始图片、完整请求/响应、完整 RootDocument 或 chain-of-thought。
- [x] 端到端 fake-provider 验证从 synthetic response 到持久化 attempt/terminal 摘要；涉及数据库的测试使用 Testcontainers PostgreSQL。
- [x] 证明合法 OBSERVE 输出仍进入既有下游流程，分类扩展不改变 Candidate materialization、evaluator 或人工审核语义。
- [x] 相关局部与 server gates 通过，provider attempts 为 0；不创建或打开 J1 authorization。

## Evidence

- 2026-08-18：successor-only `VisualRegionDiagnosticPolicy.FIELD_SPECIFIC` 已冻结七个 fixed codes；mixed/不可安全区分输入回退 `VISUAL_GROUNDING_REGION_INVALID`，v47 同输入仍保持 legacy generic。
- `VisualGroundingContractTest` 39/39 PASS；Testcontainers `PostgresLiveInferenceWorkflowTest` 证明 fixed code 贯通 rejected attempt、retry task 与 terminal state，payload 只保留 code/count/identity。
- A1：`.sdlc/evidence/20260818-015529-image-only-v48-successor/`；Provider attempts/reservations/cost/API-key reads=0，OPEN authorization=0。
