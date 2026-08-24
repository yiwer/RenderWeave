# 实现 fixed identity Frame/Rect 先序真实 Engine PNG 内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 24, 25, 26, 49, 50, 92, 93, 94（均已 resolved）

## Question

T94 已能把多个 direct Rect 变成 exact PNG，但正式 Template 产品预览不能只支持 Canvas 平铺叶子；同时当前
`renderweave-renderer/1.0` Profile 冻结的是资源、颜色、字体、raster、sampling、blend、QR/Barcode 等完整合同，
现有子集不能诚实注册 Profile。怎样在不引入 AA、blend、一般 transform、clip 或伪 Profile 的前提下，把首个真实
容器 scene 纵切接进同一 Engine Interface？

## Answer（本票冻结的实施决定）

1. **保持同一 deep Interface**：唯一入口仍为
   `render_png(&AdmittedRenderDocument, dpi)`；不新增 public paint-list、测试 bypass、daemon success 或产品 route。
2. **递归 closed scene**：Canvas/Frame 的 authored children 只允许 `rect | frame`。Rect 继续沿用 T94 的
   visible、opacity=1、ABSOLUTE/FIXED、identity transform、零圆角、无 stroke、opaque fill 与 native integer
   device-edge 条件。
3. **Frame 精确子集**：Frame 必须 visible、opacity=1、ABSOLUTE/FIXED、identity transform、零圆角、无 stroke、
   `clipContent=false`；允许 closed 四边 nonnegative padding 和 optional opaque fill。Frame fill 在 descendants 前绘制，
   children 的 world origin 由 parent ContentBox + authored x/y 精确累加；不支持的 Frame paint/clip 返回
   `FRAME_PAINT`，不 silent fallback。
4. **layout/decimal 双锁**：Rust 逐 occurrence 对齐 definite-layout preorder、kind、occurrenceId、fixed LayoutBox 与
   Frame ContentBox；raster edge 使用 canonical decimal6 的 parent/padding/child 精确和，不拿 binary64 layout 结果做
   pixel snap 或 rounding。
5. **先准备、后绘制**：全部 Frame/Rect 先转换成先序 `PixelRect` paint list；任何 nested child 失败时最终 RGBA8
   buffer 尚未分配/修改。成功后才填 Canvas background，并按 Canvas child → Frame fill → descendants → later sibling
   顺序 hard-clip 到 surface 后覆写。
6. **共享语料与 TDD**：新增 5×3 exact PNG 正例，冻结 direct blue Rect、green Frame fill、padding 位移后的 red child、
   later yellow sibling 的最终像素；新增 nested subpixel child 与 `clipContent=true` negatives。目标 9 rendered +
   10 unsupported、19/19 cases、61 checks。
7. **诚实边界**：只报告
   `PREORDER_FIXED_IDENTITY_FRAME_RECT_PIXEL_ALIGNED_OPAQUE_PNG_KERNEL_UNWIRED`。Renderer Profile 仍
   `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、daemon `UNWIRED`、product route
   `CLOSED`。一般 clip/transform/opacity/stroke/AA/blend、Stack/Grid/Group、Text/Image/resource/font、JPEG、
   LayoutTrace、Java/OpenAPI/Web/E6、正式记录、物理认证、J1/A3/READY 均不在本票。

## 验证与完成信号

- TDD RED：Rust rendered case 以 `SCENE_STRUCTURE` 拒绝 Frame，nested subpixel negative 同样误报
  `SCENE_STRUCTURE`；Python independent 在共享 Frame 正例 drift。
- TDD GREEN：Rust Engine vectors 2/2；Python independent 19/19 cases、61 checks；workspace Rust tests 与
  pycompile 绿色。
- 分级：`render`、affected `fast`、sequential `server`、Goal `full` 与状态回填后的 resolution `fast` 均已通过。
- Provider attempts、API Key reads、付费调用和真实数据保持 0；不 push/tag/PR，不开放产品 route，不宣称
  Template/Renderer READY。

## Resolution

- TDD RED：共享 5×3 Frame 正例首先被 Rust Engine 以 `SCENE_STRUCTURE` 拒绝，nested subpixel negative 同样误报
  `SCENE_STRUCTURE`；Python independent 对同一正例报 result drift。GREEN 后 Rust focused vectors 2/2、Python
  independent 19/19 cases、61 checks 全部通过。
- 实现保持唯一 `render_png(&AdmittedRenderDocument, dpi)` Interface：Engine 逐 occurrence 锁定 admitted tree 与
  definite-layout preorder，以 canonical decimal6 精确累加 Frame ContentBox/padding/child origin；全部 Frame/Rect
  先准备成 preorder `PixelRect`，成功后才分配 surface，并按 Frame fill → descendants → later sibling 覆写。
  `clipContent=true` 等未支持 Frame paint 稳定返回 `FRAME_PAINT`，nested subpixel child 稳定返回
  `NON_PIXEL_ALIGNED_RECT`，均不产生 partial output。
- shared Engine-PNG `/1` 最终为 9 rendered + 10 unsupported、19/19 cases、61 checks，vector SHA-256
  `acb3adf55f8b67914918f20197e5bdb4668985759b51a2a02fe0810cb0eba363`。workspace `fmt --check`、locked
  `check`、Clippy all-targets `-D warnings`、workspace tests、`py_compile`、JSON inventory/unique/SHA 与
  `git diff --check` 均通过。
- 分级证据：`render` `.sdlc/evidence/20260824-160735-render/`、affected `fast`
  `.sdlc/evidence/20260824-160810-fast/`、顺序 `server` `.sdlc/evidence/20260824-160833-server/` 与 17-step
  `full` `.sdlc/evidence/20260824-162151-full/` 均 exit 0。`full` 用时 1186.556 秒；App 344 tests、0 failures、
  0 errors、15 controlled skips；Node 24 Web 26 files/212 tests、production build、runtime canary、Playwright
  23 passed + 1 controlled skip、Draft browser journey 与 inference replay E2E 1/1 均通过。状态回填后的
  resolution `fast` `.sdlc/evidence/20260824-164434-fast/` 亦为 exit 0。
- 状态止于 `automated_verified`：Engine 报告
  `PREORDER_FIXED_IDENTITY_FRAME_RECT_PIXEL_ALIGNED_OPAQUE_PNG_KERNEL_UNWIRED`、Profile `NOT_REGISTERED`、
  certification `NOT_CERTIFIED`、process raster `ABSENT`、daemon `UNWIRED`、product route `CLOSED`。`full` 的
  P0/R0/R1 均为离线严格重放，provider attempts、API Key reads、provider reservations 与费用均为 0；visual diff
  仍为 J0，未使用真实数据，未 push/tag/PR，也未把 `/prototype` 作为产品交付。
