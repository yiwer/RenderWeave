# 冻结 Provider 生产路线与 Profile migration 边界

Type: grilling
Status: resolved
Claimed by: Kimi（2026-08-17 会话）
Blocked by: none

## Question

在 Provider 生产合同权威与内部信任边界已知后，首个 IMAGE_ONLY production route 应精确选择哪个获准产品、华北 2 endpoint、视觉模型、Key/secret owner、region 与网络出口；当前 Token Plan 路由如何 fail-closed 退出；新 immutable Inference Profile、价格、capability、Prompt/pipeline snapshot、Provider contract identity 和 deployment policy 如何绑定；旧 product-v45 snapshot 如何只读恢复但不接受新生产 run；合同、模型目录、endpoint、价格、SLA 或处理政策漂移时如何撤销准入、drain 和重新认证？本 ticket 只冻结 Provider/Profile migration 语义，不执行控制台变更、真实调用或质量认证。

## Comments

- 2026-08-16（用户输入，经 Kimi 会话记录）：用户明确**不采用 Token Plan**，首选标准**按量付费**模式，即业务空间 API Key（环境变量 `DASHSCOPE_API_KEY` 风格，区别于当前运行时固定的 `DASHSCOPE_TOKEN_API_KEY`）。这与研究报告 §5.1 指向的唯一官方生产路线（华北 2 业务空间专属按量付费 endpoint）一致。本输入冻结的是路线**方向**，不是 endpoint/模型/Profile 的精确身份——精确身份仍待本票在 04、06 解除阻塞后冻结；且当前代码固定在 Token Plan base URL 与 `DASHSCOPE_TOKEN_API_KEY`（specs/changes/20260812-dashscope-token-plan-startup-routing.md），切换属于本票的 migration 语义范围。
- 2026-08-16（Kimi 会话记录，2026-08-17 lifecycle 更新）：ticket 06 已 resolved——所有者决定以标准按量付费在线服务协议 + 明示风险接受为 authority 基础。ticket 04 随后也已 resolved，本票现为 unblocked frontier。路线方向细化为：标准按量付费 endpoint `https://dashscope.aliyuncs.com/compatible-mode/v1` + `DASHSCOPE_API_KEY`，模型首选 `qwen3.8-max`（文档级可用证据，见 ticket 02 Comments）；本票仍须冻结 exact endpoint/模型/Profile identity 与 migration 语义。
- 2026-08-17（Kimi 会话记录）：route migration 已按所有者指示直接执行并落地为 `specs/changes/20260817-dashscope-payasyougo-route-migration.md`：adapter allowlist 唯一批准 URL 改为标准按量付费 endpoint，凭据只读 `DASHSCOPE_API_KEY`/`_FILE`，application.yml、compose.live.yaml、spec §8.7、README 与 Web 文案同步；v45 Profile bytes 不变（其声明的 endpoint/Key 名与运行时恢复一致）；Token Plan URL 进入 adapter 拒绝名单测试。本票剩余范围收窄为：漂移撤销/drain/重认证语义与 Profile 绑定的精确冻结。
- 2026-08-17（Kimi 会话，live 证据输入）：48h scoped J1 试用矩阵 6/6 完成（授权 `plans/live-canary-authorizations/20260817-payasyougo-trial.json` 已 CLOSED，总耗 ¥2.26 / 513,345 token）。选型证据：**qwen3.8-max 2/2 REVIEW_REQUIRED**，难图（m3-detail）42 秒零拒绝、32,421 token 全矩阵最低；**qwen3.7-plus 1/2**（易图过、难图 `LIVE_CALL_BUDGET_EXHAUSTED`）；**qwen3.7-flash 0/2**（两图 OBSERVE 合同违规、失败码互不相同）。证据支持 Max 作为质量档 sole-finalist 候选；Flash 是否留在生产目录需在本票与 ticket 05 定夺。

## Answer

2026-08-17 经两轮 grilling 冻结（全部按所有者确认的推荐）：

### Profile 精确绑定与认证身份

1. **认证状态外部化**：新增 `ProfileCertificationRecord`（已入 CONTEXT.md 词汇表）——绑 `profileId` + Profile bytes SHA-256 + 认证结论 + 有效期；授予/撤销只改写该记录，Profile bytes 永不改写。这是唯一同时满足 immutable 红线与可撤销认证的形态。
2. **首个生产目录只认证 Max 一份 sole-finalist**：`dashscope-qwen38-max-product-v45-hybrid-generic` 的继任者 v46（见下）。Flash/Plus 保持 `EXPERIMENTAL`、不进生产目录；Flash 不做代码删除（超出本票范围），Plus 留作未来"成本档认证"候选（其难图 FAILED 是预算配置问题而非质量死刑）。
3. **v46 = v45 最小 diff**：仅改 `profileId`、`maximumTotalCalls` 7→**12**、`maximumEstimatedCostMicrosCny` ¥2→**¥6**（12×单次 OBSERVE 拒绝最贵 ¥0.44 ≈ ¥5.3 封顶）；`maximumOutputTokens`=8192（失控输出保险丝）、`stageTimeoutSeconds`=360、全部 prompt/pipeline/阈值/价格快照原样。因预算参数不影响单次调用质量，2026-08-17 试用证据（Max 2/2 REVIEW_REQUIRED）由 v46 继承。v45 永远停留 EXPERIMENTAL 历史态，认证记录只绑 v46 hash。snake_case 命名约定不进 v46 prompt（保持与试用证据可比性），归 ticket 05 认证合同处理。
4. v46 Profile 的创建属于下游执行步骤，随 ticket 05 认证流程落地；本地图不实施产品代码。

### 漂移、撤销、drain 与重认证

5. **价格漂移永不触发质量重认证**，只触发 ticket 09 的成本预算复核；运行时由 `maximumEstimatedCostMicrosCny` 兜底。
6. **不做 Provider 行为漂移的自动检测**（所有者明示接受的残余风险）：撤销触发清单闭合为 (i) 所有者人工撤销；(ii) 事故驱动——人工审核 Candidate 时发现质量崩塌；(iii) 人工注意到 Provider 合同/政策变更。无常态 canary、无条款巡检。
7. **drain 语义继承 ticket 04 第 13 条**（软 drain）：撤销后新 live typed 503、QUEUED 入稳定终态、RUNNING 在最近安全边界停止、在途结果只记账不推进、`REVIEW_REQUIRED` 不受阻断；重开开关不复活旧 run。
8. **重认证**：撤销即把对应 `ProfileCertificationRecord` 标记 revoked-with-reason；重认证开具新记录——bytes 未变可复用同一 hash 但至少需 fresh 5-case canary（语料/门槛归 ticket 05），bytes 变更则绑新 identity。

### 下游依赖

- ticket 05：认证合同须设计 `ProfileCertificationRecord` 的存储/签发/撤销机制，并纳入 snake_case 命名约定与阈值校准问题（7999bps 贴线现象）。
- ticket 09：价格漂移触发的成本预算复核入口来自本票第 5 条。
