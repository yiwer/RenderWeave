# 实现 prepared IMAGE 整数 box 采样内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 10, 16, 19, 23, 24, 25, 26, 48, 92, 97, 99, 103, 105, 106, 107, 108, 109, 110, 111, 113, 119, 120（本切片前置均已 resolved）

## Question

T107–T113 已在唯一 prepared-resource Engine PNG Interface 中闭合 exact 1:1 IMAGE、alpha、clip、subtree
opacity 与方形 quarter-turn，但任一 source/device 尺寸差异仍返回 `IMAGE_RESAMPLING`。如何依据 Ticket 10/16
冻结整数 device box 上的 `CONTAIN | COVER | FILL` 与 `LINEAR | NEAREST`，并保持 exact pixels、独立重放与
Profile fail-closed 边界？

## Answer（本票冻结的实施决定）

1. **只深化现有 deep Interface**：继续使用
   `render_png_with_prepared_resources(document, manifest, dpi)`；不新增 caller pixels、第二套 raster、Java/
   OpenAPI/Web/route 或 partial Profile seam。
2. **准入子集**：prepared IMAGE 必须保持 centered origin、positive unit scale、整数 device box；本票新增的
   resampling 只接受零旋转。既有 exact 1:1 方形 quarter-turn 分支保持不变。subpixel box、任意角度、
   resampled rotation、ancestor transform 与 coverage/AA 继续 fail closed。
3. **精确反向映射**：对每个目标 pixel center `(x+0.5,y+0.5)`，使用有理数从 centered fit rectangle 反算
   source edge coordinate。`FILL` 独立缩放两轴；`CONTAIN` 取较小 exact ratio并保留透明 bars；`COVER` 取较大
   exact ratio并由 LayoutBox hard clip 居中裁切；source 边缘 clamp。
4. **采样 Profile 语义**：`NEAREST` 比较 source pixel center 的精确距离，同距固定选择较小 source index；
   `LINEAR` 先按现有 HALF_UP `/255` 规则把四个 source texel 转为 premultiplied RGBA8，再以二维精确有理权重
   一次求和、一次 HALF_UP，随后进入既有 premultiplied source-over 和最终单次 unpremultiply。
5. **TDD 与独立重放**：shared prepared IMAGE vectors 升级为 `/4`；先把既有 width-resampling negative 改为
   `FILL + LINEAR` positive，使 Rust public Interface 与 Python independent verifier 在同一 case RED。GREEN 后补
   NEAREST tie、LINEAR alpha、downsample、CONTAIN bars、COVER crop、clip/opacity 回归，并保留 subpixel 与 45°
   replacement negatives。
6. **诚实边界**：不开放 native Skia/FreeType/HarfBuzz build、Text/vector/QR/Barcode/JPEG/LayoutTrace、Profile
   registration/certification、daemon RequestRegistry success、public Rendering API/E6、正式 `/templates` route、
   J1/A3/READY 或外部副作用；`/prototype` 不计最终交付。

## 验证与完成信号

- Rust/Python 在同一首个 resampling case 共同 RED，再以独立控制流逐 pixel/PNG byte GREEN。
- focused Rust/Python、fmt/check/clippy `-D warnings`/workspace tests、`py_compile`、JSON inventory/SHA/unique、
  `git diff --check`；随后 canonical `render` → affected `fast` → sequential `server` → Goal `full` → resolution
  `fast`，Maven 不并发。
- 最高只报 `automated_verified`；Profile/daemon/API/product route 在完整边界前继续如实保持关闭，不 push/tag/PR。

## TDD RED

- Rust public Interface：`fill-linear-width-resampling unexpectedly rejected: ... IMAGE_RESAMPLING`。
- Python independent verifier：`fill-linear-width-resampling result drifted`。
- 两边均先通过旧 inventory/authority 与 unsupported 回归，再在同一新增 positive 失败。

## Results

- Rust primary 已在既有 `render_png_with_prepared_resources` 内实现零旋转、整数 device box 的
  `CONTAIN | COVER | FILL` 与 `LINEAR | NEAREST`；既有 exact 1:1 centered unit quarter-turn 分支保持不变，
  subpixel 与 45° rotation 继续 fail closed。目标 half-integer center、exact rational inverse mapping、lower-index
  NEAREST tie、edge clamp、premultiplied RGBA8 bilinear single HALF_UP、CONTAIN 透明 bars、COVER 居中裁切及
  clip/subtree-opacity/source-over 均进入同一 authored-order raster path。
- Python independent verifier 以独立逐 pixel 控制流重算 mapping、sampling、premultiply、blend 与 exact PNG bytes。
  shared vectors 已升级为 `/4`：31 rendered + 2 unsupported、33/33 cases、195 checks；vector SHA-256
  `0165159dba1ad90c75faaef7e1e5d7254c8adff6e1c0e388fbea44d715148295`。新增覆盖 width/both-axis
  up/downsample、NEAREST exact tie、LINEAR alpha、CONTAIN bars、COVER crop 与 clip/opacity mapping；focused
  Rust/Python、fmt/check/clippy `-D warnings`、workspace、`py_compile`、JSON inventory/unique/SHA 与
  `git diff --check` 均绿。
- 分级 A1 证据：canonical `render` `.sdlc/evidence/20260825-233831-render/`（59.681 秒）、affected `fast`
  `.sdlc/evidence/20260825-233944-fast/`（11.123 秒）、sequential `server`
  `.sdlc/evidence/20260825-234002-server/`（673.432 秒）与 17-step Goal `full`
  `.sdlc/evidence/20260825-235123-full/`（1131.999 秒）均 passed。full 覆盖 Windows/Linux Renderer、8 个 Maven
  modules、Node 24 Web 28 files/217 tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft/inference
  browser journeys；provider attempts/API Key reads/reservations/cost/open authorization=0。状态回填后的 resolution
  `fast` `.sdlc/evidence/20260826-001253-fast/` 也以 3/3 steps、passed/A1、12.824 秒通过。
- 最终状态仅为 `resolved / automated_verified`。Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process
  raster `ABSENT`、native stack `BUILD_NOT_AUTHORIZED`、daemon RequestRegistry success/public Rendering API/E6/正式
  `/templates` route `CLOSED`；Text shaping、world scene、JPEG 与最终产品接线继续后续 DAG，`/prototype` 不计交付，
  未推进 J1/A3/READY，未 push/tag/PR。
