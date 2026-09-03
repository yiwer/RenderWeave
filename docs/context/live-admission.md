# 领域分片：Live 准入、认证与 Payload 治理

> 由 `CONTEXT-MAP.md` 路由加载。触碰 Inference Profile、Profile Certification、live run 授权、外传
> 确认、Provider 出口、预算/账本、payload 生命周期（加密、过期、删除）或 readiness 投影时读取本分片。
> 该词汇群由 IMAGE_ONLY Production Admission 工作管辖（停泊中，见 `plans/image-only-production-admission-plan-v1.md`）。

| 术语 | 精确定义 | 不代表什么 |
|---|---|---|
| Goal authority epoch | 一段由明确决策创建、具有独立上限且只追加 reservation 的视觉评测预算权威；它通过不可变 lineage 连接前序消费事实。 | 不是清零历史消费、复用旧 J1、重新打开旧 ledger 或自动获得 Provider 调用权限。 |
| Quarantined charged baseline | 精确逐条账本不可恢复时，把经多源确认的前序累计消费按最坏上界继续计费并终结调度歧义的不可变基线。 | 不是伪造 SETTLED usage、活动 RESERVED、预算退款或可再次消费的余额。 |
| Inference Profile | 版本化的 provider/model/prompt/output schema/budget/eval 配置快照。 | 不使用 `latest` 语义。 |
| Product Profile catalog | 当前允许创建新 live run 的、顺序固定且 ID 精确的 immutable Inference Profile 集合。 | 不是质量认证、`latest` alias，也不会改变历史 run snapshot。 |
| Profile readiness | 启动时探测到的本地 capability 是否精确满足某个 Inference Profile 的 payload-free admission 事实。 | 不是模型质量、Provider 凭据、费用授权或对缺失能力的静默降级。 |
| Profile Certification | 一份精确不可变 Inference Profile 对冻结语料、指标、独立复核与人工判断所形成的质量资格事实。 | 不是 Profile readiness、产品目录可见性、单例可达、运行授权或自动成为默认模型。 |
| FrozenCertificationCycle | 在查看 stage 输出前绑定 exact Profile bytes、5/20/60 case hashes/assignment、20 HOLDOUT、threshold、evaluator 与 evidence identity 的不可变认证周期。 | 不是可补丁的评测配置、历史 N7/R5 assignment、live J1、Profile grant 或失败后可原地重跑的容器。 |
| Profile Successor Diagnostic | 新 immutable Profile 在进入评分 canary 前，对一个已知失败模式执行的独立、非计分、fresh-authorized live 回归周期。 | 不是 FrozenCertificationCycle、5/20/60 case、Profile Certification 证据、旧 cycle retry 或后续 live 授权。 |
| ProfileCertificationRecord | 对某一 exact Inference Profile（`profileId` + bytes SHA-256）授予、维持或撤销 Profile Certification 的 append-only 事件投影；状态变化只追加 event，既有 event 与 Profile bytes 均不改写。 | 不是 Inference Profile bytes 的一部分、Profile readiness、live 运行授权或 Product Profile catalog 成员资格本身。 |
| InputProvenance | 一次推断输入的来源与提交者是否拥有处理及外发权利的事实；首个产品值为 `USER_PROVIDED`。 | 不是数据敏感等级、内容安全判断、Provider 处理合同或长期授权。 |
| SensitivityClass | 对一次推断输入的数据敏感程度作出的闭合分类；首版只有可准入的 `ORDINARY_DESIGN` 与必拒绝的 `RESTRICTED`，缺失或未知不构成第三个可放行值。 | 不是 InputProvenance、用户勾选的外发确认、文件类型、AI 自动判定或模型 Profile。 |
| GatewayAssertion | 唯一外部网关为一次 API 请求签发、由应用验证的短期身份事实，以 opaque actor 与 request identity 把上游认证及 mutation intent 带入审计边界。 | 不是 RenderWeave 账号/RBAC、客户端可声明 header、长期 session、数据外传确认或业务授权。 |
| ExternalTransferNotice | 在用户确认前展示、以 exact version 与内容身份冻结 Provider、处理政策、Profile、预算及 payload retention 的不可变说明。 | 不是营销页面、动态帮助文本、Provider 合同本身、用户确认或可在确认后静默改写的文案。 |
| ExternalTransferConfirmation | 操作者逐 run 同意把 exact 已分类输入按 exact ExternalTransferNotice、Provider policy/Profile 与有界预算外传的不可变确认事实。 | 不是布尔字段、GatewayAssertion、历史 J1、跨 retry/run/Profile/input 可复用授权、无限期排队许可或 Profile Certification。 |
| ImageOnlyAdmissionPolicy | 决定 IMAGE_ONLY live Provider 工作当前是否可被准入或继续的应用级紧急策略。 | 不是 ExternalTransferConfirmation、Provider 合同、Profile Certification、整站 readiness 或对已产生 Candidate 的审核禁令。 |
| ProviderEgressPermit | 独立决定 RenderWeave 当前是否可向获准 Provider route 发起新调用的出口许可。 | 不是网络可达性、secret 存在性、GatewayAssertion、业务准入策略或任意 HTTP 能力。 |
| ProviderCallAuthorization | 每次外部模型调用前，由有效确认、未撤销合同/Profile、双重开关、exact 输入与剩余预算共同形成的瞬时准入事实。 | 不是 run 创建成功、已排队状态、可缓存布尔值、自动 retry 权利或历史 J1。 |
| Ambiguous Provider Attempt | 请求可能已离开应用、但 Provider 是否接纳及是否产生费用均无权威结论的一次调用尝试。 | 不是可安全重放的失败、零费用证明、已结算 usage 或允许猜测结果的超时。 |
| Live Admission Audit | 以 opaque identity、digest、decision code 与 usage/cost 记录 live 准入和外传决策的 payload-free 审计投影。 | 不是原始图片、OCR、完整模型请求/响应、GatewayAssertion token、个人身份目录或 chain-of-thought。 |
| Inference Payload | 一次推断中承载源内容的原始上传、规范化图片与临时派生视图的统称。 | 不是 input fingerprint、Live Admission Audit、Candidate、Evidence locator 或已创建的 Draft Bundle。 |
| Live Input Manifest | 对一组 exact 规范化推断输入的有序、payload-free 身份投影，用于把确认、run 与实际外传内容绑定在一起。 | 不是原始文件名、图片 bytes、可编辑上传清单、Provider request 或跨输入复用的授权。 |
| Payload Expiry | 从 payload 首次进入系统起计算、所有引用 run 共同遵守的绝对内容删除期限。 | 不是 run deadline、确认有效期、日志 retention、可被 retry 延长的 lease 或 Provider 侧删除证明。 |
| Encrypted Inference Artifact | 以独立内容密钥保护、同时保留可验证内容身份的持久化 Inference Payload artifact。 | 不是明文 Blob、磁盘整卷加密、Provider payload、可公开下载对象或长期保留许可。 |
| Payload Deletion Tombstone | payload 被撤回或到期后立即禁止其读取、外传、retry 与 apply，并持续驱动物理及密码删除的 payload-free 权威事实。 | 不是 bytes 已删除证明、可恢复软删除、审计删除、Draft Bundle 删除或 retention 延期。 |
| Tenant Operator | 经唯一 gateway 认证并获准访问 RenderWeave 的 opaque 单租户操作者；首个生产部署把所有获准操作者视为同一数据信任域。 | 不是应用内账号、角色、资源 owner、外传确认本身或跨租户身份。 |
| ProductionLiveAuthority | 在冻结 revision、deployment、Provider contract、已认证 Profile、发布阶段及总预算边界内，允许真实 Tenant Operator 发起普通生产 live run 的有期产品权威。 | 不是 Profile Certification、历史 J1、永久开关、Agent/CI/canary 授权、逐 run confirmation 或任意 Provider 能力。 |
| ServiceReadiness | Web/API、PostgreSQL、Blob 与确定性 Schema 能力足以继续接收常规流量的整站可用事实。 | 不是 IMAGE_ONLY、Provider、OCR 或某份 Profile 已可创建 live run。 |
| ImageOnlyReadiness | 新建 IMAGE_ONLY live run 在 Provider 合同、Profile Certification、secret/egress、OCR capability 与 live policy 上同时满足准入的 payload-free feature 状态。 | 不是 ServiceReadiness、一次 run 必然成功、质量认证本身或逐 run 外传确认。 |
| ProductionUsable | IMAGE_ONLY Schema Recognition 同时取得 Profile Certification、live admission、operations acceptance 与 recovery proof 后的复合生产终态。 | 不是 Profile readiness、一次成功 live run、`EXPERIMENTAL` opt-in、自动 gate 全绿或历史 J1。 |
