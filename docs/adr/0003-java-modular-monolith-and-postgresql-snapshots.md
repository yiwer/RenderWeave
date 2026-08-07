# ADR-0003：Java modular monolith、显式 SQL 与 PostgreSQL 完整快照

- 状态：accepted
- 日期：2026-08-07
- 关联：AC-003–AC-007, AC-019, AC-023

## 背景与约束

v1 同时有确定性领域核、引用图事务、异步 AI job 和 Web API，但部署规模是单节点小团队。旧项目的范围漂移表明此时拆微服务或建立通用平台层会放大边界错误。

## 决策

- Java 21 + Spring Boot 4.1.x、Spring MVC/JDBC/virtual threads；不用 WebFlux/R2DBC。
- Maven modules：schema → validation → inference → app assembly，依赖方向由 ArchUnit 验证。
- PostgreSQL 是所有环境唯一数据库语义；测试用 Testcontainers，不用 H2/SQLite。
- Draft revision 和 Static 保存完整 DSL JSONB snapshot；compiled JSON Schema 保存 exact JSON text。
- 引用 edge 是同事务 projection；DSL snapshot 是内容事实源。
- JdbcClient/NamedParameterJdbcTemplate + explicit SQL + Flyway；不用 JPA。
- graph-changing 命令使用固定域 advisory lock；job 使用 PostgreSQL lease/checkpoint，不引入消息中间件。
- Static append-only 由 domain/repository/API 强制；DB 只用普通 PK/unique/FK/check，不增加不可变 trigger。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| Rust server | 性能/安全好 | 领域和 Spring 生态重写成本，团队路径偏离 | 当前核心风险不是 CPU |
| microservices + queue | 独立伸缩 | 事务、运维和一致性复杂度过高 | 单节点 10-user 基线不需要 |
| JPA field tables | CRUD 快 | polymorphic DSL、snapshot/ordering/精确 JSON 映射复杂 | 完整 JSONB + projection 更符合聚合 |
| DB immutability trigger | 物理阻断强 | migration/运维复杂，用户明确不需要 | 应用测试与权限边界足够用于 v1 |

## 后果与验证

- 正向：一次事务可覆盖 graph、revision 和 candidate apply；模块可独立测试。
- 代价：应用必须维护 projection consistency；单节点无 HA。
- 验证：Testcontainers concurrency/fault tests、ArchUnit、Compose canary（A1/A2）。
- 恢复：Flyway/数据库备份和补偿脚本独立于 Git；外部 AI 费用不可由数据库回滚。

