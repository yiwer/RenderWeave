# 实现 definite Group/Frame/Stack/Grid/Rect 容器 scene 的真实 Engine PNG 内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 24, 25, 26, 91, 96（均已 resolved）

## Question

T96 已能绘制 fixed ABSOLUTE Frame/Rect 与矩形 descendant clip，但 `layout_definite_resource_free` 实际已经
权威产生 Group/Stack/Grid 及其 ABSOLUTE/STACK/GRID children 的完整 preorder boxes。怎样复用该 deep module，
把这些确定性容器接入真实 PNG scene，同时不重写第三套布局、不选择尚未物化的 alpha/AA tolerance，也不伪造
Renderer Profile、daemon RESULT 或最终产品 route？

## Answer（本票冻结的实施决定）

1. **唯一 Engine Interface 不变**：入口仍为 `render_png(&AdmittedRenderDocument, dpi)`；Engine 只消费
   `layout_definite_resource_free` 的 immutable preorder entries。Rust 不重算 Stack/Grid/Group arrange；Python
   Engine replay 复用既有独立 `DefiniteLayouter`，不读取 Rust output 或 expected layout boxes。
2. **scene 子闭包**：支持 identity、`visible=true`、`opacity=1` 的 Group/Frame/Stack/Grid/Rect。Group 只承载
   descendants；Frame/Stack/Grid 允许 zero-radius、no-stroke、optional opaque solid fill 与 `clipContent`；Rect
   允许 zero-radius、no-stroke、opaque solid fill。placement 可为当前 definite layout kernel 已实际支持的
   ABSOLUTE/STACK/GRID 与 size-mode 组合。
3. **权威 geometry handoff**：每个 node 只按 occurrenceId/kind 对齐对应 layout entry；Rect 和容器 appearance
   使用最终 LayoutBox，container descendants 已由 layout entry 给出绝对坐标，不从 authored placement 再推导。
   Frame/Stack/Grid 的 ContentBox 只作合同完整性核验，padding/stroke/arrange 语义仍由 layout module 独占。
4. **无 tolerance 的 device lowering**：把有限 IEEE-754 binary64 layout edge 按其 exact bit pattern 还原成有理数，
   与 canonical decimal6 bleed 精确相加后乘 DPI/72；仅原生为整数 device edge 时准入。绝不 round/snap/epsilon/
   tolerance。paint edge 不对齐返回 `NON_PIXEL_ALIGNED_RECT`，clip edge 不对齐返回
   `NON_PIXEL_ALIGNED_CLIP`。
5. **preorder 与全有或全无**：Group 直接递归；Frame/Stack/Grid self fill → descendants → later sibling；
   rectangular clip 与 ancestor/surface 求交。完整 scene 的 paint/clip 全部 prepare 成功后才分配 RGBA8 surface，
   任一后续失败均零 partial output。
6. **共享语料与 TDD**：新增 6×4 exact PNG，单图同时覆盖 outer Grid、GRID child Stack、STACK child Group、
   Group 的 ABSOLUTE Rect、GRID child Frame clip 与 later Grid Rect；新增 subpixel Stack fill negative。Rust/Python
   先共同 RED，目标 11 rendered + 11 unsupported、22/22 cases、70 checks；positive pixel SHA-256 为
   `2a5d0dad5e3232403fe569a3674340b5c168dc80ddabf4546a0fec12b413cd78`，PNG content SHA-256 为
   `0225df717ed6d92c168303a2c677e16e39ceaebad1bb96778d12b9ff79d85073`、202 bytes。
7. **诚实边界**：只报告
   `PREORDER_DEFINITE_IDENTITY_GROUP_FRAME_STACK_GRID_RECT_PIXEL_ALIGNED_OPAQUE_RECTANGULAR_CLIP_PNG_KERNEL_UNWIRED`。
   Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、daemon `UNWIRED`、product
   route `CLOSED`。非 identity transform、alpha/opacity/blend、rounded/stroke/AA、Ellipse/Vector/Text/Image/
   resource/font、compositionViewport、JPEG/LayoutTrace、Java/OpenAPI/Web/E6、formal records、J1/A3/READY 均不在本票。

## 验证与完成信号

- TDD RED：新增 container positive 先稳定为 `SCENE_STRUCTURE`；subpixel Stack fill 同样在 container kind guard
  处失败，而不是目标 `NON_PIXEL_ALIGNED_RECT`。
- TDD GREEN：Rust Engine vectors 2/2；Python independent 22/22 cases、70 checks；workspace Rust tests、
  `fmt --check`、Clippy `-D warnings`、`py_compile`、exact JSON/SHA/unique 与 `git diff --check` 全绿。
- 分级：focused → `render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。
- 最高状态只到 `automated_verified`；provider attempts/API Key reads/付费调用/真实数据保持 0；不 push/tag/PR，
  不开放产品 route，不宣称 Template/Renderer READY。

## Resolution

- TDD RED：新增 combined container 正例在 Rust Engine 稳定返回 `SCENE_STRUCTURE`，Python independent 对同一
  结果 drift；subpixel Stack fill 也在旧 container kind guard 处失败。GREEN 后 Rust focused vectors 2/2、
  Python independent 22/22 cases、70 checks 全部通过。
- 实现保持唯一 `render_png(&AdmittedRenderDocument, dpi)` Interface：Rust 只消费 authoritative definite-layout
  preorder entries，Python replay 复用独立 `DefiniteLayouter`；Group 递归、Frame/Stack/Grid 自身 fill 与矩形 clip、
  Rect paint 均使用最终 LayoutBox。所有 paint/clip 在 surface 分配前 prepare，binary64 edge 与 decimal6 bleed
  以精确有理数降到 device space，不做 snap、round、epsilon 或 tolerance。
- shared Engine-PNG `/1` 最终为 11 rendered + 11 unsupported、22/22 cases、70 checks，vector SHA-256
  `5fd82e654f67158ef54c9835b6a02ceb42916f5c607a48cd836f3cf4275f9c2d`；combined 6×4 PNG content SHA-256
  `0225df717ed6d92c168303a2c677e16e39ceaebad1bb96778d12b9ff79d85073`、pixel SHA-256
  `2a5d0dad5e3232403fe569a3674340b5c168dc80ddabf4546a0fec12b413cd78`、202 bytes。workspace `fmt --check`、
  locked `check`、Clippy all-targets `-D warnings`、workspace tests、`py_compile`、JSON inventory/unique/SHA 与
  `git diff --check` 均通过。
- 分级证据：`render` `.sdlc/evidence/20260824-180804-render/`、affected `fast`
  `.sdlc/evidence/20260824-180832-fast/`、顺序 `server` `.sdlc/evidence/20260824-180851-server/` 与 17-step
  `full` `.sdlc/evidence/20260824-182148-full/` 均 exit 0。`full` 用时 1103.221 秒；App 344 tests、0 failures、
  0 errors、15 controlled skips；Node 24 Web 26 files/212 tests、production build、runtime canary、Playwright
  23 passed + 1 controlled skip、Draft browser journey 与 inference replay E2E 1/1 均通过。状态回填后的
  resolution `fast` `.sdlc/evidence/20260824-184237-fast/` 亦为 exit 0。
- 状态止于 `automated_verified`：Engine 报告
  `PREORDER_DEFINITE_IDENTITY_GROUP_FRAME_STACK_GRID_RECT_PIXEL_ALIGNED_OPAQUE_RECTANGULAR_CLIP_PNG_KERNEL_UNWIRED`，
  Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、daemon `UNWIRED`、product route
  `CLOSED`。`full` 的 P0/R0/R1 均为离线严格重放，provider attempts、API Key reads、provider reservations 与
  费用均为 0；visual diff 仍为 J0，未使用真实数据，未 push/tag/PR，也未把 `/prototype` 作为产品交付。
