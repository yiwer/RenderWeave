# 实现 pixel-aligned 矩形 Frame descendant clip 的真实 Engine PNG 内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 24, 25, 26, 49, 50, 92, 93, 94, 95（均已 resolved）

## Question

T95 已能按先序绘制 fixed identity Frame/Rect，但 `clipContent=true` 仍整体失败，因而真实 Template 场景不能表达
容器内容裁剪。怎样严格复用 `renderweave-layout/1.0` 已冻结的 inner-border clip 语义，在不引入 rounded AA、stroke、
blend、一般 transform 或伪 Renderer Profile 的前提下，完成首个可逐像素重放的 descendant clip 纵切？

## Answer（本票冻结的实施决定）

1. **保持唯一 deep Interface**：入口仍为 `render_png(&AdmittedRenderDocument, dpi)`；不暴露 paint/clip list，
   不新增 test bypass、daemon success、Profile registration 或产品 route。
2. **精确子集**：沿用 T95 的 fixed ABSOLUTE、identity、visible、opacity=1、zero-radius、no-stroke Frame/Rect
   闭包。Frame 允许 `clipContent=true`，但 inner-border 四条 world/device edge 必须原生落在整数 pixel boundary；
   不做 snap、rounding 或 coverage 猜测。rounded/stroked/subpixel clip 继续 fail closed。
3. **权威裁剪边界**：依据 Ticket 10，zero-stroke/zero-radius Frame 的 descendant clip 是 LayoutBox 对应的
   inner-border rectangle；padding 只改变 ContentBox/child origin，不是 clip boundary。Frame self fill 仅继承 ancestor/
   surface clip；压入本 Frame clip 后才绘制 descendants。
4. **祖先求交与顺序**：prepare 阶段携带 surface 起始 clip，逐层对 ancestor clip 与当前 Frame clip 求矩形交集；
   Rect 和 nested Frame self paint 在 prepare 时收窄到当时有效 clip。顺序仍为 Frame fill → descendants → later sibling。
5. **全有或全无**：全部 clip/paint 在最终 RGBA8 surface 分配前完成。任一 subpixel clip 或后续 child 失败时不分配、
   不修改、也不编码 partial output。
6. **共享语料与 TDD**：把既有 `clippingOpaqueFrame` 扩为 outer+nested clips、padding-area child 与越界 Rect 的
   5×3 exact PNG 正例；新增无 fill 的 subpixel Frame clip negative，期望稳定
   `NON_PIXEL_ALIGNED_CLIP`。shared `/1` 先让 Rust/Python 共同 RED，目标 10 rendered + 10 unsupported、
   20/20 cases、64 checks。
7. **诚实边界**：只报告
   `PREORDER_FIXED_IDENTITY_FRAME_RECT_PIXEL_ALIGNED_OPAQUE_RECTANGULAR_CLIP_PNG_KERNEL_UNWIRED`。
   Profile 仍 `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、daemon `UNWIRED`、
   product route `CLOSED`。rounded/stroke/opacity/AA/blend/general transform、Stack/Grid/Group、Text/Image/resource/
   font、JPEG、LayoutTrace、Java/OpenAPI/Web/E6、正式记录、物理认证、J1/A3/READY 均不在本票。

## 验证与完成信号

- TDD RED：Rust 对 clip 正例仍返回 `FRAME_PAINT`，Python independent 同样 result drift；subpixel clip 也误报
  `FRAME_PAINT`。
- TDD GREEN：Rust Engine vectors 2/2；Python independent 20/20 cases、64 checks；workspace Rust tests、
  pycompile 与 exact JSON/SHA inventory 绿色。
- 分级：focused → `render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。
- 最高状态只到 `automated_verified`；provider attempts、API Key reads、付费调用与真实数据保持 0；不 push/tag/PR，
  不开放产品 route，不宣称 Template/Renderer READY。

## Resolution

- TDD RED：共享 clip 正例先在 Rust Engine 以 `FRAME_PAINT` 拒绝，Python independent 对同一结果 drift；新增
  subpixel clip negative 也误报 `FRAME_PAINT`。GREEN 后 Rust focused vectors 2/2、Python independent
  20/20 cases、64 checks 全部通过。
- 实现继续保持唯一 `render_png(&AdmittedRenderDocument, dpi)` Interface：prepare 阶段从 surface clip 开始，
  对每个 pixel-aligned Frame inner-border 与 ancestor clip 求交；Frame self fill 只继承 ancestor clip，descendants
  才继承当前 Frame clip，padding 仅改变 child origin。全部 clip/paint 成功准备后才分配 RGBA8 surface；空交集与
  nested clip 确定性处理，subpixel edge 稳定返回 `NON_PIXEL_ALIGNED_CLIP` 且不产生 partial output。
- shared Engine-PNG `/1` 最终为 10 rendered + 10 unsupported、20/20 cases、64 checks，vector SHA-256
  `dc55cdad90e314ff642b94c79566f42a35bb09d8464e82b964134ed49ce7fe28`；exact 5×3 PNG content SHA-256
  `66d50dd15eb4e1989e85e08a72d5ac7534c99d5db772ad9b319288fe3728b3ba`、pixel SHA-256
  `bdbefd38fb705ee80cbf5ccaea4178f196a16bbc5e7206023748608373aa1089`、165 bytes。workspace `fmt --check`、
  locked `check`、Clippy all-targets `-D warnings`、workspace tests、`py_compile`、JSON inventory/unique/SHA 与
  `git diff --check` 均通过。
- 分级证据：`render` `.sdlc/evidence/20260824-170349-render/`、affected `fast`
  `.sdlc/evidence/20260824-170419-fast/`、顺序 `server` `.sdlc/evidence/20260824-170439-server/` 与 17-step
  `full` `.sdlc/evidence/20260824-171757-full/` 均 exit 0。`full` 用时 1166.572 秒；App 344 tests、0 failures、
  0 errors、15 controlled skips；Node 24 Web 26 files/212 tests、production build、runtime canary、Playwright
  23 passed + 1 controlled skip、Draft browser journey 与 inference replay E2E 1/1 均通过。状态回填后的
  resolution `fast` `.sdlc/evidence/20260824-173920-fast/` 亦为 exit 0。
- 状态止于 `automated_verified`：Engine 报告
  `PREORDER_FIXED_IDENTITY_FRAME_RECT_PIXEL_ALIGNED_OPAQUE_RECTANGULAR_CLIP_PNG_KERNEL_UNWIRED`，Profile
  `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、daemon `UNWIRED`、product route
  `CLOSED`。`full` 的 P0/R0/R1 均为离线严格重放，provider attempts、API Key reads、provider reservations 与
  费用均为 0；visual diff 仍为 J0，未使用真实数据，未 push/tag/PR，也未把 `/prototype` 作为产品交付。
