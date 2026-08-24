# 实现 authored-order 多不透明 Rect 的真实 Engine PNG 输出内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 24, 25, 26, 92, 93（均已 resolved：typed RenderDocument、surface/PNG、layout 与单 Rect Engine kernel）

## Question

T93 已让一个像素对齐、不透明 direct Rect 经过真实 Engine Interface 形成 exact PNG，但正式 Template 产品页所需的
scene 不能停留在单节点，也不能在 paint 过程中遇到后续 unsupported child 时泄漏部分结果。如何在仍不选择 AA、
blend、容器或 Profile 语义的前提下，冻结最小的多节点 paint-order 与全有或全无执行事实？

## Answer（本票冻结的实施决定）

1. **保持同一 deep Interface**：唯一入口仍为
   `render_png(&AdmittedRenderDocument, dpi)`；不新增 public traversal、paint-list、surface 或测试 bypass。
2. **多 direct Rect 子集**：Canvas 可有零个或多个直接 `rect` child；每个 child 都必须满足 T93 的
   visible、opacity=1、ABSOLUTE/FIXED、default identity transform、零圆角、无 stroke、opaque fill 与原生整数
   device edge 条件。任何非 Rect、嵌套、unsupported paint/geometry 或资源继续 fail closed。
3. **先准备、后绘制**：同一 definite layout 的 preorder 必须与 Canvas + authored children 一一对应；Engine 在分配并
   修改最终 pixel buffer 前先按 child 顺序验证并准备全部 `PixelRect`。任一 child 失败则不编码、不返回 output，绝不
   忽略失败 child 或形成 partial result。
4. **authored order 即 paint order**：Canvas background 先覆盖 bleed-inclusive surface；随后按 `children` authored
   order 逐个 hard-clip、row-major 覆写。后出现的不透明 Rect 在重叠像素上覆盖先出现 Rect；本票不引入 z-index、
   blend mode、partial alpha、AA coverage 或一般 compositing。
5. **共同语料与 TDD**：先把现有 two-Rect negative 改为 3×2 overlap positive，使最后一个红 Rect 覆盖先前蓝 Rect
   的一个 pixel，并在 Rust/Python 共同 RED；新增“首 Rect 可绘制、第二 Rect subpixel”negative，证明后续错误仍
   fail closed。目标为 8 rendered + 8 unsupported、16/16 cases、52 checks。
6. **诚实边界**：本票只证明
   `AUTHORED_ORDER_MULTI_PIXEL_ALIGNED_OPAQUE_RECT_PNG_KERNEL_UNWIRED`。process Profile 仍
   `NOT_REGISTERED`、certification `NOT_CERTIFIED`、raster inventory `ABSENT`、daemon `UNWIRED`、product route
   `CLOSED`。容器/clip/transform/AA、Text/Image/resource/font、JPEG/LayoutTrace、daemon RESULT、Java/OpenAPI/
   Web/E6、formal records、物理认证、J1/A3/READY 均不在本票。

## 验证与完成信号

- TDD：shared overlap tracer 先在 Rust/Python 对同一 expected bytes 共同 RED，再分别最小 GREEN；测试只通过 Engine
  Interface 观察 output/error。
- 局部：focused Engine vectors、Python stdlib replay、workspace fmt/check/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → Goal `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。Provider attempts、
  API Key reads、付费调用与真实数据保持 0；不 push/tag/PR，不开放产品 route，不宣称 Template/Renderer READY。

## Resolution

- TDD RED：共享 overlap positive 首先被 Rust Engine 以 `SCENE_STRUCTURE` 拒绝，第二 Rect subpixel negative 也先返回
  `SCENE_STRUCTURE` 而非冻结的 `NON_PIXEL_ALIGNED_RECT`；Rust focused vectors 为 2 failed，Python 独立 tracer 同时
  报 overlap result drift。GREEN 后 Rust focused vectors 2/2、Python independent 16/16 cases、52 checks 全部通过。
- 实现保持唯一 `render_png(&AdmittedRenderDocument, dpi)` Interface：Engine 先验证 definite-layout preorder，再准备
  全部 direct Rect 的 `PixelRect`；只有全部成功才分配 RGBA8 surface，并在 Canvas background 后按 authored order
  hard-clipped paint。后 Rect 对重叠 pixel 的覆写已由 exact PNG bytes/digest 冻结，后续 subpixel child 仍全有或全无
  fail closed。
- shared Engine-PNG `/1` 最终为 8 rendered + 8 unsupported、16/16 cases、52 checks，vector SHA-256
  `a34a2dd5eb9874691cf2e90f2f75f49dc7d0650d6d7fb306a62ec213c51e2d45`。workspace `fmt --check`、
  locked `check`、Clippy all-targets `-D warnings`、workspace tests、`py_compile`、JSON inventory/unique/SHA 与
  `git diff --check` 均通过。
- 分级证据：`render` `.sdlc/evidence/20260824-150832-render/`、affected `fast`
  `.sdlc/evidence/20260824-150858-fast/`、顺序 `server` `.sdlc/evidence/20260824-150916-server/` 与 17-step
  `full` `.sdlc/evidence/20260824-152240-full/` 均 exit 0。`full` 用时 1438.987 秒；App 344 tests、0 failures、
  0 errors、15 controlled skips；Node 24 Web 26 files/212 tests、production build、runtime canary、Playwright
  23 passed + 1 controlled skip、Draft browser journey 与 inference replay E2E 1/1 均通过。状态回填后的 resolution
  `fast` `.sdlc/evidence/20260824-154857-fast/` 亦为 exit 0。
- 状态止于 `automated_verified`：Engine 独立证据仍报告
  `AUTHORED_ORDER_MULTI_PIXEL_ALIGNED_OPAQUE_RECT_PNG_KERNEL_UNWIRED`、Profile `NOT_REGISTERED`、certification
  `NOT_CERTIFIED`、process raster `ABSENT`、daemon `UNWIRED`、product route `CLOSED`。`full` 的 P0/R0/R1 均为
  离线严格重放，provider attempts、API Key reads、provider reservations 与费用均为 0；visual diff 仍为 J0，未使用
  真实数据，未 push/tag/PR，也未把 `/prototype` 作为产品交付。
