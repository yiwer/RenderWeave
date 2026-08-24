# 实现 Renderer resource media/descriptor 复核与 request-local raw cache 内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 13, 16, 19, 22, 23, 46, 47, 48, 100, 101（均已 resolved）

## Question

T101 已让 daemon 按 manifest 顺序取得并完成 length/SHA-256 核验的 exact resource bytes，但这些 bytes 尚未按
sealed `kind/mediaType/technicalDescriptor` 重新证明，request 内也没有受 Ticket 19 容量约束、绑定 exact
Renderer Profile 的 verified raw cache。如何先形成不依赖完整 IMAGE entropy decode、FONT outline parse、Skia/
FreeType build 或 Profile registration 的严格资源事实子闭包，并保证 cache 不能绕过 lease、first-error、预算与
descriptor 一致性？

## Answer（本票冻结的实施决定）

1. **只深化 resource deep module**：在 `renderweave-renderer-resource` 内新增唯一 raw-preparation Interface；只消费
   T46 typed `AdmittedRenderResource` 与 T101 owned `FetchedResource`，不重新解析 RenderDocument、不接收任意 URL、
   Asset/Template identity、ownerScope 或 caller bytes。成功值只暴露 occurrence-local `resourceId` 与共享的 immutable
   verified raw content。
2. **Profile identity 不等于 Profile available**：cache key 固定包含 exact `renderweave-renderer/1.0` 资源解释身份，
   再包含 `kind + lowercase sha256 + byteLength + mediaType`；这只是冻结算法 identity，不向 process manifest 注册
   Profile、不改变 HELLO capability，也不开放 daemon success path。
3. **本票的 media/descriptor 子闭包**：对无 embedded ICC 的静态 PNG/JPEG/WebP 重做 magic、closed container/header、
   允许的 bit-depth/color/component/frame 子集、唯一 EXIF orientation、encoded/logical dimensions 与
   `SRGB_8BIT` descriptor 比较；对 TTF/OTF 重做 sfnt magic、single-face directory、table bounds/uniqueness/checksum、
   banned/required table、flavor 与 unitsPerEm 比较。wrong declared media/magic 返回 `MEDIA_MISMATCH`；结构或已排除
   feature 返回 `DECODE_FAILED`；stored descriptor 与 exact bytes 漂移是内部不变量并折叠
   `RENDER_INTERNAL_ERROR`。canonical ICC 解压等值、完整 image entropy decode、完整 glyph/CFF parse 均留给后续
   decoder 票，不把 header parse 冒充完整 decode。
4. **256 MiB request-local raw cache**：固定 inclusive `268_435_456` bytes 与
   `assetsAndFetch.requestRawCacheBytes`。只在 transport integrity 与本票 media/descriptor 全部通过后原子插入；
   unique content 才占 retained-byte budget，duplicate exact key 复用同一 immutable bytes。cache miss 不建立负缓存。
5. **cache hit 仍是一次 occurrence**：lookup 前按 caller-supplied monotonic wall snapshot 重检当前 entry lease；命中后
   重新核对 length/SHA-256/media/descriptor，不能减少 manifest/occurrence/lease/first-error 语义。发现 cached bytes
   corruption 时立即驱逐该 key 并 terminal fail，不回退旧内容、不换 URL、不重新 resolve current。
6. **安全 problem 与 Debug**：公开错误只含 closed code、`RESOURCE_PREPARATION`、opaque resourceId 与容量错误唯一
   limitId；Debug/problem/evidence 不含 bytes、URL、hash、descriptor 原文、observed count 或底层 parser 文本。
7. **共同语料与 TDD**：新 shared corpus 只引用既有 immutable Asset acceptance vectors 的 exact bytes，并另加
   media drift、descriptor drift、raw-budget、duplicate hit、lease expiry 与 corruption 模型。Rust primary 与 Python
   independent verifier 使用独立 parser/control flow；先由缺失 public Interface 与首个合法 PNG case 共同 RED，再逐
   格式 GREEN。
8. **诚实边界**：本票不实现 canonical ICC 解压等值、完整 IMAGE decode/orientation pixels/sRGB conversion、FONT
   outline/CFF full parse/shaping、512 MiB decoded cache、network skip-on-cache-hit orchestration、daemon output、scene/
   raster/JPEG/LayoutTrace、Profile registration、OpenAPI/Web/Product Editor route、formal records、physical Linux
   certification、J1/A3/READY。resource bytes 最多推进为 `MEDIA_DESCRIPTOR_PREFLIGHT_AUTOMATED_VERIFIED`，不能称
   `DECODED`。

## 验证与完成信号

- 逐格式 public Interface RED→GREEN；focused Rust + shared Python independent replay；workspace fmt/check/clippy
  `-D warnings`/tests、`py_compile`、JSON inventory/SHA、offline/vendor/process manifest 与 `git diff --check`。
- 分级 gate：`render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。最高状态只可
  `automated_verified`；不运行 provider、不读取 API Key、不发送真实数据、不 push/tag/PR，不把 `/prototype`
  视为最终产品交付。

## Resolution evidence

- `renderweave-renderer-resource` 已新增 sealed media/descriptor preparer 与 request-local content-addressed raw cache；
  PNG/JPEG/WebP/TTF/OTF 的 header/sfnt 子闭包、wrong magic/media、排除 feature、descriptor drift、lease hit、
  duplicate reuse、inclusive 256 MiB budget 与 corruption eviction 均由 Rust primary 覆盖。resource 18 unit + 2
  fetch public-interface + 2 media/cache public-interface tests 全绿，focused Clippy `-D warnings` 通过。
- Rust 与独立 Python verifier 对共享资源语料重放为 54/54 cases、239 checks；T102 vector SHA-256
  `e8f8869b0d41b9253a5e61939d9e517f103a0acc5f2fab58eddc5995619c044f`，既有 Asset bytes vector SHA-256
  `74638228ba0910079feb86412314361f09d1761583a58176e6449d90435e9da2`。Cargo.lock 未引入新 dependency，保持
  SHA-256 `5acd41e397411003ae3259820df73033cd9f7a048722eb38bee6c91a8cc71f82`。
- 首次 `render` `.sdlc/evidence/20260825-001457-render/` 正确发现测试直连 base64 dependency 导致 lock drift；移除
  该依赖并改为测试内 fixture decoder 后，绿色 A1 证据为 `render`
  `.sdlc/evidence/20260825-001652-render/`（2 steps，37.673 秒）、affected `fast`
  `.sdlc/evidence/20260825-001738-fast/`（3 steps，11.244 秒）、顺序 `server`
  `.sdlc/evidence/20260825-001757-server/`（1006.124 秒）与 17-step Goal `full`
  `.sdlc/evidence/20260825-003452-full/`（1524.803 秒），均 exit 0。
- `full` 中 Windows/offline Linux Rust workspace 与 Linux UDS、Java renderer 29 tests、App 347/0/0/15、Node 24
  Web 26 files/212 tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft browser journey 与
  inference replay E2E 1/1 均通过；R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost/open
  authorization=0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260825-010305-fast/` 3 steps 均 exit 0（11.003 秒）。
- 完成边界保持诚实：resource bytes 为 `MEDIA_DESCRIPTOR_PREFLIGHT_AUTOMATED_VERIFIED`，raw cache 为
  `REQUEST_LOCAL_CONTENT_ADDRESSED_268435456_BYTES_AUTOMATED_VERIFIED_UNWIRED`；完整 IMAGE/FONT decode、decoded
  cache、Profile registration、certification、process raster、daemon RESULT 与最终 Product Editor route 仍分别为
  `ABSENT/NOT_REGISTERED/NOT_CERTIFIED/UNWIRED/CLOSED`。未运行 provider、读取 API Key、发送真实数据或
  push/tag/PR，也未把 `/prototype` 视为最终产品交付。
