# 接通 daemon manifest-order resource preparation pipeline

Type: task
Status: resolved / automated_verified
Completed by: Codex `/root`（single-writer）
Blocked by: 13, 16, 19, 22, 23, 46, 47, 48, 100, 101, 102, 103, 104（本切片前置均已 resolved）

## Question

T101 已让 daemon 按 manifest 批量 fetch exact bytes，但成功 bytes 随即被丢弃；T102–T104 的 raw/media、IMAGE
decode 与 FONT prepare 仍只在独立 kernel 中验证。如何把它们接成真实请求级资源准备链，并保证“一个资源完整
fetch/verify/decode-or-prepare 后才开始下一个资源”的 first-error 顺序、共享总 fetch budget 与原子零输出失败，
同时不把尚未实现的 shaping、scene/raster、RESULT 或未注册 Profile 冒充完成？

## Answer（本票冻结的实施决定）

1. **唯一 manifest pipeline deep Interface**：新增 `ManifestResourcePreparer`，输入 admitted manifest、exact
   `FetchTargetPolicy`、唯一 `ResourceFetcher`、Renderer Profile、command deadline 与请求开始 wall snapshot；输出
   只可能是完整 immutable `PreparedResourceManifest`，成员保持 manifest order，并可按 resourceId 只读查找。
2. **完整串行单元**：每个 manifest entry 依次执行 target admission → fetch/retry/body integrity → raw cache/media/
   descriptor → IMAGE decode/orientation/cache 或 FONT outline/cmap/cache；当前 entry 完整成功前不触发下一 entry。
   这取代“先 fetch 全部、后 prepare 全部”，冻结 first error 与零 partial prepared manifest。
3. **请求级 transport state**：把 `ResourceFetcher` 深化为逐 entry `fetch_resource`，显式共享一个
   `RequestResourceFetchState`，其中 physical bytes 与 resource phase monotonic start 贯穿整个 manifest；不得因逐项
   调用重置 512 MiB、20 秒、attempt/backoff 或 lease/deadline checkpoints。
4. **请求级 semantic caches**：pipeline 内部唯一持有 T102 raw、T103 decoded IMAGE、T104 prepared FONT caches。
   duplicate content 仍按 occurrence 独立 fetch/lease/first-error 处理，但 raw/decode/font semantic content 按 exact
   Profile key 复用；输出记录 raw 与 final semantic cache hit，不接受 URL/resourceId 作为 cache identity。
5. **closed problems**：fetch problem 原样保持；`MEDIA_MISMATCH`、`DECODE_FAILED` 与资源 capacity problem 在
   `RESOURCE_PREPARATION` 携带安全 resourceId/limitId；target、descriptor、fetcher output、cache/profile invariant
   漂移统一折叠无 locator 的 `RENDER_INTERNAL_ERROR`。任何失败不返回 partial manifest、Scene 或 RenderOutput。
6. **daemon 真实接线但不冒充 success**：`RequestRegistry` 在静态 document/layout preflight 后调用该 pipeline，并
   持有完整 prepared manifest 至后续 Engine seam；本票收口时因 Profile 未注册仍返回既有
   `COMMAND_ADMISSION/RENDER_INTERNAL_ERROR`，但不再丢弃未经准备的 fetched bytes。
7. **TDD 与独立重放**：public Interface 先 RED；共享 vector 绑定既有 Asset IMAGE/FONT corpus，冻结
   font→image→duplicate-font manifest、cache hits/stats、preparation-before-next-fetch、fetch failure stop、problem
   projection 与 honest boundary。Rust primary 执行真实 codecs/parser；Python stdlib 独立重放 order/cache/problem
   状态机，不冒充 codec pixel 或物理 transport A2。
8. **诚实边界**：本票不构建 Skia/FreeType/HarfBuzz，不实现 shaping/glyph consumer、Image/Text intrinsic、scene/
   raster/encode/RESULT/Profile registration、Java/OpenAPI/Web/Product Editor route、formal record、physical
   certification、J1/A3/READY；`/prototype` 不计最终产品交付。

## 验证与完成信号

- RED→GREEN：public pipeline/session/output Interface；真实 admitted FONT+IMAGE+duplicate content；first preparation
  failure 阻止后续 fetch；fetch failure 阻止后续 prepare；empty/shape/invariant/problem negatives；daemon exact
  terminal replay；Windows/Linux workspace fmt/check/clippy `-D warnings`/tests 与 Python independent replay。
- 分级 gate：`asset`/`render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。最高只可
  `automated_verified`；provider/API Key/费用/真实数据=0，不 push/tag/PR。

## 实施结果与证据

- `ManifestResourcePreparer` 已成为 daemon 唯一资源准备入口：每个 manifest entry 严格串行执行 target admission →
  fetch/integrity → raw/media → IMAGE decode 或 FONT prepare，任一失败均停止后续 fetch/prepare，成功时才返回完整
  immutable `PreparedResourceManifest`。`RequestResourceFetchState` 与 raw/decoded/font caches 均贯穿整次请求。
- protocol closed problem codec 已接纳 `MEDIA_MISMATCH` 与 `DECODE_FAILED`；daemon 在 lease、静态资源策略与 layout
  preflight 后真实调用 pipeline，并在尚无 Profile/output seam 时保持既有
  `COMMAND_ADMISSION/RENDER_INTERNAL_ERROR` terminal，不产生 partial Scene、image bytes 或 RESULT。
- Rust workspace、fmt 与 Clippy `-D warnings` 全绿；resource 39 unit + 1 public Interface、daemon 11、protocol 11
  tests 全绿。独立 Python replay 7/7 cases、102 checks，vector SHA-256
  `4943ac9da9e44aa08607d8ddee7f4c677dcf0d9ae84f1a1b6831f2c94782ccb7`，mutation corpus SHA-256
  `99ec8636a6fe4826766695615a850f232736a945491e8c2ffe9a1d8dfc752e9c`。
- 分级证据：`asset` `.sdlc/evidence/20260825-044326-asset/`、canonical `render`
  `.sdlc/evidence/20260825-044459-render/`、affected `fast` `.sdlc/evidence/20260825-044549-fast/`、sequential
  `server` `.sdlc/evidence/20260825-044609-server/` 与 Goal `full`
  `.sdlc/evidence/20260825-050311-full/` 均通过；full 17/17 steps，包含 Node 24 Web 26 files/212 tests、runtime
  canary、Playwright 23 passed + 1 controlled skip、Draft 与 inference 正式产品旅程；resolution `fast`
  `.sdlc/evidence/20260825-053323-fast/` 再次通过。
- 诚实边界未升级：font shaping/glyph consumer/scene/daemon output 均 `UNWIRED`，native font stack
  `BUILD_NOT_AUTHORIZED`，Profile `NOT_REGISTERED`，certification `NOT_CERTIFIED`，raster `ABSENT`，正式 Product
  Editor route `CLOSED`；provider attempts/API Key reads/reservations/cost/open authorization/真实数据均为 0。
