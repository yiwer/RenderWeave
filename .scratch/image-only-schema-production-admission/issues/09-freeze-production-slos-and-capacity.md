# 冻结 IMAGE_ONLY 生产 SLO、容量与成本预算

Type: grilling
Status: resolved
Claimed by: Kimi（2026-08-17 会话）
Blocked by: 05 — 冻结新的 IMAGE_ONLY Profile Certification authority；07 — 冻结 Provider 生产路线与 Profile migration 边界；08 — 冻结 RapidOCR 生产拓扑与 capability admission

## Question

在 sole-finalist immutable Profile、标准按量付费 Provider route 与 exact normalizer/OCR sidecar 均已冻结后，首个单节点生产部署应以哪些可独立验证的指标和窗口定义 `ServiceReadiness` 与 `ImageOnlyReadiness` 的生产 SLO：API create/lookup/review/apply availability，上传与 durable enqueue latency，queue wait、首个 Provider attempt、端到端 `REVIEW_REQUIRED` latency，Provider/sidecar timeout 与 failure budget，每 run/日/月 call、token、费用与并发上限，PNG/JPEG byte/pixel/总量上限，CPU/RAM/PID/磁盘/Blob/删除 backlog 水位，以及达到 soft/hard boundary 时的 reject、drain、degrade 与 recovery 行为？每个目标应由什么负载模型、A1/A2 gate、观测窗口和 error-budget burn 判定，且 Provider outage 或 IMAGE_ONLY 过载不得拖垮确定性 Schema 服务。

## Answer

2026-08-17 经两轮 grilling 冻结（全部按所有者确认的推荐）：

### 负载模型（一切 SLO 的前提）

≤20 live run/日（hard reject 超出）、并发 ≤2、单 run 1–10 图（04-16）。单租户单节点定位，超出即拒新 run，确定性站点不受影响。

### 延迟 SLO（7 日滚动窗口）

- 端到端 `REVIEW_REQUIRED`：P50 ≤ 3min，**P90 ≤ 15min**（v46 最坏 12 调用×~40s+开销 ≈9–10min，留 50% 冗余；实测 Max 42s–6min）
- 上传→durable enqueue：P95 ≤ 5s；queue wait P90 ≤ 20min（并发 2 串行消化）；首个 Provider attempt P90 ≤ 2min（04-10 的 15min 确认过期为硬外框）
- review/apply/lookup 为纯本地操作，可用性随 `ServiceReadiness` 轴，不单列 SLO

### 可用性 SLO（月度窗口）

`ServiceReadiness` 99.5%；`ImageOnlyReadiness` 99%，其不可用不计入 ServiceReadiness（04-05 双轴）；Provider outage 只影响 IMAGE_ONLY 轴。

### 成本与用量预算

- 单 run：v46 Profile cap ¥6 / 12 调用（ticket 07）
- 日 soft ¥30（告警）/ 月 hard ¥500：触 hard 自动关 `ImageOnlyAdmissionPolicy` 新 run，在途照走、审核不受限
- 不设独立 token 日月上限（费用 cap 已涵盖）；日 20 run 硬拒为用量上限

### 输入与资源水位

- 单图 ≤10MiB 且 ≤25Mpx；每 run ≤10 图、合计 ≤32MiB（与 transport 10MiB/32MiB 对齐）；normalizer 输出侧上限归 ticket 12 API 合同钉
- 磁盘 soft 70%（告警）/ hard 85%（拒新上传）；PG 连接 80% soft；Blob 删除 backlog >24h → `PAYLOAD_DELETION_UNHEALTHY` fail-closed（继承 04-24）；OCR sidecar 2C/2GB/PID64/60s（继承 ticket 08）；API 节点 CPU/RAM 不设独立 SLO，由 ticket 11 遥测观测

### Timeout、失败预算与恢复

- `stageTimeoutSeconds`=360 维持；sidecar 60s（ticket 08）
- 失败预算按计数：任一自然日 Provider 拒绝/超时致 FAILED ≥4 且占当日 ≥50% → 告警并建议人工评估撤销认证（接 ticket 07 事故驱动撤销），不自动撤销
- 恢复语义：**测量驱动的水位关闭在回落 soft 以下时自动重开；`ImageOnlyAdmissionPolicy`/`ProviderEgressPermit` 人工紧急开关永远只人工操作**；重开不复活旧 run（继承 04-13）

### 证据与 gate

A1 = 持续遥测（ticket 11 payload-free 指标）+ 月度 SLO 报告（自动产出、所有者过目）；A2 = `CapacityBaselineTest` 扩展到 inference enqueue/review/apply 路径（Provider-zero 离线重放），纳入 release gate（ticket 12）。

### 下游

- ticket 10 阻塞解除（frontier）；ticket 11 须实现上述 payload-free 指标与月度报告；ticket 12 须把扩展后的 capacity baseline 纳入 release gate。
