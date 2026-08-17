# 冻结 staged rollout、rollback 与 ProductionLiveAuthority

Type: grilling
Status: resolved
Claimed by: Kimi（2026-08-17 会话）
Blocked by: 05 — 冻结新的 IMAGE_ONLY Profile Certification authority；07 — 冻结 Provider 生产路线与 Profile migration 边界；08 — 冻结 RapidOCR 生产拓扑与 capability admission；09 — 冻结 IMAGE_ONLY 生产 SLO、容量与成本预算；10 — 冻结单节点持久化、备份与恢复合同；11 — 冻结 payload-free OperationalTelemetry、告警与值守合同；12 — 冻结 IMAGE_ONLY API 合同与 release gate

## Question

在 sole-finalist Profile Certification、生产 route/topology、SLO、recovery、telemetry 与 release gate 全部成立后，`guarded pilot → limited production → default production` 每阶段应冻结哪些 exact revision/deployment/Profile/Provider contract、输入范围、用户范围、并发、call/token/cost、时间、error budget、negative terminal、A1/A2、visual/business/ops/policy J1 与人工验收条件；`ProductionLiveAuthority` 如何创建、到期、撤销和阻断 Agent/CI/canary 继承？Provider/model/price/terms、Profile、sidecar image、secret、KEK、gateway、database、SLO 或质量漂移时应如何 stop admission、drain、rollback、保留 REVIEW_REQUIRED、恢复旧 deterministic service 与要求重新认证；最终哪些 exact-revision evidence 才允许宣布 `ProductionUsable` 并把 Wayfinder 移交 `$to-spec`？

## Answer

2026-08-17 经 grilling 一轮冻结（全部按所有者确认的推荐）：

### 三阶段结构与各阶段范围/caps（Q1）

1. **guarded pilot**：仅所有者本人；输入=所有者自有 ordinary-design 图片；≤5 run/日、并发≤2；run 级 caps 继承 v46（12 calls/¥6）；阶段总量 ≤100 calls/≤¥50；时长 ≥2 周且 ≥30 run 才可申请 exit gate；authority 期限 60 天。
2. **limited production**：白名单 ≤10 名租户内用户；输入范围不变；≤20 run/日、并发≤2（09 全量负载模型）；预算纳入 09 日 soft ¥30/月 hard ¥500；≥4 周且 ≥100 run；authority 90 天。
3. **default production**：租户内不限名单（gateway 仍是边界）；SLO/容量/预算全量继承 09；authority 180 天，到期续期需新 quad J1。
4. 每阶段 authority 绑定 exact identity：app git SHA、Profile v46 bytes SHA-256、Provider route（DashScope 按量付费 endpoint）、sidecar image digest、capability id——任一项变化 = authority 不匹配 = fail-closed。

### 阶段推进/回退判据与验收组合（Q2）

5. **Exit gate（每阶段）**：(i) SLO 达标（E2E P90≤15min、双轴可用性、失败预算未烧尽）；(ii) 零未关闭 P0/误分类/数据政策事件；(iii) A1 pack（telemetry 快照+audit 完整性）+ A2（独立重放 PG 快照重算 SLO）；(iv) **quad J1**——visual（抽样 ≥10% run 人工复核质量）、business（所有者验收）、ops（告警/runbook/drill 验证）、policy（数据政策合规）；(v) pilot→limited 前完成 restore drill（ticket 10 已冻结首入生产前必做），limited→default 前第二次 restore drill + 第二次 kill-switch 演练。
6. **回退**：任一判据破坏 → 退回上一阶段（authority 降级或撤销重签）；阶段失败 = negative terminal，如实记录不修补（继承 05 精神）。
7. **推进不自动**：quad J1 全部签字后才签发下一阶段 authority；不存在"时间到自动晋级"。

### ProductionLiveAuthority 形态与生命周期（Q3）

8. **形态**：PG append-only 表（与 `ProfileCertificationRecord` 同构），字段：authorityId、stage、app git SHA、profileId + Profile bytes SHA-256、Provider route identity、sidecar image digest + capability id、输入/用户范围、aggregate call/cost caps、生效时间、到期时间、quad J1 引用、撤销事件（只 append 不 UPDATE）。
9. **运行时校验**：每次 live admission 检查 authority 存在 + active + 未过期 + 未撤销 + 全部 exact identity 匹配；任一失败 fail-closed（typed 503 + reason code）。
10. **不继承**（04-32 落地）：Agent、CI、脚本、评测、运维 canary 各需 fresh bounded J1（更低 caps、synthetic 数据分类）；authority 不能用于签发新 authority（无委托链）。
11. **期限**：pilot 60 天、limited 90 天、default 180 天；到期 fail-closed；续期 = 新记录 + 新 quad J1；撤销即时生效（04-13 drain 语义 + audit 事件）。

### Drift 响应矩阵（Q4，全部 typed 事件进 audit）

12. **Provider terms/DPA 变更** → 立即暂停 authority（stop admission），需新所有者风险接受 J1（06 机制）才恢复。
13. **Provider model deprecation/公告变更** → 计划内 drain + 07 Profile migration；**不做自动漂移检测**（07 已冻结，残余风险已接受）。
14. **价格漂移** → 成本复核（07）；突破 09 月 hard ¥500 → 自动关新 run（人工恢复）。
15. **Profile bytes 变动**（immutability 违反）→ readiness fail-closed + 安全事件处置。
16. **sidecar image digest 变化** → capability 探针失败 → OCR 轴 readiness fail-closed；新 image 走 08 新 capability admission，旧 authority 不覆盖。
17. **Provider Key 失效/轮换** → 04-27：先关 egress + drain worker，再换 secret，不得用未授权 live 调用试 Key；KEK 轮换 = re-wrap only；KEK 丢失 = crypto-erasure 等效销毁（10）+ authority 撤销。
18. **gateway/mTLS 失效** → 外部轴 readiness fail-closed（04 双轴）。
19. **DB 异常** → 10 reconciliation + 不复活 sweep；恢复按 RTO≤4h。
20. **SLO/error budget 烧尽** → 09 自动关新 run（人工开关永不自动恢复）。
21. **质量漂移**（LOW_CONFIDENCE/BLOCKER 率连续 7 天 >20%）→ stop admission + 05 事件制重新认证（至少 fresh 5-case canary）。
22. **所有 stop 场景统一 04-13 drain 语义**：新 live typed 503；QUEUED 稳定终态；RUNNING 最近安全边界停；`REVIEW_REQUIRED` 保留可 review/apply；deterministic replay 不受影响。

### ProductionUsable 宣布条件与 `$to-spec` handoff（Q5）

23. **宣布条件**：default production 稳定运行 ≥4 周且 ≥100 run；全部 exit criteria 持续绿色；零未关闭 P0/政策事件。
24. **exact-revision evidence pack**：(i) v46 `ProfileCertificationRecord` + 认证 evidence；(ii) 三阶段 authority 记录 + 各阶段 A1/A2 pack；(iii) 12 的完整 release gate 全绿记录（contract tests、SDK regen、security headers、DB migration/recovery、容量扩展、kill-switch 演练）；(iv) 两次 restore drill 记录；(v) telemetry PG 快照 + A2 独立重算；(vi) quad J1 最终签字。
25. **宣布是人类行为**：所有者持 evidence pack 宣布 `ProductionUsable`；agent 只能汇编 pack，不能宣布。
26. **handoff 包**：Blueprint 归一化文档（13 票决策汇总 + 跨票冲突检查）+ evidence 索引 + 各票链接 → `$to-spec` 接手实施规划；本地图到此闭环。

### 下游

- 全部 13 张决策票 resolved；最后一片 fog（Blueprint 归一化结构 + 跨票冲突检查 + handoff 包形态）毕业为新票。
