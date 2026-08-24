# 实现空 Canvas 的真实 Engine PNG 输出内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 22, 23, 24, 25, 26（均已 resolved：process seam、typed RenderDocument、surface/PNG、layout preflight 与 definite layout）

## Question

在正式 Template 产品页所需的 E6 仍被真实 RenderEngine 输出阻塞、而完整节点绘制、资源解码、JPEG、Profile
注册与 daemon RESULT 尚未完成时，如何先把最小但真实的空 Canvas 闭包沿同一产品语义执行为 exact PNG bytes，
建立后续 scene/raster/output 可持续深化的小 Interface，同时不把 partial Engine、测试像素或未认证 Profile 暴露给
产品 route？

## Answer（本票冻结的实施决定）

1. **新增 Engine deep module**：在 Rust workspace 新增内部 `renderweave-renderer-engine` crate，唯一外部
   Interface 为 `render_png(&AdmittedRenderDocument, dpi)`；调用者只取得 closed success 或 fail-closed
   error，不学习 JSON traversal、layout、surface、pixel buffer、PNG chunk 或 digest 细节。该 Interface 是后续
   节点绘制、资源准备、JPEG 与 daemon Adapter 的稳定测试面，不把内部 scene/raster seam 暴露为产品合同。
2. **首个真实且严格的支持子集**：只接受已通过 T23 admission、资源 manifest 为空、根 Canvas `children=[]`、
   background alpha 恰为 `00 | FF` 的文档与 PNG positive integer DPI。调用同一 T25/T26 layout Interface，并要求
   结果恰有一个 Canvas entry；任何 child、resource、partial alpha、layout unsupported 或非 PNG 扩展需求均
   fail closed，绝不忽略内容、降级、画 placeholder 或返回旧图片。
3. **真实 surface/raster/encode 顺序**：从 canonical Canvas 的 trim + bleed decimal 通过 T24
   `preflight_surface` 计算 exact pixel dimensions；透明背景强制 RGB=0，opaque 背景保留 authored sRGB8，填满
   bleed-inclusive surface；随后只把这份真实 row-major straight RGBA8 surface 交给
   `encode_straight_rgba8`。不使用 synthetic encoder fixture 充当 raster，不做 pixel snap，也不让 DPI 反馈 layout。
4. **小而完整的结果**：success 返回 width/height/DPI/media type/output profile/byte length/content SHA-256 与
   immutable encoded bytes；digest 只覆盖最终图片 bytes。错误仅区分 contract invariant、当前未支持子集、layout
   与 output failure，不携 raw document、颜色、图片 bytes、路径或业务 identity；本票不形成 public problem
   mapping。
5. **共同语料与 TDD**：新增 shared Engine-PNG vectors `/1`。先以 Rust Interface tests 与 Python stdlib
   independent verifier 对同一透明空 Canvas tracer 共同 RED；最小 GREEN 后覆盖 opaque background、bleed/
   half-up dimensions、alpha-zero RGB normalization，以及 nonempty child、resource-bearing、partial-alpha 与
   unsupported DPI/budget negatives。Python 独立重算 eligibility、surface、pixels、exact PNG bytes 与 digest，
   不调用 Rust。
6. **诚实边界**：本票只证明空 Canvas PNG kernel。`rendererProfiles:[]`、`profileAvailability=NOT_REGISTERED`、
   `certificationStatus=NOT_CERTIFIED` 与 daemon command 的稳定失败行为保持不变；process manifest 只更新离线
   implementation inventory，不新增 advertised Profile。Text/Image/compositionViewport、shape/container paint、
   transform/clip/opacity、resource fetch/decode/font shaping、JPEG、LayoutTrace、registry/queue/cancel success、
   daemon RESULT、Java/OpenAPI/Web/E6/正式 route、formal records、物理 Linux 认证、J1/A3/READY 均不在本票。

## 验证与完成信号

- TDD：shared tracer 在 Rust/Python 对同一 case 共同 RED，随后分别实现至 GREEN；只通过 deep module Interface
  断言 observable bytes/metadata/error，不测试私有 helper。
- 局部：focused Engine vectors、Python stdlib replay、workspace fmt/check/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → Goal `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。Provider attempts、
  API Key reads、付费调用与真实数据均保持 0；不 push/tag/PR，不开放产品 route，不宣称 Template/Renderer READY。

## Resolution（2026-08-24）

- 新增 workspace-internal `renderweave-renderer-engine`，通过唯一 Interface
  `render_png(&AdmittedRenderDocument, dpi)` 串起 admission 后的 definite layout、真实 RGBA8 surface、exact PNG
  encoding 与最终 bytes SHA-256；无资源、无 child、alpha `00 | FF` 之外的输入继续 fail closed。
- shared Engine-PNG `/1` 与 Python stdlib 独立 verifier 最终为 5 rendered + 4 unsupported、9/9 cases、31 checks；
  vector SHA-256 为 `4db688dd2136d1d83fba18ba727b6eaef909dd54902498181107e76f31d9c3c7`。
- focused Rust fmt/check/clippy `-D warnings`/workspace tests、Python replay、JSON 与 `git diff --check` 全绿；分级证据为
  `render` `.sdlc/evidence/20260824-125810-render/`、affected `fast`
  `.sdlc/evidence/20260824-125844-fast/`、顺序 `server` `.sdlc/evidence/20260824-125906-server/` 与 Goal
  `full` `.sdlc/evidence/20260824-131813-full/`。full 用时 1643.948 秒，App 344/0/0/15、Node 24 Web
  26 files/212 tests、runtime canary、Playwright 23 passed + 1 controlled skip、最终 replay E2E 1/1 均通过。
  生命周期回填后的 resolution `fast` `.sdlc/evidence/20260824-134750-fast/` 亦为 exit 0。
- `process-manifest.json` 只把新增 crate 后的 `cargoLockSha256` 更新为
  `a59e9ba5734177be14fe4f46d757734b6621feace8f24dc846997463dd9a0c27`；manifest SHA-256 为
  `2a23251aa38c26f7f4686e7deb41e77df88b5499a8b13ce353622358573e1d94`，没有新增 advertised Profile 或 raster
  inventory。最终边界仍为 `rendererProfiles:[]`、`NOT_REGISTERED`、`NOT_CERTIFIED`、raster `ABSENT`、daemon
  `UNWIRED`、product route `CLOSED`、provider attempts/API Key reads/付费调用/真实数据均为 0。
