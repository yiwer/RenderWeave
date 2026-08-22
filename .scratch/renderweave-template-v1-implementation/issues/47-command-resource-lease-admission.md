# 实现 Command-bound RenderResource lease 覆盖准入

Type: task
Status: resolved
Claimed by: Codex `/root`（single-writer）
Blocked by: 13, 22, 23, 46（均已 resolved；Ticket 19 的 lease safety margin cell 已冻结，formal record/认证仍 open）

## Question

在 Renderer 尚未实现 HTTPS transport、actual-byte verification、decode/shaping、raster 与 success RESULT 时，
如何把已经分别通过 strict Command 和 typed RenderDocument admission 的 `deadlineAt` 与每个 `expiresAt` 合并成
一个 fail-closed handoff invariant：所有资源 lease 在 Command admission 时都必须覆盖绝对 deadline 后精确
5 秒，并按 manifest 顺序返回第一个不足资源的稳定 code/resourceId，同时不把这一步冒充 fetch-time expiry、
URL 安全或已注册 Renderer Profile？

## Answer（本票冻结的实施决定）

1. **深化既有 deep module seam**：由 `renderweave-renderer-document` 对 typed
   `AdmittedRenderDocument` 暴露纯函数式 lease coverage admission；输入只有 protocol 已解析的
   `deadline_epoch_millis`，不重新解析 JSON、不读取 Clock、网络、Resolver、配置或业务身份。
2. **精确整数比较**：冻结 `leaseSafetyMarginMillis=5000`；对每个 manifest entry 按 encounter order 比较
   `expiresAtEpochSecond × 1000 >= deadlineEpochMillis + 5000`。使用足够宽的 checked/exact integer 域，既不把
   epoch-second 向下舍入成虚假覆盖，也不因合法的大整数 expiry 乘法溢出。5000 精确接受、4999 拒绝。
3. **稳定零输出失败**：第一个不足 lease 返回 `RESOURCE_LEASE_EXPIRED`、`COMMAND_ADMISSION` 与该项 opaque
   `resourceId`；不返回 URL、expiry、deadline、hash、actual margin、path 或自由文本参数。daemon 在任何
   resource fetch/layout/raster/RESULT 前形成并缓存同一 terminal problem，exact Command replay 保持相同结果。
4. **共同语料与 TDD**：升级既有 RenderDocument/resource shared vector 与 Rust/Python independent verifier，
   覆盖 resource-free、4999/5000/5001、Java ceil-to-second producer 结果、manifest first-error、第二项首错与
   `u64` 最大 expiry。先使 primary/independent replay RED，再实现 Rust kernel 和 daemon 接线。
5. **明确不越界**：canonical HTTPS/app-origin/path-prefix、DNS/egress、attempt-time expiry/deadline、HTTP/retry、
   actual bytes/length/hash/media/magic/decode、request-local cache/cancel、scene/raster/JPEG/RESULT/Profile 注册、
   E6 与认证均不在本票；process manifest 继续 NOT_REGISTERED/NOT_CERTIFIED/raster ABSENT。

## 验证与完成信号

- 局部：shared-vector Rust tests + Python stdlib independent replay → workspace fmt/clippy `-D warnings`/test、
  `py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → `server`/`fast` → 完整 `full`；不新增 Java/OpenAPI/Web/migration 或外部 I/O。
- 保证上限：Rust/daemon/gate A1，shared exact lease boundary 的 Rust+Python replay A2；无 actual fetch、A3/J1。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `renderweave-renderer-document` 现以纯函数和 `i128` 精确整数域按 manifest order 验证每项 typed lease 覆盖
  Command deadline 后 5000ms；5000 精确接受、4999 拒绝，合法 `u64` 最大 expiry 不会在毫秒换算时溢出。
- daemon 在 document admission 后、layout/fetch/raster/RESULT 前返回并缓存首个
  `RESOURCE_LEASE_EXPIRED/COMMAND_ADMISSION` + opaque resourceId 的 canonical problem；parameters 为空，exact
  Command replay 保持相同结果，未泄漏 URL、expiry、deadline、hash 或 path。
- shared RenderDocument/resource vector 与 Python verifier identity 升级为 `/3`：14 document、42 resource
  scalar/descriptor、19 aggregate、8 lease，共 83/83 cases、106 checks；vector SHA-256 为
  `ba0680eb5506062b887674f513ad4f9026d56a974c5b541f28ff6098c4c8de3a`，all-kinds fixture SHA-256 保持
  `1b83a605c13837b0fa6d3a3cbf5e84fb97c71116ba8a81942cf97a3d7df9b031`。
- A1/A2 证据：`render` `.sdlc/evidence/20260822-075544-render/`、`server`
  `.sdlc/evidence/20260822-075614-server/`、治理前 `fast` `.sdlc/evidence/20260822-081600-fast/`；resolution
  governance 后的最终 Fast/Full 目录按不可自指策略只在 commit handoff 报告。
- 生命周期为 `resolved / automated_verified`。Profile 仍 NOT_REGISTERED、certification NOT_CERTIFIED、resource
  bytes UNFETCHED、world scene/raster ABSENT、daemon output UNWIRED；未证明 URL/fetch/attempt-time checks/actual
  bytes/decode/cache、A3/J1 或 READY。Provider attempts/API Key reads/paid external calls 均为 0；未发送真实数据，
  未 push/tag/PR。
