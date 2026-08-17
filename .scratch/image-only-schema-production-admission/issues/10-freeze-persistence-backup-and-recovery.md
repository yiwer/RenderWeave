# 冻结单节点持久化、备份与恢复合同

Type: grilling
Status: resolved
Claimed by: Kimi（2026-08-17 会话）
Blocked by: 09 — 冻结 IMAGE_ONLY 生产 SLO、容量与成本预算

## Question

在 7 天 `Payload Expiry`、per-artifact envelope encryption、24 小时删除 SLO 与生产容量预算已冻结后，PostgreSQL、Encrypted Inference Artifact、wrapped DEK、KEK、audit chain、Candidate 与 Draft Bundle 的单节点持久化边界应如何定义：在线及备份加密、备份频率/保留/访问、RPO/RTO、同一维护窗口的一致性点、restore 顺序、deletion tombstone 与过期 payload 在备份恢复后的重新清除、KEK 丢失/轮换、missing/corrupt artifact、storage-full、部分删除、数据库与 Blob 漂移的 fail-closed 语义分别是什么？应要求哪些 backup/restore、storage-failure、artifact-integrity 与 crypto-erasure drill 形成 A1/A2 和 ops J1，且恢复不得复活已删除 payload、旧 confirmation、过期 ProductionLiveAuthority 或 CLOSED J1。

## Answer

2026-08-17 经 grilling 冻结（全部按所有者确认的推荐）：

### 备份

1. **形态/频率/保留**：每日一次 `pg_dump` + Blob 目录 tar 快照至外挂卷/异盘，滚动保留 **7 天**（与 payload 7 天生命周期对齐）；访问仅所有者。WAL 连续归档不做（单节点过度工程）。
2. **过渡期硬要求**：备份只在信封加密（04-22）落地后启用——生产准入本身以加密实现为前置；若任何过渡期必须备份，归档级整体加密 + 密钥与 KEK 分离单独保管。
3. **RPO ≤ 24h / RTO ≤ 4h**（人工 runbook）。认证记录等关键行另有 git/evidence 双写兜底。

### 恢复合同

4. **Restore 顺序**（runbook 阻塞步骤，逐步放行）：PG restore → Blob restore → reconciliation sweep → 不复活 sweep → 校验通过后才开放流量。
5. **一致性**：PG 为权威。Blob 孤儿对象（无 PG 引用）直接删除；PG 有引用但 Blob 缺失或 hash 校验 corrupt 的 artifact 标记 missing/corrupt，关联 run 入稳定终态、禁止 apply——fail-closed，不猜内容。
6. **不复活清单**：开放流量前必须完成——重放 deletion tombstone（旧备份里已删 payload 的 bytes 立即重删）；过期 payload 立即清除；过期 confirmation/ProductionLiveAuthority、CLOSED J1、已关闭的 kill switch 状态不得因备份时间差复活（04-13 扩展到 restore 场景）。
7. **KEK**：丢失 = 全部加密 artifact 不可读 = 等效 crypto-erasure，受影响 run fail-closed 不抢救；所有者须持有 KEK 的离线副本（防单点），但 KEK 不进任何备份/日志（04-22）。轮换 = 只 re-wrap wrapped DEK（不解密 artifact 本体），在线分批。
8. **storage-full / 部分删除**：继承 ticket 09 水位线（磁盘 hard 85% 拒新上传、删除 backlog >24h fail-closed）。

### Drill 与证据

9. **首次生产准入前必须完成一次完整 restore drill**（ProductionUsable 的 recovery proof 实体）；之后每 release 或每季度一次。内容：隔离环境恢复 → reconciliation → 不复活 sweep → crypto-erasure 验证（已删 payload 恢复后不可读）。A1 = drill 记录（耗时/校验结果）；A2 = 独立 hash 对账与不可读性验证；ops J1 = 所有者确认 drill 报告。

### 下游

- ticket 11 阻塞解除；本票的 reconciliation sweep、不复活 sweep 与 drill 程序归最终 Blueprint 执行阶段实现。
