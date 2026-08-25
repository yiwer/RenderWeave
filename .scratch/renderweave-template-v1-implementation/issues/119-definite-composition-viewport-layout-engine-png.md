# 实现 definite compositionViewport CONTAIN/CENTER 布局与资源无关 Engine PNG 纵切

Type: task
Status: resolved
Claimed by: Codex `/root`（single-writer）
Blocked by: 12, 19, 21, 23, 25, 26, 42, 92, 97, 110, 111（本切片前置均已 resolved）

## Question

T114 已形成真实 Template list/create/editor page 基座，但 E6 与正式产品路由仍被完整 Renderer/Profile 阻塞；
T23 已把 TemplateUse 降为 closed `compositionViewport`，当前 Layout 却仍在该 kind 直接返回 unsupported，Engine
也无法绘制 child artboard。如何在不依赖 native raster、未注册 partial Profile、也不把 child 栅格化为图片的
前提下，完成首个能直接推进 TemplateUse→Authoritative Preview 的真实纵切？

## Answer（本票冻结的实施决定）

1. **复用两个既有深 Interface**：Layout 只深化 public `layout_definite_resource_free`，Engine 只深化 public
   `render_png`；不新增第二套 layout、scene、viewport parser、public Rendering API、Profile 或 test-only route。
   这两个已由项目规格与既有票据确认的 seam 也是本票唯一测试面。
2. **source-space 先布局**：对 definite FIXED/FILL host `compositionViewport`，先按 `sourceCanvas.widthPt ×
   heightPt` 的原始 trim 在独立请求内子布局中递归处理 source children；不得把 host 尺寸反馈进 child Stack/Grid/
   Text/Image 约束，也不得预布局于 Evaluator。viewport entry、sourceCanvas entry 与 descendants 保持冻结 preorder。
3. **固定 binary64 CONTAIN/CENTER**：以固定顺序计算
   `scale=min(hostWidth/sourceWidth, hostHeight/sourceHeight)`，再计算 mapped width/height 与
   `(host-mapped)/2` 双轴 offset；所有中间值必须 finite/nonnegative，禁止 epsilon、pixel snap 或容差分支。
   子布局的 LayoutBox/ContentBox 只在完整成功后统一映射进 parent 坐标；nested definite viewport 递归复用同一路径。
4. **真实资源无关 PNG scene**：Engine 按 preorder 消费 viewport→sourceCanvas→children。TemplateUse host 无 self
   paint；host `visible/opacity` 只包裹整个 child subtree 一次；source Canvas concrete background 先于 children；
   descendant clip 为 ancestor ∩ host ∩ mapped source trim，letterbox 不绘制。只接受既有 Engine 已支持的
   identity、pixel-aligned Rect/Group/Frame/Stack/Grid scene，非像素对齐 clip/paint 与非 identity transform 继续
   fail closed；不创建 child raster snapshot。
5. **TDD 与独立控制**：先把现有 `composition-viewport` layout tracer 转正，并新增一个 Engine
   CONTAIN/CENTER/background/child tracer，使 Rust primary 与 Python independent verifier 分别在同一公开 seam
   RED；最小 GREEN 后逐项补 source overflow clip、host opacity、nested viewport、STACK host 与 authored DFS
   first-error regressions。shared layout vector 升级 `/59`，Engine vector 保持其已冻结 `/1` identity并只追加案例。
6. **诚实边界**：HUG host、任意 rotation、subpixel/AA、prepared IMAGE resampling、Text shaping、Ellipse/Vector/
   QR/Barcode、JPEG/LayoutTrace、RequestRegistry success、Profile registration/certification、native build、公共
   Rendering API、E6、正式产品 route、physical J1/A3/READY 与外部副作用均不在本票；`/prototype` 不计交付。

## 验证与完成信号

- RED→GREEN：Layout 与 Engine 各自以 public Interface + frozen literal expected values观察；不测 private helper，
  不用同算法生成 expected，不 mock 内部 module。
- 局部：focused Rust tests、Python independent replay、Cargo fmt/check/clippy `-D warnings`/workspace tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：canonical `render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`；Maven不并发。
- 最高只报 `automated_verified`；Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  `ABSENT`、native stack `BUILD_NOT_AUTHORIZED`、公共 Rendering API/正式产品 route `CLOSED`；provider/API Key/
  费用/真实数据=0，不 push/tag/PR。

## Results

- Layout 已在 source Canvas 原始 trim 内递归完成后执行 fixed binary64 CONTAIN/CENTER 映射；nested viewport、
  Stack host、source overflow 与 authored DFS first-error 均通过 public seam 回归。shared `/59` 为 271 laid-out +
  17 unsupported、288/288 cases、861 checks，vector SHA-256
  `6c1b6dd1172a10bf223a477a4945a2b1fbe6e5d2bbe2dce8dc996e9196f4eadf`。
- Engine 已按 viewport→sourceCanvas→children preorder 绘制 source background 与 descendants，并执行
  ancestor∩host∩source hard clip 和 host subtree opacity isolation；resource-free `/1` 为 27 rendered +
  11 unsupported、38/38 cases、118 checks，vector SHA-256
  `55b76d93490c3ed8c01b3c81084781dea3d0856af81a8d495d92017ed28163e1`。
- canonical `render` `.sdlc/evidence/20260825-212203-render/`、affected `fast`
  `.sdlc/evidence/20260825-212302-fast/`、sequential `server`
  `.sdlc/evidence/20260825-212323-server/` 与 17-step Goal `full`
  `.sdlc/evidence/20260825-213503-full/` 均 passed/A1；full 中 Windows/Linux Renderer、Node 24 Web 217/217、
  runtime canary 与浏览器 journeys 均绿，provider attempts/API Key reads/reservations/cost/open authorization=0；状态回填后的
  resolution `fast` `.sdlc/evidence/20260825-215741-fast/` 也以 3/3 steps、passed/A1、11.546 秒通过。
- 最终状态仅为 `resolved / automated_verified`；Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process
  raster `ABSENT`、native stack `BUILD_NOT_AUTHORIZED`、公共 Rendering API/E6/正式产品 route `CLOSED`。
