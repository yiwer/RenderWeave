# T180 — terminal registry 与 sealed output 5 分钟 retention 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T22, T98, T122, T179 (resolved)

## 目标

物化 Ticket 19 `RW-T19-S8-072`、`RW-T19-S9-015/018/019` 与 Rendering-pipeline
cap-050：`deadlineAndRetention.terminalRegistryAndOutputRetentionMillis` 使用 EXACT `300000`，observed
`299999/300000/300001`，contract stage `REQUEST_CONTROL`、public stage `ENGINE`、zero boundary
`ALGORITHM_INVARIANT`。该 retention-invariant 没有 public problem code；deployment/profile 不匹配必须在 accepted
execution 前失败封闭，禁止伪造 Render problem code。

最小 terminal registry 与已授权完整 sealed response 或安全 terminal problem 保留到
`max(sealedAt, deadlineAt) + 300000 ms`。访问、同 Command replay、Unknown retry、cancel 或 wall-clock 回读都不得
续期；到达 exact expiry 即不再 replay 旧 terminal。未 seal 的 document、lease、resource/raw sidecar/temp 不属于本
retention。pre-command cancel tombstone `60000 ms` 与 capability/resolver recovery `300000 ms` 分属 cap-051/052，
本票不提前实现。

## Interface / seam

- 既有 package-internal `renderweave-rendering-pipeline-capacity-guard/1.0` 增加 code-less exact invariant outcome；
  它只投影 limitId 与 public stage，不把 deployment invariant 伪装为请求失败，也不扩展 public API/SPI。
- Rust daemon 的既有 `RequestRegistry` 是 terminal replay state 的 owning module。production 读取 frame-arrival time
  清理过期 entry，并在 terminal response 已完整形成后再次读取同一 wall-clock seam，以实际 `sealedAt` 与 Command
  deadline 的较晚者固定一次 expiry；现有 fixed-time helper 只供既有 internal tests，production 不缓存 arrival time
  冒充 seal time。
- exact replay 在 expiry 前返回同一 immutable `TerminalResponse`，不修改 entry；exact expiry 先 purge。safe terminal
  problem 与未来 sealed result 共用同一 terminal entry/retention 规则，pre-command cancel 继续走独立 tombstone。
- 不修改 Renderer Command/process wire、manifest identity、HTTP/OpenAPI/Web、migration、Profile registration 或
  certification；不运行 provider、读取 API Key、处理真实数据、push、tag 或 PR。

## TDD 与验证

- Java guard tracer 先因 cap-050 invariant identity/outcome 缺失形成 compile RED；GREEN 覆盖
  `299999/300000/300001`、EXACT accessor 与禁止 public rejection。
- Rust registry 先以 scripted clock 证明当前 arrival-time retention 在 terminal seal 晚于 deadline 时提前过期；
  GREEN 后证明 `max(sealedAt, deadlineAt)+300000`、expiry 前 exact replay、访问不续期、exact expiry 不再 replay。
- 运行 focused Java guard 与 Rust daemon tests、Rendering reactor、`render` 与 `fast`；无 app/public wiring 变化，
  不重复 server/full。最高 `automated_verified`；claim 时 A0、J0 pending，J1 未批准。

## Resolution

- Java internal capacity guard 已登记 code-less cap-050 EXACT invariant；`299999/300000/300001` 分别产生
  violation/admit/violation，并强制普通 `admit`/`rejection` 路径拒绝该无 code invariant，未发明 public problem code。
- Rust `RequestRegistry` production path 现在分别读取 arrival 与 terminal seal wall-clock，使用
  `max(deadlineAt, max(sealedAt, arrivalAt)) + 300000` 一次性确定 retention，checked arithmetic fail-closed；已有
  terminal replay 只 clone immutable response，不改 expiry，exact expiry 先 purge。cap-051 pre-command cancel 路径
  保持独立且未改，cap-052 仍 deferred。
- TDD RED：Rust scripted clock 首先因缺少 `handle_with_clock` 不能编译；Java 在 `-am` reactor 隔离后仅因 cap-050
  enum identity 缺失不能编译。GREEN：Java guard `42/42`；Rust daemon `14` unit + `3` prepared-result integration，
  Rust workspace all-target tests、clippy `-D warnings` 与 fmt 均通过；受影响 Maven reactor 为
  Schema `20` / Validation `13` / Template `86` / Asset `92` / Rendering `296`，零失败。
- A1 gate：`.sdlc/evidence/20260829-124606-render/` metadata `passed`（2/2 steps），
  `.sdlc/evidence/20260829-124806-fast/` metadata `passed`（3/3 steps）；最终 `cargo fmt --check` 与
  `git diff --check` 通过。render gate 的独立 replay 只覆盖既有未变 wire/vector；T180 clock-retention 尚无专项 A2/A3，
  J0 pending、J1 未批准，因此生命周期为 `automated_verified`。
- 未修改 HTTP/OpenAPI/Web、migration、Renderer Command/process wire、manifest identity、Profile registration 或
  certification；未运行 provider、读取 API Key、处理真实数据、push、tag 或 PR。
