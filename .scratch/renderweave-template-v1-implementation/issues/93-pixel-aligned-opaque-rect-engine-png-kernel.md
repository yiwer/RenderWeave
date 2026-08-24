# 实现像素对齐不透明 Rect 的真实 Engine PNG 输出内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 24, 25, 26, 92（均已 resolved：typed RenderDocument、surface/PNG、layout 与 Engine deep Interface）

## Question

T92 已证明空 Canvas 可沿真实 Engine Interface 形成 exact PNG，但正式 Template 产品页的 E6 不能只显示背景。
如何在不引入 Skia/AA 未认证语义、资源、daemon RESULT 或 partial Profile availability 的前提下，让首个真实非空
scene 经过同一 layout、device lowering、raster 与 encoder 输出可独立重放的图片？

## Answer（本票冻结的实施决定）

1. **保持同一 deep Interface**：继续只暴露
   `render_png(&AdmittedRenderDocument, dpi)`；scene traversal、point→device lowering、hard clip、pixel buffer、
   paint 与 PNG identity 均保留在 Engine 内部，不新增 public scene/raster helper 或测试专用 bypass。
2. **最小非空支持子集**：除 T92 empty Canvas 外，只接受资源 manifest 为空且 Canvas 恰有一个直接 `rect` child。
   Rect 必须 `visible=true`、`opacity=1`、`ABSOLUTE + FIXED/FIXED`、default identity transform、四个 corner radius
   均为 0、存在 alpha=`FF` 的 fill 且无 stroke。Canvas background 仍只接受 alpha `00 | FF`。任何其他 kind、
   多 child、容器、隐藏/部分 opacity、圆角、stroke、transform、非 FIXED placement 或 partial alpha 都全有或全无地
   fail closed，绝不忽略 unsupported paint。
3. **不做 pixel snap 的整数边界退化路径**：先运行同一 definite layout，并要求 preorder 恰为 Canvas + 对应 Rect；
   direct Rect 的 layout box 必须与 authored FIXED box exact binary64 一致。Rect 四条 device edge 由 canonical
   decimal6 authored box、精确 left/top bleed 与整数 DPI 计算；只有每条 edge 本来就整除 `72pt` device denominator
   时才进入本 kernel，subpixel edge 返回内部 unsupported。该 eligibility 检查不是 round/snap，DPI 不反馈 layout。
4. **真实 paint 与 clip**：Canvas background 先覆盖 bleed-inclusive surface；随后把 Rect 的整数 half-open device
   box 与 `[0,widthPx) × [0,heightPx)` 相交，并以 authored opaque sRGB8 fill 覆写对应 row-major straight RGBA8
   pixels。由于边界精确落在 pixel boundary、coverage 恒为 0/1 且 source alpha 为 255，本子集不选择 AA tie、
   partial coverage、unpremultiply 或一般 Porter-Duff rounding；这些仍需 pinned raster build/Renderer Profile 语料。
5. **共同语料与 TDD**：shared Engine-PNG vectors 保持 `/1` identity，先把一个 3×2、Rect 覆盖右下 2×1 pixels 的
   tracer 加入 Rust/Python 并共同 RED；两份实现独立计算 eligibility、pixels、exact PNG 与 digest。保留 T92 全部
   cases，并把既有 subpixel `nonemptyRect` 精化为 `NON_PIXEL_ALIGNED_RECT` negative；另冻结 unsupported kind/
   geometry/alpha 的失败关闭。
6. **诚实边界**：本票只证明 `PIXEL_ALIGNED_OPAQUE_RECT_PNG_KERNEL_UNWIRED`。process advertised
   `rendererProfiles:[]`、Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、daemon raster/RESULT `UNWIRED` 与
   product route `CLOSED` 均不变。多节点 paint order、容器 clip/opacity/transform、ellipse/path/text/image、资源
   fetch/decode/font、AA、JPEG/LayoutTrace、Java/OpenAPI/Web/E6、formal records、物理 Linux 认证、J1/A3/READY
   均不在本票。

## 验证与完成信号

- TDD：shared tracer 先在 Rust/Python 对同一 expected bytes 共同 RED，再分别最小 GREEN；测试只通过 Engine
  Interface 观察 output/error。
- 局部：focused Engine vectors、Python stdlib replay、workspace fmt/check/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → Goal `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。Provider attempts、
  API Key reads、付费调用与真实数据保持 0；不 push/tag/PR，不开放产品 route，不宣称 Template/Renderer READY。

## Resolution

- TDD tracer 先在 Rust/Python 对同一 3×2 expected PNG 共同 RED：Rust 返回 `NONEMPTY_CANVAS`，Python 报
  `result drifted`；随后两份实现分别沿同一 Engine Interface 独立 GREEN。
- `render_png` 现保留 T92 empty Canvas，并为恰好一个像素对齐、不透明 direct Rect 完成 layout → 精确 device
  edge eligibility → background → hard clip → row-major RGBA8 paint → exact PNG 的真实纵切；subpixel Rect 稳定
  `NON_PIXEL_ALIGNED_RECT`，其余未冻结 scene/paint 继续 fail closed。
- shared Engine-PNG `/1` 最终为 7 rendered + 8 unsupported、15/15 cases、49 checks；vector SHA-256
  `578b2446b557059cddc49a57634c0aa65d5a3a5ba565b7963be3c854b67597ee`。Rust workspace
  fmt/check/clippy `-D warnings`/tests、Python verifier/`py_compile`、JSON inventory 与 `git diff --check` 均通过。
- 分级证据：`render` `.sdlc/evidence/20260824-140946-render/`、affected `fast`
  `.sdlc/evidence/20260824-141021-fast/`、顺序 `server`
  `.sdlc/evidence/20260824-141041-server/` 与 17-step `full`
  `.sdlc/evidence/20260824-143058-full/` 均为 exit 0。full App 344/0/0/15、Node 24 Web 26 files/212
  tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft journey 与 inference replay E2E 1/1
  均绿色；full 独立 Engine report 为 A2、15/15、49 checks。状态回填后的 resolution `fast`
  `.sdlc/evidence/20260824-145508-fast/` 亦为 exit 0。
- 生命周期最高为 `automated_verified`。Profile 仍 `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  inventory `ABSENT`、daemon output `UNWIRED`、正式 Template 产品 route `CLOSED`；本票没有把 `/prototype`
  当作产品交付。Provider attempts、API Key reads、付费调用与真实数据均为 0，未 push/tag/PR。
