# 实现 Public Rendering HTTP 交付纵切

Type: task
Status: resolved / automated_verified
Claimed by: —
Blocked by: 16, 18, 19, 21, 22, 122（本切片所需合同与 Java application/process seam 已物化）

## Question

正式 `RenderingApplication` 已能执行 Host 授权、Certified Profile availability、一次 Evaluation、同 Command
恢复和结果释放前重检，但产品调用方仍没有真实 HTTP/OpenAPI seam；现有 Engine result metadata 又在 process
Adapter 内被压缩成图片、尺寸和 output selection，无法按冻结合同向公共调用方交付完整 length/digest/profile
headers。如何补齐正式 Render 与 Authoritative Preview 的公共交付，同时不注册 partial Profile、不伪造图片、
不泄漏 Engine requestId/RenderDocument，也不提前开放 `/templates` 产品导航？

## Answer（本票冻结的实施决定）

1. **两个受信任入口**：`POST /api/v1/templates/{templateId}/render` 固定选择 `FORMAL_OUTPUT`，
   `POST /api/v1/templates/{templateId}/authoritative-preview` 固定选择 `AUTHORITATIVE_PREVIEW`；purpose 不进入
   body/query，调用者不能借请求字段扩大 capability。两者均消费原始 strict RenderInput UTF-8 JSON bytes，
   output 只由 bounded `format=PNG|JPEG`、`dpi` 与 JPEG-only `quality` 选择。
2. **传输准入**：请求 media type 固定为
   `application/vnd.renderweave.render-input+json;version=1.0`；Content-Encoding 只接纳 absent/`identity`；
   entity 在进入 application 前按 8 MiB 上限有界读取。省略 DPI 展开 96，省略 JPEG quality 展开 90；PNG
   携带 quality、未知 format、越界整数、非法 Template identity 或空 body 均以稳定请求 problem 失败且不调用
   `RenderingApplication`。
3. **完整安全 metadata**：深化 `RenderOutput` 为 immutable、self-validating 的完整公共成功值，保留
   result/Renderer/DSL/Layout/Output Profile、format/media type、dimensions、DPI、JPEG quality、byte length 与
   raw SHA-256。process Adapter 必须把已 strict 解析并与 body 核验的 metadata 全量交接；application 再与选中的
   exact Profile、sealed layout 与原 output selection 对照，不一致折叠为 `RENDER_INTERNAL_ERROR`。
4. **原子成功交付**：只有 `Rendered` 才返回 raw `image/png|image/jpeg`；固定写出 `Content-Length`、标准
   `Content-Digest` 和 RenderWeave result/profile/format/dimension headers。冻结 Ticket 16 的
   `RenderWeave-Request-Id` 在公共 seam 上承载 public `renderOperationId`，绝不暴露 Engine requestId。
5. **closed failure**：NotFound/Forbidden/authority unavailable/Profile unavailable 与全部 RenderingProblem 映射为
   `application/vnd.renderweave.render-problem+json;version=1.0`，携带 stable code、closed stage/location/limit 与
   public `renderOperationId`；任何失败均零图片、零 success metadata。HTTP status 只映射 transport category，
   客户端以 code 为语义权威。
6. **合同同步**：OpenAPI 升版并生成 Web SDK；本票不实现 preview UI/cancel/LayoutTrace，不挂载 `/templates` route，
   不注册/认证 Renderer Profile，不调用 T108 daemon success kernel，不运行未授权 native build，也不制造 fixture、
   synthetic raster、placeholder 或旧图 fallback。

## TDD 与验证

- 先新增 standalone MockMvc controller contract tests与 `RenderOutput`/application metadata drift tests，覆盖两个 route
  的 purpose 隔离、默认参数、PNG/JPEG success headers、body digest、public/Engine identity隔离、8 MiB/encoding/
  media/parameter admission、closed outcome/status/problem 映射与零 application call；旧代码因 controller/metadata
  seam 缺失必须 RED。
- GREEN 后执行 focused rendering/app tests、OpenAPI generation/typecheck 与 `git diff --check`；随后依次
  `fast → server → full → resolution fast`。Maven串行、精确 staging，既有 Image-Only dirty work不进入提交；
  最高只报 `automated_verified`。

## Results

- 已物化两个 server-selected public seam：正式 Render 固定 `FORMAL_OUTPUT`，Authoritative Preview 固定
  `AUTHORITATIVE_PREVIEW`；strict media/identity encoding、8 MiB entity、PNG/JPEG、DPI/JPEG quality admission
  均在 application 调用前 fail closed，OpenAPI 升为 0.17.0 并生成 Web SDK。
- `RenderOutput` 现为 immutable self-validating 完整成功值；process Adapter 保留 result/Renderer/DSL/Layout/Output
  Profile、media、尺寸、DPI/quality、length 与 SHA-256，application 对 selected Profile/layout/output 再核验。
  只有完整 `Rendered` 返回 raw image 与固定 digest/profile headers；公共 request header 只携带
  `renderOperationId`，全部 closed failure 零图片、零 success metadata。
- TDD 先在旧 `RenderOutput` 合同上得到有效 test-compile RED；GREEN 后 Rendering focused 12 tests、App focused
  20 tests、Rendering 全量 121 tests、OpenAPI clean generation、Node 24 typecheck 与 `git diff --check` 均通过。
- clean snapshot A1 gates：`fast` `.sdlc/evidence/20260826-064107-fast/`（3/3，34.571 秒）、顺序 `server`
  `.sdlc/evidence/20260826-064151-server/`（982.743 秒）与 17-step Goal `full`
  `.sdlc/evidence/20260826-065824-full/`（17/17，1588.628 秒）全部 passed。`full` 覆盖 App 367 tests/
  0 failures/0 errors/15 skipped、Node 24 Web 28 files/217 tests、runtime canary、Playwright 23 passed + 1
  controlled skip、Draft 与 inference browser journeys；R0/R1/P0 provider attempts=0。
- 状态为 `resolved / automated_verified`。公共 Rendering HTTP seam 已开放，renderer gate 的全局
  `publicRenderRouteAdded` identity 已同步；但 Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process
  raster `ABSENT`、daemon success output path `UNWIRED`、native stack `BUILD_NOT_AUTHORIZED`。E6 Web preview、正式
  `/templates` product route、J1/A3/READY 均未由本票推进，也没有调用 provider、读取 API Key 或制造 fallback 图片。
- 最终状态更新后的 affected `render` `.sdlc/evidence/20260826-072836-render/`（2/2，86.570 秒）确认
  `publicRenderRouteAdded=true` 且上述关闭边界不变；resolution `fast`
  `.sdlc/evidence/20260826-073019-fast/`（3/3，27.707 秒）通过。
