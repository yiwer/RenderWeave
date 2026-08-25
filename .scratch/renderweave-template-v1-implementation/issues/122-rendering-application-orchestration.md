# 实现 Rendering 应用编排与结果释放边界

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 07, 16, 18, 19, 21, 22（本切片所需合同与 Java/Engine seam 均已物化）

## Question

Evaluator、RenderEngine process port 与真实 RenderOutput 值已存在，但生产代码仍没有一个调用方可用的深接口来
原子执行 Host 授权、Certified Profile 可用性、一次 Evaluation、同 Command 恢复以及结果释放前权限重检。
如何补齐这段正式产品业务核心，同时不注册 partial/test-only Renderer Profile、不开放占位 HTTP route，也不把
Engine request identity 或 RenderDocument 泄漏给产品调用方？

## Answer（本票冻结的实施决定）

1. **单一产品应用接口**：在 `rendering.api` 物化 `RenderingApplication.render(...)`，输入只含服务端创建的
   invocation、根 Template、原始 RenderInput、bounded output selection 与受信任 purpose（正式输出或
   Authoritative Preview）；closed outcome 始终携带独立 public `renderOperationId`，绝不暴露 Engine requestId、
   Command、RenderDocument、digest、lease 或内部 Profile 选择细节。
2. **Rendering-owned Host facet**：新增窄 `RenderingAuthority` SPI。正式输出要求 `template.render`；权威预览要求
   `template.read + template.render`。授权成功只返回 trusted ownerScope、一次性 recheck identity 与 disclosure；
   hidden/forbidden/unavailable 保持 closed，调用方不能自报 ownerScope 或 capability。
3. **Profile availability 先于 payload work**：新增 `RendererProfileAuthority` SPI，以 output selection 选择 exact
   Layout/Renderer compatibility。没有 Certified/available mapping 时返回 `RendererUnavailable`，且 Evaluator、
   capability runtime、Asset resolver 与 Engine 调用次数都必须为零。
4. **一个绝对 deadline**：public admission 时一次性展开冻结的 60 秒 absolute deadline，并把 exact
   rendererProfile/deadline 传入 `EvaluationCommand`。Evaluator 不再自行延长 deadline，Asset lease audience 与
   Renderer Command 使用同一 selected Profile 和同一 deadline。
5. **一次 Evaluation、同一 Command 恢复**：每个 operation 只创建一次独立 UUID v4 Engine requestId、只调用一次
   Evaluator、只构造一次 immutable `RendererCommand`。`Unknown` 以及 nonterminal `RENDER_ENGINE_BUSY` 仅在原 deadline
   内重发同一 Command；不重新授权、seal、freeze closure、resolve Asset、重建 CapabilityState 或延长 deadline。
6. **释放前权威重检**：Evaluator rejection、Engine terminal problem、Renderer unavailable 与完整 RenderOutput 在对外
   返回前都消费同一 recheck identity。最新 hidden/forbidden/unavailable 覆盖并丢弃内部结果；Engine output 的
   request/output/Profile metadata 漂移折叠为有界 `RENDER_INTERNAL_ERROR`。
7. **生产失败封闭装配**：app 提供 fail-closed Host facet 和 Profile authority；显式 single-owner 开发配置可授权
   `template.render`，但没有另行注入 Certified Profile authority + Engine 时仍不进入 Evaluation。该装配是真实后续
   public Rendering controller 的业务服务，不创建测试专用 Profile、synthetic success 或 placeholder route。
8. **诚实边界**：本票不开放 public HTTP/OpenAPI/Web/E6、cancel/trace、daemon success、Profile registration/
   certification、native build、JPEG/Text/剩余 raster、正式 `/templates` route、J1/A3/READY 或外部副作用；
   `/prototype` 不计最终交付。

## TDD 与验证

- 先新增 Rendering application contract tests，使缺失 public interface/authority/profile/orchestrator 共同 RED；测试覆盖
  授权矩阵、availability-before-evaluation、deadline/Profile 贯穿、public/Engine identity 分离、Evaluator exactly
  once、Unknown/BUSY same-object resend、closed outcome 映射、释放前 auth drift 丢弃与默认 app fail-closed。
- GREEN 后执行 focused rendering/app tests、architecture/public-surface 回归与 `git diff --check`；随后按
  `fast → server → full → resolution fast` 分级扩大，Maven 串行、精确 staging，不 push/tag/PR。
- 最高只报 `automated_verified`；最终产品交付仍要求 public Rendering API、E6、正式 `/templates` 页面、完整
  Certified Renderer/Profile、浏览器验收与剩余发布门控。

## Results

- 已物化生产 `RenderingApplication`、Rendering-owned `RenderingAuthority` 与 `RendererProfileAuthority`，并由
  `CanonicalRenderingApplication` 原子编排 Host authorization、Profile availability-before-payload、一次
  Evaluation、同一 immutable Command 的 Unknown/BUSY 恢复，以及每个可释放结果前的一次 authority recheck。
- public operation 与 Engine request 使用独立 UUID v4；60 秒 absolute deadline 与 exact Layout/Renderer Profile
  从 application admission 贯穿 Evaluator、Asset lease audience 与 Renderer Command。默认 app 装配在无 Certified
  Profile/Engine 时于 payload work 前失败封闭，不创建 synthetic success 或 partial/test-only Profile。
- TDD 已覆盖授权矩阵、identity/deadline/Profile 贯穿、Evaluator exactly-once、same-object resend、metadata drift、
  disclosure 红线与 release-time authority drift；focused Rendering 10 项、app adapter/configuration 11 项、Rendering
  module 119 项及完整 app reactor 回归均通过。
- A1 evidence：affected `fast` `.sdlc/evidence/20260826-010326-fast/`（3/3 steps，23.363 秒）、sequential
  `server` `.sdlc/evidence/20260826-010400-server/`（987.113 秒）与 17-step Goal `full`
  `.sdlc/evidence/20260826-012058-full/`（17/17，1520.040 秒）均 passed。full 覆盖 Windows/Linux Renderer、8 个
  Maven modules、Node 24 Web 28 files/217 tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft 与
  inference browser journeys；provider attempts/API Key reads/reservations/cost/open authorization=0。状态回填后的
  resolution `fast` `.sdlc/evidence/20260826-014841-fast/` 亦以 3/3 steps 通过。
- 状态为 `resolved / automated_verified`。Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  `ABSENT`、daemon success/public Rendering API/E6/正式 `/templates` route `CLOSED`、native stack
  `BUILD_NOT_AUTHORIZED`；最终产品页面与功能仍由后续 DAG 完成，`/prototype` 不计交付。
