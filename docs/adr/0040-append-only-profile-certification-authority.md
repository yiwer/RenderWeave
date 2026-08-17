# ADR-0040：以 append-only 事件维护 Profile Certification authority

- 状态：accepted
- 日期：2026-08-17
- 决策来源：IMAGE_ONLY production admission closed wayfinder（Issues 01–08、14）与 approved delta
- 关联：IOPA-P0-01..05、AC-IOPA-001..008、AC-IOPA-033..034、product-v45、product-v46

## 背景与约束

Inference Profile 是 provider route、模型、Prompt、pipeline、预算与能力合同的不可变语义快照；质量认证则来自
冻结语料、分阶段结果、人工 verdict 与独立复核。把认证状态写回 Profile bytes 会改变 exact identity，也会让失败
周期、撤销历史和运行时目录可见性互相污染。历史 N7/R5/R5P/R5P2 的 assignment、ledger、authorization 与 evidence
已经关闭，不能成为新生产认证的 authority。

product-v45 继续是 `ACTIVE_EXPERIMENTAL` 基线。product-v46 必须作为 hidden certification candidate 创建，且相对
v45 只改变 `profileId`、`maximumTotalCalls=12` 和 `maximumEstimatedCostMicrosCny=6000000`；认证通过前不能进入
普通产品目录。P0 又必须在 Provider-zero 条件下证明整个 authority 骨架，不能借实现工作创建可执行 live 授权。

## 决策

1. **Profile bytes 与认证 authority 分离。** Profile resource 永久不可变；其 canonical SHA-256 与 `profileId`
   共同标识认证对象。认证状态不写入 Profile resource，也不由 readiness 或 catalog visibility 推导。
2. **认证状态只由 append-only events 投影。** cycle 创建、stage 结果、grant 与 revoke 都追加事件；数据库拒绝
   对既有事件执行 UPDATE/DELETE。失败是周期的 terminal state，后续尝试必须使用新的 cycle identity，不能原地
   patch 或重排事件。
3. **每个 cycle 在输出可见前冻结。** `FrozenCertificationCycle` 绑定 exact Profile SHA、seeded 5/20/60 case
   assignment、20 HOLDOUT、阈值、evaluator identity 与 evidence identity。DEV 阶段不可读取 HOLDOUT assignment。
4. **阶段评价沿用封闭门槛。** 5-case 必须 5/5，20-case 至少 18/20，60-case 至少 54/60；只有到达
   `REVIEW_REQUIRED`/`COMPLETED` 且人工接受的 case 才计分。7999bps 仅告警；非合同 casing 失败，kebab-case 只可
   经人工归一化。
5. **v46 预算由 Profile 自身拥有。** 单 run 的 settled + reserved 聚合费用不得超过 ¥6，最多 12 次 provider
   attempt；调用方不能通过传入更宽 cap 扩权。历史 Profile 保持既有兼容语义。
6. **live authorization 仍是逐阶段外部硬门。** 每次授权必须绑定 exact Profile SHA、cycle/manifest/evaluator、
   case hashes、数据分类、route/model、次数、费用和时间窗。每模型 1M tokens 与 48h 只是允许的最大边界，不能替代
   exact scoped J1；P0 只保留不可执行模板，OPEN authorization 数量必须为 0。
7. **P0 preflight 不产生执行许可。** 它只能针对 append-only 投影给出的唯一 next stage 生成 Provider-zero proof，
   且固定 `grantsProviderEgress=false`。P1 在任何真实调用前还必须实现原子 runs/calls/tokens/cost 消费与 CLOSED
   ledger；重复 proof 不能消费额度，也不能打开 Provider egress。

## 备选方案

| 方案 | 优点 | 未选择原因 |
| --- | --- | --- |
| 把 certification 状态写进 Profile JSON | 单文件读取简单 | 改变 exact bytes，混淆合同与外部质量事实，也无法诚实保存撤销历史 |
| 维护可更新的 certification row | 查询直接 | UPDATE 会抹除顺序、失败与撤销证据，难以独立重放 |
| 复用历史 N7/R5 assignment/ledger | 可减少准备工作 | authority epoch、数据与授权均已关闭，不满足 fresh cycle 与 exact J1 |
| 一次宽泛 J1 覆盖 5/20/60 | 操作较少 | 在 case hashes、次数和费用未知时不能精确授权，也破坏阶段失败即停止的边界 |
| 由调用方传入 run cost cap | 更灵活 | 可绕过 Profile-owned ¥6 ceiling，造成同一 Profile 在不同调用点语义漂移 |

## 后果与验证

- 正向后果：Profile identity、质量认证、目录可见性与 live permission 成为四个可分别审计的事实；grant/revoke 和
  negative terminal 可独立重放，历史关闭路线不能静默复活。
- 代价：需要事件表、投影服务、严格 stage manifest 和每阶段授权文件；新的认证尝试必须生成新 identity。
- P0 验证：Java focused tests、Testcontainers PostgreSQL trigger/预算边界和 Python strict-input replay 共同证明
  v46 三字段差异、canonical hash、60-case/20-HOLDOUT assignment、threshold/evaluator identity 与 Provider-zero。
- 保证边界：P0 自动门最多为 `automated_verified`；它不形成 certification grant、人工接受、live J1、发布或
  `ProductionUsable`。P1 的 fresh owner inputs 与 exact per-stage J1 仍是外部阻断点。
