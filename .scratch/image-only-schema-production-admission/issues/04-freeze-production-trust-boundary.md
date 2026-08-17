# 冻结单租户 IMAGE_ONLY 生产信任与数据边界

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: none

## Question

在“单租户、单节点、外部网关承担 TLS/认证/访问控制，RenderWeave 不实现账号/RBAC/多租户”的已定范围内，生产信任合同应如何精确定义：哪些网络入口可达 API/actuator，应用是否需要接收并审计网关签发的 actor/request identity，哪些 `SensitivityClass` 可与 `InputProvenance=USER_PROVIDED` 一起进入 live，ExternalTransferConfirmation 绑定哪些 policy/profile/run/time 事实，secret 与 Provider 出口由谁拥有，IMAGE_ONLY capability 降级时整站与 feature readiness 如何区分，以及哪些输入、恢复、retry 或过期 queued run 必须重新确认或直接拒绝？

## Answer

以下 32 项决策经用户逐轮接受，并于 2026-08-17 完成 shared-understanding confirmation；它们共同构成本票 resolved 终态：

1. 唯一公共入口是 gateway；API、数据库和 Actuator 均保持私有，Actuator 只供运维面。gateway 删除任何客户端伪造身份 header，并为每次请求签发应用可验证的 `GatewayAssertion`；RenderWeave 不建立账号、session 或 RBAC。
2. live 只接纳 `InputProvenance=USER_PROVIDED` 且 `SensitivityClass=ORDINARY_DESIGN`。`RESTRICTED`、缺失或未知分类全部 fail-closed；AI 不得自动把输入升级为可外传。
3. 应用 Provider adapter 独占生产 Key，经 orchestrator 以只读 secret file 注入；Web、gateway、PostgreSQL 与 OCR sidecar 均不得持有。adapter 只允许 exact endpoint allowlist，并另有主机防火墙与独立 live dequeue/egress kill switch；首版不引入 egress proxy。
4. 生产 OCR 使用无 IP 网络、仅 UDS 的独立 sidecar，并拥有独立 cgroup/resource limit；当前同进程外 subprocess 只保留为 dev/offline 路径。
5. `ServiceReadiness` 与 `ImageOnlyReadiness` 分轴；IMAGE_ONLY 不可用时确定性 Schema 站点仍可 ready，新 live 请求 fail-closed。
6. `GatewayAssertion` 使用非对称短期 JWS，由应用公钥验证；claims 绑定 `iss/aud/sub(actorId)/iat/exp/jti/requestId/method/path`，mutation 还绑定 `Idempotency-Key` digest，最长 60 秒。同一 mutation jti 防重放；持久化 actor/request/jti 而非完整 token 或 PII。Actuator 另用 mTLS/service identity。
7. `ExternalTransferConfirmation` 是与 live run 原子创建的一等不可变记录，绑定 actor/request、policy 与 Provider contract identity、exact Profile snapshot digest、Provider/model/endpoint/region、`USER_PROVIDED+ORDINARY_DESIGN`、实际输入 manifest/fingerprint、调用/费用上限、条款版本、签发时间、`dispatchNotAfter` 与 runId。首次 Provider attempt 必须在 15 分钟内开始；不得跨 run/retry/Profile/input 复用。
8. `ImageOnlyAdmissionPolicy` 与 `ProviderEgressPermit` 是两个独立、默认关闭的紧急开关；任一关闭都阻断新调用与 retry。在途调用尽力取消，返回结果只记账且不再推进；已经进入 `REVIEW_REQUIRED` 的 run 仍可 review/apply。
9. 内部 Actuator 只投影 `ServiceReadiness`；经认证的 `/live-availability` 投影 `ImageOnlyReadiness`，使用稳定低基数 reason code：`PROVIDER_CONTRACT_UNAVAILABLE`、`PROFILE_NOT_CERTIFIED`、`DOCUMENT_VISION_UNAVAILABLE`、`LIVE_POLICY_DISABLED`、`CREDENTIAL_UNAVAILABLE`、`EGRESS_DISABLED`。新 live 不可用返回 typed 503，不拖垮确定性站点。
10. run 与 confirmation 原子创建；首次 Provider attempt 超过 15 分钟未开始即终止为 `LIVE_CONFIRMATION_EXPIRED`，不得续期、等待后恢复或自动再确认。dequeue 时及每次 Provider request 前都重新验证 exact confirmation/input、未撤销合同/Profile、双重开关、endpoint/secret/capability 与调用/费用余量。
11. `providerCallsNotAfter` 固定为 confirmation 签发后 2 小时。相同 run 的 crash recovery 只有在全部 exact identity 未变、窗口仍有效、双重开关开启且没有 ambiguous attempt 时才能继续。截止后不得发起新调用；已合法 dispatch 的调用可在固定 per-call timeout 内结束。请求可能已经外传却无权威结果时终止为 `LIVE_PROVIDER_ATTEMPT_AMBIGUOUS`，不得自动重放。
12. 用户触发 live retry 必须创建新 run，并取得 fresh `GatewayAssertion` 与 fresh `ExternalTransferConfirmation`。只有 retained input manifest/fingerprint 完全一致、仍在保留期内且 UI 明示该 exact 输入时才可复用；自动 transport retry 仅在确定 bytes 尚未离开应用，或 Provider 书面合同保证 exact attempt-key 幂等时允许，并继续受原调用/费用/时间上限约束。
13. kill switch 或 authority 撤销后，新 live 返回 typed 503；QUEUED run 在 sweep/dequeue 时进入稳定终态，RUNNING run 在最近安全边界停止，在途结果只记账、不推进。重新打开开关不得复活旧 run；`REVIEW_REQUIRED` 不受阻断。
14. `Live Admission Audit` 只持久化 opaque actor/request/jti、confirmation/digest、exact policy/合同/Profile、domain-separated input fingerprint、分类、时间、decision code 与 usage/cost；禁止在审计、日志或普通证据中保存完整 JWS、姓名/邮箱、原文件名、图片、OCR 文本、完整 prompt/response 或 chain-of-thought。具体 retention 由后续 operations/data-retention 决策冻结，且不得借审计延长 payload 生命周期。
15. 原始上传 bytes 只在规范化期间存在且不落盘。normalized PNG 的 `payloadExpiresAt` 从首次上传起最多 7 天，所有引用 run 共享且 retry 不得延长；剩余不足 24 小时时不得复用，必须重新上传。`COMPLETED` 后立即安排删除；`FAILED`、`CANCELLED` 与 live admission terminal 最多保留 24 小时供显式 retry；`REVIEW_REQUIRED` 到第 7 天转 `LIVE_REVIEW_EXPIRED` 并禁止 apply。在线 PostgreSQL/Blob 删除硬 SLO 为 24 小时。
16. 首个生产 IMAGE_ONLY 只接纳每 run 1–10 张静态 PNG/JPEG；magic bytes、声明 media type、单帧和 dimensions 必须一致。PDF、SVG、GIF、WebP、APNG、远程 URL 与压缩包全部拒绝；exact byte/pixel/capacity limit 由后续 SLO ticket 基于压力证据冻结。
17. 唯一内容路径为 Browser → Gateway → API → isolated normalizer/OCR → private BlobStore。Provider 只接收应用由 normalized artifact 生成的 Base64 Data URL，不接收文件名、Blob locator、内部 URL 或回调地址。overview/tile/crop bytes 只在调用期间存在，仅保留 descriptor/digest；浏览器只能经认证 gateway/API 读取审核图片并得到 `Cache-Control: private, no-store`，BlobStore 不直接暴露。
18. actorId 只作为确认主体与审计身份，不形成应用内 ownership/RBAC。gateway 对 exact method/path 授权；同一租户内获准 reviewer 可读取、编辑或 apply 任意 run，每次 mutation 记录实际 actor。另一 actor 发起 retry 必须提交自己的 fresh confirmation；首个生产部署明确接受全部 Tenant Operator 属于同一数据信任域。
19. live 创建保留一次 multipart command：浏览器在提交前展示本地文件名、数量、Provider/model/region、Profile、分类、调用/费用上限、payload retention 与条款；确认后才上传。服务端规范化成功后，以实际 `Live Input Manifest`/fingerprint 原子创建 confirmation 与 run，原文件名不持久化；规范化、manifest 或条款漂移使整条命令失败且不留下 QUEUED run。
20. 生产环境把 magic/frame/dimension 检查、解码、metadata stripping、sRGB PNG 规范化和 OCR 放入无 IP、仅 UDS、受资源约束的 sidecar；API 只执行 multipart byte 上限和 closed envelope 检查。Ticket 08 再冻结 exact image、依赖、资源、供应链与 capability identity。
21. 生产 gateway→API 与 API→PostgreSQL 使用双向认证 TLS；Actuator 使用独立内部 listener 与 ops service identity，不能进入公共 nginx。OCR 只走 UDS；Provider 只走校验证书与 hostname 的 HTTPS，并禁止 redirect、环境代理和明文降级。`GatewayAssertion` 不替代传输加密。
22. 每个 persisted artifact 使用随机 DEK 做 AEAD envelope encryption，wrapped DEK 存于 PostgreSQL，KEK 由 orchestrator 独立提供且与 Provider Key 分离。删除同时清除 ciphertext 与 wrapped DEK 形成 crypto-erasure；PostgreSQL volume 和 backup 的整卷加密归后续 operations ticket。KEK 不进入日志、API、gateway 或 OCR sidecar。
23. live create 响应丢失时，客户端用 fresh `GatewayAssertion`/jti 与原 `Idempotency-Key` 重发；只有 manifest、confirmation terms、policy/Profile、分类及预算 fingerprint 全部相同才返回原 run/confirmation，且不新建或再次调度。任何漂移返回 409；幂等绑定 retention 不得短于对应 `Live Admission Audit`。
24. 提供幂等 payload 删除命令；命令立即建立 `Payload Deletion Tombstone`，阻断读取、retry、Provider 调用和 apply，并终止尚未进入不可中断 apply transaction 的 run。既有 Draft Bundle 与 payload-free audit/usage 保留。Blob 删除异步重试；超过 24 小时仍未清除时，`ImageOnlyReadiness` 以 `PAYLOAD_DELETION_UNHEALTHY` fail-closed 并停止接收新 live payload，直至恢复。
25. 首个生产 Web 只允许 same-origin；gateway 使用 `Secure`、`HttpOnly`、`SameSite=Strict` session cookie、CSRF token 与 Origin/Fetch-Metadata 校验并关闭 CORS，通过后才签发 `GatewayAssertion`。API、SSE、artifact 和错误响应全部 `no-store`，页面禁止 iframe 嵌入。
26. 普通产品路径中 payload deletion deadline 优先，不自动建立 forensic hold。发现误分类或疑似 `RESTRICTED` 后立即关闭双开关、撤销相关 confirmation、删除 payload，只保留 payload-free audit/usage。任何图片取证保留必须取得新的具名法律/安全人工授权及独立范围/期限，明确不属于首个生产版本。
27. Gateway signer、mTLS identity、Provider Key 与 Blob KEK 全部分域。Gateway JWS 通过 `kid` 轮换，旧公钥最多重叠 60 秒 assertion TTL 加 30 秒 clock skew；mTLS 用双 trust-set 轮换。Provider Key 轮换先关闭 egress 并 drain worker，再替换 secret，且不得用未授权 live 调用试 Key。KEK 版本化并重包 DEK，只有 A1 扫描证明旧 KEK 引用为零后才能销毁。
28. persisted deadline 全部使用 UTC wall clock，进程内 timeout 使用 monotonic clock；生产主机必须有受监控的时间同步。偏差超过 30 秒、时间来源失效或检测到 clock rollback 时，`ImageOnlyReadiness` 以 `TIME_AUTHORITY_UNAVAILABLE` fail-closed。30 秒容差只用于 `GatewayAssertion` 验证，不延长 confirmation、Provider call 或 payload expiry。
29. `Live Admission Audit` 采用 append-only、逐 run 单调 sequence 与 digest-chain，运行时数据库 role 无 UPDATE/DELETE 权限。每次 Provider call 必须先在同一事务中持久化 call authorization、attempt identity 与费用 reservation，提交后才能发送 bytes。审计不可写或 chain 失效时以 `AUDIT_INTEGRITY_UNAVAILABLE` 阻断新 live 调用；独立周期校验归 operations ticket。
30. 服务端发布 immutable `ExternalTransferNotice`，绑定 semantic version、locale/content digest、Provider 法定主体、model/region、处理目的、合同允许的 retention/secondary-use、人类访问边界、Profile、调用/费用上限及本地 payload retention。confirmation 绑定实际展示的 locale digest；过期或漂移返回 `LIVE_TRANSFER_NOTICE_STALE`。新 notice 使尚未首次 dispatch 的旧 confirmation 失效；已运行任务只有在旧合同仍有效时才能继续。
31. `ImageOnlyAdmissionPolicy` 是持久化、版本化应用策略，`ProviderEgressPermit` 由应用外 orchestrator/firewall 执行。关闭状态跨进程和主机重启保持，凭据存在不能自动开启；只有 ops mTLS identity 可修改且修改进入 payload-free audit。重启后已授权的开启状态可恢复，但每个 queued/run call 仍重新验证全部 authorization 与 deadline。
32. 后续 rollout ticket 必须定义新的 `ProductionLiveAuthority`，绑定 exact revision、deployment、Provider contract、sole-finalist Profile、发布阶段、aggregate call/cost caps 与有效期，并经 fresh J1 激活。在它获批前继续执行“无 fresh J1 不 live”；获批后真实 Tenant Operator 的普通产品 run 由该 authority、逐 run confirmation 和逐调用 authorization 共同准入，不需逐点击 J1。Agent、CI、脚本、评测与运维 canary 不继承产品 authority，仍需各自 fresh bounded J1。
