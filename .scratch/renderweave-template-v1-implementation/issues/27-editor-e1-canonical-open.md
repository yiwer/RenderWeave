# 实现 Editor E1 canonical open、显式 readiness 重检与三模式工作区骨架

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 06, 09, 20（均已 resolved：Template CRUD、Editor J1 架构结论、current-only 依赖投影）

## Question

如何把 T09 已验收的 E1 从 throwaway 状态机物化为真实产品增量：打开 ACTIVE Template 时先读取并验证
trusted canonical current，建立绑定 exact `revision + contentHash` 的 Canonical editor baseline；旧 readiness
一律不可作为本次结论，随后通过显式、可授权、可与 current identity 对齐的权威重检获得新状态；Web 同时形成
Structured / Raw Repair / Compatibility Read-only 三个互斥模式和 Canvas Focus 工作区骨架，但不提前开放尚无
save、preview、recovery 与 dirty guard 的产品 route？

## Answer（本票冻结的实施决定）

1. **读取保持无副作用**：`GET /api/v1/templates/{templateId}` 继续只做授权、current 读取与持久化完整性复核，
   不隐式改变 readiness。新增 authoring 深接口 `TemplateApplication.recheckCurrent(...)` 与显式
   `POST /api/v1/templates/{templateId}/readiness-recheck`；它先按 READ capability 授权，再对最新 ACTIVE current
   重建完整 dependency projection、重算并持久化 `READY | INVALID`，返回绑定该次检查的 templateId、revision、
   contentHash 与 readiness。权限隐藏、DELETED、integrity mismatch、authority/persistence unavailable 均使用 closed
   outcomes；current 漂移只做有界重试，绝不让旧检查覆盖新 revision。
2. **合同修正与 lossless identity**：OpenAPI 同票增加该真实 operation，并把 current response 的 readiness 从错误的
   `READY` 单值修正为既有领域闭集 `READY | INVALID | STALE`；Web SDK 由 source of truth 重新生成。Editor 打开边界
   读取原始 response text，用 lossless JSON 保留 int64 revision 与 decimal token，按
   `renderweave-design-content/1\0 + canonical DesignDSL UTF-8` 重算 SHA-256；任何 envelope、canonical bytes 或
   contentHash 不一致都 fail closed，不能装入 Structured Editor。
3. **打开编排与 generation guard**：客户端顺序执行 canonical current GET → 显式 readiness recheck；重检结果只有
   与 baseline 的 templateId/revision/contentHash 全等时才能替换“权威重检中”。若 current 在两步间前进，丢弃旧
   结果并有界重开；网络/503 保留可信 baseline、显示可重试状态并持续禁止未来 authoritative preview，不把旧
   persisted readiness 冒充结论。
4. **三个互斥内容模式**：exact `renderweave-design/1.0` + `renderweave-expression/1.0` trusted current 进入
   Structured 的只读投影；完整但客户端不理解 exact profile/wire 的内容进入 Compatibility Read-only，只显示安全
   identity/metadata；Raw Repair 仅承载未来 import 的原始 buffer，当前 GET 的 malformed/integrity failure 不能降级
   到 Raw Repair。模式是状态而不是可随意切换的 tabs，E1 不提供 partial reserialize、save 或 migration。
5. **真实但未发布的 Product shell**：按已批准 Canvas Focus 复用锁定 `design.md`：56px chrome，画布居中，左侧
   structure/nodes/assets/definitions/exchange 五入口，右 inspector，底部 dock，顶栏持续展示 Template identity、
   permanent StaticSchema、revision、readiness 与 clean baseline；画布明确标记“浏览器只读投影 · 非权威”。所有
   E1 控件必须有真实行为（导航、节点选择、面板开合、失败重试），尚未实现的 save/preview/import/recovery 按钮
   完全不出现，不用 disabled placeholder 伪装能力。
6. **发布边界**：组件与 API client 是可挂载的产品代码并由 DOM/状态测试执行，但本票不把
   `/templates/:templateId` 接入 `App`。T09 的 J1 决定继续有效：只有后续 E2–E9 所需 canonical dirty/save/
   conflict/confirmation/reconciliation/authoritative preview/recovery/import/a11y 闭环真实存在后，才单独开放产品
   route。prototype routes 仍是 throwaway，不调用本票 API。

## 验证与完成信号

- TDD：先以 Template application/controller contract 测试捕获缺位 recheck operation RED；再以 Web lossless/hash/
  drift/mode tests 与 `TemplateEditorShell` DOM 测试捕获缺位 E1 RED；最后实现到绿。
- 局部：`renderweave-template` focused tests、app Template API focused tests、Web Vitest/typecheck/lint。
- 受影响：OpenAPI generated-diff、`template` → `server`/`web`/`fast` → 完整 `full`；数据库语义继续使用
  Testcontainers PostgreSQL，不使用 H2/SQLite。
- 可访问性：skip link、landmark、可见 focus、键盘完成五入口/节点选择/面板与 retry，状态使用文本+图标并以克制
  polite live region 更新；1024px 以下呈现可键盘/读屏操作的不支持宽度说明。
- 保证上限：Java/Web/contract/gate 为 A1；浏览器产品 route、人工 UI J1、A3 与 Renderer 认证均不在本票。
- 完成：所有 gate 绿色后改为 `resolved / automated_verified`，形成一个 verified local commit 且 worktree clean；
  不 push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。Editor/Renderer/Template v1 仍不 READY。

## Resolution（2026-08-21）

- Template authoring seam 已新增显式 `recheckCurrent`：READ 授权、current 完整性复核、完整 dependency projection、
  revision-guarded `READY | INVALID` 持久化与最多 3 次漂移重试均由 closed outcome 覆盖；GET authoring read
  保持无副作用。审查同时发现旧 readiness authority 的“重试一次”实现实际可无限递归，已以先 RED 的回归测试
  收敛为同一 3 次上限。
- OpenAPI 合同升至 `0.14.0`，增加 `POST /api/v1/templates/{templateId}/readiness-recheck`，修正 current
  readiness 为 `READY | INVALID | STALE`，并由 source of truth 重新生成 Web SDK。
- Web 已实现 raw-text lossless canonical open、domain-separated SHA-256、current identity 漂移丢弃、generation
  guard 与严格错误分类；仅网络错误/503 保留可信 baseline 并显示 readiness unavailable，损坏、隐藏、删除与
  malformed success 均 fail closed。
- Structured / Raw Repair / Compatibility Read-only 三模式与 Canvas Focus Product shell 已形成真实可挂载组件，
  覆盖五入口、节点选择、面板开合、retry、skip link、键盘焦点、live status、reduced motion 与小宽度说明；组件
  仍未接入产品 route，也没有 save/preview/import/recovery 的 disabled placeholder。
- A1 证据：`template` `.sdlc/evidence/20260821-072932-template/`、`fast`
  `.sdlc/evidence/20260821-072956-fast/`、`server` `.sdlc/evidence/20260821-073017-server/`、Node 24 `web`
  `.sdlc/evidence/20260821-074148-web/`；最终 `full` 按不可自指策略只在提交交接中报告。Provider attempts=0，
  未读取 API Key、未发送真实数据、未 push/tag/PR。
- 保证状态为 `automated_verified`：未执行已发布产品 route 的浏览器观察、人工 Editor J1、A3 或 Renderer 认证；
  Editor/Renderer/Template v1 均不声明 READY。
