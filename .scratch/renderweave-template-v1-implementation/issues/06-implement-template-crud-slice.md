# 实现 Template create/read/save PostgreSQL 纵切

Type: task
Status: resolved
Claimed by: Codex `/root`
Blocked by: 03, 04

## Question

如何沿现有 StaticSchema Web/OpenAPI/controller/service/store/PostgreSQL seam 实现第一个完整 Template create/read/save 纵切，使请求 ownerScope 只能来自 Host capability、永久 schema 与 canonical baseline 可验证、accepted save 追加 immutable revision、expectedRevision 冲突失败封闭，并以当时真实的下一 Flyway 版本、Testcontainers PostgreSQL、OpenAPI contract 和 server/full gate 证明端到端行为？只允许为已连通能力创建表、接口和 route，不实现 Editor 页面、Asset、Evaluator 或 Renderer，也不创建 placeholder。

## Answer

T06 沿 ADR-0042 冻结 seam 物化首个真实 Template create/current-read/save PostgreSQL 纵切；T05 与 T07-T09 保持
open，本票未创建 Asset/Evaluator/Renderer/Editor surface 或 placeholder。

`renderweave-template` 新增 authoring public Interface `TemplateApplication`，只暴露 `create/getCurrent/save`
三个 closed 方法：Create 返回 `CreatedReadable|CreatedOpaque|CreateDesignRejected|
CreateStaticSchemaNotFound|CreateForbidden|CreateAuthorityUnavailable|CreatePersistenceUnavailable`；
Current 与 Save 各自以 `NotFound|Deleted|IntegrityMismatch|AuthorityUnavailable|PersistenceUnavailable`
fail closed，Save 另含携带 `OptionalLong currentRevision` 的 `SaveRevisionConflict`。`CreateCommand`/
`SaveCommand` 对 raw DesignDSL bytes 防御性拷贝，`SaveCommand.expectedRevision` 必须非负且有后继。internal
`CanonicalTemplateApplication` 经 `DesignDslAuthority` 取 canonical bytes 与 content hash，不复制
parser/canonicalizer，也不接受调用者自报 hash。

ADR-0041 窄 assembly seam 同票落地：`TemplateModule` 是 app 可 import 的唯一 Template `.internal` 类型，只
公开静态 `application(...)` factory，注入 app-owned `OwnerScopeAuthority`/`TemplatePersistence` Adapter 与
Schema authority；architecture/public-surface 测试正向锁定该 exact 类型并用 synthetic-negative guard 拒绝
其他 `.internal` import。`renderweave-schema` 新增 provider-owned `StaticSchemaAuthority` Interface；
`renderweave-app` 以 `PostgresTemplatePersistence`、`PostgresStaticSchemaAuthority`、
`FailClosedOwnerScopeAuthority`（生产默认）与 dev/test 配置固定的 `ConfiguredSingleOwnerScopeAuthority`
实现 consumer seam；请求 ownerScope 不可自报。

V018（forward-only）建 `template_aggregate`（owner_scope、schema FK → static_schema、只读
`readiness IN ('READY')`）与 `template_revision`（JSONB trusted read + canonical BYTEA 1..16 MiB +
`content_hash ~ '^sha256:[0-9a-f]{64}$'`）；`current_revision` 经 DEFERRABLE FK 保证零部分写入，任何
revision 无 UPDATE/DELETE/重编译路径。HTTP 面为 `/api/v1/templates` 的 POST（
`application/vnd.renderweave.design+json` + schemaKey/versionTag）→ 201、GET/{id} → current、
PUT/{id}?expectedRevision → append/409；错误统一 problem+json（如 `TEMPLATE_REVISION_CONFLICT`、
`TEMPLATE_FORBIDDEN`）。OpenAPI 升 0.10.0、Web SDK 已重新生成，SystemStatus contractVersion 同步 0.10.0，
`EnvironmentCanaryTest` 断言 18 个 applied migrations。

验证：Template module contract/public-surface/architecture、app API（raw DesignDSL body、媒体类型拒绝、
ownerScope 不可输入、expectedRevision 冲突）与 persistence（same-hash 追加、并发只一胜、零部分写入、
terminal DELETED 保留历史）测试全部运行于 Testcontainers PostgreSQL；`template` composite 与 `server`/
`web` 通过；完整 `full` 15/15 通过（evidence `.sdlc/evidence/20260819-111831-full/`），draft-browser-e2e
与 inference-browser-e2e 旅程及进程清理均通过。同票把 `tools/run-draft-e2e.ps1` 清理加固为 CIM 瞬断可
重试、失败只降级为 owned-handle 清理并如实写入 metadata，不再让 finally 异常吞掉旅程结果。

本票只形成自动证据（A1；kernel/registry exact replay 在原边界仍为 A2），无 J1/A3；未注册 DesignDSL
Profile available，无 Editor/Asset/Evaluator/Renderer 产品 surface。Ticket 19 open，Template v1 不 READY。
下一个 unblocked frontier 是 T05；T07-T09 继续按其 Blocked by 解锁。
