# 接通 pixel-aligned solid alpha source-over Engine PNG 内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 16, 92, 93, 94, 95, 96, 97, 99, 109（本切片前置均已 resolved）

## Question

T109 已把 Engine surface 深化为 fixed premultiplied RGBA8，并为 prepared IMAGE 冻结 exact source-over；但同一
Engine 仍拒绝 partial Canvas background 与 Rect/container solid fill。怎样复用已经验证的 alpha arithmetic，
让真实模板常见的半透明色块进入 exact PNG，同时不把 pixel-aligned full coverage 冒充 vector AA、subtree
opacity isolation、general transform 或完整 Renderer Profile？

## Answer（本票冻结的实施决定）

1. **不扩张 public deep Interface**：继续只使用 `render_png` 与
   `render_png_with_prepared_resources`；不新增 caller surface/pixels、blend mode、rounding 开关或 test-only token。
   T108 daemon seal 自动消费深化后的同一 Engine output。
2. **Canvas background 接入同一 premultiplied surface**：任意 admitted `#RRGGBBAA` 先按 T109
   `mul255(c,a)=floor((c*a+127)/255)` 转为 premultiplied RGBA8 并填满含 bleed surface；`a=0` 强制 RGB=0，
   不再只允许 `00 | FF`。
3. **solid fill 使用 exact source-over**：pixel-aligned、identity、zero-radius/no-stroke、node opacity=1 的 Rect，
   以及现有 Frame/Stack/Grid container fill，均把 authored straight RGBA8 逐像素 premultiply 后按 T109
   `out=src+mul255(dst,255-srcA)` 合成。opaque source 仍 byte-identical；transparent source 不改 destination。
4. **paint order 与原子性不变**：完整 resource/layout/scene prepare 成功后才分配 surface；Canvas → container
   self fill → descendants → later siblings 的现有 authored order、rectangular clip 与一次 final unpremultiply 不变。
   任一后续 unsupported 仍零 output。
5. **TDD 与独立重放**：先把现有 partial background/Rect negatives 转为 positives，并新增 opaque-background
   underlay、repeated partial Rect source-over、partial container fill + child 的 frozen exact PNG vectors，使 Rust
   public Interface RED；Python stdlib 独立实现 premultiply/source-over/unpremultiply 并核对旧 opaque bytes/hash。
6. **诚实边界**：本票不实现 rounded/stroked coverage、Ellipse/Vector/Text/QR/Barcode、partial node/subtree
   opacity isolation、resampling、subpixel edge、transform、JPEG/layoutTrace、Profile registration、
   RequestRegistry/Java/OpenAPI/Web/正式产品 route、native build、physical certification、J1/A3/READY 或外部副作用；
   `/prototype` 不计交付。

## 验证与完成信号

- focused Rust vectors先 RED 后 GREEN；Python independent、prepared IMAGE/daemon regressions、fmt、clippy
  `-D warnings`、workspace tests、JSON/hash/inventory 与 `git diff --check` 全绿。
- 分级 gate：canonical `render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。
  最高只报 `automated_verified`；Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  `ABSENT`、正式产品 route `CLOSED`；provider/API Key/费用/真实数据=0，不 push/tag/PR。

## Result

- Canvas 任意 admitted alpha 现按 fixed `mul255` 规则 premultiply 为初始 surface；pixel-aligned、identity、
  zero-radius/no-stroke、node opacity 1 的 Rect/Frame/Stack/Grid solid fill 复用同一 integer source-over，最终仅
  unpremultiply 一次。opaque 输出保持 byte-identical，alpha=0 隐藏 RGB 归零且透明 source 不改变 destination。
- 共享 Engine corpus 冻结为 18 rendered + 11 unsupported，共 29 cases/91 independent checks；新增 partial Canvas、
  transparent/opaque underlay、repeated authored-order Rect 与 container fill→child composition。focused Rust 2/2、
  prepared IMAGE 18/18 cases/120 checks、daemon prepared-result integration 3/3 与 Windows/Linux workspace 回归全绿。
  vector SHA-256 为 `2d5835e2e5e30935f9f2e17f5e70983a428211aa1997253c7036aaad0b6c093a`。
- 精确能力标签为
  `PREORDER_DEFINITE_IDENTITY_GROUP_FRAME_STACK_GRID_RECT_PIXEL_ALIGNED_SOLID_ALPHA_PREMULTIPLIED_SOURCE_OVER_RECTANGULAR_CLIP_VISIBILITY_ZERO_OPACITY_SUPPRESSION_PNG_KERNEL_PROFILE_GATED`。
  partial subtree opacity isolation、subpixel/rounded/stroke coverage、resampling/transform 与其余诚实边界继续 fail closed。

## Evidence

- canonical `render`：`.sdlc/evidence/20260825-110659-render/`（45.416 秒）。
- affected `fast`：`.sdlc/evidence/20260825-110857-fast/`（11.918 秒）。
- sequential `server`：`.sdlc/evidence/20260825-110918-server/`（807.971 秒）。
- Goal `full`：`.sdlc/evidence/20260825-112257-full/`（17/17 steps，1217.692 秒），覆盖 Renderer
  Windows/Linux UDS、8 个 Maven modules、Node 24 Web 26 files/212 tests、runtime canary、Playwright
  23 passed + 1 controlled skip、Draft 与 inference browser journeys；provider attempts/API Key reads/费用/真实数据=0。
- 状态回填后的 resolution `fast`：`.sdlc/evidence/20260825-114508-fast/`（11.173 秒）。Profile `NOT_REGISTERED`、certification
  `NOT_CERTIFIED`、process raster `ABSENT`、正式产品 route `CLOSED`、native stack `BUILD_NOT_AUTHORIZED`；
  `/prototype` 不计最终产品交付，未 push/tag/PR。
