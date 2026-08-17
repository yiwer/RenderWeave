# 实现 Template create/read/save PostgreSQL 纵切

Type: task
Status: open
Blocked by: 03, 04

## Question

如何沿现有 StaticSchema Web/OpenAPI/controller/service/store/PostgreSQL seam 实现第一个完整 Template create/read/save 纵切，使请求 ownerScope 只能来自 Host capability、永久 schema 与 canonical baseline 可验证、accepted save 追加 immutable revision、expectedRevision 冲突失败封闭，并以当时真实的下一 Flyway 版本、Testcontainers PostgreSQL、OpenAPI contract 和 server/full gate 证明端到端行为？只允许为已连通能力创建表、接口和 route，不实现 Editor 页面、Asset、Evaluator 或 Renderer，也不创建 placeholder。
