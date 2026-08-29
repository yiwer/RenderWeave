# T183 — Renderer Stack water-fill round formula guard

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T120, T122, T182 (resolved)

## 目标

物化 Ticket 19 `RW-T19-S8-083`、`RW-T19-S9-015/018/019` 与 Renderer exact-output cap-053：
`layoutFontAndRaster.stackWaterFillRoundsPerContainer` 使用 FORMULA_MAX `fillChildCount+1`，observed
`fillChildCount/fillChildCount+1/fillChildCount+2`，contract stage `LAYOUT_PROFILE`、public stage `ENGINE`、zero
boundary `ALGORITHM_INVARIANT`。该 Profile algorithm invariant 没有 public problem code；不匹配必须阻止
certification/accepted execution，禁止发明 Render problem taxonomy。

T120 已在 Rust primary 与 Python independent verifier 中实现 bounded authored-order Stack water filling，但 Rust
production loop 仍以内联 `0..=fillChildCount` 拥有公式，冻结 fixture 要求的
`renderweave-renderer-exact-output-capacity-guard/1.0` limit-specific seam 尚不存在。本票把公式与身份收口到唯一
guard，并让真实 loop 在每轮任何 measure/layout work 前消费同一 guard。

## Interface / seam

- 深化既有 `renderweave-renderer-layout` module；新增一个小的 Renderer-workspace-internal public Interface，供真实
  layout implementation 与未来 exact-output fixture executor 共用。不新建 crate、跨上下文 common module 或
  test-only override。
- guard 只接收 `fillChildCount` 与 observed round count，使用 checked arithmetic 判定 `<= fillChildCount+1`，并在
  rejection 中封闭 contractId、limitId、contract/public stage 与 zero boundary；不承载 Node、LayoutBox 或 caller
  budget。
- `stack_main_fill_allocations` 在每轮 allocation/measurement 前预留 round；超公式或 arithmetic overflow 继续经
  既有 fail-closed unsupported 路径终止，零 partial layout/output。
- cap-054+ deferred；不修改 Renderer process wire、manifest identity、HTTP/OpenAPI/Web/Flyway、Profile
  registration/certification，不运行 provider、读取 API Key、处理真实数据、push、tag 或 PR。

## TDD 与验证

- 先在 public guard seam 写 cap-053 三点 tracer；缺少 Interface 必须产生 compile RED。
- 最小实现 guard 后接入真实 Stack loop；保持 T120 frozen layout vectors byte/box exact，并补 checked-overflow
  fail-closed 回归。
- 运行 focused layout crate、Rust workspace fmt/clippy/test、canonical render 与 fast gate；最高
  `automated_verified`。claim 时 A0、J0 pending，J1 未批准。

## Resolution

- `renderweave-renderer-layout` 已新增唯一 `RendererExactOutputCapacityGuard` Interface，contract identity 固定为
  `renderweave-renderer-exact-output-capacity-guard/1.0`。cap-053 三点按 `fillChildCount+1` FORMULA_MAX 判定；
  rejection 封闭 frozen limitId、LAYOUT_PROFILE / ENGINE、无 problem code 与 ALGORITHM_INVARIANT。
- `stack_main_fill_allocations` 已删除 inline `0..=fillChildCount` authority；每轮在构造 active set、计算 share 或
  写 allocation 前，以 checked observed-round increment 调用同一 guard。公式 arithmetic overflow 与超限均沿既有
  `STACK_MAIN_FILL` fail-closed 路径终止，零 partial layout/output。
- TDD RED 精确为 integration tracer 无法导入缺失 Interface；GREEN 后 focused layout crate 为 `7/7`。Rust
  workspace all-target tests、fmt 与 clippy `-D warnings` 全绿。
- A1：render `.sdlc/evidence/20260829-142852-render/` metadata passed（2/2），fast
  `.sdlc/evidence/20260829-142948-fast/` metadata passed（3/3）。render gate 同时保持 Python independent definite
  layout `288/288` cases、`868` checks，但只证明既有布局输出无漂移，不冒充 T183 guard identity 专项 A2。
- T183-specific A2/A3 无，J0 pending、J1 未批准，因此生命周期为 `automated_verified`。cap-054+ deferred；无
  HTTP/OpenAPI/Web/Flyway/Renderer process wire/manifest/Profile registration/certification/provider/API Key/真实
  数据/费用/push/tag/PR；Profile 保持 NOT_REGISTERED、Certification NOT_CERTIFIED、Raster ABSENT。
