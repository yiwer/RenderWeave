# 实现 Renderer FONT outline/cmap 完整解析与 request-local prepared cache 内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 13, 16, 19, 22, 23, 46, 47, 48, 100, 101, 102, 103（本切片前置均已 resolved）

## Question

T102 只把 exact fetched TTF/OTF bytes 推进到 sfnt directory/checksum、descriptor facts 与 raw cache；它明确把
完整 `glyf`/CFF/cmap parse 延后。如何形成后续 shaping/raster 可以安全消费的请求内 FONT preparation seam，
同时不提前引入尚未获构建授权和认证的 Skia/FreeType/HarfBuzz，也不把候选 parser 冒充最终 Renderer Profile？

## Answer（本票冻结的实施决定）

1. **只深化 Renderer resource FONT seam**：新增 `RequestPreparedFontCache` deep Interface，只消费同 occurrence
   typed FONT resource、T102 `PreparedRawResource`、exact `ResourcePreparationProfile` 与单调 wall snapshot；输出
   occurrence-local `PreparedFontResource`。不接受 Asset/Template identity、任意 caller bytes、系统 font、family、
   locale 或 fallback。
2. **完整重放已批准 outline/cmap 子集**：在 T102 media/descriptor 复核之后，再从 exact bytes 独立解析 sfnt
   directory、所有 TTF simple/composite glyph records 与 composite DAG、CFF header/INDEX/DICT/CharStrings/subr
   调用边界，以及 Unicode cmap formats 0/4/6/12；`faceIndex=0`、flavor、unitsPerEm、glyph/table facts 必须一致。
   collection、variable/color/bitmap/SVG/Graphite/AAT-only、malformed/contradictory bytes 继续 fail closed。
3. **不偷渡 shaping 语义**：本票只证明 FONT resource preparation。可选 `cmap/GDEF/GSUB/GPOS/kern` exact tables
   保留在同一 immutable bytes 中；script/BiDi/grapheme/line break/OpenType feature execution、missing-glyph consumer
   检查、decoration metrics 使用判定、vertical shaping 与 glyph raster 都由后续 Layout/Renderer 票实现。
4. **request-local semantic budget/cache**：key 固定为 exact Renderer Profile + kind/hash/length/media；只共享
   raw cache 已持有的 immutable exact bytes，不复制 FONT body。Engine-owned inclusive limits 固定为 unique fonts
   32、单 FONT tables 256、request tables 4096，对应冻结 Ticket 19 limitId。64 MiB unique FONT bytes 继续由
   sealed RenderDocument admission 唯一执行，raw retention 继续由 T102 的 256 MiB cache guard 唯一执行，本票不造
   duplicate guard。命中仍重检 lease、raw integrity/media/descriptor 与 cached fact digest；corruption 驱逐并
   失败且不退款，不做跨请求/negative cache/fallback。
5. **TDD 与共同语料**：共享 vector 绑定既有 Asset FONT corpus 的 2 个 admitted TTF/OTF 与 6 个 invalid/
   unsupported cases，冻结解析 facts、四个预算边界和 cache state model。Rust public Interface 先 RED；Rust primary
   执行 parser/cache，Python independent verifier 独立重放 vector/cache/facts，并复用 Asset gate 的 fontTools A2
   证明 codec/font parser corpus，不以单侧实现冒充 A3。
6. **诚实边界**：本票不下载或构建 Skia/FreeType/HarfBuzz，不执行 GN/Clang/Linux spike，不接 daemon success、
   shaping/layout Text、scene/raster/RESULT、Profile registration、Java/OpenAPI/Web/Product Editor route、formal
   record、物理 certification、J1/A3/READY；`/prototype` 不计最终产品交付。

## 验证与完成信号

- RED→GREEN：public Interface、TTF/CFF/cmap full parse、malformed outline/subr/composite/cmap negatives、cache hit/
  corruption 与 32/256/4096 boundaries；Rust workspace fmt/check/clippy `-D warnings`/tests；Python
  independent replay、Asset fontTools replay、offline/vendor/process manifest 与 `git diff --check`。
- 分级 gate：`asset`/`render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。最高只可
  `automated_verified`；provider/API Key/费用/真实数据=0，不 push/tag/PR。

## Resolution evidence

- RED→GREEN：缺失的 public FONT preparation/cache Interface 先红；GREEN 后 resource crate 34 unit tests 与 1 个
  public Interface test、Windows/Linux workspace fmt/check/clippy `-D warnings`/tests 全绿。实现覆盖 TTF simple/
  composite `glyf` 与 composite DAG、approved CFF CharStrings/subr 边界、Unicode cmap 0/4/6/12、descriptor facts、
  cache hit/corruption，以及 inclusive 32/256/4096 budgets。
- deep Interface：`PreparedFontResource` 与 `RequestPreparedFontCache` 只消费同 occurrence typed FONT、T102
  `PreparedRawResource`、exact Profile 和单调 wall snapshot；prepared 与 raw cache 共享同一 immutable `Arc` bytes，
  hit 仍复核 lease/media/profile/integrity/facts，corruption 驱逐且不退款。
- 共同语料与独立重放：FONT vector SHA-256
  `1e7b33cf8c02b1ef73b5e9094121e7e524360462200e1f74692410b36603598f`，既有 Asset FONT corpus SHA-256
  `0f44fdef29d989049e77bcf3659fca4b7958b7009053c82d74c46f9f1984e4ca`，mutation corpus SHA-256
  `1c9b677d253719b053693dd94b7cb31cd362ff58d3e2cee6d69efcb107ed7db7`；Python stdlib independent replay
  15/15 cases、184 checks，Asset gate 复用 fontTools/stdlib A2 语料。
- 分级证据：`asset` `.sdlc/evidence/20260825-031650-asset/`、`render`
  `.sdlc/evidence/20260825-031712-render/`、affected `fast` `.sdlc/evidence/20260825-031829-fast/`、sequential
  `server` `.sdlc/evidence/20260825-031847-server/`（App 347 tests，0 failures，15 controlled skips）、Goal `full`
  `.sdlc/evidence/20260825-033613-full/` 17 steps 全绿。full 覆盖跨平台 Renderer、完整 Maven、Node 24 Web
  26 files/212 tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft 与 inference 产品旅程；provider
  attempts/API Key reads/reservations/cost/open authorization=0。
- 边界：FONT preparation/cache 已 `FULL_FONT_PARSE_AUTOMATED_VERIFIED_UNWIRED`；shaping、glyph consumer、native font
  stack、daemon output 仍 `UNWIRED`/`BUILD_NOT_AUTHORIZED`，Profile `NOT_REGISTERED`、certification
  `NOT_CERTIFIED`、raster `ABSENT`、最终 Product Editor 权威预览 route `CLOSED`，未声称 J1/A3/READY。
