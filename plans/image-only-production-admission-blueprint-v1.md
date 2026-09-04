# IMAGE_ONLY Schema Recognition Production Admission Blueprint v1

> 文档指针（2026-09-03）：根 `CONTEXT.md` 已拆分为 `CONTEXT-MAP.md` + `docs/context/` 分片；本索引中的「CONTEXT.md」术语现居 `docs/context/live-admission.md` 与 `schema-inference.md`，checkpoint 中「第 N 行」是登记时点的历史引用，新布局下请按术语名检索。

- 版本：v1（2026-08-17）
- 来源：wayfinder map `.scratch/image-only-schema-production-admission/map.md`
- 状态：**决策完备，执行未开始**。本文档是 16 张决策票的唯一汇编视图；每条决策只写 gist + 票链接，细节以票为唯一权威存储。
- 当前事实：product-v45 为 ACTIVE_EXPERIMENTAL 基线；v46 Profile 尚未创建；信封加密、OCR sidecar、ProductionLiveAuthority 均未实现；无生产部署。

## 票清单

| # | 票 | 类型 |
|---|----|------|
| 01 | [确定 IMAGE_ONLY 生产准入的当前权威状态](../.scratch/image-only-schema-production-admission/issues/01-establish-canonical-authority.md) | grilling |
| 02 | [核实 DashScope 对生产图片的处理合同](../.scratch/image-only-schema-production-admission/issues/02-research-dashscope-processing-contract.md) | research |
| 03 | [核实 RapidOCR 生产容器的许可与运行约束](../.scratch/image-only-schema-production-admission/issues/03-research-rapidocr-container-constraints.md) | research |
| 04 | [冻结单租户 IMAGE_ONLY 生产信任与数据边界](../.scratch/image-only-schema-production-admission/issues/04-freeze-production-trust-boundary.md) | grilling |
| 05 | [冻结新的 IMAGE_ONLY Profile Certification authority](../.scratch/image-only-schema-production-admission/issues/05-freeze-image-only-profile-certification-authority.md) | grilling |
| 06 | [取得 Provider 生产产品与数据处理合同权威](../.scratch/image-only-schema-production-admission/issues/06-obtain-provider-production-contract-authority.md) | task（所有者决定） |
| 07 | [冻结 Provider 生产路线与 Profile migration 边界](../.scratch/image-only-schema-production-admission/issues/07-freeze-provider-route-and-profile-migration.md) | grilling |
| 08 | [冻结 RapidOCR 生产拓扑与 capability admission](../.scratch/image-only-schema-production-admission/issues/08-freeze-rapidocr-production-topology.md) | grilling |
| 09 | [冻结 IMAGE_ONLY 生产 SLO、容量与成本预算](../.scratch/image-only-schema-production-admission/issues/09-freeze-production-slos-and-capacity.md) | grilling |
| 10 | [冻结单节点持久化、备份与恢复合同](../.scratch/image-only-schema-production-admission/issues/10-freeze-persistence-backup-and-recovery.md) | grilling |
| 11 | [冻结 payload-free OperationalTelemetry、告警与值守合同](../.scratch/image-only-schema-production-admission/issues/11-freeze-operational-telemetry-and-oncall.md) | grilling |
| 12 | [冻结 IMAGE_ONLY API 合同与 release gate](../.scratch/image-only-schema-production-admission/issues/12-freeze-api-contract-and-release-gates.md) | grilling |
| 13 | [冻结 staged rollout、rollback 与 ProductionLiveAuthority](../.scratch/image-only-schema-production-admission/issues/13-freeze-rollout-and-production-live-authority.md) | grilling |
| 14 | [评估 DeepSeek-OCR 类强 OCR/版面模型强化本地确定性层](../.scratch/image-only-schema-production-admission/issues/14-evaluate-deepseek-ocr-class-models.md) | research |
| 15 | [DeepSeek-OCR 经 DashScope API 的引入决策与集成形态](../.scratch/image-only-schema-production-admission/issues/15-deepseek-ocr-via-dashscope-integration.md) | grilling + scoped J1 spike |
| 16 | [冻结 Blueprint 归一化结构与 `$to-spec` handoff 包](../.scratch/image-only-schema-production-admission/issues/16-freeze-blueprint-normalization-and-handoff.md) | grilling |

## 1. 术语与权威状态（票 01、05）

- 权威基线：product-v45 仅为 ACTIVE_EXPERIMENTAL；N7/R5/R5P/R5P2 与历史 J1 全部 CLOSED 不可复用，生产认证必须进入全新 mode-specific authority（票 01）。
- `ProductionUsable` = Profile Certification + live admission + operations acceptance + recovery proof 的复合终态，无任何单一信号可替代（map Notes）。
- 新增《IMAGE_ONLY Profile Certification Contract》approved delta 承接 IMAGE_ONLY mode slice；v1 AC-021 全局保持未完成，结论不外溢 image/json/combined（票 05）。
- 关键词汇已入 CONTEXT.md：`ProfileCertificationRecord`、`ExternalTransferConfirmation`、`ImageOnlyAdmissionPolicy`、`ProductionLiveAuthority`。

## 2. Provider 合同与路线（票 02、06、07、14、15）

- Token Plan 明确不允许应用后端生产调用 = NO-GO（票 02，维持）。
- Authority 基础：所有者决定**不签书面企业合同**，以标准按量付费在线服务协议 + 明示风险接受为准（票 06，2026-08-16/17）。
- 生产路线：endpoint `https://dashscope.aliyuncs.com/compatible-mode/v1` + 环境变量 `DASHSCOPE_API_KEY` + 模型 `qwen3.8-max`（文档级可用，控制台开通状态执行时核实）。Route migration 已落地：`specs/changes/20260817-dashscope-payasyougo-route-migration.md`（APPROVED），adapter allowlist 唯一批准该 endpoint、Token Plan URL 入拒绝名单测试（票 07 Comments / 票 06 Errata）。
- 首个生产目录只认证 Max 一份 sole-finalist；Flash/Plus 保持 EXPERIMENTAL（Flash 0/2 合同违规；Plus 难图失败系预算配置非质量死刑，留作未来成本档候选）（票 07）。
- 价格漂移永不触发质量重认证，只触发成本预算复核；不做 Provider 行为漂移自动检测（残余风险已接受，撤销触发清单闭合：人工撤销/事故驱动/人工注意合同变更）（票 07）。
- DeepSeek-OCR：自托管 GPU 硬约束不可行（票 14）；经 DashScope API spike（3 调用/¥0.0008）证实接入零障碍但密版面漏识别+幻觉——**首个生产版本不引入**，未来凭新 scoped J1 重评估（票 15）。

## 3. 信任边界与数据政策（票 04，32 项决策）

按主题归组 gist（全量语义以票 04 Answer 为准）：

- **入口与身份**：唯一公共入口是 gateway（TLS/认证/访问控制）；API/DB/Actuator 私有；gateway 签发非对称短期 JWS `GatewayAssertion`（≤60s，绑 actor/request/jti/method/path，mutation 加 Idempotency-Key digest）；应用无账号/session/RBAC。
- **输入准入**：仅 `InputProvenance=USER_PROVIDED` + `SensitivityClass=ORDINARY_DESIGN`；RESTRICTED/缺失/未知 fail-closed；每 run 1–10 张静态 PNG/JPEG，PDF/SVG/GIF/WebP/APNG/URL/压缩包全拒。
- **Secret 分域**：Provider Key 由 adapter 独占（只读 secret file 注入）；Gateway signer/mTLS/Provider Key/Blob KEK 四者分域；Key 轮换先关 egress + drain，不得用未授权调用试 Key；KEK 版本化 re-wrap。
- **确认记录**：`ExternalTransferConfirmation` 一等不可变记录，与 run 原子创建，绑 notice/policy/Profile digest/合同/manifest/caps；首次 Provider attempt ≤15 分钟，否则 `LIVE_CONFIRMATION_EXPIRED`；`providerCallsNotAfter` = 签发后 2 小时；`LIVE_PROVIDER_ATTEMPT_AMBIGUOUS` 不自动重放。
- **双轴 readiness**：`ServiceReadiness` 与 `ImageOnlyReadiness` 分轴；IMAGE_ONLY 不可用不拖垮确定性站点；新 live 不可用 typed 503。
- **紧急开关**：`ImageOnlyAdmissionPolicy`（应用内、持久化版本化）与 `ProviderEgressPermit`（orchestrator/firewall 执行）双开关默认关闭；drain 语义 = 新 live 503、QUEUED 稳定终态、RUNNING 最近安全边界停、在途只记账、`REVIEW_REQUIRED` 不受阻、重开不复活旧 run。
- **payload 生命周期**：原始 bytes 不落盘；normalized PNG 自首次上传最多 7 天（`payloadExpiresAt` 全引用共享、retry 不延长、剩余 <24h 必须重新上传）；COMPLETED 立即安排删除；FAILED/CANCELLED 保留 ≤24h；`REVIEW_REQUIRED` 第 7 天转 `LIVE_REVIEW_EXPIRED` 禁止 apply；在线删除硬 SLO 24h；幂等删除命令 + `Payload Deletion Tombstone`，backlog >24h → `PAYLOAD_DELETION_UNHEALTHY` fail-closed。
- **内容路径**：Browser → Gateway → API → isolated normalizer/OCR → private BlobStore；Provider 只收 Base64 Data URL；审核图片 `Cache-Control: private, no-store`。
- **加密**：per-artifact 随机 DEK AEAD 信封加密，wrapped DEK 存 PG，KEK 由 orchestrator 独立提供；删除 = ciphertext + wrapped DEK 双清 = crypto-erasure；KEK 不进日志/API/gateway/sidecar。
- **审计**：`Live Admission Audit` append-only + digest-chain + 运行时 role 无 UPDATE/DELETE；payload-free（禁存图片/文件名/OCR 文本/完整 prompt-response/CoT/PII）；每次 Provider call 先同事务落 call authorization + attempt identity + 费用 reservation 再发 bytes；审计不可写 → `AUDIT_INTEGRITY_UNAVAILABLE` 阻断。
- **幂等与 retry**：live create 丢失用 fresh jti + 原 Idempotency-Key 重发，全匹配才返回原 run，漂移 409；用户 retry = 新 run + fresh confirmation；自动 transport retry 仅限 bytes 未离开或 Provider 书面幂等保证。
- **Web 安全**：same-origin only、Secure/HttpOnly/SameSite=Strict cookie、CSRF + Origin/Fetch-Metadata、关 CORS、全响应 no-store、禁 iframe。
- **误分类**：发现即关双开关 + 撤销 confirmation + 删 payload，只留 payload-free audit；forensic hold 需独立具名法律/安全授权，不属首版。
- **时间权威**：deadline 用 UTC wall clock、进程内 timeout 用 monotonic；偏差 >30s / 时间源失效 / clock rollback → `TIME_AUTHORITY_UNAVAILABLE` fail-closed。
- **Notice**：immutable `ExternalTransferNotice` 绑 semantic version + locale digest；confirmation 绑实际展示 digest；漂移 → `LIVE_TRANSFER_NOTICE_STALE`。
- **Actor 模型**：actorId 仅审计身份；同租户获准 reviewer 可读/审/apply 任意 run（mutation 记 actor）；接受全部 Tenant Operator 同数据信任域。
- **Authority 要求**：`ProductionLiveAuthority` 获批前维持"无 fresh J1 不 live"；Agent/CI/脚本/评测/canary 不继承产品 authority（票 04-32，落地见票 13）。

## 4. OCR 拓扑（票 03、08）

- 拓扑：无 IP 网络、仅 UDS、独立 cgroup 的 sidecar；同镜像 stdio subprocess 仅 dev/offline；API 镜像保持纯 JRE（Alpine/musl 不准入 OCR 运行时）。
- 平台：`linux/amd64 + CPython 3.12 + glibc≥2.28 + AVX2 + CPU-only`；ARM/Alpine 不准入（票 03 证据）。
- 镜像：`python:3.12-slim-bookworm`（glibc 2.36）按 digest pin；`pip --require-hashes` 全量锁；`omegaconf==2.3.0` + 内部 `antlr4-python3-runtime==4.9.3` wheel 入库；三份 exact ONNX 模型构建期提取、SHA-256 校验、只读预置、启动零下载。
- 协议：HTTP/1.1 over UDS，stdlib server 零新增依赖；JSON envelope 沿用现 stdio 形状；代码归 `docker/ocr-sidecar/`，复用 `tools/document-vision/` 调用逻辑。
- 资源：2 CPU / 2GB RAM / PID 64 / 60s 超时 / OOM 限 sidecar；read-only rootfs、non-root、drop-all-caps。
- capability id 不变：`rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1`。
- 探针两层：启动阻塞层（capability + 合成图固定断言），长稳/资源层归遥测（票 11）。
- license：构建期 notice/provenance bundle（SBOM 附 license、补 RapidOCR LICENSE/NOTICE），所有者 J1 接受 Apache-2.0 主线。
- rollback：首版无 pair，rollback = `DOCUMENT_VISION_UNAVAILABLE` fail-closed，确定性站点不受影响。
- 构建门禁：全离线 build、SBOM/CVE/malware 扫描、签名 attestation digest；任一缺失不准入。

## 5. Profile 认证（票 05、07）

- 认证外部化：`ProfileCertificationRecord`（PG append-only，绑 profileId + bytes SHA-256 + verdict + 证据指针 + 门槛 + 签发时间；撤销 = 追加 revoked-with-reason 行，从不 UPDATE）；`ImageOnlyReadiness` 的 `PROFILE_NOT_CERTIFIED` 读最新行。
- 首周期只认证 **v46 Max** 管线（RapidOCR 本地确定性层）。
- v46 = v45 最小 diff：`maximumTotalCalls` 7→12、`maximumEstimatedCostMicrosCny` ¥2→¥6；`maximumOutputTokens`=8192、`stageTimeoutSeconds`=360、prompt/pipeline/阈值/价格快照原样；继承 2026-08-17 试用证据（Max 2/2 REVIEW_REQUIRED）；snake_case 约定不进 v46 prompt（保持证据可比性），归认证合同。
- Corpus：复用 N9/R1 60-case 语料 + 58-metric evaluator；canary 5-case 用所有者 fresh 真实设计图；frozen manifest（每 case hash + DEV/HOLDOUT 标签，seeded，周期内不可改）；HOLDOUT = final 60 例中 20 例，DEV 全程不可见。
- 门槛：单 case 通过 = 到 `REVIEW_REQUIRED`/`COMPLETED` 且人工审核接受（8000bps 维持 flag-only，7999bps 贴线为预期）；周期门槛 canary 5/5、DEV ≥18/20、final ≥54/60；任一不过 = 周期终止、失败终点报告、不修补重跑。
- 命名约定入合同：`proposedSchemaKey`/`proposedFieldKey` 必须 snake_case；本周期内 kebab-case 不算 case fail，下一 prompt 版本必须内化。
- Exact-revision gate：记录钉 corpus manifest hash + evaluator revision + 门槛数值 + 证据指针；漂移 = 记录失效需重认证。
- 有效期纯事件制；撤销清单见票 07；重认证至少 fresh 5-case canary。
- J1 双层：每阶段开始一份 scoped live J1（沿用 2026-08-17 试用格式）；周期末一份 production-policy J1。证据 = 阶段 A1（run ledger 汇总）+ A2（evaluator 独立重算）。
- Prohibited reuse set：N7 全部、R5 v2、R5P v1、R5P2 全部、历史 v45 J1/ledger、`.scratch/visual-recognition-vnext-n7-closeout/` 全部（白名单：`DocumentObservationIR/1.0`、N9/R1 基础设施、票 01–08/14 决定、2026-08-17 试用证据）。
- 守护条款：合同保留 `PROFILE_MIGRATION_REQUIRED_BEFORE_CERTIFICATION`。
- 费用量级：¥40–95/周期（单 run 实测 ¥0.43–1.11）。

## 6. SLO、容量与成本（票 09）

- 负载模型：≤20 live run/日（hard reject）、并发 ≤2、单 run 1–10 图；超出即拒新 run，确定性站点不受影响。
- 延迟 SLO（7 日滚动）：E2E `REVIEW_REQUIRED` P50 ≤3min、**P90 ≤15min**；上传→durable enqueue P95 ≤5s；queue wait P90 ≤20min；首个 Provider attempt P90 ≤2min（15min 确认过期为硬外框）。
- 可用性 SLO（月度）：`ServiceReadiness` 99.5%、`ImageOnlyReadiness` 99% 双轴；Provider outage 只影响 IMAGE_ONLY 轴。
- 成本：单 run cap ¥6/12 调用（v46）；日 soft ¥30（告警）/月 hard ¥500（自动关新 run，在途照走、审核不受限）；不设独立 token 上限。
- 输入：单图 ≤10MiB 且 ≤25Mpx；每 run ≤10 图合计 ≤32MiB；normalizer 输出侧上限归票 12 API 合同。
- 水位：磁盘 70% soft/85% hard（拒新上传）；PG 连接 80% soft；Blob 删除 backlog >24h fail-closed；sidecar 2C/2GB/PID64/60s。
- Timeout：`stageTimeoutSeconds`=360；sidecar 60s。
- 失败预算：自然日 Provider 拒绝/超时 FAILED ≥4 且 ≥50% → 告警建议人工评估撤销，不自动撤销。
- 恢复：测量驱动水位关闭回落自动重开；两个人工紧急开关永远只人工操作；重开不复活旧 run。
- 证据：A1 = 持续遥测 + 月度 SLO 报告；A2 = `CapacityBaselineTest` 扩展（Provider-zero 离线重放）进 release gate。

## 7. 持久化、备份与恢复（票 10）

- 备份：每日 `pg_dump` + Blob tar 至外挂卷，滚动 7 天，访问仅所有者；不做 WAL 连续归档。**硬前置：备份只在信封加密落地后启用**；过渡期必须备份则归档级整体加密 + 密钥与 KEK 分离。
- RPO ≤24h / RTO ≤4h（人工 runbook）；认证记录等关键行 git/evidence 双写兜底。
- Restore 顺序（阻塞步骤）：PG → Blob → reconciliation sweep → 不复活 sweep → 校验通过才开放流量。
- 一致性：PG 为权威；Blob 孤儿直接删；PG 有引用但 Blob 缺失/corrupt → 标记 missing/corrupt、关联 run 稳定终态、禁止 apply。
- 不复活清单：重放 deletion tombstone、过期 payload 立即清除、过期 confirmation/authority、CLOSED J1、已关闭 kill switch 状态不复活。
- KEK：丢失 = 等效 crypto-erasure 不抢救；所有者持离线副本；轮换 = 只 re-wrap wrapped DEK。
- Drill：**首入生产前必须完整 restore drill**（含 crypto-erasure 验证）；之后每 release 或每季度。A1 = drill 记录；A2 = 独立 hash 对账 + 不可读性验证；ops J1 = 所有者确认。

## 8. 遥测、告警与值守（票 11）

- 形态：应用内聚合 + 内部 actuator listener（mTLS）JSON + PG append-only 周期快照；告警 = 日志 + 可配 webhook；**无 Prometheus/Grafana**；Dashboard = 现有 Web monitor 页 + actuator JSON + 月报。
- 指标：双轴 readiness + reason code、run 生命周期计数、stage 延迟直方图、Provider attempt/token/费用（label 仅 profileId）、queue depth/lease age、sidecar 探针 + CPU/RAM 曲线、payload 生命周期、backup freshness 与 drill 结果。**label 只许封闭枚举**；runId/actor/图片 hash 永不为 label。
- Retention：Live Admission Audit 在线 90 天、月度归档 13 个月；应用日志 30 天；指标原始 30 天 + 小时聚合 13 个月；不借审计延长 payload 生命周期。
- 告警两级：warning（日报）= 磁盘 70%、日成本 ¥30、失败预算触发、backup >25h、sidecar 探针劣化；page（即时）= ImageOnlyReadiness fail-closed、`PAYLOAD_DELETION_UNHEALTHY`、审计完整性失败、月成本 ¥500、restore drill 失败、误分类事件。
- 值守：所有者即 oncall；每条 alert 一页 runbook；Provider 事故 = kill switch + drain。
- 行为证明：release gate 含 Provider-zero 离线演练；A2 = 独立重放 PG 快照重算 SLO。

## 9. API 合同与 release gate（票 12）

- 逐 run 确认升级：`POST /api/v1/inference-runs/live` metadata 废弃旧布尔 `externalTransferConfirmed`/`experimentalProfileConfirmed`，换精确字段集（`externalTransferNoticeVersion` + `externalTransferPolicyVersion` + `liveInputManifestId`），服务端落一等 `ExternalTransferConfirmation`；旧布尔 typed 422，不静默迁移不双跑。
- 合同显式表达：InputProvenance/SensitivityClass、Profile identity（profileId + bytes SHA-256 引用）、call/cost caps（Profile 决定不由请求传入）、readiness reason code、payload tombstone 状态、Candidate review/apply（bulk confirm 禁止入合同，`CANDIDATE_BULK_RESOLUTION_FORBIDDEN` 维持）。
- Drift 迁移：旧布尔、Token Plan 字段、旧 Profile catalog、旧 caps/删除描述、公开 Actuator 全部 typed 410/422 fail-closed；OpenAPI 升版；SDK 重新生成 + diff check 进 gate。
- Release gate = full 家族（fast/server/web/e2e/full，Node 24 exact-clean）+ contract tests + SDK regen + security headers + DB migration/recovery 兼容 + CapacityBaselineTest 扩展 + Provider-zero kill-switch/drain 离线演练。窄绿/历史证据/人工解释不构成生产接受。
- 验收：合同兼容轴 + 数据政策轴双轴人类 J1；agent 自审不算数。
- 错误 taxonomy 封闭：枚举码 + 静态文案，禁插值用户数据；retry = Idempotency-Key / typed 409 / ambiguous 先查询再决策。

## 10. Rollout 与 ProductionLiveAuthority（票 13）

- 三阶段：
  - **guarded pilot**：仅所有者；≤5 run/日、并发≤2；阶段总量 ≤100 calls/≤¥50；≥2 周且 ≥30 run 才可申请 exit；authority 60 天。
  - **limited production**：白名单 ≤10 用户；≤20 run/日（09 全量）；预算纳入 09；≥4 周且 ≥100 run；authority 90 天。
  - **default production**：租户内不限名单；SLO 全量继承 09；authority 180 天。
- 每阶段绑 exact identity：app git SHA、Profile v46 bytes SHA-256、route、sidecar image digest、capability id；任一变 = fail-closed。
- Exit gate：SLO 达标 + 零未关闭 P0/误分类/政策事件 + A1/A2 pack + **quad J1**（visual 抽样≥10% / business / ops / policy）+ restore drill（pilot→limited 首次；limited→default 第二次 + 第二次 kill-switch 演练）。推进不自动；失败 = negative terminal 不修补。
- `ProductionLiveAuthority`：PG append-only（authorityId/stage/exact identities/范围/aggregate caps/生效到期/quad J1 引用/撤销事件）；每次 live admission 运行时全匹配校验，失败 typed 503；Agent/CI/脚本/评测/canary 各需 fresh bounded J1，无委托链；到期 fail-closed，续期 = 新记录 + 新 quad J1；撤销即时生效。
- Drift 矩阵（11 类触发器全 typed 事件）：terms 变更→暂停+新风险接受 J1；model deprecation→计划 drain+migration；不做自动漂移检测；价格漂移→成本复核、破 ¥500 自动关新 run；Profile bytes 变动→fail-closed+安全事件；sidecar digest 变→OCR 轴 fail-closed+新 capability admission；Key 轮换→04-27 流程；KEK 丢失=等效销毁+撤销；gateway/mTLS 失效→外部轴 fail-closed；DB 异常→reconciliation+不复活；error budget 烧尽→自动关新 run（人工恢复）；质量漂移（LOW_CONFIDENCE/BLOCKER 率连续 7 天 >20%）→stop admission + 重认证（fresh 5-case canary）。所有 stop 统一 04-13 drain 语义。
- `ProductionUsable`：default 稳定 ≥4 周且 ≥100 run + 持续绿色 + 零未关闭事件 + exact-revision evidence pack + quad J1 终签；**宣布是人类行为**，agent 只汇编。

## 11. Exact identity 总表

| 名称 | 值 | 来源票 |
|------|----|--------|
| Provider route endpoint | `https://dashscope.aliyuncs.com/compatible-mode/v1` | 06/07 |
| Key 环境变量 | `DASHSCOPE_API_KEY`（只读，支持 `_FILE`） | 06/07 |
| 生产模型 | `qwen3.8-max` | 06/07 |
| 现役 Profile（EXPERIMENTAL） | `dashscope-qwen38-max-product-v45-hybrid-generic` | 01/07 |
| 生产 Profile（待创建） | v46，profileId 与 bytes SHA-256 随认证周期创建时固定 | 05/07 |
| v46 预算参数 | `maximumTotalCalls`=12、`maximumEstimatedCostMicrosCny`=¥6、`maximumOutputTokens`=8192、`stageTimeoutSeconds`=360 | 07 |
| OCR capability id | `rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1` | 08 |
| OCR 镜像基座 | `python:3.12-slim-bookworm`（digest pin，执行期固定） | 08 |
| OCR 资源上限 | 2 CPU / 2GB RAM / PID 64 / 60s | 08/09 |
| 输入上限 | 单图 ≤10MiB/≤25Mpx；每 run 1–10 图、≤32MiB | 04/09 |
| 负载模型 | ≤20 run/日、并发 ≤2 | 09/13 |
| 延迟 SLO | E2E P90 ≤15min（P50 ≤3min）；enqueue P95 ≤5s；queue wait P90 ≤20min；首 attempt P90 ≤2min | 09 |
| 可用性 SLO | Service 99.5% / ImageOnly 99%（月度） | 09 |
| 成本预算 | run ¥6；日 soft ¥30；月 hard ¥500 | 07/09 |
| payload 生命周期 | 7 天；删除硬 SLO 24h | 04 |
| 审计 retention | 在线 90 天 / 归档 13 个月 | 11 |
| 备份 | 每日 pg_dump+Blob tar，滚动 7 天；RPO ≤24h / RTO ≤4h | 10 |
| 认证门槛 | canary 5/5、DEV ≥18/20、final ≥54/60（HOLDOUT 20/60） | 05 |
| confirmation 窗口 | 首次 attempt ≤15min；`providerCallsNotAfter` 2h | 04 |
| authority 期限 | pilot 60 天 / limited 90 天 / default 180 天 | 13 |
| reason code 全集 | 04-9 六值 + `PAYLOAD_DELETION_UNHEALTHY`、`TIME_AUTHORITY_UNAVAILABLE`、`AUDIT_INTEGRITY_UNAVAILABLE`（共 9 值，见附录 drift note 1） | 04 |

## 12. 残余风险与接受记录

| 风险 | 接受决策 | 来源票 |
|------|----------|--------|
| 在线协议保留技术/人工审核权利——不得宣称"零人工访问" | 所有者明示接受 | 06 |
| Provider 侧无数值保留期/删除 SLA——本地政策不依赖 Provider 数字 | 所有者明示接受 | 06 |
| 子处理者/跨境/中国站 DPA 无书面保证——缓释 = 单租户 + 仅 ORDINARY_DESIGN + 逐 run 确认 | 所有者明示接受 | 06 |
| Provider 模型/价格/条款漂移无合同锁定 | 接受；撤销/drain/重认证语义兜底 | 06/07 |
| 不做 Provider 行为漂移自动检测（无常态 canary、无条款巡检） | 所有者明示接受；撤销触发清单闭合 | 07 |
| 无书面合同要约束的是 Provider 行为而非所有者行为 | 所有者知悉后仍以在线条款为充分 | 06 |
| DeepSeek-OCR 不引入导致本地确定性层维持 RapidOCR 现状 | 接受；未来凭新 J1 重评估 | 15 |
| KEK 丢失 = 全部加密 artifact 不可读 | 接受为设计语义（等效 crypto-erasure）；所有者持离线副本 | 10 |
| 认证阈值校准使 live Candidate 全部停人工审核（7999bps 贴线） | 接受为预期行为；8000bps 维持 flag-only | 05 |
| 同租户全部 Tenant Operator 属同一数据信任域 | 首版明示接受 | 04 |

## 13. 证据索引

| 证据 | 级别 | 位置 |
|------|------|------|
| DashScope 处理合同研究（票 02） | A1 | `docs/research/image-only-production-admission/dashscope-processing-contract.md` @ 分支 `research/image-only-production-dashscope-contract` |
| RapidOCR 容器约束研究（票 03） | A1 | `docs/research/image-only-production-admission/rapidocr-container-constraints.md` @ 分支 `research/image-only-production-rapidocr-container` |
| DeepSeek-OCR 类模型评估（票 14） | A1 | `docs/research/image-only-production-admission/deepseek-ocr-class-models-eval.md` @ 分支 `research/image-only-deepseek-ocr-eval`（commit 449d863） |
| DeepSeek-OCR DashScope 接入事实（票 15） | A1 | `docs/research/image-only-production-admission/deepseek-ocr-dashscope-api-facts.md` @ 同分支（commit 2b737f3） |
| 按量付费 route migration change-spec | A1 | `specs/changes/20260817-dashscope-payasyougo-route-migration.md`（APPROVED，main） |
| migration 后门控 | A1 | `.sdlc/evidence/20260817-001358-fast`、`.sdlc/evidence/20260817-001552-server` |
| 试用矩阵授权（6 run/¥2.26，Max 2/2） | J1 CLOSED | `plans/live-canary-authorizations/20260817-payasyougo-trial.json` |
| DeepSeek-OCR spike 授权（3 调用/¥0.0008） | J1 CLOSED | `plans/live-canary-authorizations/20260817-deepseek-ocr-spike.json` |
| 试用 Draft Bundle（bus-route-info/bus-stop/transit-sign rev0） | A1 | 一次性容器 `renderweave-trial-pg`（所有者决定保留本地栈） |
| Provider 合同请求包（历史材料） | — | `.scratch/image-only-schema-production-admission/assets/provider-contract-request.md` |

## 14. Out of scope（map 原样）

- 多租户、应用内账号/RBAC、HA、横向扩展、render farm 或多区域容灾。
- JSON_ONLY、COMBINED 的质量认证或对全局 AC-021 的完成声明。
- 自动发布 StaticSchema、自动跳过 Candidate 人工审核、AI update/delete/publish/SQL/filesystem/arbitrary HTTP。
- 第二 Provider、自动跨模型 fallback、动态 `latest` Profile 或在运行中改写 immutable Profile。
- Template、Renderer、Workspace、数据 Connector 或图片渲染能力。
- 修复/重跑旧 N7、R5、R5P、R5P2，或复用它们的 CLOSED J1、identity、assignment 和 ledger。
- 本轮实施产品代码、生产 cutover、真实用户数据传输、真实 Provider 调用或 API Key 检查。

## 附录 A：跨票冲突检查结果（2026-08-17，四维检查）

**数值一致性**：payload 7 天 / 删除 24h（04↔09↔10↔11）、审计 90 天+13 个月（04-14 移交↔11）、¥30/¥500（09↔11↔13）、12 calls/¥6（07↔09↔12↔13）、P90 15min（09↔13）、≤20 run/日+并发≤2（09↔13）、1–10 图（04↔09）、sidecar 2C/2GB/PID64/60s（08↔09）、stageTimeout 360（07↔09）、RPO 24h/RTO 4h（10↔13）、15min/2h confirmation 窗口（04 内部↔09 引用）、backup 每日+7 天滚动↔backup >25h 告警（10↔11）、认证门槛 5/5·18/20·54/60（05↔07 重认证 canary）——**全部一致**。

**术语一致性**：四个关键词汇均在 CONTEXT.md（第 34/39/40/51 行）且与各票用语一致——**无冲突**。

**Identity 引用一致性**：endpoint、Key 变量名、模型名、capability id 在全部引用处逐字符一致。v46 profileId 与 bytes SHA-256 尚不存在（认证周期创建时固定）——计划内 identity，非冲突。

**红线一致性**：对照 AGENTS.md 禁区逐条核对（不自动发布/人工审核/AI 能力边界/payload-free 日志/scoped J1/不用 H2·SQLite/Node 24 基线）——**无冲突**。

**Drift notes（编辑性，已按"以源票为准"处置，无需新票）**：

1. Reason code 全集实为 **9 值**：票 04-9 初始六值 + 04-24 `PAYLOAD_DELETION_UNHEALTHY` + 04-28 `TIME_AUTHORITY_UNAVAILABLE` + 04-29 `AUDIT_INTEGRITY_UNAVAILABLE`。票 11 第 2 条"04-9 六值"表述以票 04 为准（本表 exact identity 总表已按 9 值收录）。
2. 现行 `openapi/renderweave-v1.yaml` 的 `CreateLiveRunRequest` 仍含旧布尔 `externalTransferConfirmed` 与"CNY 5"描述——已知 drift，由票 12 的 fail-closed 迁移与 OpenAPI 升版覆盖，执行期处理。
3. 票 06 Question 中"新 immutable Profile"表述经 Errata 收窄为"change-spec delta + adapter/config 迁移"（v45 bytes 本就声明按量付费 endpoint/Key 名）——route migration 已落地，无残留。

## 附录 B：闭环验收 checklist（所有者验收用）

对照 13 张决策票逐条确认本 Blueprint 覆盖无遗漏；附录 A 冲突检查结果零未决（3 条 drift note 均为编辑性已处置）。验收 = 所有者 J1；通过后 map 标记 closed，`$to-spec` 凭本包启动实施规划。

- [ ] 票 01 权威状态（§1）
- [ ] 票 02 DashScope 合同事实（§2）
- [ ] 票 03 RapidOCR 容器约束（§4）
- [ ] 票 04 信任边界 32 项（§3）
- [ ] 票 05 认证 authority（§1/§5）
- [ ] 票 06 Provider authority + 风险接受（§2/§12）
- [ ] 票 07 路线与 migration（§2/§5/§11）
- [ ] 票 08 OCR 拓扑（§4/§11）
- [ ] 票 09 SLO/容量/成本（§6/§11）
- [ ] 票 10 持久化/备份/恢复（§7/§11）
- [ ] 票 11 遥测/告警/值守（§8/§11）
- [ ] 票 12 API 合同/release gate（§9）
- [ ] 票 13 rollout/authority（§10/§11）
- [ ] 票 14/15 DeepSeek-OCR 研究与不引入决策（§2/§12）
- [ ] 票 16 本 Blueprint 结构与闭环规则（头部/§13/附录）
