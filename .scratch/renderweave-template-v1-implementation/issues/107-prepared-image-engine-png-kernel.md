# 接通 prepared IMAGE Engine PNG kernel

Type: task
Status: resolved
Claimed by: Codex `/root`（single-writer）
Blocked by: 10, 13, 16, 19, 22–26, 33–45, 53–54, 91–106（本切片前置均已 resolved）

## Question

T106 已让权威 definite layout 消费完整 `PreparedResourceManifest`，但 Engine 仍在任何 resource manifest
到达时返回 `RESOURCE_MANIFEST`。如何让 exact、orientation-normalized straight RGBA8 IMAGE 首次进入同一
authored-order scene 与 PNG pixel output，同时不伪装已经实现通用 sampling、premultiplied source-over、
native Skia stack、daemon RESULT/Profile 或最终产品 route？

## Answer（本票冻结的实施决定）

1. **保留旧入口，新增 prepared-resource 深接口**：现有 `render_png(document, dpi)` 的 resource-free bytes、
   error order 与 `RESOURCE_MANIFEST` 边界不变；新增
   `render_png_with_prepared_resources(document, manifest, dpi)`，只接受 T105 真实 immutable manifest，并复用
   T106 `layout_definite_with_prepared_resources`。不接受裸 bytes、宽高 map 或 test-only prepared token。
2. **只开放无重采样退化闭包**：Image 的 device-space LayoutBox 四边必须精确落在 integer pixel edge，且其
   宽高必须分别等于 prepared oriented logical pixel 宽高。此时 `CONTAIN/COVER/FILL` 都退化为同一满框映射，
   `LINEAR/NEAREST` 都命中原 pixel center；实现只做 row-major exact pixel copy，不调用缩放或采样 kernel。
3. **首票不混合透明像素**：draw-enabled Image 的每个 source alpha 必须为 255；否则稳定
   `NON_OPAQUE_IMAGE_ALPHA` fail closed。partial node opacity、非 identity transform、重采样/裁切 fit、subpixel
   box 均在 surface allocation 与 output seal 前全有或全无失败。隐藏/zero-opacity subtree 仍先完成 manifest
   fetch/decode/layout，再复用 T99 draw suppression。
4. **scene 顺序与 clip**：把 Rect 与 Image 收敛为同一内部 authored-order paint command；Image 始终自裁到
   LayoutBox，并与既有 ancestor rectangular clip/surface hard clip 求交。部分裁剪保留 destination→source 的
   exact integer offset，不重复布局、不 snap/round/epsilon。
5. **诚实边界**：本票不实现 general nearest/bilinear、premultiplication/source-over/transparent RGB、Text、
   vector/QR/barcode、JPEG output、native Skia/FreeType/HarfBuzz build、daemon RESULT/Profile registration、Java/
   OpenAPI/Web/正式 Product route、formal records、physical certification、J1/A3/READY；`/prototype` 不计交付。
6. **TDD 与独立重放**：先用缺失 public Interface 和 prepared IMAGE vectors 取得 RED。Rust primary 必须经真实
   resource preparation；Python stdlib 独立校验 PNG chunk/CRC/zlib/filter、resource digest/descriptor、definite
   layout、clip/order/pixels 与 exact PNG bytes。旧 Engine vectors 必须保持 byte-identical green。

## 验证与完成信号

- focused public Interface、Rust workspace、Python independent、fmt、clippy `-D warnings`、JSON identity 与旧
  Engine regression 全绿。
- 分级 gate：`asset`/`render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。
  最高只报 `automated_verified`；provider/API Key/费用/真实数据=0，不 push/tag/PR。

## Result

- 保留 resource-free `render_png(document, dpi)` 的 bytes/error order，新 public deep Interface
  `render_png_with_prepared_resources(document, manifest, dpi)` 只消费 T105 完整 immutable manifest，并复用
  T106 权威 definite layout。Rect/Image 已收敛到同一 authored-order paint stream。
- 首个 IMAGE raster 闭包已完成：orientation-normalized straight RGBA8 source 与 integer device LayoutBox 精确
  1:1、source alpha 全 255、identity transform/draw opacity 1 时，按 row-major exact copy 进入 PNG；self、ancestor
  与 surface rectangular clip 保留 exact destination→source offset。三种 fit/两种 sampling 只在无重采样退化
  等价时开放，其余路径稳定 fail closed 且无 partial output。
- TDD 先以缺失 public Interface 获得 RED，再完成最小 GREEN。Rust primary 与 Python stdlib 独立重放覆盖
  9 rendered + 5 unsupported、14/14 cases、59 checks；vector SHA-256
  `b9b473ec9b4fc39ac1fa39185f62ac3a52f685f7dc5f72431408d5c06daf57d7`。旧 Engine 26/26 cases、82 checks
  保持 byte-identical green。

## Evidence

- canonical `render`：`.sdlc/evidence/20260825-073242-render/`；affected `fast`：
  `.sdlc/evidence/20260825-073337-fast/`；sequential `server`：
  `.sdlc/evidence/20260825-073358-server/`，Maven `BUILD SUCCESS`、App 347/0/0/15。
- Goal `full`：`.sdlc/evidence/20260825-075209-full/`，17/17 steps、1558.348 秒；包含 renderer Windows/Linux
  network-none replay、Node 24 Web 26 files/212 tests、runtime canary、Playwright 23 passed + 1 controlled skip、
  Draft 与 inference browser journeys。状态回填后的 resolution `fast`
  `.sdlc/evidence/20260825-082841-fast/` 亦通过。
- Cargo lock SHA-256 为 `dcd02daa3bc4298e92da6d4c72961725d3e24fdef0d32e654030b030791cdd5f`，machine
  manifest SHA-256 为 `8e1f5114bc5a2a08dc6834b4c81b175f4614a519ab03a705ed0d43209c8cda2d`，protocol
  vectors SHA-256 为 `ee13576063c93cf5a7bf7ac85ac34a99b47f3016441f121d3e34d0345c2cef1c`；process independent
  7 vectors/110 checks 与 vendor 3067 files 全绿。
- 状态最高为 `automated_verified`。Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  `ABSENT`、daemon output `UNWIRED`、正式产品 route `CLOSED`、native font stack `BUILD_NOT_AUTHORIZED`；general
  sampling/blend/Text/RESULT/Profile/产品接线继续后续 DAG。provider attempts/API Key reads/费用/真实数据=0；
  未 push/tag/PR，`/prototype` 不计最终产品交付。
