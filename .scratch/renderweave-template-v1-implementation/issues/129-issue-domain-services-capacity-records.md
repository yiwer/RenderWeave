# 发行 Domain Services 容量 Case/Oracle 并完成 post-issuance replay

Type: task
Status: resolved / automated_verified
Claimed by: —
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

- 实现 revision `2298349afa00a60624cca52a5f0074dbde273941` 新增 fail-closed issuer 与 Node/Python
  post-issuance verifier；修正 revision `adfd0da82c257111a871d97284e7697b2cf92bf7` 消除流式读取时的共享
  buffer 漂移，并成为 issuance target 绑定的实现 identity。正式发行 revision
  `ccdcc255a5e6130305fcd26bd9017b9a145fec84` 只追加 Domain Services assigned suffix，并同步中央闭包。
- 旧 46 条 Case 与 46 条 Oracle 分别以 SHA-256
  `ebe58e84977ee84befe89f8c126f37fbec7076cd7725b29f1b7609448fe7ef16`、
  `3825b9fb40c1b43c484da3ebdf29429ed749e622012212ca79f9cea5b9cb9339` 保持逐字节前缀；正式 registry 现为
  58/58，其中新增 Domain Services 12/12。Case registry 为 187511 bytes、SHA-256
  `bba2e350cc29c5fc8faae7f4bdb47c8bd0e2e60a3dd78d6f64d9d355b2440add`；Oracle registry 为 76967
  bytes、SHA-256 `a0f513b0d305d00b1797f32db38488b92e5775fa8c19a3fcb00d4e2394e3d2db`。
- issuance target SHA-256 为 `44c1d878848ab87fc4e4485c4b6142f1710204f39b64578c0a95102a7b0288e2`，绑定
  assigned corpus digest `5a236de3cf36155df7244b049b045cefda55960cf16efe8212c3981e5463844f`。Node 24 primary 与
  Python 3.13 independent 在 `.sdlc/evidence/20260826-132006-domain-services-record-issuance/` 均为
  421/421 checks；正式 A2 证据 SHA-256 为
  `25300d96c9e48c10de420a50601b69ad2127f0ca2e0d843a78617a0b2f830c27`。
- 中央 execution-class catalog、bootstrap order 与 acceptance manifest 已把
  `EXEC::DOMAIN_SERVICES::1.0` 标记为 12/12 `EXECUTABLE_A2_REPLAYED`；Phase 2 仍为
  `CAPACITY_BOUNDARY`，全局只发行并可执行 12/525 capacity records。SPEC Registry 1.14 target SHA-256
  `64019fa9b8c91d999032ce0fb6fbe689ef2d242d65307e0e81566321a32901fc`，393 artifacts，Node/Python
  分别 22974/22882 checks；Editor namespace 继续为 0/0 formal records，静态回放 38/21165 checks。
- 正式 `template-static` `.sdlc/evidence/20260826-133205-t129-template-static/` 与 fresh
  `domain-services` `.sdlc/evidence/20260826-133238-t129-domain-services/` 全绿；后者再次通过 executor roles
  2/2、capacity 12/12、真实 Testcontainers PostgreSQL transaction 3/3。`fast`
  `.sdlc/evidence/20260826-133503-fast/`、顺序 `server` `.sdlc/evidence/20260826-133527-server/` 也通过。
- 发布级 `full` `.sdlc/evidence/20260826-134646-full/` 在 exact `ccdcc255...` revision 上 17/17 steps、
  1118.377 秒通过：完整 Maven reactor、Node 24 Web 32 files / 251 tests、typecheck/lint/build、runtime/R0/R1/P0、
  正式 Template 产品旅程与浏览器回放均绿色（25 passed + 1 controlled skip；另有 inference replay 1/1）；
  provider attempts、API Key reads、reservations/cost 均为 0。
- 用户既有 360 项 dirty work 仍为原指纹
  `4bddd1c955a4b4f55d984f3febd551742dd6f63c`，备份 stash
  `f3c29199ec510ec3f809b3f8263f5d2806cb0740` 保持不变。本票没有发行其余 513 capacity 或 combined/
  non-capacity records，没有注册/认证 Profile、运行独立 native deployment/rehearsal、调用 provider、处理真实数据/
  生产或取得 J1/A3/READY；repository `full` 中既有 Rust checks 不改变 `BUILD_NOT_AUTHORIZED`。
