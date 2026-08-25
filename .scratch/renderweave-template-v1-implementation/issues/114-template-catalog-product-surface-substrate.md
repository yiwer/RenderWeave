# 实现 Template 目录与正式产品页接线基座

Type: task
Status: resolved
Claimed by: Codex `/root`（single-writer）
Blocked by: 06, 27, 28, 29, 30, 31, 32, 35, 36, 37（均已 resolved）

## Question

现有 Template create/current/save/readiness API 与 E1–E5/E7–E9 Product Editor 组件已经真实存在，但没有
same-ownerScope Template 目录，也没有正式产品页的真实 create/open 接线基座；同时 E6 Authoritative Preview
仍被完整 `renderweave-renderer/1.0` Profile、物理认证与公共 Rendering seam 阻塞。如何补齐可复用的真实目录、
创建页与 editor page wrapper，使最终产品激活时不依赖 fixture、prototype 或假数据，同时继续遵守“E6 完成前
不开放 `/templates/:templateId`”的冻结门槛？

## Answer（本票冻结的实施决定）

1. **Template-owned catalog deep Interface**：在既有 `TemplateApplication` / `OwnerScopeAuthority` /
   `TemplatePersistence` seam 内新增只读 catalog operation。它独立要求 `template.read`，只返回 trusted
   ownerScope 内 ACTIVE current 的 `templateId / displayName / permanent StaticSchemaRef / revision / readiness /
   updatedAt`，不返回 DesignDSL、ownerScope、dependency、Asset、child Template 或 renderer 内部事实。
2. **稳定有界游标**：目录按 `updatedAt DESC, templateId ASC` 排序；`search` 只匹配 displayName 或 templateId，
   trim 后最多 200 Unicode code units；`limit` 为 1..50，默认 20。opaque base64url cursor 只承载上一项 exact
   `updatedAt + templateId`，malformed cursor 以 400 零写失败；查询最多读取 `limit + 1` 项并返回 optional
   `nextCursor`。不在本票开放 DELETED/history/copy/delete/restore。
3. **真实 HTTP/OpenAPI 纵切**：在已有 `GET/POST /api/v1/templates` 的同一路径补充 GET catalog；授权拒绝为
   403，authority/persistence unavailable 为 503。OpenAPI source、生成 Web SDK、Java contract/API/
   Testcontainers PostgreSQL 测试同票更新，不新增 migration 或表。
4. **最终产品组件基座**：新增使用真实 SDK/API 的 `TemplateListPage`、`TemplateCreatePage` 与
   `TemplateEditorPage` wrapper。列表支持搜索、稳定 cursor 继续加载、empty/loading/error；创建页从真实
   StaticSchema 目录选择永久 Schema，生成 exact v1 minimal Canvas DesignDSL 并调用真实 create，成功后导航
   exact Template editor URL；editor wrapper 只把 route `templateId` 交给已有 `TemplateEditorSurface`。
5. **发布门保持关闭**：本票不把上述组件加入 `App` routes 或全局导航；只有 E6 真实 Authoritative Preview、
   public Rendering API、完整失败撤图与浏览器验收闭环后，后继激活票才一次性开放 `/templates`、
   `/templates/new`、`/templates/:templateId`。不出现 disabled preview、fake raster、fixture catalog、
   `/prototype` 复用或占位 route。
6. **TDD 与 UI 纪律**：先以 Template application/persistence/controller/OpenAPI/Web component tests 捕获缺位
   catalog、create transport 与 route wrapper RED，再最小实现到 GREEN。页面严格复用根 `design.md`、
   `ResourceFrame`、现有 controls/tokens 与 Template Canvas Focus shell；键盘、focus、live status、reduced motion、
   1024/1280/1440 与 200% zoom 约束继续保留。

## 验证与完成信号

- focused TDD：Template module contract/public-surface、PostgreSQL persistence/API、OpenAPI generated diff、Node 24
  Vitest/typecheck/lint/build；非法 cursor/search/limit、无 READ、跨 scope、空目录与多页稳定性均有回归。
- 分级：focused/local → `template`（如受影响）→ sequential `server` → `web` → `fast` → Goal `full`；Maven gate
  不并发，`git diff --check` 绿色。
- 最高只报 `automated_verified`；正式 routes、E6、Renderer Profile registration/certification、native
  Skia/FreeType/HarfBuzz build、physical Linux、J1/A3/READY 与外部副作用不在本票。provider/API Key/费用/
  真实数据=0，不 push/tag/PR，`/prototype` 不计最终交付。

## 实施结果

- Template application/SPI 已提供独立 catalog deep operation：先校验 `template.read` 与 trusted ownerScope，
  只列出同 scope 的 ACTIVE current 摘要，并以 `updatedAt DESC, templateId ASC` 和 opaque cursor 稳定分页；
  search、limit、cursor 均在 application boundary 做有界校验。
- PostgreSQL adapter 使用 `limit + 1` keyset query，displayName/templateId 搜索与 ownerScope/ACTIVE 条件均下推；
  controller、OpenAPI 0.16.0 和生成 Web SDK 已接通 `GET /api/v1/templates`，403/400/503 边界有回归覆盖。
- 新增真实 `TemplateListPage`、`TemplateCreatePage`、`TemplateEditorPage`：目录支持搜索、继续加载和完整状态；
  创建页读取真实 StaticSchema catalog、提交 exact minimal Canvas DSL 并导航 exact editor URL；editor wrapper 复用
  既有 `TemplateEditorSurface`。这些组件未接入 `App`，没有 fixture、disabled preview、假 raster 或 prototype 复用。
- TDD 揭示并修正了错误归因：损坏的持久化摘要不再被 catalog cursor 的解析 catch 误报为 400，而是按
  persistence/catalog unavailable 映射为 503；malformed client cursor 仍保持 400 且零写入。

## 证据与处置

- focused Template contract/public、application/authority/PostgreSQL/API 与 Web component tests 均绿；Node 24
  typecheck/lint 绿色，`template` gate 为 `.sdlc/evidence/20260825-161820-template/`。
- 分级 A1 gate：`server` `.sdlc/evidence/20260825-161854-server/`、`web`
  `.sdlc/evidence/20260825-163341-web/`、`fast` `.sdlc/evidence/20260825-163427-fast/` 与 17-step Goal `full`
  `.sdlc/evidence/20260825-163448-full/` 均 exit 0；full 中 Web 217/217、Playwright 23 passed + 1 controlled skip，
  provider attempts/API Key reads/reservations/cost 均为 0。
- 状态与证据回填后的 resolution `fast` `.sdlc/evidence/20260825-170428-fast/` 也以 3/3 steps exit 0。
- 本票状态为 `resolved / automated_verified`。完整 Renderer Profile 仍 `NOT_REGISTERED`、certification
  `NOT_CERTIFIED`、process raster `ABSENT`、正式产品 route `CLOSED`；未推进 J1/A3/READY，未 push/tag/PR。
