# IMAGE_ONLY Production Admission handoff — stable after IOPA-P2-04

本文件供同一设备上的新 Agent session 接续。当前稳定点是 **IOPA-P2-04=`automated_verified`**；下一唯一实施入口是
**IOPA-P2-05 Payload-free audit chain 与双开关**。接手时先重锚定，保留当前工作树，再开始修改。

## 1. 接手顺序

1. 读取仓库根目录 `AGENTS.md`、`CONSTITUTION.md`、`CONTEXT.md`。
2. 读取 approved delta `specs/changes/20260817-image-only-production-admission.md`，再读取
   `plans/image-only-production-admission-blueprint-v1.md`。Blueprint 是索引；细节冲突时源票唯一权威。
3. 读取 `plans/image-only-production-admission-plan-v1.md` 的 §0.16、IOPA-P2-05、Gate、人工阻断、风险与末尾
   Decision。
4. P2-05 必读源票：
   - `.scratch/image-only-schema-production-admission/issues/04-freeze-production-trust-boundary.md` 的决策
     8、13、14、29、31；
   - `.scratch/image-only-schema-production-admission/issues/11-freeze-operational-telemetry-and-oncall.md`，仅用于
     audit/telemetry retention、低基数与 Provider-zero drain exercise 的下游边界。
5. 运行 `git status --short`，确认当前 `main` HEAD 与 dirty tree。完成标准：接手记录明确写出
   `P2-01..04=automated_verified`、`P2-05=not-started`、所有 live authorization CLOSED，且没有把 HEAD 当成完整实现。

若 approved delta、Blueprint、源票或当前代码在数值、identity、信任边界、术语或生命周期上存在实质冲突，按
Blueprint 附录 A 开新票；不在实现中静默选择新语义。

## 2. 当前快照

- 仓库：`D:\Yiwer\code\RenderWeave`；branch=`main`；handoff 时 HEAD=
  `7848c821aa9b809dd8cadb2b5e28f40f6947a90e`。
- **工作树很脏且未提交**：P0/P1 successor recovery、P2-01..04、ADRs、migrations、tests、gates、plans 与 evidence
  均在其中；tracked diff 之外还有大量 material untracked files。保留全部现状，不执行 reset/checkout/clean，
  不删除 `.scratch`、`plans/live-canary-authorizations` 或 `.sdlc/evidence`。
- 未获用户 commit/push 指令；handoff 前没有 commit、push、tag、部署或 production activation。
- 生命周期：P0=`automated_verified`；v46–v51 diagnostics 为 immutable negative terminal；v52 one-shot diagnostic
  已 CLOSED 于 `REVIEW_REQUIRED`，manual review 仍 pending；P1 scoring 未解锁。
- P2-01 gateway authority、P2-02 notice/manifest/confirmation、P2-03 envelope encryption、P2-04
  expiry/tombstone/delete worker 均为 `automated_verified`。加密与 lifecycle scheduler 默认关闭；无生产配置。
- Goal live aggregate=`159,069/1,500,000` model tokens，remaining=`1,340,931`；当前 OPEN authorization=0。
  Standing approval 只允许在 exact identity 就绪后实例化逐 stage scoped J1，不是 wildcard permit。P2-05 是
  Provider-zero，不需要或不应发起真实模型调用。
- 当前 thread 的 Goal 控制面可能仍显示历史 `blocked`；产品实施事实以本 handoff 与计划 Decision 为准。新 session
  只有在用户明确要求 `/goal` 时创建/恢复 Goal；不得据此把整体 Wayfinder 宣布 complete。

## 3. 已冻结证据

| Stage | A1 evidence | Summary SHA-256 | Implementation identity |
|---|---|---|---|
| P2-01 | `.sdlc/evidence/20260818-160325-image-only-p2-admission/` | `65987841931e1ffc535fbc45ecb6c9a6166d79e2a37a4fa834a831b7a2973c13` | `renderweave-image-only-p2-admission/1.0:7986cbf7ef6b5f055b866c893086bed26f7f6fa87fd0ea7900adf624e27836d4` |
| P2-02 | `.sdlc/evidence/20260818-163143-image-only-p2-confirmation/` | `f5d20b15c0e3a839c3a455cbd2de1ba54783eb8ab9d329d96f7c28357e6d68d1` | `renderweave-image-only-p2-confirmation/1.0:567838f09c41f29179f89dc5fcd0e6ed8bd59fdb85a7c7ddb779bcdee48ba110` |
| P2-03 | `.sdlc/evidence/20260818-165229-image-only-p2-encryption/` | `161cf4380b0cebce3c4c23eb05c1318a34d5d3a042540fcdc15e8cbb35f6c393` | `renderweave-image-only-p2-encryption/1.0:b1131f9b5130c331e4b041b3fcb01027f8b14c4b52c9f54f8341039b7ede950d` |
| P2-04 | `.sdlc/evidence/20260818-173253-image-only-p2-payload-lifecycle/` | `27b7473070563e205aadc5003fbddcd19071bd68368b2d53903ee697cce2e548` | `renderweave-image-only-p2-payload-lifecycle/1.0:bc91c3de4d54fc3eed3660d55dee9d40fc586d9963c4ac05ff0925d002a092ac` |

P2-04 gate=`123/123 PASS`（lifecycle PostgreSQL 6 + affected regression 117），verifier=`2/2 PASS`，Provider/
credential usage=0。证据的 `git-status.txt` 与 `input-manifest.sha256` 是 handoff 前最近一次 material A1 快照；之后
只更新了计划/NOTES checkpoint 并新增本 handoff，没有修改 P2-04 implementation-identity material。

## 4. P2-04 代码落点

- V025：`renderweave-app/src/main/resources/db/migration/V025__payload_retention_tombstones_and_deletion_tasks.sql`。
- authority/worker：`PostgresPayloadLifecycleStore`、`PayloadLifecycleScheduler` 与
  `renderweave-inference/.../retention/`。
- 加密 ingest lease seam：`ArtifactEnvelopeStore`、`PostgresArtifactEnvelopeStore`、
  `EnvelopeEncryptedBlobStore`。
- guards：`PostgresLiveAdmissionStore`、`PostgresInferenceRunStore`、`PostgresCandidateApplyStore`、
  `LiveInferenceWorker`、`CandidateReviewService`、`InferenceController`。
- ADR：`docs/adr/0051-separate-logical-payload-tombstones-from-physical-erasure.md`。
- gate：`tools/run-image-only-p2-payload-lifecycle.ps1`、
  `tools/verify_image_only_p2_payload_lifecycle.py`，入口已加入 `tools/run-gate.ps1`。

重新修改这些 material files 会使 P2-04 implementation identity 失效；届时必须重跑对应 gate 并更新证据，不得继续
引用旧 identity。

## 5. 下一任务：IOPA-P2-05

目标：建立一个 payload-free 的 `Live Admission Audit` authority，并让独立、默认关闭的
`ImageOnlyAdmissionPolicy` 与 `ProviderEgressPermit` 在 admission、dequeue 和每次 Provider call 前共同生效。

已知交叉引用 drift：计划的 P2-05 `AC` 行包含 AC-IOPA-024（backup），却未列 approved delta 中直接定义 audit
chain 的 AC-IOPA-017；任务正文、Blueprint 与源票 04 的语义一致。接手时以 approved delta 的可观察行为和源票为
准，不把 P2-05 自动外推为 backup/telemetry 全局完成；若要改写计划映射，先按 Blueprint 附录 A 判断并记录处置。

建议实施顺序与完成标准：

1. **重锚定现有 seam**：检查 V018 provider reservation、V022/V023 admission authority、P2-04 readiness 与
   `LiveInferenceWorker.invoke`。完成标准：列出所有可能发送 Provider bytes 的入口及其事务边界。
2. **审计 authority**：使用下一个实际可用 Flyway 版本建立逐 run 单调 sequence + domain-separated digest chain；
   event 只允许 opaque identity、digest、固定 code、usage/cost 与时间。Flyway owner 与 runtime role 分离，runtime
   对 audit fact 无 UPDATE/DELETE。完成标准：duplicate/reorder/delete/tamper/missing 均由独立 replay fail closed。
3. **原子 call authorization**：把 call authorization、attempt identity、费用 reservation 与 audit event 放进同一
   PostgreSQL transaction，提交后才产生 Provider permit。现有 `budgetStore.reserve(...)` 单独成功不等于满足本条；
   不得在 reservation 与 audit 之间留下可外传窗口。完成标准：每个 crash point 都证明“无已提交审计+reservation
   就没有 provider call”。
4. **双开关**：`ImageOnlyAdmissionPolicy` 是持久化、版本化的应用策略；`ProviderEgressPermit` 是应用外
   orchestrator/firewall authority 的只读 port。两者默认关闭且互不推导，credential/configured 状态不能开启任一
   开关。完成标准：00/01/10 均拒绝，只有 11 才能通过其他前置检查；重启保持关闭/既定状态，重开不复活旧 run。
5. **stop/drain 与 readiness**：新 live typed 503；QUEUED 进入稳定终态；RUNNING 在最近安全边界停止；已 dispatch
   的结果只完成 usage/audit settlement，不推进；`REVIEW_REQUIRED` 仍可 review/apply。audit 不可写或 chain 异常
   投影 `AUDIT_INTEGRITY_UNAVAILABLE`。完成标准：Provider-zero 状态矩阵与 restart replay 全绿。
6. **payload scan**：覆盖 audit、常规日志、problem、metric/webhook projection 与 evidence；注入图片签名、原文件名、
   OCR、完整 prompt/response、PII、secret、CoT canary，任何一个进入输出即 gate 失败。不要把 runId/actor/image hash
   用作 metric label。完成标准：A1 integration + independent scanner A2。
7. **收尾**：新增 audit/dual-switch ADR、专用 verifier 与 `image-only-p2-*` gate，运行 focused→affected→Phase；
   更新计划、NOTES 与 evidence identity。只把 P2-05 的 slice 标为 automated_verified；OperationalTelemetry、backup
   和完整 AC-IOPA-023/024 仍由后续任务完成，不得提前宣称全局 AC complete。

P2-05 的最低测试矩阵：Testcontainers PostgreSQL runtime-role 权限；chain positive/duplicate/reorder/delete/tamper；
audit write failure；call transaction crash points；dual-switch 4 组合；queued/running/in-flight/review drain；restart
no-resurrection；payload/secret canaries。普通 gate 必须清空 credential/J1 环境并断言 provider attempts、reservations、
tokens、cost 与 key reads 均为 0。

## 6. 持续红线

- 真实付费调用只能使用当次 exact scoped J1 JSON，落在 `plans/live-canary-authorizations/`；不读取或输出 Key。
- Candidate 必须人工逐项审核；Agent 不自动 apply，不发布 StaticSchema，不宣布 ProductionUsable。
- 常规日志/evidence payload-free；数据库测试使用 Testcontainers PostgreSQL，不用 H2/SQLite。
- Provider adapter 不是任意 HTTP 能力；AI 无任意 SQL、文件、删除、发布或部署权限。
- 未经用户明确要求不 commit/push/tag/deploy。保留用户及前序 Agent 的 dirty worktree。
- P2-05 Provider-zero；若出现任何意外 provider attempt、payload/secret 泄漏或源票实质冲突，立即停止受影响路径并
  保留证据，其余独立安全任务可继续。

## 7. 给新 session 的启动提示

```text
在 D:\Yiwer\code\RenderWeave 接续 IMAGE_ONLY Production Admission。先完整读取
plans/image-only-production-admission-handoff-20260818-p2-04.md，并按其“接手顺序”重锚定。
保留当前未提交工作树；当前稳定点 P2-04=automated_verified，下一唯一入口 P2-05，Provider-zero。
按源票 04/11、approved delta 与实施计划实现 audit chain + 双开关，使用 Testcontainers，落 ADR/gate/evidence；
不读取 Key，不 live，不 apply/publish/deploy/commit/push，实质冲突按 Blueprint 附录 A 开票。
```
