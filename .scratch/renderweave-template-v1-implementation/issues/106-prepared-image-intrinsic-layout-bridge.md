# 接通 prepared IMAGE intrinsic definite layout

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 10, 13, 16, 19, 22, 23, 25, 26, 33, 34, 40–45, 53–64, 91, 103, 105（本切片前置均已 resolved）

## Question

T105 已把 exact IMAGE bytes 解码、orientation 归一化并封存在完整 immutable
`PreparedResourceManifest`，但 layout 仍一律以 `RESOURCE_DEPENDENT_KIND` 拒绝 Image。如何让权威布局只消费该
不可伪造的 prepared manifest，完成 Image fixed/FILL 与单轴 HUG 的 logical-pixel-ratio 语义，并复用既有
ABSOLUTE/Stack/Grid/容器 solver，而不复制布局、信任 descriptor-only 宽高或提前冒充 scene/raster/RESULT？

## Answer（本票冻结的实施决定）

1. **唯一 prepared layout deep Interface**：新增 `layout_definite_with_prepared_resources(document, manifest)`；
   layout 直接消费 T105 的完整 `PreparedResourceManifest`，不得接受裸宽高 map、test-only token 或调用者自报
   intrinsic facts。既有 `layout_definite_resource_free` bytes/行为保持不变。
2. **整份 manifest 防御复核**：进入布局前核对 RendererV1 Profile、resource count/order/id/kind，并对每个 IMAGE
   再核对 admitted descriptor logical dimensions 与 prepared oriented pixels。漂移作为内部 invariant、零 partial
   layout；fixed/FILL Image 也必须经过该完整身份边界。
3. **Image intrinsic**：单轴 HUG 只从另一 definite outer axis 与 prepared logical `widthPx:heightPx` 推导；忽略
   DPI/EXIF physical resolution。固定 binary64 次序为先形成 logical ratio、再与 opposite outer size 相乘；只对
   HUG 轴应用既有 min/max clamp。ratio 被 clamp 改变后留给后续 fit，不回流另一轴。
4. **既有 solver 复用**：把同一只读 resource context 贯穿 ABSOLUTE、Group/Frame HUG、Stack measure/FILL 后单次
   cross-HUG remeasure、Grid columns-first/AUTO contribution/arrange。只有现有 solver 已产生 definite opposite
   outer offer 的路径才开放；缺少 offer 继续 fail closed，不新增 tolerance 或一般 constraint solver。
5. **边界**：Text 仍 `RESOURCE_DEPENDENT_KIND`；Image `fit`/`sampling` 只属于后续 scene/raster，不反馈 layout。
   本票不构建 Skia/FreeType/HarfBuzz，不实现 shaping、paint、sampling、raster、JPEG、daemon RESULT/Profile、Java/
   OpenAPI/Web/Product route、formal records、physical certification、J1/A3/READY；`/prototype` 不计交付。
6. **TDD 与独立重放**：public Interface 先 RED；共享 vectors 使用真实已批准 PNG bytes（含 orientation 后 1×2
   logical image）覆盖 fixed、两方向 HUG、HUG-only clamp、ABSOLUTE FILL offer、Frame/Group、Stack、Grid/AUTO 与
   manifest mismatch/旧 resource-free boundary。Rust primary 经过真实 T105 prepare；Python stdlib 独立解析 PNG/
   EXIF facts并重放布局算术。

## 验证与完成信号

- focused public Interface/Rust workspace/Python independent/fmt/clippy `-D warnings`/JSON identity 全绿；既有
  definite-layout `/54` 与 Engine vectors bytes/结果不回归。
- 分级 gate：`asset`/`render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。最高只可
  `automated_verified`；provider/API Key/费用/真实数据=0，不 push/tag/PR。

## Result

- 新增唯一 public deep Interface `layout_definite_with_prepared_resources(document, manifest)`；它直接消费 T105
  产生的完整 immutable `PreparedResourceManifest`，并在布局前复核 Profile、resource count/order/id/kind 以及
  IMAGE admitted logical dimensions 与 prepared oriented pixels。调用方无法传入裸宽高或伪造 intrinsic facts。
- Image fixed/FILL 与单轴 HUG 已接入既有 definite solver；HUG 按 prepared logical pixel ratio、固定 binary64
  次序推导且只 clamp HUG 轴。同一 resource context 已贯穿 ABSOLUTE、Group/Frame、Stack 与 Grid/AUTO 路径；
  旧 resource-free API 与既有 definite-layout/Engine vectors 保持不变。
- TDD public Interface 先红后绿。真实 T105 prepare 的 Rust public replay 与 Python stdlib 独立 replay 覆盖 10 个
  success、2 个 negative case，共 81 checks；vector SHA-256
  `275579debd1ba894a64836258da402ea0e974046895ae985642be832cf430b14`。

## Evidence

- canonical `render`：`.sdlc/evidence/20260825-061340-render/`；affected `fast`：
  `.sdlc/evidence/20260825-061452-fast/`；sequential `server`：
  `.sdlc/evidence/20260825-061512-server/`，Maven `BUILD SUCCESS`、App 347/0/0/15。
- Goal `full`：`.sdlc/evidence/20260825-063343-full/`，17/17 steps、1542.353 秒；包含 Windows/Linux
  network-none Rust workspace、Node 24 Web 26 files/212 tests、runtime canary、Playwright 23 passed + 1 controlled
  skip、Draft 与 inference browser journeys。状态回填后的 resolution `fast`
  `.sdlc/evidence/20260825-070236-fast/` 亦通过。
- 新 layout path dependency 使 Cargo lock SHA-256 变为
  `48d2ae407c941e746ef1bd425b04e4f69258c2f1e122d25a53ac2b61c4e3655c`，machine manifest SHA-256 变为
  `689d6e198193249c72af9f524021e4a2899aa72a592d5285f8bee9e2909f978c`；protocol HELLO vectors 已原子轮换并
  通过 110 checks。
- 边界保持：Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、scene/raster `ABSENT`、daemon output
  `UNWIRED`、正式产品 route `CLOSED`、native font stack `BUILD_NOT_AUTHORIZED`。provider attempts/API Key reads/
  费用/真实数据=0；未 push/tag/PR，`/prototype` 不计最终产品交付。
