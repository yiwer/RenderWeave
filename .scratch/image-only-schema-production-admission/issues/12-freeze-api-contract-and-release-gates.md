# 冻结 IMAGE_ONLY API 合同与 release gate

Type: grilling
Status: resolved
Claimed by: Kimi（2026-08-17 会话）
Blocked by: 05 — 冻结新的 IMAGE_ONLY Profile Certification authority；07 — 冻结 Provider 生产路线与 Profile migration 边界；08 — 冻结 RapidOCR 生产拓扑与 capability admission；09 — 冻结 IMAGE_ONLY 生产 SLO、容量与成本预算；10 — 冻结单节点持久化、备份与恢复合同；11 — 冻结 payload-free OperationalTelemetry、告警与值守合同

## Question

在 Profile/Provider/OCR、信任边界、SLO、恢复和 telemetry 均已冻结后，OpenAPI、generated Web SDK 与产品 UI 必须如何表达 `InputProvenance`、`SensitivityClass`、`ExternalTransferNotice/Confirmation`、Live Input Manifest、Profile/contract identity、call/cost caps、readiness reason、retry/ambiguous attempt、payload expiry/deletion tombstone、audit-safe errors 与 Candidate review/apply；旧 boolean confirmation、Token Plan fields、旧 Profile catalog、call-count/cost/删除描述及公开 Actuator 等 drift 应怎样 fail-closed 迁移？哪些 contract tests、schema diff、SDK regeneration check、security headers、database migration/recovery compatibility、exact-revision fast/server/web/e2e/full gates 与双轴 code review 必须成为 release gate，且不得把 narrow green、历史 evidence 或人工解释等同生产接受。

## Answer

2026-08-17 经 grilling 冻结（全部按所有者确认的推荐）：

### 公开 API 合同表达（Q1：换精确字段集，旧布尔 typed 拒绝）

1. **逐 run 确认升级为一等记录**：`POST /api/v1/inference-runs/live` 的 metadata 废弃旧布尔 `externalTransferConfirmed`（同时废除 `experimentalProfileConfirmed` 这类布尔确认），替换为精确字段集——`externalTransferNoticeVersion`、`externalTransferPolicyVersion`、`liveInputManifestId`（绑定 Live Input Manifest identity）；服务端据此落一等 `ExternalTransferConfirmation` 记录，即 ticket 04「逐 run 外传确认 + 逐 call authorization」在 API 层的具体形态。旧布尔出现即 **typed 422 拒绝**，不做静默迁移、不做新旧双格式兼容。
2. **合同必须显式表达**：`InputProvenance`/`SensitivityClass`（ordinary-design 准入、`RESTRICTED` fail-closed）；Profile/contract identity（精确 `profileId` + Profile bytes SHA-256 引用）；call/cost caps（v46 为 12 次/¥6，由 Profile 决定、不由请求传入）；readiness reason code（04 双轴六值）；payload expiry/deletion tombstone 状态；Candidate review/apply 流程（bulk confirm 保持禁止——现行 `CANDIDATE_BULK_RESOLUTION_FORBIDDEN` 行为入合同，逐项 CONFIRMED 后 apply 原子建 Draft Bundle）。

### Drift 迁移（Q2：全部 fail-closed，不双跑）

3. 旧 boolean confirmation、Token Plan 字段、旧 Profile catalog、旧 call-count/cost/删除描述、公开 Actuator 端点——全部 **typed 410 Gone / 422 Unprocessable** 拒绝并附 reason code；不设新旧并行双跑窗口。
4. **OpenAPI 升版**（`openapi/renderweave-v1.yaml` 版本号提升 + change note）；Web SDK 由 `npm run api:generate` 从冻结 yaml 重新生成，**SDK diff check 进 gate**（生成物与提交物不一致即红）。

### Release gate 构成（Q3）

5. Release gate = 现有 **full gate 家族**（`tools/run-gate.ps1` fast/server/web/e2e/full，全部 Node 24 exact-clean）**之上叠加**：
   - contract tests（OpenAPI yaml ↔ 服务端实现的请求/响应/错误 taxonomy 一致性）；
   - SDK regeneration check（上条）；
   - security headers 检查（外部网关缺席时应用层兜底头）；
   - DB migration/recovery compatibility（迁移前后 contract tests 双跑 + ticket 10 restore drill 产物）；
   - ticket 09 的 CapacityBaselineTest 扩展；
   - ticket 11 的 Provider-zero kill-switch/drain 离线演练。

   窄绿（只跑局部 gate 声称通过）、历史 evidence、人工解释均不构成生产接受。

### 验收权威（Q4：双轴 review）

6. **双轴 code review**：合同兼容轴（API/SDK/迁移兼容性）+ 数据政策轴（payload-free、生命周期、审计完整性）各自独立人类签字；agent 自审不算数，两轴都是 J1。

### Audit-safe errors 与 retry（Q5）

7. **错误 taxonomy 封闭**：公开错误只许枚举码 + 静态文案；禁止把用户数据、文件名、图片 hash、prompt 片段插值进错误消息；detail 只允许引用 payload-free 标识（runId、reason code）。
8. **retry 语义按 04 幂等/歧义决策落地**：客户端幂等重试走 `Idempotency-Key`；并发/状态冲突 typed 409 + reason code；ambiguous attempt（未知是否已计费/已执行）走「先查询、再决策」路径，不自动盲目重试。

### 下游

- ticket 13 阻塞解除（其全部上游已 resolved）：本票冻结的错误 taxonomy、release gate 清单与双轴 review 构成 rollout 阶段推进判据与 `ProductionLiveAuthority` 签发前置的一部分。
