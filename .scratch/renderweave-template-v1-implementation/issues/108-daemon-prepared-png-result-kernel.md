# 接通 daemon prepared PNG Engine→RESULT 原子封存内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 22, 98, 105, 107（均已 resolved）

## Question

T98 已提供唯一 `seal_result`，T105 已让 daemon 得到完整 immutable `PreparedResourceManifest`，T107 也能从
同一 manifest 产生真实 PNG；但三段能力仍未组成一个可由后续已认证 Profile 调用的原子执行边界，daemon 的
terminal/replay 模型也只能表达单个 PROBLEM frame。如何接通真实 Engine→seal→RESULT_METADATA/RESULT_IMAGE
链路，同时保持当前未注册 process manifest 的网络路径 fail closed，不构造 partial/test-only Profile？

## Answer（本票冻结的实施决定）

1. **新增唯一 prepared-result 深接口**：daemon crate 暴露
   `seal_prepared_png_result(admittedCommand, admittedDocument, preparedManifest)`；只接受既有 typed token，复核
   Command 的 exact renderer/output/diagnostics 与 document bytes identity，再调用 T107 Engine 并把 owned PNG
   bytes 交给 T98 `seal_result`。不接受裸 JSON、裸 pixels、caller digest/length/尺寸或测试专用授权标志。
2. **derived identity 只写一次**：width/height/dpi/output profile 来自 Engine/Command，byte length 与 image digest
   仍只由 seal 从 exact bytes 派生；bridge 对 Engine 与 sealed length/digest 做防御性交叉校验。JPEG、layoutTrace、
   document/profile 漂移和任一 Engine unsupported/error 均在构造任何 terminal frame 前全有或全无失败。
3. **terminal 深化为一或二帧**：daemon registry 的 immutable terminal outcome 可表示一个 PROBLEM，或已经完整
   seal 的 `RESULT_METADATA` 后接 `RESULT_IMAGE`；连接 writer 只消费完整 outcome，按固定顺序写帧。传输中断仍由
   registry exact replay 语义处理，不产生 warning/placeholder/partial success。
4. **Profile 门保持关闭**：当前 `process-manifest.json` 继续 exact
   `rendererProfiles=[] / NOT_REGISTERED / NOT_CERTIFIED / ABSENT`；RequestRegistry 不调用 success kernel，合法
   Command 仍稳定返回现有 COMMAND_ADMISSION problem。测试直接验证生产 deep Interface，不伪造 registered/
   certified manifest，也不增加 bypass constructor、route 或配置开关。
5. **TDD 与共同事实**：先以缺失 public Interface 的 daemon integration test 取得 RED；GREEN 必须让真实
   RenderDocument admission、空 manifest preparation、Engine exact PNG 与 Result seal 一次贯通，并复用既有
   independently replayed Engine/Protocol corpus 逐 byte 核对 metadata、UUID network prefix 与 image bytes。
   现有 daemon problem replay、Java Adapter 双帧校验和 Python process/Engine A2 必须保持绿色。
6. **诚实边界**：本票不实现 general sampling/blend/Text/vector/QR/barcode/JPEG/layoutTrace，不注册 Profile，
   不改 Java/OpenAPI/Web/正式产品 route，不执行 native Skia/FreeType/HarfBuzz build、physical certification、
   J1/A3/READY 或外部副作用；`/prototype` 不计最终产品交付。

## 验证与完成信号

- focused daemon public Interface、Rust workspace fmt/clippy/tests、existing Python independent replay、JSON/hash/
  inventory 与 `git diff --check` 全绿。
- 分级 gate：`render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。最高只报
  `automated_verified`；provider/API Key/费用/真实数据=0，不 push/tag/PR。

## Result

- daemon crate 已提供唯一 public deep Interface
  `seal_prepared_png_result(&AdmittedCommand,&AdmittedRenderDocument,&PreparedResourceManifest)`：它复核 exact
  renderer/document/output/diagnostics identity，经 T107 Engine 取得 owned PNG bytes，再交给 T98 `seal_result`；
  sealed byte length/digest 还会与 Engine identity 防御性交叉核验。
- immutable `TerminalResponse` 已能全有或全无地表达单个 PROBLEM，或严格有序的
  `RESULT_METADATA` + `RESULT_IMAGE`；连接 writer 与 registry replay 均消费完整 terminal frame sequence，未复制
  大 image payload，也不存在 warning/placeholder/partial success。
- TDD 先由缺失 public Interface 得到 unresolved-import RED，再以真实 empty manifest 与真实 fetch/verify/decode
  IMAGE 两条路径完成 GREEN；daemon 12 项单测与 3 项 public integration tests 覆盖 exact PNG/result bytes、双帧
  顺序，以及 document/Profile 漂移、JPEG、layoutTrace 的 pre-result fail-closed。
- RequestRegistry 仍不调用 success kernel，合法网络 Command 仍返回既有稳定 problem；因此本票只达到
  `PREPARED_PNG_RESULT_KERNEL_AUTOMATED_VERIFIED_PROFILE_GATED`，没有把 daemon/product output 宣称为已开放。

## Evidence

- canonical `render`：`.sdlc/evidence/20260825-085407-render/`（44.023 秒）；Windows Rust、Linux network-none/
  UDS、Java Adapter 与 Python independent replay 全绿，process independent 保持 7 vectors/110 checks、A2。
- affected `fast`：`.sdlc/evidence/20260825-085459-fast/`（11.599 秒）；sequential `server`：
  `.sdlc/evidence/20260825-085621-server/`（1034.315 秒），8 个 Maven modules `BUILD SUCCESS`、App
  347 tests/0 failures/0 errors/15 skipped。
- Goal `full`：`.sdlc/evidence/20260825-091345-full/`，17/17 steps、1598.379 秒；包含 renderer Windows/Linux
  UDS、Node 24 Web 26 files/212 tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft 与 inference
  browser journeys。状态回填后的 resolution `fast` `.sdlc/evidence/20260825-094253-fast/` 亦通过。
- Cargo lock SHA-256 为 `4d25500fb52cf97899d0bcc8fac75fb9a7e9ec9528595f2aff3e5dae88111d3a`，process
  manifest SHA-256 为 `f814c98e415e1bee96af198bb36a2eefd91726f3264f26909217e4270afbdeeb`，protocol
  vectors SHA-256 为 `ba7dc3bfd9fcb986ec8e93edf65402f8c956024cd9b29c1908777c67e24a4e62`；vendor
  3067 files identity 未变。
- 状态最高为 `automated_verified`。Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  `ABSENT`、正式产品 route `CLOSED`、native font stack `BUILD_NOT_AUTHORIZED`；provider attempts/API Key reads/
  费用/真实数据=0，未 push/tag/PR，`/prototype` 不计最终产品交付。
