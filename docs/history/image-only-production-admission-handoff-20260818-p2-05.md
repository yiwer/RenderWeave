# IMAGE_ONLY Production Admission handoff — stable after IOPA-P2-05

本文件供同一设备上的新 Agent session 接续。当前稳定点是 **IOPA-P2-05=`automated_verified`**；下一唯一实施入口是
**IOPA-P2-06 No-IP UDS OCR sidecar**。接手时先重锚定，保留当前工作树，再开始修改。

## 1. 接手顺序

1. 读取仓库根目录 `AGENTS.md`、`CONSTITUTION.md`、`CONTEXT.md`。
2. 读取 approved delta `specs/changes/20260817-image-only-production-admission.md`，再读取
   `plans/image-only-production-admission-blueprint-v1.md`。Blueprint 是索引；细节冲突时源票唯一权威。
3. 读取 `plans/image-only-production-admission-plan-v1.md` 的 §0.17、IOPA-P2-06、Gate、人工阻断、风险与末尾
   Decision。
4. P2-06 必读源票：
   - `.scratch/image-only-schema-production-admission/issues/08-freeze-rapidocr-production-topology.md`（拓扑、
     exact image/依赖/资源/供应链与 capability identity）；
   - `.scratch/image-only-schema-production-admission/issues/03-research-rapidocr-container-constraints.md`
     （平台/许可约束证据）；
   - `.scratch/image-only-schema-production-admission/issues/11-freeze-operational-telemetry-and-oncall.md`
     仅用于 sidecar 长稳探针归遥测、启动阻塞层归 ticket 08 的下游边界；
   - approved delta §6（OCR sidecar and perception seam）与 AC-IOPA-018/019/020、AC-IOPA-034。
5. 运行 `git status --short`，确认当前 `main` HEAD 与 dirty tree。完成标准：接手记录明确写出
   `P2-01..05=automated_verified`、`P2-06=not-started`、所有 live authorization CLOSED，且没有把 HEAD 当成完整实现。

若 approved delta、Blueprint、源票或当前代码在数值、identity、信任边界、术语或生命周期上存在实质冲突，按
Blueprint 附录 A 开新票；不在实现中静默选择新语义。

## 2. 当前快照

- 仓库：`D:\Yiwer\code\RenderWeave`；branch=`main`；handoff 时 HEAD=
  `7848c821aa9b809dd8cadb2b5e28f40f6947a90e`。
- **工作树很脏且未提交**：P0/P1 successor recovery、P2-01..05、ADRs、migrations、tests、gates、plans 与 evidence
  均在其中；tracked diff 之外还有大量 material untracked files。保留全部现状，不执行 reset/checkout/clean，
  不删除 `.scratch`、`plans/live-canary-authorizations` 或 `.sdlc/evidence`。
- 未获用户 commit/push 指令；handoff 前没有 commit、push、tag、部署或 production activation。
- 生命周期：P0=`automated_verified`；v46–v51 diagnostics 为 immutable negative terminal；v52 one-shot diagnostic
  已 CLOSED 于 `REVIEW_REQUIRED`，manual review 仍 pending；P1 scoring 未解锁。
- P2-01 gateway authority、P2-02 notice/manifest/confirmation、P2-03 envelope encryption、P2-04
  expiry/tombstone/delete worker、P2-05 audit chain 与双开关均为 `automated_verified`。双开关默认关闭
  （policy version 1=DEFAULT_CLOSED、egress permit 缺省 disabled）；加密与 lifecycle scheduler 默认关闭；无生产配置。
- Goal live aggregate=`159,069/1,500,000` model tokens，remaining=`1,340,931`；当前 OPEN authorization=0。
  Standing approval 只允许在 exact identity 就绪后实例化逐 stage scoped J1，不是 wildcard permit。P2-06 是
  Provider-zero，不需要或不应发起真实模型调用。
- 新 session 只有在用户明确要求 `/goal` 时创建/恢复 Goal；不得据此把整体 Wayfinder 宣布 complete。

## 3. 已冻结证据

| Stage | A1 evidence | Summary SHA-256 | Implementation identity |
|---|---|---|---|
| P2-01 | `.sdlc/evidence/20260818-160325-image-only-p2-admission/` | `65987841931e1ffc535fbc45ecb6c9a6166d79e2a37a4fa834a831b7a2973c13` | `renderweave-image-only-p2-admission/1.0:7986cbf7ef6b5f055b866c893086bed26f7f6fa87fd0ea7900adf624e27836d4` |
| P2-02 | `.sdlc/evidence/20260818-163143-image-only-p2-confirmation/` | `f5d20b15c0e3a839c3a455cbd2de1ba54783eb8ab9d329d96f7c28357e6d68d1` | `renderweave-image-only-p2-confirmation/1.0:567838f09c41f29179f89dc5fcd0e6ed8bd59fdb85a7c7ddb779bcdee48ba110` |
| P2-03 | `.sdlc/evidence/20260818-165229-image-only-p2-encryption/` | `161cf4380b0cebce3c4c23eb05c1318a34d5d3a042540fcdc15e8cbb35f6c393` | `renderweave-image-only-p2-encryption/1.0:b1131f9b5130c331e4b041b3fcb01027f8b14c4b52c9f54f8341039b7ede950d` |
| P2-04 | `.sdlc/evidence/20260818-173253-image-only-p2-payload-lifecycle/` | `27b7473070563e205aadc5003fbddcd19071bd68368b2d53903ee697cce2e548` | `renderweave-image-only-p2-payload-lifecycle/1.0:bc91c3de4d54fc3eed3660d55dee9d40fc586d9963c4ac05ff0925d002a092ac` |
| P2-05 | `.sdlc/evidence/20260818-204141-image-only-p2-audit-dual-switch/` | `7e6b06e3c09606ce1a8d5670b0cfbb4de11af246f0341228410f32349f8bc31c` | `renderweave-image-only-p2-audit-dual-switch/1.0:2bc701c58ce30c2c9d9dcf67b79e6649f57354fa996b6938e856c8a4ce80c87d` |

P2-05 gate=verifier 3/3 + inference 15/15 + app 受影响回归 149/149（总 164/164 PASS），Provider/credential
usage=0。P2-05 修改了 P2-01..04 共享 material（worker/controller/admission store 等），历史 identity 由 P2-05
受影响回归重验接续；历史 evidence 保留为快照。证据的 `git-status.txt` 与 `input-manifest.sha256` 是 handoff
前最近一次 material A1 快照；之后只更新了计划/NOTES checkpoint 并新增本 handoff，没有修改 P2-05
implementation-identity material。

## 4. P2-05 代码落点

- V026：`renderweave-app/src/main/resources/db/migration/V026__live_admission_audit_chain_and_dual_switches.sql`。
- audit/policy/gate：`PostgresLiveAuditStore`、`PostgresImageOnlyAdmissionPolicyStore`、
  `PostgresLiveProviderCallGate`、`PostgresAuditIntegrityProbe`、`FileSystemProviderEgressPermit` 与
  `renderweave-inference/.../audit/`、`.../admission/`（`ImageOnlyAdmissionPolicy`、`ProviderEgressPermit`、
  `LiveProviderCallGate`、`ProviderCallPermit`、`LiveNoticeAuthority`）。
- worker 接线：`LiveInferenceWorker`（eligibility/authorize/settle/drain）；controller 双开关与 audit readiness；
  `ImageOnlyProductionAdmission` 开关前置。
- ADR：`docs/adr/0052-payload-free-audit-chain-and-independent-dual-switches.md`。
- gate：`tools/run-image-only-p2-audit-dual-switch.ps1`、`tools/verify_image_only_p2_audit_dual_switch.py`，
  入口已加入 `tools/run-gate.ps1`。

重新修改这些 material files 会使 P2-05 implementation identity 失效；届时必须重跑对应 gate 并更新证据，不得继续
引用旧 identity。

## 5. 下一任务：IOPA-P2-06

目标：把生产 OCR 从 fixed subprocess 迁到无 IP、仅 UDS、独立 cgroup 的 sidecar，exact base digest、locked
wheels/packages/models、zero-download 启动、startup capability+synthetic probe 阻断，R0 behavior-equivalence
保持 `DocumentObservationIR/1.0` 语义；失败只关闭 ImageOnlyReadiness，确定性站点不受影响。

关键边界（以源票 08/03 与 approved delta §6 为准）：

- `linux/amd64 + CPython 3.12 + glibc≥2.28 + AVX2 + CPU-only`；ARM/Alpine 不准入。
- `python:3.12-slim-bookworm` 按 digest pin；`pip --require-hashes` 全量锁；lock 必须含 `omegaconf==2.3.0`
  与固定 builder 产生的内部 `antlr4-python3-runtime==4.9.3` wheel；三份 capability ONNX 构建期提取、逐一
  SHA-256 校验、只读预置、启动零下载。
- HTTP/1.1 over UDS，no-IP；read-only rootfs、non-root、drop-all-caps、2CPU/2GB/PID64/60s；API JRE 不含 OCR runtime。
- capability id 保持 `rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1`（只有 R0
  behavior-equivalence、supply chain 与探针全过时才保持）。
- 人工条件：完整 provenance/SBOM/CVE/malware/license/NOTICE/attestation 后由所有者记录 Apache-2.0 主线及
  exact model/system-library disposition 的 license J1；J0 不准入。
- dev/offline 可保留 stdio Adapter；生产路径只走 UDS sidecar。

Provider-zero 提醒：sidecar 构建与 R0 回放不需要任何模型调用；普通 gate 仍清空 credential 环境并断言 provider
attempts/reservations/tokens/cost/key reads=0。

## 6. 持续红线

- 真实付费调用只能使用当次 exact scoped J1 JSON，落在 `plans/live-canary-authorizations/`；不读取或输出 Key。
- Candidate 必须人工逐项审核；Agent 不自动 apply，不发布 StaticSchema，不宣布 ProductionUsable。
- 常规日志/evidence payload-free；数据库测试使用 Testcontainers PostgreSQL，不用 H2/SQLite。
- Provider adapter 不是任意 HTTP 能力；AI 无任意 SQL、文件、删除、发布或部署权限。
- 双开关默认关闭：任何测试/演练启用开关必须显式且随上下文关闭；生产配置不得由 Agent 写入。
- 未经用户明确要求不 commit/push/tag/deploy。保留用户及前序 Agent 的 dirty worktree。
- P2-06 Provider-zero；若出现任何意外 provider attempt、payload/secret 泄漏或源票实质冲突，立即停止受影响路径并
  保留证据，其余独立安全任务可继续。

## 7. 给新 session 的启动提示

```text
在 D:\Yiwer\code\RenderWeave 接续 IMAGE_ONLY Production Admission。先完整读取
plans/image-only-production-admission-handoff-20260818-p2-05.md，并按其“接手顺序”重锚定。
保留当前未提交工作树；当前稳定点 P2-05=automated_verified，下一唯一入口 P2-06，Provider-zero。
按源票 08/03、approved delta §6 与实施计划实现 No-IP UDS OCR sidecar 与 R0 behavior-equivalence；
不读取 Key，不 live，不 apply/publish/deploy/commit/push，实质冲突按 Blueprint 附录 A 开票。
```
