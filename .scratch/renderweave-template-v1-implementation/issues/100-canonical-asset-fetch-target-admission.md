# 实现 Renderer canonical Asset fetch 目标准入与 daemon 部署 identity

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 13, 22, 23, 46, 47, 48（均已 resolved）

## Question

T13 已由 app 签发 Renderer-only canonical HTTPS Asset fetch URL，T46/T47/T48 也已完成 typed manifest、Command-bound
lease 与 caller-supplied body integrity；但 Rust daemon 仍没有 app-origin 部署 identity，也没有在任何网络动作前
阻止 origin/prefix confusion、userinfo、default-port drift、dot segment、percent-encoding、query/fragment 或反斜杠
绕过。怎样建立一个未来真实 HTTPS Adapter 必须消费的窄 deep Interface，并把它接入 daemon 的 manifest-order
resource stage，同时仍不把“URL 已准入”冒充 DNS/egress、HTTP fetch、actual bytes 或 Renderer Profile？

## Answer（本票冻结的实施决定）

1. **深化 resource-preparation deep module**：在既有 `renderweave-renderer-resource` 中新增唯一
   `FetchTargetPolicy` Interface。它只消费 daemon 启动时的 exact deployment origin、固定 Asset path prefix 与
   T46 typed `AdmittedRenderResource`，成功返回绑定原资源生命周期的 `AdmittedFetchTarget`；未来 transport 只能
   从该 token 取得 fetch URL，不得直接消费 manifest string。
2. **显式部署 identity**：daemon 新增必填且只出现一次的 `--asset-fetch-origin`；Java process Supervisor 从现有
   `renderweave.asset.fetch-base-url` 原样传递。空值、非 UTF-8 或非 canonical origin 在 bind socket 前以配置错误
   fail closed。path prefix 固定为 app 已物化的 `/internal/render-assets`，不接受 Command/caller 覆盖。
3. **canonical origin 子闭包**：只接受 ASCII `https://` + lowercase DNS/IPv4-style host；label 非空、首尾为
   alphanumeric，中间只含 lowercase alphanumeric/hyphen。允许显式 canonical non-default port `1..65535`；禁止
   userinfo、percent encoding、反斜杠、path/query/fragment、trailing dot、显式 `:443`、前导零 port 与 IPv6 literal。
   这只冻结当前 app-origin 部署闭包，不宣称实现一般 URL 标准化。
4. **exact-origin + segment-boundary prefix**：fetch URL 必须逐 byte 以 canonical origin +
   `/internal/render-assets/` 开始并至少有一个非空 suffix segment；path segment 只允许 RFC3986 unreserved ASCII，
   禁止空 segment、`.`/`..`、`%`、query、fragment、反斜杠、控制符与非 ASCII。因 origin 逐 byte 匹配，scheme/host/
   port case drift、host suffix attack 与 userinfo 均不能落入 allowlist；不做修复、decode 或 fallback。
5. **daemon 顺序与安全错误**：Command/document/lease admission 后按 manifest encounter order逐项 target-admit，
   在任何 layout/fetch/body/decode 前遇到首个违约即原子缓存一个 `RENDER_INTERNAL_ERROR/RESOURCE_PREPARATION`；
   不把 URL、origin、path、token、expiry 或原因写入 problem/debug/日志。resource-free Command 不受影响。
6. **共同语料纵向 TDD**：新增 shared fetch-target vectors，由 Rust public Interface 与 Python stdlib 独立控制流
   重放 canonical/non-default origin 正例，以及 scheme/host/port/userinfo/prefix/dot/percent/query/fragment/
   backslash/Unicode 等负例；先共同 RED，再实现 kernel、daemon 与 Java Supervisor wiring。
7. **诚实边界**：不实现 DNS/egress/rebinding、rustls/HTTP client、headers/status/redirect/proxy/retry/backoff、
   attempt-time Clock/expiry、actual fetch/body retain/cache、media/magic/decode/font/Image、scene 扩展、daemon RESULT、
   Profile registration、Java/OpenAPI/Web/E6 或产品 route。resource bytes 仍 `UNFETCHED`，transport/daemon output
   仍 `UNWIRED`，Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`。

## 验证与完成信号

- TDD RED：shared vectors + Rust public-interface tests 先因 `FetchTargetPolicy` 缺位失败；独立 Python 对同一 corpus
  在实现前保持缺位，不以 Java URI 或 expected outcome 作为 oracle。
- TDD GREEN：focused resource/daemon Rust tests + Python stdlib independent replay + Java Supervisor tests → workspace
  fmt/check/clippy/tests → Cargo.lock/process manifest/protocol vector identity、JSON inventory/SHA 与 `git diff --check`。
- 分级：`render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。
- 最高状态只到 `automated_verified`；不运行 provider、不读取 API Key、不发送真实数据、不 push/tag/PR，不开放
  正式产品 route，也不把 `/prototype` 当作交付。

## Resolution

- shared fetch-target corpus 先使 Rust public Interface、daemon registry 与 Java Supervisor 构造边界按预期 RED；
  GREEN 后由唯一 `FetchTargetPolicy` 产出 `AdmittedFetchTarget`，daemon 在 layout/fetch/body/decode 前按 manifest
  encounter order 准入，并将首个违约安全映射为 `RENDER_INTERNAL_ERROR/RESOURCE_PREPARATION`，不泄漏 URL。
- daemon 现在要求唯一 `--asset-fetch-origin`，Java Supervisor 从 `renderweave.asset.fetch-base-url` 传递；固定
  `/internal/render-assets` prefix 与 canonical lowercase ASCII HTTPS exact-origin/segment-boundary 规则已接线。
  resource-free Command 保持兼容；DNS/egress/HTTP/body/decode 仍未实现。
- focused 验证全绿：resource Rust 7 tests、daemon Windows 8 tests、Linux/UDS 9 tests、Java renderer 27 tests；
  Python independent replay 为 14 policy + 22 target = 36/36 cases、76 checks，vector SHA-256
  `0d02e44c57e9082452d651de28b9e6fee24ddcc94d573252a09aaece6be1b4e9`。
- 分级 gate 全绿：`render` `.sdlc/evidence/20260824-211301-render/`（20.551 秒）、affected `fast`
  `.sdlc/evidence/20260824-211337-fast/`（9.646 秒）、顺序 `server`
  `.sdlc/evidence/20260824-211353-server/`（1002.067 秒）与 17-step `full`
  `.sdlc/evidence/20260824-213045-full/`（1598.099 秒）。full 中 App 345/0/0/15、Node 24 Web 26 files/
  212 tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft browser journey 与 inference replay
  E2E 1/1 均通过；状态回填后的 resolution `fast` `.sdlc/evidence/20260824-215948-fast/` 亦 exit 0
  （9.448 秒）。
- 状态为 `resolved/automated_verified`。resource bytes `UNFETCHED`、transport/daemon output `UNWIRED`、Profile
  `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、正式产品 route `CLOSED`；provider
  attempts/API Key reads/费用/真实数据=0，未 push/tag/PR，也未把 `/prototype` 计为最终产品交付。
