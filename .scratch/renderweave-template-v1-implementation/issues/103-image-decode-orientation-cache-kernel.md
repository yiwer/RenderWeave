# 实现 Renderer IMAGE 完整解码、orientation 与 request-local decoded cache 内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 13, 16, 19, 22, 23, 46, 47, 48, 100, 101, 102（均已 resolved）

## Question

T102 已把 exact fetched IMAGE bytes 推进到 media/header/descriptor preflight 与 request-local verified raw cache，
但 canonical sRGB ICC、完整 PNG/JPEG/WebP entropy decode、八种 orientation、straight RGBA8 归一化以及 512 MiB
request-local decoded cache 仍不存在。如何形成后续 Image measure/raster 可直接消费、同时不把未接线的 daemon、
未注册 Profile 或未认证物理构建冒充最终产品能力的 IMAGE 深模块？

## Answer（本票冻结的实施决定）

1. **只深化 Renderer resource IMAGE seam**：新增 IMAGE decoder/cache deep Interface，只消费 T102 的
   `PreparedRawResource`、同 occurrence 的 typed `AdmittedRenderResource`、exact
   `ResourcePreparationProfile` 与单调 wall snapshot；不接收 Asset/Template identity、任意 caller bytes、URL 或
   ownerScope，不修改 RenderDocument、layout、scene、daemon RESULT 或产品 route。
2. **固定纯 Rust decoder closure**：PNG `0.18.1`、`jpeg-decoder 0.3.2` 的
   `platform_independent`/无 Rayon路径、`image-webp 0.2.4` 均 exact pin、vendor 并写入 process dependency identity。
   JPEG 与 WebP 的色度上采样、PNG expansion、decoder memory limit 与所有版本均显式固定；Profile availability 仍为
   `NOT_REGISTERED`，本票只证明候选算法，不宣称 Certified/READY。
3. **完整 IMAGE 语义**：重放 Ticket 16 的静态 PNG/JPEG/WebP 子集；对无 profile、PNG sRGB chunk 或 byte-exact
   canonical sRGB ICC 三种输入统一为 straight RGBA8 sRGB。embedded ICC 必须由 codec 重新抽取并与仓库 canonical
   3144 bytes/SHA-256 精确相等；malformed、missing segment、conflict、非 canonical profile 或 entropy failure 返回
   `DECODE_FAILED`。decoder dimensions 必须与 sealed descriptor 相等，否则折叠 `RENDER_INTERNAL_ERROR`。
4. **orientation 精确一次**：在 decode/sRGB 后按 EXIF 1..8 的冻结枚举执行 identity、四种 rotation/reflection 与两种
   diagonal transform；输出 dimensions 必须是 descriptor 的 logical dimensions，DPI 与其余 metadata 不传播。
5. **512 MiB request-local decoded cache**：key 固定为 exact Renderer Profile + kind/hash/length/media；只缓存
   orientation 后 immutable straight RGBA8，unique content 计 exact `width * height * 4`，inclusive limit 为
   `536_870_912` / `assetsAndFetch.requestDecodedCacheBytes`。命中仍重检 occurrence lease、raw integrity/media/
   descriptor 与 cached pixel digest；corruption 驱逐并失败且不退款，不做跨请求、negative cache、fallback 或重选。
6. **容量与失败安全**：任何输出 allocation 前先做 checked size/cache reservation；codec scratch candidate 上限固定
   `134_217_728` / `rendererSurfaceAndOutput.decoderScratchBytes` 并传给支持 limit 的 decoder。实际峰值内存与双物理
   Linux exact-pixel replay 仍属于后续 certification，A1/A2 不能替代 A3。
7. **TDD 与共同语料**：共享 vector 绑定既有 41-case Asset corpus、canonical ICC、14 个 admitted IMAGE exact RGBA8、
   corrupt entropy/ICC negatives、八种 orientation mapping 与 decoded-cache boundary/state model。Rust primary 先因
   public Interface 缺失 RED；Python independent verifier 只独立重放 vector identity、orientation、digest 与 cache
   状态机，不冒充 JPEG/WebP codec pixel A2。
8. **诚实边界**：本票不实现 FONT full parse/shaping、Image intrinsic measure、scene image paint/sampling/blend、
   daemon success/RESULT、Profile registration、Java/OpenAPI/Web/Product Editor route、formal record issuance、物理
   certification、J1/A3/READY；`/prototype` 继续不计最终产品交付。

## 验证与完成信号

- RED→GREEN：public/focused Rust、14-case codec pixels、8-orientation mapping、ICC/entropy negatives、cache/budget/
  corruption；Python independent structural replay；workspace fmt/check/clippy `-D warnings`/tests、offline/vendor/process
  manifest 与 `git diff --check`。
- 分级 gate：`render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。最高只可
  `automated_verified`；provider/API Key/费用/真实数据=0，不 push/tag/PR。

## Resolution evidence

- RED：public IMAGE decoder/cache Interface 与首个 PNG exact-pixel case 因实现缺失失败；GREEN 后 Rust public test、
  14 个 codec pixel cases、8 个 orientation mappings、ICC/entropy negatives、cache/budget/corruption、workspace
  fmt/check/clippy `-D warnings`/64 tests 全绿。
- 实现：新增 exact-pinned/vendored PNG `0.18.1`、platform-independent JPEG `0.3.2` 与 WebP `0.2.4` decode，
  canonical sRGB ICC、straight RGBA8、logical dimensions、inclusive 512 MiB request-local decoded cache 与 128 MiB
  decoder scratch boundary；cache hit 重检 occurrence/raw/media/descriptor/pixel digest，corruption 驱逐且不退款。
- 上游修正：发现 frozen canonical WebP 把 VP8X ICC bit 写成 alpha bit；改为规范的 `0x20`，保留 ANIM/ANMF
  animation 拒绝语义并重生成 Asset corpus。Asset gate `.sdlc/evidence/20260825-013807-asset/`：Java/Python
  41/41，全程 Profile `NOT_REGISTERED`。
- 共同语料与独立重放：IMAGE vector SHA-256
  `dfff93643ace7658f7e07e8b661bbe1a80af9af6aa2b1fa2138d81e329729c18`，expected-pixel corpus SHA-256
  `7e9f943643709136c69dfce8f0af58889f8a852eefbabe58505f6a5626dfe3b9`；Python 33/33 cases、394 checks，
  orientation/cache/structure 为 A2，codec pixels 如实保持 A1。resource-media replay 54/54 cases、239 checks；
  process replay 7 vectors、110 checks、3067 vendor files。
- identity closure：Cargo.lock SHA-256
  `2ad2056fabeb385f2f809c3daf185081a4ed1a01c6b9d240ecc49c5105399978`，vendor tree SHA-256
  `d850e13ae472704cb3065b3add62cb7e60b3d123dae0e1d62ec89a2be3dbefef`，process manifest SHA-256
  `293df9d26d8a7d884721d7f7314eac80ecee300a20b736645aab533b69b555e1`。
- 分级证据：`render` `.sdlc/evidence/20260825-015039-render/`、affected `fast`
  `.sdlc/evidence/20260825-015224-fast/`、sequential `server`
  `.sdlc/evidence/20260825-015257-server/`（347 tests，0 failures）、Goal `full`
  `.sdlc/evidence/20260825-021032-full/` 全绿；full 包含跨平台 Renderer、完整 Maven、Node 24 Web 212 tests、
  runtime canary、Playwright 23 passed + 1 controlled skip、Draft 与 inference 产品旅程，provider attempts/API Key
  reads/reservations/cost/open authorization=0。状态回填后的 resolution `fast`
  `.sdlc/evidence/20260825-023720-fast/` 3 steps 均 exit 0。
- 边界：IMAGE kernel/decoded cache 已 `automated_verified`，但 daemon output 仍 `UNWIRED`、Profile
  `NOT_REGISTERED`、certification `NOT_CERTIFIED`、raster `ABSENT`、最终 Product Editor 权威预览 route `CLOSED`；
  FONT、scene/raster/RESULT/Profile 与产品接线继续后续 DAG，未声称 J1/A3/READY。
