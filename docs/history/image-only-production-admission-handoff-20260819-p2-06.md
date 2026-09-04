# IMAGE_ONLY Production Admission handoff — stable after IOPA-P2-06

本文件供同一设备上的新 Agent session 接续。当前稳定点是 **IOPA-P2-06 实现 slice=`automated_verified`**
（capability 产品准入仍被 license J1 阻断，`J0_PENDING`）；下一唯一实施入口是
**IOPA-P3-01 Production admission 与 9-value readiness**。接手时先重锚定，保留当前工作树，再开始修改。

## 1. 接手顺序

1. 读取仓库根目录 `AGENTS.md`、`CONSTITUTION.md`、`CONTEXT.md`。
2. 读取 approved delta `specs/changes/20260817-image-only-production-admission.md`，再读取
   `plans/image-only-production-admission-blueprint-v1.md`。Blueprint 是索引；细节冲突时源票唯一权威。
3. 读取 `plans/image-only-production-admission-plan-v1.md` 的 §0.18、IOPA-P3-01、Gate、人工阻断、风险与末尾
   Decision。
4. P3-01 必读源票：
   - `.scratch/image-only-schema-production-admission/issues/04-freeze-production-trust-boundary.md`
     决策 9（9 值 reason code）、28（时间权威）、32（ProductionLiveAuthority 前置）；
   - `.scratch/image-only-schema-production-admission/issues/13-freeze-rollout-and-production-live-authority.md`
     （authority append-only、exact identity 绑定、drift 矩阵、无委托链）；
   - approved delta §7（Readiness, policy and audit）与 AC-IOPA-013、AC-IOPA-030、AC-IOPA-014（revalidation 部分）。
5. 运行 `git status --short`，确认当前 `main` HEAD 与 dirty tree。完成标准：接手记录明确写出
   `P2-01..06=automated_verified`（P2-06 capability admission=blocked_pending_license_J1）、
   `P3-01=not-started`、所有 live authorization CLOSED，且没有把 HEAD 当成完整实现。

若 approved delta、Blueprint、源票或当前代码在数值、identity、信任边界、术语或生命周期上存在实质冲突，按
Blueprint 附录 A 开新票；不在实现中静默选择新语义。

## 2. 当前快照

- 仓库：`D:\Yiwer\code\RenderWeave`；branch=`main`；handoff 时 HEAD=
  `7848c821aa9b809dd8cadb2b5e28f40f6947a90e`。
- **工作树很脏且未提交**：P0/P1 successor recovery、P2-01..06、ADRs、migrations、tests、gates、plans 与
  evidence 均在其中；tracked diff 之外还有大量 material untracked files（含 `docker/ocr-sidecar/vendor/`
  的 vendored wheels）。保留全部现状，不执行 reset/checkout/clean，不删除 `.scratch`、
  `plans/live-canary-authorizations` 或 `.sdlc/evidence`。
- 未获用户 commit/push 指令；handoff 前没有 commit、push、tag、部署或 production activation。
- 生命周期：P0=`automated_verified`；v46–v51 diagnostics 为 immutable negative terminal；v52 one-shot
  diagnostic 已 CLOSED 于 `REVIEW_REQUIRED`，manual review 仍 pending；P1 scoring 未解锁。
- P2-01..05 均 `automated_verified`；P2-06 实现 slice `automated_verified`，但 OCR capability 产品准入被
  所有者 license J1 阻断（`J0_PENDING`）——在 J1 前不得把 OCR 维度写成已准入，ImageOnlyReadiness 对
  `DOCUMENT_VISION_UNAVAILABLE`/capability 未准入保持 fail-closed。
- Goal live aggregate=`159,069/1,500,000` model tokens，remaining=`1,340,931`；当前 OPEN authorization=0。
  Standing approval 只允许在 exact identity 就绪后实例化逐 stage scoped J1，不是 wildcard permit。
  P3-01 是 Provider-zero，不需要或不应发起真实模型调用。
- 新 session 只有在用户明确要求 `/goal` 时创建/恢复 Goal；不得据此把整体 Wayfinder 宣布 complete。

## 3. 已冻结证据

| Stage | A1 evidence | Summary SHA-256 | Implementation identity |
|---|---|---|---|
| P2-01 | `.sdlc/evidence/20260818-160325-image-only-p2-admission/` | `65987841931e1ffc535fbc45ecb6c9a6166d79e2a37a4fa834a831b7a2973c13` | `renderweave-image-only-p2-admission/1.0:7986cbf7ef6b5f055b866c893086bed26f7f6fa87fd0ea7900adf624e27836d4` |
| P2-02 | `.sdlc/evidence/20260818-163143-image-only-p2-confirmation/` | `f5d20b15c0e3a839c3a455cbd2de1ba54783eb8ab9d329d96f7c28357e6d68d1` | `renderweave-image-only-p2-confirmation/1.0:567838f09c41f29179f89dc5fcd0e6ed8bd59fdb85a7c7ddb779bcdee48ba110` |
| P2-03 | `.sdlc/evidence/20260818-165229-image-only-p2-encryption/` | `161cf4380b0cebce3c4c23eb05c1318a34d5d3a042540fcdc15e8cbb35f6c393` | `renderweave-image-only-p2-encryption/1.0:b1131f9b5130c331e4b041b3fcb01027f8b14c4b52c9f54f8341039b7ede950d` |
| P2-04 | `.sdlc/evidence/20260818-173253-image-only-p2-payload-lifecycle/` | `27b7473070563e205aadc5003fbddcd19071bd68368b2d53903ee697cce2e548` | `renderweave-image-only-p2-payload-lifecycle/1.0:bc91c3de4d54fc3eed3660d55dee9d40fc586d9963c4ac05ff0925d002a092ac` |
| P2-05 | `.sdlc/evidence/20260818-204141-image-only-p2-audit-dual-switch/` | `7e6b06e3c09606ce1a8d5670b0cfbb4de11af246f0341228410f32349f8bc31c` | `renderweave-image-only-p2-audit-dual-switch/1.0:2bc701c58ce30c2c9d9dcf67b79e6649f57354fa996b6938e856c8a4ce80c87d` |
| P2-06 | `.sdlc/evidence/20260818-235714-image-only-p2-ocr-sidecar/` | `419ec9aa993d5ce2c239d4768180e56666fec4833ce769a3e881c283f7017852` | `renderweave-image-only-p2-ocr-sidecar/1.0:eb7786df804c227584a3558bc65c94993bdc709d597a6cf3baac1df0d4f9ea5a` |

P2-06 gate：verifier 2/2 + 启动/UDS/等价/加固探针全绿 + app 受影响回归 18/18；sidecar image=
`sha256:76d836f38b392e762d927ff899badcce2a49d71a3cecfcc6558772dc4528f9a8`。后续 stage 若修改前序
material files，历史 implementation identity 由新 gate 的受影响回归重验接续；历史 evidence 保留为快照。

## 4. P2-06 代码落点

- sidecar：`docker/ocr-sidecar/`（`Dockerfile`、`sidecar_server.py`、`probe_client.py`、
  `equivalence_probe.py`、`extract_models.py`、`requirements.lock`、`headless.lock`、`vendor/`、`NOTICE.md`）。
- Java：`UnixDomainSocketDocumentVisionRunner`、`LocalProcessDocumentVisionPreprocessor.forUnixSocket`、
  `InferenceApplicationConfiguration`（`renderweave.inference.document-vision.ud-socket`）。
- compose：`compose.yaml` 的 `ocr-sidecar` 服务（no-IP/read-only/cap-drop/资源上限/共享 socket 卷）。
- ADR：`docs/adr/0053-run-production-ocr-as-no-ip-unix-socket-sidecar.md`。
- gate：`tools/run-image-only-p2-ocr-sidecar.ps1`、`tools/verify_image_only_p2_ocr_sidecar.py`，
  入口已加入 `tools/run-gate.ps1`。

重新修改这些 material files 会使 P2-06 implementation identity 失效；届时必须重跑对应 gate 并更新证据，不得继续
引用旧 identity。

## 5. 下一任务：IOPA-P3-01

目标：`ImageOnlyProductionAdmission` 深 Module 收口 create/dequeue/call 前的完整准入谓词，并引入
append-only `ProductionLiveAuthority`（先只实现无 grant 状态）与完整 9 值 `ImageOnlyReadiness`。

建议实施顺序与完成标准：

1. **9-value readiness 投影**：`PROVIDER_CONTRACT_UNAVAILABLE`、`PROFILE_NOT_CERTIFIED`、
   `DOCUMENT_VISION_UNAVAILABLE`、`LIVE_POLICY_DISABLED`、`CREDENTIAL_UNAVAILABLE`、`EGRESS_DISABLED`、
   `PAYLOAD_DELETION_UNHEALTHY`、`TIME_AUTHORITY_UNAVAILABLE`、`AUDIT_INTEGRITY_UNAVAILABLE`；
   ServiceReadiness 与 ImageOnlyReadiness 分轴；typed 503；确定性站点不被 OCR/Provider 故障拖垮。
   完成标准：每一谓词单独失败的 Testcontainers/单测矩阵与优先级稳定性。
2. **ProductionLiveAuthority schema**：append-only grant/revoke 事件表（V027），绑定 stage、app SHA、
   Profile hash、route、sidecar digest/capability、actor/input scope、aggregate caps、effective/expiry、
   quad J1 引用；本 stage 只实现无 grant（任何 live admission 因缺失 authority 而 typed 503）。
   完成标准：one-field drift 矩阵、expiry/revoke 语义、authority 不能签发 authority。
3. **时间权威**：UTC wall-clock deadline + monotonic 进程内 timeout；偏差 >30s/来源失效/rollback 投影
   `TIME_AUTHORITY_UNAVAILABLE`（injected clock 测试）。
4. **admission/dequeue/call 重验**：把现有双开关/confirmation/tombstone/audit/budget 守卫统一到单一
   Module 入口（不复制谓词），每次 dequeue 与 Provider call 前重算，不缓存 create 时布尔。
5. **收尾**：ADR（production admission authority）、专用 verifier 与 `image-only-p3-*` gate，
   focused→affected→Phase；更新计划、NOTES 与 evidence identity。只把 P3-01 slice 标 automated_verified；
   per-call budget/drain 细化（P3-02）、capacity/telemetry（P3-03）、breaking contract（P3-04）仍属后续。

P3-01 最低测试矩阵：9 值 reason code 逐一 fail closed；ServiceReadiness 独立；authority 缺失/漂移/过期/
撤销 typed 503；时间偏差/rollback fail closed；dequeue/call 重验不缓存；普通 gate 清空 credential 并断言
provider attempts/reservations/tokens/cost/key reads=0。

## 6. 持续红线

- 真实付费调用只能使用当次 exact scoped J1 JSON，落在 `plans/live-canary-authorizations/`；不读取或输出 Key。
- Candidate 必须人工逐项审核；Agent 不自动 apply，不发布 StaticSchema，不宣布 ProductionUsable。
- 常规日志/evidence payload-free；数据库测试使用 Testcontainers PostgreSQL，不用 H2/SQLite。
- Provider adapter 不是任意 HTTP 能力；AI 无任意 SQL、文件、删除、发布或部署权限。
- 双开关默认关闭：任何测试/演练启用开关必须显式且随上下文关闭；生产配置不得由 Agent 写入。
- OCR capability license J1 前不得宣布 capability 已准入或解除 `DOCUMENT_VISION_UNAVAILABLE` 语义。
- 未经用户明确要求不 commit/push/tag/deploy。保留用户及前序 Agent 的 dirty worktree。
- P3-01 Provider-zero；若出现任何意外 provider attempt、payload/secret 泄漏或源票实质冲突，立即停止受影响
  路径并保留证据，其余独立安全任务可继续。

## 7. 给新 session 的启动提示

```text
在 D:\Yiwer\code\RenderWeave 接续 IMAGE_ONLY Production Admission。先完整读取
plans/image-only-production-admission-handoff-20260819-p2-06.md，并按其“接手顺序”重锚定。
保留当前未提交工作树；当前稳定点 P2-06=automated_verified（capability admission=J0_PENDING），
下一唯一入口 P3-01，Provider-zero。按源票 04/13、approved delta §7 与实施计划实现 9-value readiness
与 ProductionLiveAuthority schema；不读取 Key，不 live，不 apply/publish/deploy/commit/push，
实质冲突按 Blueprint 附录 A 开票。
```
