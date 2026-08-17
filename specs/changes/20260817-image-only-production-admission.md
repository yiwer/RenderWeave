# Spec Delta：IMAGE_ONLY Schema Recognition Production Admission

- 状态：**APPROVED**
- Triage：`ready-for-agent`
- 日期：2026-08-17
- 批准权威：所有者于 2026-08-17 验收通过
  `image-only-schema-production-admission` wayfinder 的 16 张 resolved 决策票及 Blueprint v1，并在本次
  `$to-spec` handoff 中要求转为 approved delta 与实施计划
- Blueprint 索引：`plans/image-only-production-admission-blueprint-v1.md`
- 细节权威：`.scratch/image-only-schema-production-admission/issues/` 下对应票；Blueprint 与本 delta
  都是索引/执行视图，不能覆盖源票
- 影响 AC/规则：细化 AC-015、AC-017、AC-018、AC-019、AC-020、AC-021、AC-022、AC-024；
  新增 AC-IOPA-001..034；R-INF-001..008、R-API-001、R-OPS-001..002
- 再锚定关系：本 delta 是 `RULE-ANCHOR-001` 的 approved 对照基准。若实现发现与源票存在数值、
  identity、信任或生命周期实质冲突，停止受影响任务并按 Blueprint 附录 A 开新票；不得静默修改本地图决策
- 非授权声明：本 delta 不创建 live J1、不读取 API Key、不授权真实数据/生产部署或任何 Provider 调用；
  所有既有 Flash/Plus/Max 试用与 DeepSeek-OCR spike authorization 均保持 `CLOSED`

## Problem Statement

RenderWeave 已有可运行的 IMAGE_ONLY product-v45 工程路径，但它只是
`ACTIVE_EXPERIMENTAL` 基线。历史 N7、R5、R5P、R5P2 路线及其 J1、ledger、identity 和负面终态均已
关闭，不能继续调度或被后续窄绿证据覆盖。当前也不存在 v46 Profile、有效
`ProfileCertificationRecord`、信封加密、生产 OCR sidecar、逐 run 精确外传确认、
`ProductionLiveAuthority`、恢复证明或生产部署。

因此，用户虽然可以在显式实验入口中取得 Candidate，却不能获得一条满足质量、数据保护、运行准入、
恢复与人工权威的生产路径。若直接把“可运行”“两例成功”或旧布尔确认当作生产资格，将导致 Profile 质量
外推、真实图片明文持久化、旧授权复用、Provider 重放歧义、删除后复活以及 Agent/CI 意外继承生产权限。

## Solution

新增一份只覆盖 IMAGE_ONLY mode slice 的生产准入合同。它先创建 v46 Max immutable Profile，并通过
fresh 5-case canary、20-case DEV 与 60-case final/HOLDOUT 认证周期形成可撤销的
`ProfileCertificationRecord`；随后把 live 路径升级为 gateway 签名身份、输入分类、精确 notice/manifest/
confirmation、per-artifact 信封加密、无网 UDS OCR sidecar、逐调用授权、双开关、payload-free 审计、
封闭 readiness 与容量/成本预算；最后以扩展 release gate、完整 restore drill、A1/A2 证据和人工 J1
创建 60 天 guarded-pilot `ProductionLiveAuthority`。

该路径始终保留 Candidate 人工逐项审核和 create-only Draft Bundle 原子 apply；它不自动发布
StaticSchema，也不把 IMAGE_ONLY 认证外推为全局 AC-021、JSON_ONLY 或 COMBINED 已完成。
`ProductionUsable` 仍是 Profile Certification、live admission、operations acceptance 与 recovery proof
共同成立后的人工宣布结果，不由本 delta、单次 pilot 或自动 gate 自行产生。

## User Stories

1. As an 所有者, I want product-v45、N7、R5、R5P 与 R5P2 的权威状态被精确继承, so that 已关闭的失败路线不会被误当作可继续的生产任务。
2. As an 所有者, I want IMAGE_ONLY 使用独立的 mode-specific production contract, so that 全局 AC-021 与其他 inference mode 不会被错误宣布完成。
3. As an Profile 维护者, I want 从 v45 创建一份最小差异的 v46 Max Profile, so that 试用质量信号保持可比且 12-call/¥6 运行边界得到精确冻结。
4. As an Profile 维护者, I want v46 bytes 与 SHA-256 一经创建即不可变, so that 认证和运行始终绑定同一份 Profile。
5. As an Profile 维护者, I want Flash 与 Plus 保持 EXPERIMENTAL 且不进入首个生产目录, so that 未认证模型不会成为生产选择或自动 fallback。
6. As an 认证负责人, I want Profile Certification 存在于 Profile bytes 外部的 append-only record, so that 认证可以撤销而不改写历史 Profile。
7. As an 认证负责人, I want 认证 manifest 在周期开始前冻结 case hash、assignment、threshold 与 evaluator identity, so that 结果不能在看到输出后调参。
8. As an 认证负责人, I want fresh 5-case canary 使用所有者自有 ordinary-design 图片, so that 大规模认证前先证明 exact v46 的真实可达性。
9. As an 认证负责人, I want 20-case DEV 阶段达到至少 18/20, so that 明显不稳定的 Profile 不会消耗 final 阶段预算。
10. As an 认证负责人, I want final 60-case 达到至少 54/60 且 20 个 HOLDOUT 在 DEV 期间不可见, so that 生产资格具有独立的未见样本证据。
11. As an 人工审核者, I want 每个认证 case 只有到达 REVIEW_REQUIRED/COMPLETED 且人工接受才算通过, so that 自动合同合法性不会替代结构质量判断。
12. As an 人工审核者, I want 7999bps 贴线项继续进入逐项审核而不自动判 case 失败, so that 当前 flag-only 阈值语义保持不变。
13. As an Schema 维护者, I want proposedSchemaKey 与 proposedFieldKey 遵守 snake_case 认证约定, so that 生产 Candidate 的命名可预测且不依赖事后随意改名。
14. As an 认证负责人, I want 任一阶段不过即形成不可变负面终态且不修补重跑, so that 失败不会被挑选性 rerun 覆盖。
15. As an 认证负责人, I want 每个 live 阶段都有 fresh scoped J1 和 CLOSED ledger, so that 精确 Profile、输入、次数、费用与时限均被当次授权约束。
16. As an 独立复核者, I want 复用 N9/R1 的 60-case/58-metric 基础设施独立重算认证结果, so that A1 运行汇总可以得到严格输入范围内的 A2 复核。
17. As an Tenant Operator, I want 只有 USER_PROVIDED 与 ORDINARY_DESIGN 输入可进入 live, so that restricted、缺失或未知分类都 fail-closed。
18. As an Tenant Operator, I want 在上传前看到 exact Provider、模型、Profile、region、预算、retention 与条款 notice, so that 外传决定是知情且具体的。
19. As an Tenant Operator, I want confirmation 绑定实际规范化输入 manifest 而不是本地文件名或布尔值, so that 被外传的 bytes 与我确认的内容一致。
20. As an Tenant Operator, I want 首次 Provider attempt 超过 15 分钟时 run 稳定终止, so that 排队不会把旧确认变成无限期外传许可。
21. As an Tenant Operator, I want 用户 retry 创建新 run 和 fresh confirmation, so that 旧 run、Profile、输入或预算授权不会跨 retry 复用。
22. As an Tenant Operator, I want live create 响应丢失后可用原 Idempotency-Key 安全查询/重发, so that 网络故障不会创建重复 run 或重复调用。
23. As an Tenant Operator, I want ambiguous Provider attempt 先查询并终止而不自动盲重放, so that 未知计费或未知执行结果不会被重复发送。
24. As an Tenant Operator, I want 删除 payload 立即阻断读取、retry、Provider 调用与 apply, so that 物理删除异步期间逻辑访问已经停止。
25. As an Tenant Operator, I want REVIEW_REQUIRED Candidate 仍必须逐项 CONFIRMED/RESOLVED_BY_EDIT/REMOVED, so that 生产准入不会引入 confirm-all。
26. As an Tenant Operator, I want apply 仍只原子创建 Draft Bundle, so that AI 永远不能自动发布 StaticSchema 或修改/删除既有 Schema。
27. As an 同租户审核者, I want kill switch 后既有 REVIEW_REQUIRED run 仍可审核和 apply, so that 已完成且安全的人工工作不会因停止新外传而丢失。
28. As an 网关运维者, I want gateway 删除客户端身份 header 并签发 ≤60 秒的非对称 GatewayAssertion, so that actor、request、method、path 与 mutation intent 可验证且不可由客户端自报。
29. As an 网关运维者, I want public Web、gateway、private API、private PostgreSQL 与 internal Actuator 分层, so that 生产入口只有一个且运维面不暴露公网。
30. As an 安全负责人, I want gateway signer、mTLS、Provider Key 与 Blob KEK 四个 secret 域隔离, so that 任一凭据泄漏不会自动扩展为其他能力。
31. As an 安全负责人, I want Provider adapter 只读取 DASHSCOPE_API_KEY/_FILE 且只允许 exact endpoint, so that 配置不会把 adapter 变成任意 HTTP 客户端。
32. As an 安全负责人, I want ImageOnlyAdmissionPolicy 与 ProviderEgressPermit 默认关闭且相互独立, so that 应用策略和网络出口必须同时授权才可发起调用。
33. As an 安全负责人, I want 每个 Provider call 在发送 bytes 前持久化 authorization、attempt identity 与费用 reservation, so that 外部副作用有原子审计前置。
34. As an 安全负责人, I want audit 不可写或 digest chain 失效时阻断新调用, so that 无法审计的外传不会继续。
35. As an 安全负责人, I want Live Admission Audit 只含 opaque identity、digest、固定 code 与 usage/cost, so that 图片、文件名、OCR、Prompt、响应、PII、secret 与 CoT 不进入常规证据。
36. As an 安全负责人, I want 所有公开错误只使用封闭 code 与静态文案, so that 用户数据不会经错误消息或日志泄漏。
37. As an 安全负责人, I want production Web same-origin、Secure/HttpOnly/SameSite=Strict、CSRF、Origin/Fetch-Metadata 与 no-store, so that 浏览器外传入口具有明确的请求来源与缓存边界。
38. As an 存储负责人, I want 每个 persisted artifact 使用随机 DEK 的 AEAD 信封加密, so that Blob ciphertext 与数据库 wrapped DEK 缺一即不可恢复明文。
39. As an 存储负责人, I want KEK 由 orchestrator 独立提供且轮换只 re-wrap DEK, so that 密钥轮换不需要解密和重写全部 artifact。
40. As an 存储负责人, I want KEK 丢失被视为 crypto-erasure 而非尝试猜测恢复, so that 安全语义明确且不会绕过 fail-closed。
41. As an 存储负责人, I want normalized payload 最多保留 7 天且 retry 不延长 expiry, so that 内容生命周期不被工作流重试偷偷延长。
42. As an 存储负责人, I want COMPLETED 立即排删、FAILED/CANCELLED 最多 24 小时、REVIEW_REQUIRED 到期禁 apply, so that 每种 run 状态都有封闭保留语义。
43. As an 存储负责人, I want 删除 backlog 超过 24 小时使 ImageOnlyReadiness fail-closed, so that 不健康的删除系统不继续接收新图片。
44. As an OCR 运维者, I want production OCR 运行在无 IP、仅 UDS、独立 cgroup 的 sidecar, so that OCR native runtime 与 API JRE、网络和 OOM 域隔离。
45. As an OCR 运维者, I want sidecar 固定 linux/amd64、CPython 3.12、glibc≥2.28、AVX2 与 CPU-only, so that 未认证平台不会静默运行。
46. As an OCR 运维者, I want sidecar 依赖、ONNX、base image、SBOM、license 与 attestation 全部 exact pin, so that 构建和启动不依赖在线下载或浮动供应链。
47. As an OCR 运维者, I want capability 与 synthetic probe 阻塞启动而长稳资源 probe 进入遥测, so that 错误能力立即拒绝、慢性资源问题可被持续观察。
48. As an OCR 运维者, I want sidecar 不可用只返回 DOCUMENT_VISION_UNAVAILABLE, so that 确定性 Schema 站点仍保持 ServiceReadiness。
49. As an 运维负责人, I want ServiceReadiness 与 ImageOnlyReadiness 分轴, so that Provider/OCR 故障不会拖垮 Draft、StaticSchema 与 validation。
50. As an 运维负责人, I want ImageOnlyReadiness 使用完整 9 值封闭 reason code, so that 删除、时间和审计故障不会被六值旧合同遗漏。
51. As an 运维负责人, I want live 负载硬限为 20 run/日、并发 2、每 run 1–10 图, so that 单节点容量模型可验证且过载可预测拒绝。
52. As an 运维负责人, I want E2E、enqueue、queue 与 first-attempt 延迟按冻结窗口计算, so that P50/P90/P95 目标有稳定统计语义。
53. As an 运维负责人, I want 单 run ¥6/12-call、日 ¥30 soft、月 ¥500 hard 的成本边界, so that 异常费用先告警再自动停止新 run。
54. As an 运维负责人, I want 磁盘、PG 连接、Blob 删除与 sidecar 资源水位有确定动作, so that 容量压力只拒绝新 live 而不破坏确定性站点。
55. As an 运维负责人, I want telemetry label 只使用封闭枚举且从不使用 runId/actor/hash, so that 指标低基数且 payload-free。
56. As an 运维负责人, I want audit、日志、原始指标与小时聚合遵守各自 retention, so that 可观测性不会成为内容长期保存后门。
57. As an oncall 所有者, I want warning 日报与 page 即时告警映射到一页式 runbook, so that 每个生产事件都有可执行处置与验证入口。
58. As an oncall 所有者, I want Provider 事故统一执行 kill switch + drain, so that queued/running/review 状态按同一安全语义收敛。
59. As an 备份负责人, I want 信封加密完成后每日备份 PG 与 Blob 并保留 7 天, so that RPO≤24h 且备份不会引入明文副本。
60. As an 恢复负责人, I want restore 严格按 PG→Blob→reconciliation→不复活 sweep→校验放行, so that 旧备份不能绕过当前删除和 authority 状态。
61. As an 恢复负责人, I want Blob 孤儿被删除、缺失/corrupt 引用使 run 稳定失败并禁 apply, so that PG 事实不会被猜测性内容恢复覆盖。
62. As an 恢复负责人, I want tombstone、过期 payload/confirmation/authority、CLOSED J1 与关闭开关在恢复后不复活, so that 备份时间差不会重新授权外传。
63. As an 独立复核者, I want restore drill 包含 hash 对账与 crypto-erasure 不可读验证, so that 源码恢复不会冒充数据恢复证明。
64. As an API 消费者, I want live contract 使用 notice version、policy version 与 manifest identity 而不是旧布尔, so that SDK 能表达精确逐 run 权威。
65. As an API 消费者, I want 旧 boolean、Token Plan、旧 catalog、旧 caps 与公开 Actuator drift 返回 typed 410/422, so that 客户端不会静默走过时合同。
66. As a Web 用户, I want generated SDK、页面提示、readiness 与 Candidate review 同步新合同, so that UI 不会伪造服务端准入或绕过人工审核。
67. As a release 负责人, I want release gate 包含 contract、SDK regen、security、migration/recovery、capacity 与 Provider-zero drain, so that narrow green 或历史 evidence 不能被称为生产接受。
68. As a release 负责人, I want 合同兼容与数据政策由两个独立人类 J1 复核, so that Agent 自审不会替代生产风险判断。
69. As an 所有者, I want guarded pilot 仅允许本人、≤5 run/日、并发≤2、≤100 calls/≤¥50 且 authority 60 天, so that 首次真实生产暴露面严格受限。
70. As an 所有者, I want pilot authority 绑定 app SHA、v46 hash、Provider route、sidecar digest 与 capability id, so that 任一部署身份漂移立即 fail-closed。
71. As an 所有者, I want pilot exit 至少 2 周/30 run 且满足 SLO、零未关闭事件、A1/A2 与 quad J1, so that 时间流逝不会自动晋级 limited production。
72. As an 所有者, I want limited/default 的推进、回退和期限保持人工签发, so that staged rollout 不会成为自动权限升级链。
73. As an 所有者, I want ProductionLiveAuthority append-only、可到期、可即时撤销且不能委托签发新 authority, so that 产品权限有界且无委托链。
74. As an 所有者, I want Agent、CI、评测、脚本与 canary 不继承产品 authority, so that 自动化仍需各自 fresh bounded J1。
75. As an 所有者, I want terms、model、price、Profile、sidecar、Key、KEK、gateway、DB、SLO 与质量漂移触发封闭响应, so that 每种 drift 都有 stop、drain、复核或重认证动作。
76. As an 所有者, I want 只有 default 稳定≥4周/≥100 run、完整 evidence pack 与 quad J1 后才能宣布 ProductionUsable, so that 单次 pilot 或自动 gate 不会夸大生命周期状态。
77. As a future Agent, I want exact identity、negative terminal、J1 与 evidence 指针可从新上下文恢复, so that 后续实施不依赖当前会话记忆。
78. As a future Agent, I want 默认测试与 release 演练保持 Provider-zero 且清空 Key 环境, so that 普通验证永远不会产生付费调用。
79. As a future Agent, I want DeepSeek-OCR 与第二 Provider 明确排除首版, so that 已否决的 hint 路线不会潜入 v46 管线。
80. As a RenderWeave 用户, I want IMAGE_ONLY 生产化不引入 Template、Workspace、Renderer、数据适配或图片渲染, so that v1 交付边界保持稳定。

## Acceptance Criteria

| AC | 可观察行为 | 最低证据 |
|---|---|---|
| AC-IOPA-001 | canonical inventory 固定 v45 为 ACTIVE_EXPERIMENTAL；N7/R5/R5P/R5P2 与旧 J1/ledger 为 CLOSED/STALE 且不可调度 | A1 authority/prohibited-reuse gate |
| AC-IOPA-002 | v46 由 v45 只改变 profileId、maximumTotalCalls=12、maximumEstimatedCostMicrosCny=6,000,000；其他 bytes 语义相同并生成 exact SHA-256 | A1 byte-diff + independent snapshot replay |
| AC-IOPA-003 | v46 单 run 全部 reservation/settlement 累计永不超过 ¥6；历史 Profile/run 继续按原 snapshot 恢复 | A1 boundary/property + PG integration；release A2 |
| AC-IOPA-004 | ProfileCertificationRecord append-only 绑定 profileId/hash/verdict/threshold/evidence/time；grant/revoke 均追加事件且不改 Profile | Testcontainers A1；release A2 |
| AC-IOPA-005 | frozen certification manifest 完整绑定 5-case canary、20-case DEV、60-case final、20 HOLDOUT、case hash、seed、evaluator 与阈值 | A1 identity/tamper gate |
| AC-IOPA-006 | certification 依次满足 5/5、≥18/20、≥54/60；任一失败关闭周期且不能修补重跑 | A1 lifecycle + A2 evaluator replay + J1 review |
| AC-IOPA-007 | 每阶段 live 前存在 fresh scoped J1；周期末 production-policy J1 后才能 append grant record | J1 + CLOSED A1 ledger + A2 summary |
| AC-IOPA-008 | snake_case 为认证合同；本周期 kebab-case 只可人工规范化且下一 prompt 版本不得冒充 v46 | A1 contract tests + J1 case review |
| AC-IOPA-009 | gateway assertion、mTLS、actor/request/jti/method/path/idempotency digest 验证与重放拒绝成立 | A1 security integration；独立 review J1 |
| AC-IOPA-010 | 只有 USER_PROVIDED+ORDINARY_DESIGN、1–10 PNG/JPEG、单图≤10MiB/25Mpx、合计≤32MiB 被接纳 | A1 contract/property/E2E |
| AC-IOPA-011 | notice、manifest、confirmation 与 run 原子绑定；15min 首 attempt、2h calls-not-after、stale notice/confirmation 全 fail-closed | Testcontainers fault/time tests A1/A2 |
| AC-IOPA-012 | retry/idempotency/ambiguous attempt 遵守 fresh confirmation、same-fingerprint 复用、drift 409 与 no blind replay | A1 HTTP+PG+fake Provider |
| AC-IOPA-013 | ServiceReadiness 与 ImageOnlyReadiness 分轴；ImageOnly 使用 9 个 reason code 且 typed 503 | A1 runtime/contract/E2E |
| AC-IOPA-014 | dual switch、exact endpoint/credential/capability、每-call authorization 与费用余量在 dequeue/call 前重验 | A1 PG+fake Provider；release A2 |
| AC-IOPA-015 | per-artifact random DEK AEAD、wrapped DEK、KEK 隔离、tamper detection、re-wrap 与 KEK loss fail-closed 成立 | A1 crypto vectors + PG/filesystem integration；A2 review |
| AC-IOPA-016 | payload expiry/tombstone/删除/到期 review 语义成立；backlog>24h 停止新 live，既有 Draft/audit 不删除 | A1 time/fault/E2E；restore A2 |
| AC-IOPA-017 | Live Admission Audit append-only digest-chain、runtime role 无 UPDATE/DELETE、payload scan 零禁项 | Testcontainers A1 + independent payload scan A2 |
| AC-IOPA-018 | same-origin/security headers/CSRF/Origin/Fetch-Metadata/no-store/iframe denial 成立 | A1 HTTP/browser + policy J1 |
| AC-IOPA-019 | exact RapidOCR sidecar 通过 UDS、2C/2GB/PID64/60s、no-IP、只读/non-root/drop-caps 运行；API JRE 不含 OCR runtime | A1 compose/runtime/supply-chain；A2 replay |
| AC-IOPA-020 | sidecar capability/synthetic probe 与 R0 behavior-equivalence 通过；失败只关闭 ImageOnlyReadiness | A1/A2 existing IR seam + runtime canary |
| AC-IOPA-021 | 全离线 build、完整 hash lock、模型校验、SBOM/CVE/malware、license/NOTICE 与 signed attestation 缺一即不准入 | A1 artifacts + owner license J1；外部签名门若存在为 A3 |
| AC-IOPA-022 | 20 run/日、并发2、SLO、成本、水位、失败预算与测量驱动恢复均按冻结数值执行 | A1 telemetry/capacity + A2 snapshot replay |
| AC-IOPA-023 | telemetry 低基数且 payload-free；retention、warning/page、webhook 与 runbook 映射成立 | A1 integration + A2 snapshot replay + ops J1 |
| AC-IOPA-024 | 加密后每日 PG+Blob 备份、7天保留、RPO≤24h/RTO≤4h 的 runbook 可执行 | A1 isolated backup/restore drill |
| AC-IOPA-025 | restore 执行 PG→Blob→reconcile→no-resurrection→放行；孤儿、missing/corrupt、tombstone、expiry、authority/J1/kill-switch 状态正确 | A1 drill + independent hash/unreadability A2 + ops J1 |
| AC-IOPA-026 | live OpenAPI 使用精确 fields/Profile hash/readiness/tombstone/review；旧布尔和 drift typed 410/422，不双跑 | A1 contract/breaking/SDK/E2E + compatibility J1 |
| AC-IOPA-027 | Candidate 无 bulk resolution，局部 ID 不泄漏；apply 仍 create-only 原子建无 fieldId 的 Draft Bundle，published StaticSchema count/bytes/compiled artifact 不变 | A1/A2 PG+browser/architecture |
| AC-IOPA-028 | error taxonomy 封闭、静态且 payload-free；SDK regeneration diff 为零 | A1 contract/generation/payload scan |
| AC-IOPA-029 | release gate 在 full 之上覆盖 contract、SDK、security、migration/recovery、capacity、Provider-zero drain 与 exact Node24 | A1 gate pack + independent replay A2 + 双轴 J1 |
| AC-IOPA-030 | ProductionLiveAuthority append-only、exact identity/范围/caps/期限/quad-J1 全匹配；缺失、漂移、过期、撤销均 typed 503 | Testcontainers/runtime A1；pilot前 A2 |
| AC-IOPA-031 | guarded pilot 仅所有者、≤5 run/日、并发≤2、阶段≤100 calls/≤¥50、authority 60天 | A1 admission/cost/time tests + fresh scoped J1 |
| AC-IOPA-032 | stop/drain 保留 REVIEW_REQUIRED、终结 queued/running 且不复活；Agent/CI/script/eval/canary 不继承产品 authority | A1 Provider-zero exercise + A2 replay |
| AC-IOPA-033 | pilot/limited/default 推进与 ProductionUsable 宣布只由规定 A1/A2、restore、quad J1 和人类动作产生 | Evidence-pack audit + J1；无自动 self-promotion |
| AC-IOPA-034 | 默认 gate Provider attempts/reservations/cost/API-key reads=0；无 StaticSchema 自动发布、无 DeepSeek-OCR/第二 Provider、无禁区能力或 scope 占位 | A1 architecture/route/schema/payload gate；release A2 |

## Implementation Decisions

### 1. Authority and lifecycle

1. 源票是细节唯一权威；实现必须生成一份机器可读的 canonical inventory/prohibited-reuse decision，拒绝旧
   N7/R5/R5P/R5P2 identity、assignment、authorization、ledger 与 evidence 被新周期引用。
2. 生命周期从 `planned` 开始；自动实现或门控不能把状态直接提升为 `accepted`、`released` 或
   `ProductionUsable`。认证失败、阶段失败与 rollout 失败都记录 negative terminal，不通过重命名或 rerun 覆盖。
3. global AC-021 继续未完成。本合同只提供 IMAGE_ONLY production certification slice，JSON_ONLY 与
   COMBINED 不读取、继承或宣传该结论。

### 1.1 Exact identity baseline

| Identity | 本 delta 的精确值/固定时机 |
|---|---|
| Provider route | `https://dashscope.aliyuncs.com/compatible-mode/v1`；不得 redirect、读取环境代理或明文降级 |
| Provider credential | `DASHSCOPE_API_KEY` 或其只读 `_FILE` 形式；只由 Provider Adapter 读取 |
| Provider model | `qwen3.8-max` |
| Historical active Profile | `dashscope-qwen38-max-product-v45-hybrid-generic`，`ACTIVE_EXPERIMENTAL` |
| Production candidate Profile | v46；profileId 与 canonical bytes SHA-256 在资源创建时固定，之前不得预写或使用 `latest` |
| v46 fixed caps | calls=12、run aggregate cost=¥6、output tokens=8192、stage timeout=360s |
| OCR capability | `rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1` |
| OCR base/final image | `python:3.12-slim-bookworm` 与最终 sidecar 均在构建期固定 digest；当前没有可授权的 digest |
| Release application | app Git SHA 在 release candidate 固定；当前 planning SHA 不能冒充 pilot identity |
| Production authority | 当前不存在；全部既有 live authorization 为 `CLOSED` |

### 2. v46 Profile and certification authority

1. 首周期 sole finalist 是 `qwen3.8-max` v46。v46 的 canonical bytes 只相对 v45 改 Profile identity、
   `maximumTotalCalls=12` 与 `maximumEstimatedCostMicrosCny=6,000,000`；Prompt、pipeline、threshold、
   output token、stage timeout、pricing snapshot、route 与 OCR capability 不变。
2. 对 v46，¥6 是整个 run 的 Profile-owned aggregate cap，同时仍是单次预留估值的绝对拒绝上界；每次新
   reservation 都必须把已结算与仍预留费用一并计算。历史 Profile 继续按其 snapshot 语义恢复，不被改写。
3. v46 在 certification grant 之前保持隐藏的认证候选，不能作为普通生产 Profile。v45/Flash/Plus 历史
   resources、snapshot 与目录事实保持只读；只有 v46 grant record 可成为首个 production catalog 成员。
4. Certification 是独立深 Module。其 Interface 接受 frozen cycle identity 和 stage outcome，隐藏 assignment、
   threshold、ledger 汇总、review verdict、A2 replay 与 append-only grant/revoke 实现细节。
5. `ProfileCertificationRecord` 与 cycle event 使用 PostgreSQL append-only 存储；认证纯事件失效，不设时间 TTL。
   Profile bytes、corpus manifest、evaluator revision、threshold 或证据指针漂移均使记录失效并要求新周期。
6. 认证 manifest 以 N9/R1 60-case/58-metric 基础设施为骨架，建立认证专用 seeded assignment；20 个
   HOLDOUT 在 DEV 全程不可见。每个 case 的人工 verdict 是通过定义的一部分。
7. 每个 5/20/60 live stage 在开始前都需要新的 scoped J1 JSON；production-policy J1 只在全部阶段 A1/A2
   通过后签发。任一阶段失败立即关闭当次 ledger/cycle，其他零 Provider 安全实现任务仍可继续。

### 3. One primary admission seam

1. 新增深 Module `ImageOnlyProductionAdmission`。它的外部 Interface 是一条 signed live command；Module
   内聚 GatewayAssertion、classification、notice、manifest、confirmation、Profile certification、
   ProductionLiveAuthority、dual switches、time、capacity、cost、audit 与 run creation 的排序和错误语义。
2. Controller/Web 不自行拼接准入谓词。调用者只提交合同字段并处理 admitted/rejected 结果；所有 run 创建、
   confirmation 与首条 audit 记录在同一 PostgreSQL 事务中线性化。
3. 每次 dequeue 与 Provider call 前通过同一 Module 重算瞬时 `ProviderCallAuthorization`；不得缓存 run 创建时
   的布尔结果。Provider 是 true external dependency，继续使用 production HTTP Adapter 与 test fake Adapter。

### 4. Gateway, identity, notice and confirmation

1. 唯一公共入口是 gateway。应用只验证短期非对称 JWS GatewayAssertion，不建立账号、session 或 RBAC；
   actor 是 opaque audit identity，同租户 operator 在同一数据 trust domain。
2. mutation assertion 绑定 Idempotency-Key digest；jti 防重放。Actuator 使用独立 mTLS listener/service identity，
   不进入公共入口。
3. `ExternalTransferNotice` immutable 且按 version/locale/content digest 标识；
   `ExternalTransferConfirmation` 与实际 normalized `LiveInputManifest`、Profile bytes hash、合同、分类、caps、
   deadline、actor/request/run 原子绑定。
4. 新 notice 使尚未首次 dispatch 的旧 confirmation 失效。首次 dispatch 超过 15 分钟、调用晚于 2 小时、
   time authority 异常或 exact identity 漂移均 fail-closed。
5. gateway→API 与 API→PostgreSQL 使用 mTLS；Provider HTTPS 必须验证证书与 hostname，并拒绝 redirect、环境代理与
   明文降级。Gateway signer 以 `kid` 轮换，旧公钥重叠窗口最多为 60 秒 assertion TTL + 30 秒 clock skew；mTLS
   使用双 trust-set 轮换。30 秒容差只用于 assertion 验证，不延长 confirmation、call 或 payload expiry。
6. persisted deadline 使用 UTC wall clock，进程内 timeout 使用 monotonic clock；时钟偏差超过 30 秒、来源不可用或
   rollback 均关闭 ImageOnlyReadiness。只有 ops mTLS identity 可修改持久化 live policy，修改写 payload-free audit。

### 5. Input, encryption and deletion

1. public input 只接纳 ordinary user-provided static PNG/JPEG。API 先做 multipart byte 与 closed envelope gate；
   解码、magic/frame/dimension、metadata stripping、sRGB PNG normalization 与 OCR 在 isolated sidecar 中完成。
2. normalized artifact 继续保留 domain-separated SHA-256 内容身份，但持久 bytes 必须改为随机 DEK 的 AEAD
   ciphertext；wrapped DEK、algorithm/version/nonce/tag 与 integrity metadata 由 PostgreSQL 权威记录。
3. KEK 只由 orchestrator secret 提供，不进入 Blob、DB backup、HTTP、gateway、sidecar、日志或证据。
   re-wrap 只改变 wrapped DEK；旧 KEK 引用归零前不得销毁。
4. `PayloadDeletionTombstone` 是读取、外传、retry 与 apply 的首要否决事实。逻辑删除先于物理/密码删除；
   ciphertext 与 wrapped DEK 双清完成 crypto-erasure，异步失败继续重试并驱动 readiness。
5. expiry 从首次上传计算并由所有引用 run 共享；retry 不延长。剩余不足 24 小时的 payload 不可复用。
6. 原始 upload bytes 只在 normalization 期间存在且不落盘。唯一内容路径是 Browser→Gateway→API→sidecar→private
   BlobStore；Provider 只接收从 normalized artifact 生成的 Base64 Data URL，不接收原文件名、Blob locator、内部 URL、
   callback 或 redirect。overview/tile/crop bytes 仅在 call 期间存在，只保留 descriptor/digest；审核图片只经认证
   gateway/API 返回 `Cache-Control: private, no-store`。
7. PDF、SVG、GIF、WebP、APNG、远程 URL 与压缩包固定拒绝。原文件名只允许上传前本地展示，不能持久化。
8. 普通路径没有自动 forensic hold。误分类/疑似 `RESTRICTED` 时关闭双开关、撤销相关 confirmation、删除 payload，
   只保留 payload-free audit/usage；保留图片必须取得新的具名法律/安全授权及独立范围/期限，且不属于首版。

### 6. OCR sidecar and perception seam

1. 生产 OCR Adapter 从现有 fixed subprocess 改为 HTTP/1.1 over UDS；JSON envelope 与
   `DocumentObservationIR/1.0` compatibility projection 语义不变。dev/offline 可保留 stdio Adapter。
2. Sidecar 使用 exact base-image digest、locked wheels/packages/models 与 zero-download startup；运行时 no-IP、
   read-only rootfs、non-root、drop-all-caps、2 CPU、2GB、PID64、60s。
3. 现有 `normalized ArtifactSet + AcquisitionPolicy → DocumentObservationIR/1.0` 是 sidecar 的测试 Interface；
   不另建第二套 OCR DTO truth。capability identity 只有在 R0 behavior-equivalence、supply chain 和探针全过时
   才保持既定 exact value。
4. 首版没有可回滚的第二个认证 capability；失败只关闭 ImageOnlyReadiness，确定性 Schema 服务继续 ready。
5. production lock 必须包含 `omegaconf==2.3.0` 与在固定 builder 生成的内部
   `antlr4-python3-runtime==4.9.3` wheel；三份 capability ONNX 从 RapidOCR wheel 在构建期提取、逐一 SHA-256
   校验并只读预置。浮动两行 requirements、Alpine/musl、ARM 与启动下载都不准入。
6. 构建产物必须带完整 transitive/OS/model provenance、SBOM、CVE/malware 结果、license/NOTICE 与 signed
   attestation digest。RapidOCR wheel 缺失的 LICENSE/NOTICE 必须补入 bundle；Apache-2.0 主线及 exact 转换模型/
   系统库 disposition 需要所有者 license J1，J0 时 capability 不准入。

### 7. Readiness, policy and audit

1. `ServiceReadiness` 只代表 Web/API/PostgreSQL/Blob/确定性 Schema；`ImageOnlyReadiness` 额外要求 Provider
   contract、certification、credential、egress、OCR、policy、deletion、time 与 audit 全部成立。
2. ImageOnly reason code 采用票 04 的完整 9 值集合：六个初始值加
   `PAYLOAD_DELETION_UNHEALTHY`、`TIME_AUTHORITY_UNAVAILABLE`、`AUDIT_INTEGRITY_UNAVAILABLE`。
3. `ImageOnlyAdmissionPolicy` 是持久化版本化应用策略；`ProviderEgressPermit` 由应用外执行。配置 credential
   不能自动开启任何开关；人工紧急开关不因水位回落自动重开。
4. Live audit 使用 append-only sequence+digest-chain；runtime role 没有 UPDATE/DELETE。所有日志、metric、
   evidence、error 与 webhook 都执行 payload scan 和低基数约束。

### 8. SLO, capacity and telemetry

1. Admission 同时执行 run/day、concurrency、input、disk、deletion、cost 与 authority aggregate caps；硬边界
   拒绝新工作，不能取消已完成 Candidate 的人工审核。
2. OperationalTelemetry 使用应用内聚合、internal actuator JSON 与 PostgreSQL append-only periodic snapshot，
   不引入 Prometheus/Grafana。A2 verifier 从 snapshot 独立重算 SLO 与告警结论。
3. 费用以实际 usage/保守 reservation 计入 v46 run、日 soft、月 hard 与 rollout aggregate。月 hard 触发
   自动 stop-admission，但重新开启仍是人工操作。
4. 每个 warning/page code 绑定一页 runbook；webhook 只发送固定 code、level、time 与安全聚合值。
5. 测量窗口固定为：延迟 7 日滚动、availability 月度；≤20 live run/日、并发≤2；E2E P50≤3min/P90≤15min、
   enqueue P95≤5s、queue P90≤20min、first attempt P90≤2min；Service 99.5%、ImageOnly 99%。
6. 水位固定为 disk 70% warning/85% hard reject、PG connection 80% warning、deletion backlog>24h fail-closed。
   自然日 Provider reject/timeout FAILED 同时达到≥4且≥50%只告警并建议人工撤销，不自动撤销；测量恢复可解除自动
   水位关闭，但两个人工开关永不自动打开。
7. retention 固定为 audit 在线90天/月度归档13个月、应用日志30天、原始指标30天/小时聚合13个月。warning 日报包含
   disk70%、日成本¥30、failure budget、backup>25h、sidecar degradation；page 包含 readiness fail-closed、删除/
   审计异常、月成本¥500、restore失败与误分类。

### 9. Backup and recovery

1. 备份功能只有在 envelope encryption 通过后才能启用。日备份由 PG dump 与 Blob ciphertext snapshot 组成，
   保存到外挂/异盘卷 7 天；KEK 独立离线保管且不进入备份。
2. Restore runbook 固定为 PG、Blob、reconciliation、no-resurrection、validation 五个阻塞步骤。PG 是引用权威；
   orphan Blob 删除，missing/corrupt artifact 进入稳定终态并禁止 apply。
3. no-resurrection sweep 必须重放 tombstone，并重新评估当前 wall clock、confirmation、authority、J1 与开关事件；
   不能信任备份时刻的“active”投影。
4. 首入 pilot 前完成隔离 restore drill、independent hash reconciliation 与 crypto-erasure unreadability proof，
   并取得 ops J1。源码、数据与外部 Provider 费用/流量分别报告恢复状态。
5. 首版不做 WAL continuous archive；backup access 仅所有者。若加密迁移前出现必须备份的人工例外，归档必须整体
   加密且归档 key 与 KEK 分离，并先取得新的 scoped 人工批准；它不是常态生产路径。

### 10. Public contract and Web

1. live metadata 删除 `externalTransferConfirmed` 与 `experimentalProfileConfirmed`，改为 exact notice/policy/
   manifest/Profile identity 和分类字段；run caps 由 Profile/authority 决定，不由客户端扩大。
2. 旧字段、旧 Token Plan route、旧 catalog/caps/delete 描述与 public Actuator 使用 typed 410/422 拒绝；
   不提供双格式迁移窗口。
3. OpenAPI contract release 升版，generated Web SDK 必须由 source 重建且 diff-clean。UI 展示精确 notice、
   classification、Profile hash/caps、readiness 和 deletion 状态，不把 capability readiness 描述为 certification。
4. Candidate review 与 atomic apply 行为保持现有 Interface；bulk resolution 继续固定拒绝。
5. Schema DSL 继续是事实源，生产 Candidate 必须通过 RenderWeave validator；通用 JSON Schema validator 不能替代它。
   Candidate 局部 ID 在 apply 前移除，不能进入 Draft、StaticSchema 或 compiled JSON Schema；既有 StaticSchema 内容与
   compiled artifact 永不 UPDATE、DELETE 或重编译。

### 11. Release gate and rollout

1. production release gate 在现有 full family 上叠加 contract/breaking check、SDK regen、security headers、
   Testcontainers migration/recovery compatibility、CapacityBaseline、sidecar supply chain、Provider-zero drain 与
   restore evidence pack。所有默认 gate 清空 Key/live authorization 并断言 Provider accounting 为零。
2. `ProductionLiveAuthority` 使用 append-only grant/revoke events，绑定 stage、app SHA、v46 hash、route、
   sidecar digest/capability、user/input scope、aggregate caps、effective/expiry 与 quad J1。每次 live admission
   全匹配；authority 不能签发另一个 authority。
3. guarded pilot 的 fresh scoped J1/authorization JSON 与 authority 同一范围：仅所有者 ordinary-design、
   ≤5 run/日、并发≤2、≤100 calls/≤¥50、60 天。Agent/CI/eval/canary 均不在其 actor scope 内。
4. pilot 入口不等于 pilot exit；limited/default 与 `ProductionUsable` 的时长、run 数、restore/kill-switch、
   A1/A2、零事件和 quad J1 继续作为后续人工 gate。
5. 首个 pilot authority 的 quad J1 分别引用：visual（v46 certification 人工质量证据）、business（所有者接受
   guarded pilot）、ops（release/restore/runbook）、policy（ordinary-design/notice/retention/Provider residual risk）。
   四个 verdict 必须分别记录；pilot exit 仍需基于 pilot 抽样≥10% run 的新 quad J1，不能复用 entry verdict 晋级。

## ADDED / MODIFIED / REMOVED

### ADDED

- IMAGE_ONLY mode-specific Profile Certification 与 Production Admission contract。
- v46 sole-finalist Profile、certification cycle/record、production authority、notice/manifest/confirmation、
  GatewayAssertion、dual switches、call authorization、encrypted artifact、tombstone、audit chain、双轴 readiness、
  telemetry snapshot、backup/restore/reconciliation 与 staged rollout 语义。
- AC-IOPA-001..034 及对应 release/pilot evidence pack。

### MODIFIED

- AC-015 的 IMAGE_ONLY slice 从布尔外传确认改为 classification + immutable notice/manifest/confirmation；
  source batch cap 从 30MiB 对齐为 32MiB，单图 25Mpx；JSON_ONLY/COMBINED 语义不因本 delta 获得生产认证。
- AC-019/020 的 live recovery、retry、费用与 secret 边界增加 confirmation/authority/deadline/ambiguous attempt、
  dual switch、per-call authorization 与 audit-chain 前置。
- AC-021 保持全局未完成，但新增独立 IMAGE_ONLY 5/20/60 certification contract。
- AC-022/024 增加 breaking migration、generated SDK、security、encryption、telemetry、backup/restore 与
  production release gate。
- product catalog 从三份 v45 EXPERIMENTAL 选择器迁移为“v45 历史可读 + 仅经 record 授权的 v46 Max 生产项”。

### REMOVED

- live API 中旧 external-transfer/experimental-profile boolean 权威。
- Token Plan 字段/route、客户端可提交的 run cost authority、公开 Actuator 与仅依 credential/worker/upload
  判断 live 可用的旧语义。
- normalized payload 明文持久化、永久随 run 保留以及恢复后只读检查即可放行的旧运维语义。
- 首个生产目录中的 Flash/Plus、自动模型 fallback、DeepSeek-OCR 与任何未认证 capability。

## Testing Decisions

### Test seams

主验收 seam 只有一个：

> signed `POST /api/v1/inference-runs/live` command → real PostgreSQL + encrypted artifact store + production-admission Module + fake Provider → durable `REVIEW_REQUIRED` →逐项 review/apply

它从用户/系统最高边界证明 identity、classification、notice、manifest、confirmation、idempotency、readiness、
authority、audit、cost、worker、Candidate 与 create-only apply；测试只断言 HTTP、持久化 authority/event、
Provider fake 收到的有界调用次数和最终状态，不断言 controller/helper/class collaboration。

三个不可合并的专用 seam 保留：

1. `normalized ArtifactSet + AcquisitionPolicy → DocumentObservationIR/1.0`：现有 R0 seam，证明 UDS sidecar
   与 v45/v46 perception 语义、payload-free 和故障降级；不穿透 RapidOCR/Python 内部对象。
2. `FrozenCertificationCycle → ProfileCertificationRecord event`：证明 5/20/60 assignment、threshold、人工 verdict、
   A1 ledger、A2 N9/R1 replay 与 negative terminal；live Adapter 只在 fresh J1 下启用。
3. `encrypted backup set → restored readiness decision`：在隔离拓扑中证明 reconciliation、no-resurrection、
   missing/corrupt 与 crypto-erasure；Git 回退不参与该 seam。

### What makes a good test

- 只测试外部行为、immutable identity、事务结果、typed code、状态转换、预算、deadline、redaction 与恢复；
  内部重构不应要求改测试。
- PostgreSQL 语义全部使用 Testcontainers PostgreSQL；不使用 H2/SQLite。
- Candidate/apply 测试断言局部 ID 被移除、Draft 无 fieldId、既有 StaticSchema/compiled artifact 的 identity 与 bytes
  完全不变；validator oracle 是 RenderWeave DSL validator，不用通用 JSON Schema 合法性替代。
- Provider 使用 fake Adapter 或 Provider-zero exercise；普通 gate 显式清空 Key 和 authorization，并在结尾断言
  attempts/reservations/cost/API-key reads 为 0。
- Crypto 使用固定 known-answer/tamper vectors 和随机 nonce property tests；测试 KEK 只存在于测试进程，
  不写日志/证据。
- 时间通过 injected clock/monotonic timer 测试 15min、2h、7d、24h、60d 与 rollback/skew 边界。
- 对 exact identity、manifest、record、authority、audit chain、backup index 做 positive、one-field drift、tamper、
  duplicate、reorder、missing 与 stale vectors。
- payload scan 覆盖 log、problem、webhook、metric snapshot、evidence、stderr、exception 与 restore report。
- 边界数值测试 equality 与 ±1：calls/cost/run/day/image bytes/pixels/batch bytes/disk/backlog/threshold/expiry。

### Prior art to reuse

- Profile registry/snapshot contract、Goal ledger/authorization lifecycle 与独立 verifier。
- R0 `DocumentObservationIR` behavior-equivalence、R1 60-case/58-metric Java/Python exact replay。
- PostgreSQL durable run/lease/checkpoint、provider reservation 与 Candidate atomic apply integration tests。
- `CapacityBaselineTest`、runtime canary、Node 24 Web gate、OpenAPI generated client 与 inference browser E2E。
- File-system Blob digest/tamper tests作为 encrypted Adapter 的迁移基线；旧明文行为不保留为 production contract。

### Gate order

按 `RULE-VAL-001` 扩大：focused contract/property/crypto → Testcontainers admission/storage/audit → sidecar/R0 →
OpenAPI/SDK/Web → Provider-zero end-to-end → capacity/telemetry → full production-release gate → isolated restore drill →
independent A2 → required J1。任何后续绿色结果都不能覆盖前序 authority、privacy、certification 或 recovery 失败。

## Impact

- 用户价值/范围：允许 IMAGE_ONLY 从实验路径进入受限生产试点，同时保持人工审核、Draft-only apply 和确定性站点可用。
- 实现与数据：Inference/Profile、application admission/security、PostgreSQL/Flyway、Blob/KEK、OCR container、
  OpenAPI/Web、telemetry、backup/recovery、Compose/gate 均受影响；迁移 forward-only，历史 snapshot/record 不改写。
- 验证与发布：新增 production-release 与 restore gate；本地自动上限 A1，指定独立 verifier 可达 A2；无 A3。
- DAG/预算：关键路径为 v46+认证 → secure live/release → restore proof → guarded pilot；每个 live 阶段单独 J1，
  不建立常开 ledger。
- 恢复影响：源码为 record-only diff；数据靠 forward migration、backup/reconciliation/no-resurrection；
  Provider 调用和费用不可回滚，只能 stop/drain/close/revoke。

## Out of Scope

- JSON_ONLY、COMBINED 的生产认证或全局 AC-021 完成声明。
- 多租户、应用内账号/RBAC、HA、横向扩展、多区域、render farm 或 egress proxy。
- 自动发布 StaticSchema、自动 confirm-all、自动跳过 Candidate 人工审核、AI update/delete/publish/SQL/filesystem/arbitrary HTTP。
- 第二 Provider、自动模型 fallback、动态 `latest` Profile、运行中改写 immutable Profile。
- DeepSeek-OCR、GPU OCR 节点、MinerU/PaddleOCR 新 capability；未来重评估需新票与新 scoped J1。
- Template、Renderer、Workspace、Connector、数据适配或图片渲染。
- 修复/重跑旧 N7、R5、R5P、R5P2，或复用其 CLOSED J1、identity、assignment、ledger/evidence。
- 在本次 spec/planning 交付中实施产品代码、生产 cutover、真实 Provider 调用、真实用户数据传输、API Key 检查、commit 或 push。

## Further Notes

1. Blueprint 附录 A 已完成四维冲突检查。本 delta 采用票 04 的 9 值 reason-code 全集；这是已记录的编辑性
   drift 归一化，不是新产品决策。
2. v46 的 profileId 与 bytes SHA-256 只有在资源实际创建后才成为 exact identity；在此之前任何预写 hash、
   `latest` alias 或从 v45 hash 推导的值都无效。
3. 2026-08-17 Max 2/2 REVIEW_REQUIRED 仅为允许继承的试用信号，不替代 5/20/60 门槛或新阶段 J1。
4. DashScope 标准在线条款的人工访问、数值 retention/delete SLA、子处理者/跨境/DPA 未知风险已由所有者
   明示接受；产品 notice 不得把未知项宣传为 Provider 保证。
5. 实施计划状态只能是 `Plan-ready`。guarded live、真实数据、restore drill 和 pilot 均需要各自的人类阻断与
   恢复证据，当前能力不满足无人值守 Auto-ready。

## Decision

- 批准人：RenderWeave 所有者
- 批准日期：2026-08-17
- 结论：批准按 Blueprint v1 和 16 张 resolved 源票实施；本 delta 不重新打开地图决策。
- 理由：用户已完成逐票决策与 Blueprint J1 验收，本次明确要求把该闭环结果发布为 approved delta 和
  可执行计划；实施中的普通技术选择可在不改变这些语义的前提下由 Agent 完成。
