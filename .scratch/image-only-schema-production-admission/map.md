# Wayfinder Map：IMAGE_ONLY Schema Recognition Production Admission

Label: wayfinder:map
Status: **closed**（2026-08-17 所有者 J1 验收通过；Blueprint v1 = `plans/image-only-production-admission-blueprint-v1.md`，handoff 移交 `$to-spec`）

## Destination

形成一份决策完备、可直接转交 `$to-spec` 的《IMAGE_ONLY Schema Recognition Production Admission Blueprint》：冻结生产部署信任边界、数据处理政策、精确 Profile 质量认证、服务 SLO、观测与恢复、分阶段发布/回滚以及所需 A1/A2/J1 证据。本地图不实施产品代码、不部署生产，也不执行真实 Provider 调用。

地图完成时，通往 `ProductionUsable` 的所有产品与工程决策都已有唯一答案；至少一份精确 immutable Inference Profile 可以在单租户、单节点、外部网关保护的部署中进入生产准入，Candidate 仍必须人工审核后才能原子创建 Draft Bundle。

## Notes

- `ProductionUsable` 是 `Profile Certification + live admission + operations acceptance + recovery proof` 的复合终态；不能由 Profile readiness、单例 reachability、`EXPERIMENTAL` opt-in 或历史 J1 代替。
- 首个生产目标是单租户、单节点，由外部网关承担 TLS、认证与访问控制；RenderWeave 本次不增加账号、RBAC、多租户或 HA。
- 本地图只认证 IMAGE_ONLY。现有 image/json/combined 全局 AC-021 与当前 IMAGE_ONLY 产品目录之间的 authority 冲突必须通过新的 mode-specific approved delta 解决，不能用 IMAGE_ONLY 结果外推其他模式。
- 生产准入至少要求一份 sole-finalist immutable Profile 完成认证；未认证 Profile 保持实验或隐藏，质量不得由成本、延迟或人工审核存在本身抵消。
- 首版只接纳提交者有权处理和外发的普通设计图片；`InputProvenance` 与 `SensitivityClass` 分离，受监管、秘密或 `RESTRICTED` 输入必须 fail-closed。
- R5、R5P、R5P2 与旧 N7 的 authority、evidence 和负面 terminal 都是不可变历史记录，只能作为事实输入；本地图不修补、重跑、改名或把它们解释为 PASS。
- 未来质量路线保持 solution-neutral：不把 paired/adaptive inspection 设为发布前置，也不预先创建 R5P3；若当前行为不可能达标，必须进入全新 approved spec、namespace、identity、assignment 与停止规则。
- Tracker 使用本地 Markdown；grilling tickets 必须使用 `grilling` 与 `domain-modeling`，research tickets 必须使用 `research` 且只采信一手资料。
- 外部资料不能证明的 Provider 合同事实必须继续记录为 `UNKNOWN/REQUIRES_CONTRACT_REVIEW`，不得根据营销描述或历史行为推断；ticket 06 的所有者风险接受不把未知事实改写为已获保证。
- research 使用相邻隔离 worktree 与 `research/<name>` 分支，并把单一 Markdown 报告放在 `docs/research/image-only-production-admission/`；当前 main 只持有 map、tickets 与已获确认的领域词汇。
- Provider attempts、reservations、cost 与 API-key reads 默认保持 0，直到：(a) 按量付费 route migration 的 approved change-spec 落地（新 endpoint/`DASHSCOPE_API_KEY`/新 immutable Profile）；(b) 用户授予绑定精确 Profile、数据分类、次数、费用与时限的当次 J1。书面企业合同不再是 live 前置（2026-08-16/17 所有者决定，见 ticket 06）。

## Decisions so far

<!-- 子票据解决后只追加一行 gist + link；详细答案只存在于子票据。 -->

- [确定 IMAGE_ONLY 生产准入的当前权威状态](issues/01-establish-canonical-authority.md)：product-v45 仅为 active experimental 基线；N7/R5/R5P/R5P2 与历史 J1 全部关闭，旧 plan lifecycle 只作历史索引，生产认证必须进入全新 mode-specific authority。
- [核实 DashScope 对生产图片的处理合同](issues/02-research-dashscope-processing-contract.md)：Token Plan 明确不允许应用后端生产调用；标准按量付费是候选产品，但公开资料无法证明数值保留/删除、人工访问、子处理者、地域与中国站 DPA，后续所有者风险接受另见 Provider authority 决策。
- [核实 RapidOCR 生产容器的许可与运行约束](issues/03-research-rapidocr-container-constraints.md)：exact Linux/amd64 CPU capability 有受限 A1 可行证据，但完整依赖锁、全离线供应链、license/NOTICE、目标资源证据及 subprocess/sidecar 信任选择仍未准入。
- [取得 Provider 生产产品与数据处理合同权威](issues/06-obtain-provider-production-contract-authority.md)：所有者决定不签书面企业合同，以标准按量付费在线服务协议 + 明示风险接受为 authority 基础；路线 = 标准按量付费 `dashscope.aliyuncs.com` + `DASHSCOPE_API_KEY` + `qwen3.8-max`（文档级可用）；live 红线维持 scoped J1 机制，首个 J1 范围待定，migration change-spec 落地前 attempts 仍为 0。
- [冻结单租户 IMAGE_ONLY 生产信任与数据边界](issues/04-freeze-production-trust-boundary.md)：冻结 gateway/mTLS 单租户信任、ordinary-design 准入、逐 run 外传确认与逐 call authorization、无网 sidecar、加密及 7 天 payload lifecycle、双轴 readiness/kill switch、payload-free 审计与有期 ProductionLiveAuthority；未获 fresh J1 前仍为 Provider-zero。
- [冻结 Provider 生产路线与 Profile migration 边界](issues/07-freeze-provider-route-and-profile-migration.md)：认证外部化为 `ProfileCertificationRecord`（绑 profileId+bytes SHA-256）；首个生产目录只认证 Max，新发 v46 最小 diff（12 次调用/¥6 上限）继承试用证据；价格漂移只触发成本复核；不做漂移自动检测（残余风险已接受）；drain 继承 ticket 04 软 drain；重认证开新记录、至少 fresh 5-case canary。
- [冻结 RapidOCR 生产拓扑与 capability admission](issues/08-freeze-rapidocr-production-topology.md)：拓扑继承 ticket 04 无网 UDS sidecar；`python:3.12-slim-bookworm` digest pin 基座 + 全量 hash 锁 + antlr4 wheel 入库 + 模型构建期提取只读预置；stdlib HTTP-over-UDS 协议、代码归 `docker/ocr-sidecar/`；资源上限 2C/2GB/PID64/60s 探针门禁；capability id 不变；license 走 notice bundle + 所有者 J1；首版 rollback = fail-closed。
- [冻结新的 IMAGE_ONLY Profile Certification authority](issues/05-freeze-image-only-profile-certification-authority.md)：独立《IMAGE_ONLY Profile Certification Contract》delta 承接 mode slice，AC-021 全局保持未完成；首周期只认证 v46 Max 管线；复用 N9/R1 60-case/evaluator + frozen manifest HOLDOUT 20/60；门槛 5/5、≥18/20、≥54/60、失败即终态不修补；`ProfileCertificationRecord` PG append-only、exact-revision gate 钉 corpus/evaluator/门槛、纯事件制有效期；J1 双层（逐阶段 scoped + 期末 production-policy）；prohibited reuse set 确认。
- [冻结 IMAGE_ONLY 生产 SLO、容量与成本预算](issues/09-freeze-production-slos-and-capacity.md)：负载模型 ≤20 run/日、并发 ≤2；E2E P90 ≤15min、可用性 99.5%/99% 双轴；日 soft ¥30/月 hard ¥500 触线自动关新 run；输入 ≤10MiB/25Mpx/图；失败预算按计数（日 ≥4 且 ≥50% 告警）；水位自动恢复、人工开关绝不自动；A1 遥测+月报、A2 扩展 CapacityBaselineTest 进 release gate。
- [冻结单节点持久化、备份与恢复合同](issues/10-freeze-persistence-backup-and-recovery.md)：日备 pg_dump+Blob tar 滚动 7 天、备份以信封加密落地为前置；RPO≤24h/RTO≤4h；PG 为权威、恢复先 reconciliation 再不复活 sweep（tombstone 重放、过期/关闭状态不复活）后才开放流量；KEK 丢失=等效 crypto-erasure 不抢救、轮换只 re-wrap；首入生产前必做完整 restore drill（A1+A2+ops J1）。
- [冻结 payload-free OperationalTelemetry、告警与值守合同](issues/11-freeze-operational-telemetry-and-oncall.md)：应用内聚合 + actuator JSON + PG 快照，无 Prometheus；指标 label 只许封闭枚举；audit 90 天/归档 13 个月；warning 日报 vs page 即时（与 09/10 触发器一一映射）；所有者即 oncall、每 alert 一页 runbook；release gate 含 Provider-zero kill-switch/drain 离线演练，SLO 由 PG 快照 A2 重算。
- [评估 DeepSeek-OCR 类强 OCR/版面模型强化本地确定性层](issues/14-evaluate-deepseek-ocr-class-models.md)：DeepSeek-OCR 自托管 = GPU 硬约束、CPU-only 不可行，与 ticket 08 拓扑直接冲突且版面指标落后 PaddleOCR-VL/MinerU2.5；CPU-only 内的可行替代是 MinerU pipeline 后端或 PaddleOCR 系；vanchin 托管 = 数据出域至阿里云→快手万擎，需新 Provider 审查 + scoped J1。引入决策留待后续 grilling。
- [DeepSeek-OCR 经 DashScope API 的引入决策与集成形态](issues/15-deepseek-ocr-via-dashscope-integration.md)：scoped J1 spike（3 调用/¥0.0008）证实接入零障碍、grounding 标签存在但语法异于开源版，密版面全图严重漏识别+幻觉——**首个生产版本不引入**；未来质量升级可凭新 J1 重评估；信任边界与 v46 Max + 本地 RapidOCR 生产路径维持不变。
- [冻结 IMAGE_ONLY API 合同与 release gate](issues/12-freeze-api-contract-and-release-gates.md)：live 确认升级为一等 `ExternalTransferConfirmation`（notice 版本/政策版本/manifest id 精确字段集，旧布尔 typed 422）；drift 全部 typed 410/422 fail-closed 不双跑、OpenAPI 升版、SDK diff 进 gate；release gate = full 家族（Node 24 exact-clean）+ contract tests + SDK regen + security headers + DB migration/recovery + 09 容量 + 11 kill-switch 演练；验收必须合同兼容+数据政策双轴人类 J1（agent 自审不算数）；错误 taxonomy 封闭禁插值用户数据，retry 走 Idempotency-Key/typed 409/查询再决策。
- [冻结 staged rollout、rollback 与 ProductionLiveAuthority](issues/13-freeze-rollout-and-production-live-authority.md)：三阶段 pilot（所有者/≤5 run 日/60 天）→limited（≤10 用户/09 全量/90 天）→default（租户内不限/180 天），各绑 exact identity；exit gate = SLO 达标+零未关闭事件+A1/A2 pack+quad J1（visual/business/ops/policy）+restore drill，推进不自动、失败即 negative terminal；authority 为 PG append-only（绑 app SHA/Profile bytes/route/sidecar digest/caps/期限），Agent/CI/canary 不继承、无委托链；drift 矩阵 11 类触发器全 typed 事件（terms 变更暂停、KEK 丢失=销毁+撤销、质量漂移停准入重认证等），stop 统一 04-13 drain 语义；ProductionUsable = default 稳定 ≥4 周/≥100 run + exact-revision evidence pack + quad J1 终签，宣布是人类行为，handoff 包移交 `$to-spec`。
- [冻结 Blueprint 归一化结构与 `$to-spec` handoff 包](issues/16-freeze-blueprint-normalization-and-handoff.md)：Blueprint = 单文档 `plans/image-only-production-admission-blueprint-v1.md`，15 章按决策域划分 + Exact identity 总表 + 残余风险/证据索引，决策只写 gist+票链接；跨票冲突四维检查（数值/术语/identity/红线），冲突不静默修、实质冲突开新票；handoff 包=该单文档，修订走新版本号新文件；闭环=所有者对照 13 票 checklist J1 验收（agent 汇编不算），验收后 map 标记 closed、`$to-spec` 接手。

## Not yet specified

（全部 fog 已毕业：Blueprint 归一化与 handoff 已成为 ticket 16。）

## Out of scope

- 多租户、应用内账号/RBAC、HA、横向扩展、render farm 或多区域容灾。
- JSON_ONLY、COMBINED 的质量认证或对全局 AC-021 的完成声明。
- 自动发布 StaticSchema、自动跳过 Candidate 人工审核、AI update/delete/publish/SQL/filesystem/arbitrary HTTP。
- 第二 Provider、自动跨模型 fallback、动态 `latest` Profile 或在运行中改写 immutable Profile。
- Template、Renderer、Workspace、数据 Connector 或图片渲染能力。
- 修复/重跑旧 N7、R5、R5P、R5P2，或复用它们的 CLOSED J1、identity、assignment 和 ledger。
- 本轮实施产品代码、生产 cutover、真实用户数据传输、真实 Provider 调用或 API Key 检查。
