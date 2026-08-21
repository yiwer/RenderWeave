# 实现 RenderResource manifest 防御性准入与静态容量内核

Type: task
Status: resolved
Claimed by: Codex `/root`（single-writer）
Blocked by: 13, 23（均已 resolved；Ticket 19 的资源容量 cells 已冻结，但其 formal record/认证工作仍保持 open）

## Question

在 Renderer 尚未实现 HTTPS transport、actual-byte verification、decode/shaping、raster 与 daemon success path 时，
如何先把已经由 Java 封存进 exact RenderDocument 的 RenderResource 从“只检查字段存在”深化为 Rust Engine 可安全
消费的 typed/fail-closed 边界，并防御性重做所有在 resource fetch 前即可判定的字段、descriptor 与 Ticket 19
静态容量约束，同时不把 manifest admission 冒充资源已下载或已解码？

## Answer（本票冻结的实施决定）

1. **只深化既有 document deep module**：继续由 workspace-internal `renderweave-renderer-document` 的唯一入口
   `validate_render_document` 完成 strict/canonical document admission；成功值新增 immutable typed
   `AdmittedRenderResource` 列表，不新增产品 API、网络 client、第二套 JSON parser、daemon success path 或 Profile。
2. **closed scalar contract**：每个 manifest entry 必须保持 encounter order 与 tree demand 一一对应，并严格验证
   `resourceId`、非空且不超过 2 KiB UTF-8 的 `fetchUrl`、正整数 epoch-second `expiresAt`、
   `sha256:` + 64 lowercase hex、exact `renderweave-asset-acceptance/1.0`、正整数 `byteLength`，以及
   IMAGE/FONT 与 `image/png|image/jpeg|image/webp|font/ttf|font/otf` 的 closed kind/mediaType 对应。
3. **descriptor contract**：IMAGE 只接受八种 orientation、正 encoded/logical dimensions、正确的 90°/transpose
   维度交换关系、`frameCount=1` 与 `SRGB_8BIT`，并防御性应用单内容 20,000 px edge/100M pixels；FONT 只接受
   `faceIndex=0`、`TRUETYPE_GLYF|CFF` 与 `unitsPerEm=16..16384`。IMAGE/FONT 声明 bytes 上限分别为
   64 MiB/32 MiB。这里只验证 sealed facts，不解析或信任 actual bytes。
4. **fetch 前静态预算**：以 checked integer、resource encounter order 计算 entries 2,048、unique exact contents
   128、occurrence raw 2 GiB、image pixels 1B、font bytes 512 MiB、unique raw 256 MiB、unique image pixels
   125M、unique font bytes 64 MiB、manifest 4 MiB、fetch URL 单项 2 KiB/合计 4 MiB。unique exact content key
   固定为 `(kind, sha256, byteLength, mediaType)`；相同 key 的 descriptor 必须完全相同。任何 overflow/超限都
   原子拒绝整个 document，不截断、不合并 manifest entry，也不因未来 cache hit 减少 occurrence 预算。
5. **typed handoff 不越权**：admitted resource 只暴露 Engine 必需的 opaque `resourceId`、lease URL/expiry、
   digest、media/length 与 technical descriptor；不引入 assetId/contentVersion/ownerScope/Template identity。
   `fetchUrl` canonical HTTPS/allowlist、lease 对 command deadline + 5s 的检查、HTTP response/retry、actual
   length/hash/magic/full decode、request-local cache 与 cancellation 仍由后续真实 Engine/resource 票实现。
6. **共同语料与 TDD**：先把 shared RenderDocument/vector verifier identity 升级为 `/2`，增加 scalar、descriptor
   与 compact generated aggregate boundary cases，使当前 Rust/Python 同时 RED；随后在 Rust primary 与 Python
   stdlib independent verifier 中分别实现同一已冻结判断。Java sealer bytes 与既有 all-kinds fixture 不改义。
7. **诚实能力边界**：本票最高只证明 canonical manifest defensive admission 与 fetch-before 静态预算；不证明
   URL 安全、lease 有效、资源存在、bytes/hash/media/decode 正确、font shaping、Image HUG、world scene、paint/
   raster/JPEG、daemon RESULT、Renderer Profile/certification、公开 preview/render 或 Editor E6。

## 验证与完成信号

- 局部：focused Rust document tests + Python stdlib independent replay → workspace fmt/clippy `-D warnings`/test、
  `py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → `server`/`fast` → 完整 `full`；新增 Maven/OpenAPI/Web/migration 均不在本票。
- 保证上限：Rust/document/gate A1，shared exact resource vectors 的 Rust+Python replay A2；无 actual fetch/decode、
  physical Linux certification、A3 或 J1。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；Profile
  持续 NOT_REGISTERED、certification NOT_CERTIFIED、resource bytes UNFETCHED、raster ABSENT、daemon output
  UNWIRED；不 push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `renderweave-renderer-document` 现把每个 sealed RenderResource 收敛为 immutable typed admission，并在任何
  fetch 前 fail closed 重验 encounter order/resource demand 双射、closed scalar/kind-media、IMAGE/FONT descriptor、
  单内容上限、request occurrence/unique exact-content 预算、manifest bytes 与 URL bytes；所有累计使用 checked
  integer，相同 `(kind,sha256,byteLength,mediaType)` 的 descriptor 必须一致。
- shared RenderDocument/resource vector 与 Python verifier identity 升级为 `/2`：14 个 document cases、42 个
  resource scalar/descriptor cases、19 个 aggregate cases，共 75/75、97 checks；Rust primary 与 Python stdlib
  independent replay 均通过。vector SHA-256 为
  `29dc9ef7f6c5430d8845fd87be3d9188e9a56ef2e9571d40cf3e5bc0a9e58e57`；既有 all-kinds fixture SHA-256 保持
  `1b83a605c13837b0fa6d3a3cbf5e84fb97c71116ba8a81942cf97a3d7df9b031`。
- A1/A2 证据：`render` `.sdlc/evidence/20260822-064735-render/`、`server`
  `.sdlc/evidence/20260822-064836-server/`、治理前 `fast` `.sdlc/evidence/20260822-070814-fast/`；resolution
  governance 后的最终 Fast/Full 目录按不可自指策略只在 commit handoff 报告。
- 生命周期为 `resolved / automated_verified`。Profile 仍 NOT_REGISTERED、certification NOT_CERTIFIED、resource
  bytes UNFETCHED、world scene/raster ABSENT、daemon output UNWIRED；未证明 URL/lease/actual bytes/hash/media/
  decode/cache、A3/J1 或 READY。Provider attempts/API Key reads/paid external calls 均为 0；未发送真实数据，未
  push/tag/PR。
