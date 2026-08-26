# 发行 Domain Services 容量 Case/Oracle 并完成 post-issuance replay

Type: task
Status: in_progress
Claimed by: Codex `/root`
Blocked by: 128（已 resolved）

## Question

T128 已冻结 `EXEC::DOMAIN_SERVICES::1.0` 的 exact product target、两个 required executor manifests，并让 12 个
capacity case 经唯一 Asset guard、3 个事务场景经真实 Testcontainers PostgreSQL 独立 replay，闭包报告
`preissuanceReady=true`；但 append-only formal registry 仍只有 SPEC_REGISTRY 的 46 Case / 46 Oracle，中央
execution-class catalog 与 bootstrap order 也仍诚实标记 Domain Services pending。如何发行精确的 12+12 assigned
subset，使 class 成为 `EXECUTABLE_A2_REPLAYED`，同时保证旧记录一个字节不变、正式 registry 的全图闭包可由两种
独立实现重放，且不把这一步扩大为全部 525 capacity records、Renderer/Profile 或生产认证？

## Answer（本票冻结的实施决定）

1. **append-only exact suffix**：formal Case/Oracle 必须分别以既有 46-record registry bytes 为精确前缀，只追加
   T128 target 从 525 candidate corpus 指派的 12 个 Domain Services Case 与其精确 12 个 Oracle；顺序保持 candidate
   transport order。禁止重排、重写、canonicalize 或重新生成既有 46 条，禁止发行其他 class 的 candidate。
2. **发行目标与 fail-closed 物化器**：新增 deterministic issuance target，绑定 T128 exact target/manifests、旧 formal
   prefix、完整 candidate sources、assigned suffix digests、预期 post-registry digests、中央 catalog prestate 与发行脚本。
   物化器只接受 exact 46/46 prestate 或已经 byte-identical 完成的 58/58 poststate；任何部分追加、重复、hash 漂移或
   非目标 record 都拒绝，不提供覆盖/修复 fallback。
3. **双重正式 registry replay**：Node primary 与 Python independent post-issuance verifier 分别从正式 JSONL 检查
   prefix preservation、suffix identity、schema/canonical bytes、probe/operator、coverage/oracle closure、ID/signature/
   supersession/no-orphan 约束与 target bindings；不得共享 semantic helper。SPEC_REGISTRY executor 保持只执行其 46 条
   scenario，但必须验证 append-only formal binding，不再错误要求整个 formal registry 与 SPEC candidate 完全相等。
4. **中央状态原子一致**：同批更新 `conformance-execution-classes-v1.json`、bootstrap order、acceptance manifest、
   SPEC target/executor manifests 与 A2 evidence。Domain Services 记为 12/12、`EXECUTABLE_A2_REPLAYED`；Phase 2 仍为
   `CAPACITY_BOUNDARY`，全局只报 12/525 executable，后续三个 capacity class 继续 pending，Ticket 19 保持 open。
5. **fresh product replay 与 gates**：先捕获 missing issuer/post-verifier RED；实现后在 formal append 前验证 expected bytes，
   append 后运行 Node/Python post replay、`domain-services`（真实 PostgreSQL）、`template-static`、`asset`、`fast`、顺序
   `server`、Goal `full` 与 resolution `fast`。Maven 串行、精确 staging，最高只报 `automated_verified`。
6. **诚实边界**：不改 API/OpenAPI/migration/Web/Template/Rendering 产品语义；不发行其余 513 capacity 或 combined/
   non-capacity records，不注册/认证 Profile，不运行独立 native build/deployment，不调用 provider/API Key，不使用真实
   数据/生产，也不取得 J1/A3/READY。360 项用户 dirty work 与备份 stash 保持原样。

## Results

- pending
