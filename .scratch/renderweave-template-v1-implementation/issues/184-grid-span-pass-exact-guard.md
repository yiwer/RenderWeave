# T184 — Renderer Grid span constraint exact-pass guard

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T66, T122, T183 (resolved)

## 目标

物化 Ticket 19 `RW-T19-S8-084`、`RW-T19-S9-015/018/019` 与 Renderer exact-output cap-054：
`layoutFontAndRaster.gridSpanPassesPerConstraint` 使用 EXACT `1`，observed `0/1/2`，contract stage
`LAYOUT_PROFILE`、public stage `ENGINE`、zero boundary `ALGORITHM_INVARIANT`。该 Profile algorithm invariant
没有 public problem code；不匹配必须阻止 certification/accepted execution，禁止发明 Render problem taxonomy。

T66 已在 Rust primary 与 Python independent verifier 中实现按
`(spanLength,startIndex,materializedOrder)` 稳定排序、每条 Grid AUTO/span constraint 恰好处理一次，但 frozen
fixture 要求该 exact identity 由 T183 已建立的唯一
`renderweave-renderer-exact-output-capacity-guard/1.0` seam 承载。本票深化同一 guard，并让真实 production
constraint loop 在每条 constraint 的任何 measure/layout work 前消费 EXACT `1`。

## Interface / seam

- 只深化既有 `renderweave-renderer-layout` guard；不新建 crate、重复 guard、跨上下文 common module 或 test-only
  override。
- guard 接收 observed pass count，只接受 `1`；rejection 封闭 cap-054 limitId、contract/public stage、无 problem
  code 与 zero boundary。cap-053 的公式与 identity 保持不变。
- `apply_grid_auto_constraints` 在稳定排序后、读取 span extent 或分配 deficit 前，对每条 constraint 调用同一
  guard；不匹配沿既有 Grid AUTO fail-closed unsupported 路径终止，零 partial layout/output。
- cap-054 之后的实现 residual deferred；不修改 Renderer process wire、manifest identity、HTTP/OpenAPI/Web/
  Flyway、Profile registration/certification，不运行 provider、读取 API Key、处理真实数据、push、tag 或 PR。

## TDD 与验证

- 先在 public guard seam 写 cap-054 `0/1/2` tracer；缺少方法必须产生 compile RED。
- 最小扩展既有 invariant identity 后接入真实 Grid loop；保持 T66 frozen layout vectors byte/box exact，并证明
  cap-053 metadata 未漂移。
- 运行 focused layout crate、Rust workspace fmt/clippy/test、canonical render 与 fast gate；最高
  `automated_verified`。claim 时 A0、J0 pending，J1 未批准。

## Resolution

- 既有唯一 `RendererExactOutputCapacityGuard` 已扩展 cap-054 EXACT `1`；同一 rejection 类型现在以私有 limit
  discriminator 封闭 cap-053/cap-054 各自 limitId，同时保持共同的 LAYOUT_PROFILE / ENGINE、无 problem code 与
  ALGORITHM_INVARIANT。cap-053 既有公式与 metadata 回归未漂移。
- production `apply_grid_auto_constraints` 保持 `(spanLength,startIndex,materializedOrder)` 稳定排序，并按值消费
  constraint；每个实际 constraint 在读取 span extent、计算 deficit 或分配 AUTO tracks 前调用同一 guard。该结构
  保证每条实际 constraint 只有一次 pass；任何 guard mismatch 沿既有 `GRID_AUTO_TRACK` internal unsupported
  fail-closed，零 partial layout/output。
- TDD compile RED 精确来自 public tracer 调用缺失方法的两处 E0599；最小 GREEN 后 public guard 2/2、focused
  layout 8/8。Rust workspace all-target tests 97/97，fmt 与 clippy `-D warnings` 全绿。
- A1：render `.sdlc/evidence/20260829-143918-render/` metadata passed（2/2），fast
  `.sdlc/evidence/20260829-144014-fast/` metadata passed（3/3）。render gate 保持 Python independent definite
  layout `288/288` cases、`868` checks，但只证明 T66 布局输出无漂移，不冒充 T184 guard identity 专项 A2。
- T184-specific A2/A3 无，J0 pending、J1 未批准，因此生命周期为 `automated_verified`。无 HTTP/OpenAPI/Web/
  Flyway/Renderer process wire/manifest/Profile registration/certification/provider/API Key/真实数据/费用/push/tag/
  PR；Profile 保持 NOT_REGISTERED、Certification NOT_CERTIFIED、Raster ABSENT。
