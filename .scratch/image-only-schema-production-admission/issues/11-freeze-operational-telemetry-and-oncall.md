# 冻结 payload-free OperationalTelemetry、告警与值守合同

Type: grilling
Status: resolved
Claimed by: Kimi（2026-08-17 会话）
Blocked by: 09 — 冻结 IMAGE_ONLY 生产 SLO、容量与成本预算；10 — 冻结单节点持久化、备份与恢复合同

## Question

在生产 SLO、容量边界及恢复合同确定后，首个单节点部署必须发布哪些 payload-free、低基数 OperationalTelemetry：整站与 IMAGE_ONLY readiness/reason、gateway assertion、confirmation、queue/lease/stage、Provider attempt/reservation/usage/cost、sidecar resource、artifact encryption/deletion、audit integrity、backup freshness 与 restore drill 指标和事件？每个指标的 label/cardinality/redaction、日志与 audit retention、dashboard、warning/page threshold、multi-window burn alert、值班责任、runbook、escalation、Provider incident、误分类/删除超时处置和 evidence capture 应如何冻结，确保不泄漏图片、文件名、OCR、prompt/response、PII、secret 或高基数 input identity，并能独立证明 SLO、kill switch、drain 与 recovery 行为。

## Comments

- 2026-08-17（ticket 08 resolved，输入本票）：OCR sidecar 的长稳/资源证据层探针（目标节点长稳、OOM 行为、CPU/RAM 实际曲线）不做启动阻塞，归本票冻结；启动阻塞层（capability + 合成图探针）已在 ticket 08 冻结。

## Answer

2026-08-17 经 grilling 冻结（全部按所有者确认的推荐）：

### 管道与指标

1. **形态**：应用内聚合——低基数时间序列滚动窗口聚合，经内部 actuator listener（04-21 mTLS）暴露 JSON；阈值评估在应用内；周期快照落 PG append-only（供 A2 重算）；告警 = 日志 + 可配 webhook（所有者邮箱/IM）。不引入 Prometheus/Grafana。
2. **指标清单**：双轴 readiness 状态 + reason code（04-9 六值）；run 生命周期计数（state/failureCode）；stage 延迟直方图；Provider attempt 计数（按 outcome）+ token/费用（label 仅 profileId）；queue depth/lease age；OCR sidecar 探针 + CPU/RAM 实测曲线（08 移交长稳层）；payload 生命周期（创建/删除/backlog 年龄）；backup freshness 与 drill 结果。**label 只许封闭枚举**（state/stage/reason/profileId/model），runId/actor/图片 hash 永不为 label。
3. **Dashboard** = 现有 Web monitor 页 + actuator JSON + 月报；不新建。

### Retention（04-14 的口子在此冻结）

4. Live Admission Audit 在线 **90 天**、月度归档 **13 个月**；应用日志 **30 天**；指标原始 30 天 + 小时聚合 13 个月。不借审计延长 payload 生命周期。

### 告警、值守与处置

5. **两级告警**：warning（进日报）= 磁盘 70%、日成本 soft ¥30、失败预算计数触发、backup 年龄 >25h、sidecar 长稳探针劣化；page（即时推送）= `ImageOnlyReadiness` fail-closed、`PAYLOAD_DELETION_UNHEALTHY`、审计完整性失败、月成本 hard ¥500、restore drill 失败、误分类事件（处置按 04-26）。与 09/10 触发器一一映射。
6. **值守**：所有者即 oncall；warning 日报过目、page 即时推送、响应时限自定；每条 alert 一页 runbook（现象/含义/处置/验证）；Provider 事故 = kill switch + drain（04/07），runbook 只索引。

### 行为证明

7. release gate 增加 **Provider-zero 离线演练**：关 `ImageOnlyAdmissionPolicy` → 断言新 live typed 503、QUEUED 稳定终态、RUNNING 最近安全边界停、`REVIEW_REQUIRED` 不受阻；recovery 由 ticket 10 的 restore drill 覆盖。A1 = 演练记录 + 持续遥测快照；A2 = 独立重放 PG 快照重算 SLO。

### 下游

- ticket 12 阻塞解除（全部上游 resolved）：本票指标/actuator 暴露面与 release-gate 演练归其 API 合同与门禁冻结。
