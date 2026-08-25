# 接通 prepared IMAGE alpha premultiplication/source-over Engine PNG 内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 16, 19, 103, 107, 108（本切片前置均已 resolved）

## Question

T107 已把 orientation-normalized straight RGBA8 IMAGE 接入 authored-order Engine PNG，但 draw-enabled source
只要出现非 255 alpha 就 fail closed；真实 PNG/WebP 常见透明像素因此仍不能进入 T108 prepared RESULT kernel。
如何冻结一个不依赖 native raster stack 的 exact alpha 闭包，同时不冒充 general sampling、coverage、opacity、
transform 或完整 Renderer Profile 已实现？

## Answer（本票冻结的实施决定）

1. **public deep Interface 不扩张**：继续只使用
   `render_png_with_prepared_resources(admittedDocument, preparedManifest, dpi)`；不增加 caller pixels、blend mode、
   rounding 开关或 test-only token。T108 daemon seal 自动消费本票深化后的同一 Engine output。
2. **内部 surface 固定 premultiplied RGBA8**：Canvas 初始像素先用
   `mul255(c,a)=floor((c*a+127)/255)` 从 straight 转 premultiplied，`a=0` 时 RGB 强制为 0；现有 opaque Rect 与
   background 仍 byte-identical。所有 authored paint 在同一 premultiplied surface 上顺序执行，PNG seal 前只做
   一次 fixed unpremultiply；`a=0` 输出 RGBA 全 0。
3. **source-over exact integer order**：Image source 每像素先 premultiply；随后按 authored order 对每个 channel
   执行 `outA=srcA+mul255(dstA,255-srcA)`、`outC=srcC+mul255(dstC,255-srcA)`，并做 defensive 0..255 guard。
   unpremultiply 使用 `floor((premul*255+floor(alpha/2))/alpha)`；全部运算使用有界整数，不用 float、SIMD、
   native library、linear-light 或 hidden tolerance。
4. **只移除 source-alpha 阻塞**：仍只允许 source 与 integer device LayoutBox 精确 1:1、identity transform、
   node/ancestor draw opacity 恰为 1；Image self/ancestor/surface rectangular clip 与 authored order 保持 T107
   语义。重采样、subpixel box、rotation、partial node opacity、partial Rect/background alpha 继续稳定 fail closed。
5. **TDD 与独立重放**：先把既有 oriented partial-alpha negative 转 positive并加入 alpha=0/64/128/255、透明
   background、opaque underlay、重复 source-over 的 frozen vectors，使 Rust public Interface RED；Python stdlib
   以独立控制流重放 decode/orientation、premultiply、source-over、unpremultiply、PNG bytes/hash。旧 opaque Engine
   corpus及 daemon prepared RESULT integration 必须保持 byte-identical green。
6. **诚实边界**：本票不实现 NEAREST/LINEAR resampling、coverage/AA、node opacity、transform、Text/vector/QR/
   barcode、JPEG/layoutTrace，不注册 Profile，不接 RequestRegistry/Java/OpenAPI/Web/正式产品 route，不执行 native
   Skia/FreeType/HarfBuzz build、physical certification、J1/A3/READY 或外部副作用；`/prototype` 不计交付。

## 验证与完成信号

- focused Rust public vectors先 RED 后 GREEN；Python independent、旧 Engine/daemon integration、fmt、clippy
  `-D warnings`、workspace tests、JSON/hash/inventory 与 `git diff --check` 全绿。
- 分级 gate：canonical `render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。
  最高只报 `automated_verified`；provider/API Key/费用/真实数据=0，不 push/tag/PR。

## Result

- Engine internal surface 已固定为 premultiplied RGBA8；Canvas/background、Rect 与 prepared IMAGE 都在同一
  authored-order surface 上执行，IMAGE 使用冻结的 integer `mul255`/source-over 算术，seal 前只做一次 fixed
  unpremultiply。透明 source 的隐藏 RGB 不泄漏，既有 opaque PNG bytes/hash 保持 byte-identical。
- public Interface 未扩张：`render_png_with_prepared_resources` 与 T108 `seal_prepared_png_result` 原样复用；真实
  orientation-normalized partial-alpha fixture 已贯通 fetch → verify → decode → alpha compose → exact PNG → ordered
  RESULT_METADATA/RESULT_IMAGE，未增加 caller pixels、blend mode 或 test-only bypass。
- TDD 先把既有 partial-alpha negative 转成 positive 并得到 `NON_OPAQUE_IMAGE_ALPHA` RED；GREEN 后 Rust focused
  vectors 2/2、Python independent 18/18 cases/120 checks、daemon prepared-result integration 3/3、旧 Engine
  26/26 cases/82 checks 全绿。冻结 corpus 为 14 rendered + 4 unsupported，继续对 resampling、subpixel box、
  rotation 与 partial node opacity fail closed。
- 本票精确能力标签为
  `PREPARED_IMAGE_ALPHA_1_TO_1_PREMULTIPLIED_SOURCE_OVER_EXACT_PNG_AUTOMATED_VERIFIED_PROFILE_GATED`；它是
  Renderer/Profile 与最终产品接线的前置内核，不是产品页面交付。

## Evidence

- canonical `render`：`.sdlc/evidence/20260825-100437-render/`（41.528 秒）；Windows/Linux actual UDS、Rust
  fmt/clippy `-D warnings`/workspace tests、Python independent replay 与 inventory/hash assertions 全绿。
- affected `fast`：`.sdlc/evidence/20260825-100549-fast/`（11.335 秒）；sequential `server`：
  `.sdlc/evidence/20260825-100613-server/`（1071.674 秒），8 个 Maven modules `BUILD SUCCESS`，App
  347 tests/0 failures/0 errors/15 controlled skips。
- Goal `full`：`.sdlc/evidence/20260825-102429-full/`，17/17 steps、1433.909 秒；包含 renderer Windows/Linux
  actual UDS、Node 24 Web 26 files/212 tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft 与
  inference browser journeys。状态回填后的 resolution `fast`
  `.sdlc/evidence/20260825-105202-fast/`（11.845 秒）亦通过。
- Engine prepared-image vector SHA-256 为
  `837c98e418cf5d40586e048296825b04be33c7a2d5e2184384878d763584ac52`；provider attempts/API Key reads/
  费用/真实数据=0，未 push/tag/PR。
- Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、正式产品 route `CLOSED`、
  native stack `BUILD_NOT_AUTHORIZED`；未声明 J1/A3/READY，`/prototype` 不计最终产品交付。
