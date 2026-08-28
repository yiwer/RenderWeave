# T127 — 同步 Template catalog API contractVersion 0.16.0

Type: bug
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T114, T126 (resolved)

## 目标

修复 T114 引入 Template catalog API 后留下的合同身份漂移：OpenAPI `info.version` 已为
`0.16.0`，但运行时 `/api/v1/system/status`、OpenAPI `SystemStatus.contractVersion`
以及生成 Web SDK 仍为 `0.15.0`。四处必须重新表达同一个已发布合同版本。

## Interface / seam

- 唯一运行时观察 seam 为 `GET /api/v1/system/status`；先把既有 canary 的权威期望改为
  `0.16.0` 并取得 RED，再修改生产响应取得 GREEN。
- OpenAPI `info.version` 不再 bump；只把其 `SystemStatus` closed schema 修正为同一版本，随后
  用仓库既有生成器再生 Web SDK，不手改生成产物。
- 不新增/修改 route、operation、payload shape、migration、Template/Rendering 语义或 Profile。

## 验证

focused canary RED→GREEN，SDK generation diff 与 Web tests/typecheck，随后 `web`、`fast`、
顺序 `server`、Goal `full`、resolution `fast`。最高只报 `automated_verified`；A3/J1/READY
不推进，不运行 provider，不读取 API Key，不发送真实数据，不 push/tag/PR。

## Resolution evidence

- TDD focused seam：`EnvironmentCanaryTest` 先得到 `expected 0.16.0 but was 0.15.0` 的 RED；
  生产响应修正后同一用例 1/1 GREEN。
- runtime `/api/v1/system/status`、OpenAPI `info.version`、`SystemStatus.contractVersion` 与再生成
  Web SDK 均为 `0.16.0`；生成产物只有该 literal 发生变化。
- A1 gates：`web` `.sdlc/evidence/20260828-172316-web/`、`fast`
  `.sdlc/evidence/20260828-172402-fast/`、顺序 `server`
  `.sdlc/evidence/20260828-172426-server/`、`asset`
  `.sdlc/evidence/20260828-173629-asset/` 均通过。
- Goal `full` `.sdlc/evidence/20260828-173705-full/` 的前 14/15 steps 通过，runtime canary
  实际返回 `contractVersion: 0.16.0`；唯一失败为无关 Schema Studio browser case 遇到本机
  `ERR_NO_BUFFER_SPACE`（22 passed / 1 skipped / 1 failed）。降低到 1 worker 后对该唯一失败用例的
  精确重放 1/1 通过，证据位于 `.sdlc/evidence/20260828-175513-t127-browser-replay/`。因此不把
  原 `full` metadata 伪报为绿色，而以其 14 个绿色 steps 加精确恢复重放组成 ticket A1。
- A2 未签发 ticket-specific independent replay；A3 未外部强制；J0；provider attempts/API Key
  reads/real data/Profile registration/push/tag/PR = 0。状态回填后的 resolution `fast`
  `.sdlc/evidence/20260828-175621-fast/` 通过。
