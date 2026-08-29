# T181 — pre-command cancel tombstone 60 秒 retention

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T22, T98, T122, T180 (resolved)

## 目标

物化 Ticket 19 `RW-T19-S8-073`、`RW-T19-S9-015/018/019` 与 Rendering-pipeline cap-051：
`deadlineAndRetention.preCommandCancelTombstoneMillis` 使用 EXACT `60000`，observed
`59999/60000/60001`，contract stage `REQUEST_CONTROL`、public stage `ENGINE`、zero boundary
`ALGORITHM_INVARIANT`。该 retention-invariant 没有 public problem code；deployment/profile 不匹配必须在 accepted
execution 前失败封闭，禁止伪造 Render problem code。

在 Command 到达前接纳的 cancel 只保留最小 tombstone，并从首次 cancel 线性化时刻固定保留 `60000 ms`。相同
cancel replay、冲突 retry、访问、后续 cancel 或 wall-clock 回读均不得续期；exact expiry 先删除旧 tombstone，之后
同 requestId 的新事实按普通新请求处理。期限计算必须精确且 overflow fail-closed，不能以 saturating arithmetic
静默改变冻结 retention。

## Interface / seam

- 既有 package-internal `renderweave-rendering-pipeline-capacity-guard/1.0` 增加 code-less exact invariant identity；
  只暴露 limitId 与 public stage，不扩展 public API/SPI。
- Rust daemon 既有 `RequestRegistry` 继续拥有 pre-command cancel tombstone。测试从 registry frame seam 观察 replay、
  conflict 与 expiry，不查询内部 map，也不新增 process wire。
- cap-050 terminal output retention 保持独立；cap-052 capability/resolver recovery deferred。
- 不修改 HTTP/OpenAPI/Web、migration、Renderer Command/process wire、manifest identity、Profile registration 或
  certification；不运行 provider、读取 API Key、处理真实数据、push、tag 或 PR。

## TDD 与验证

- Java tracer 先因 cap-051 enum identity 缺失形成 compile RED，再覆盖 `59999/60000/60001`、EXACT accessor 与
  禁止 public rejection。
- Rust tracer 先证明 saturating expiry 不能满足 exact retention，再通过 frame-level replay/conflict 证明 expiry 前
  immutable replay、不续期与 exact expiry purge。
- 运行 focused Java/Rust、受影响 reactor、`render` 与 `fast`；最高 `automated_verified`。claim 时 A0、J0 pending，
  J1 未批准。

## Resolution

- Java internal capacity guard 已登记 code-less cap-051 EXACT invariant；`59999/60000/60001` 分别产生
  violation/admit/violation，并强制普通 `admit`/`rejection` 路径拒绝该无 code invariant，未发明 public problem code。
- Rust `RequestRegistry` 以首次 pre-command cancel 的线性化时刻加固定 `60000 ms` 写入 tombstone expiry；matching
  replay 与 conflicting retry 均不修改 entry，`+59999` 仍 replay，`+60000` 先 purge 并允许新 digest 建立新 tombstone。
  expiry 改用 checked arithmetic，overflow 在写 registry 前 fail-closed，不再以 saturation 改写冻结时长。
- TDD RED：Java reactor 仅因 cap-051 enum identity 缺失不能编译；Rust near-overflow tracer 证明旧实现会返回已接受的
  saturated tombstone。GREEN：Java guard `43/43`；Rust pre-command focused `3/3`，Windows daemon `16` unit + `3`
  integration、Linux daemon `17` unit + `3` integration，完整 Rust workspace all-target tests、clippy `-D warnings`
  与 fmt 均通过；Maven reactor Schema `20` / Validation `13` / Template `86` / Asset `92` / Rendering `297`，零失败。
- A1 gate：`.sdlc/evidence/20260829-131604-render/` metadata `passed`（2/2 steps），
  `.sdlc/evidence/20260829-131658-fast/` metadata `passed`（3/3 steps）；最终 `git diff --check` 通过。render gate 的
  独立 replay 只覆盖既有未变 wire/vector；T181 clock-retention 尚无专项 A2/A3，J0 pending、J1 未批准，因此生命周期为
  `automated_verified`。
- cap-050 terminal retention 保持独立，cap-052 deferred；未修改 HTTP/OpenAPI/Web、migration、Renderer
  Command/process wire、manifest identity、Profile registration/certification，未运行 provider、读取 API Key、
  处理真实数据、push、tag 或 PR。
