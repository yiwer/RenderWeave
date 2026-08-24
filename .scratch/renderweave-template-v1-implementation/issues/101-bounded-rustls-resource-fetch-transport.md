# 实现 Renderer 有界 rustls HTTPS 资源传输与 daemon 接线

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 13, 16, 19, 22, 23, 46, 47, 48, 100（均已 resolved）

## Question

T100 已在任何网络动作前把 sealed RenderResource 收窄为 canonical exact-origin/path-prefix
`AdmittedFetchTarget`，但 daemon 仍不执行 DNS、egress、TLS、HTTP、重试或实际 body 读取。怎样让 daemon
取得并完整性核验真实资源字节，同时保证 DNS rebinding、环境代理、重定向、透明解压、无限重试、body
膨胀和 URL 泄漏均不能绕过冻结的 Ticket 13/16/19 合同？

## Answer（本票冻结的实施决定）

1. **传输只消费 admitted target**：在 `renderweave-renderer-resource` 内新增唯一
   `ResourceFetcher` deep Interface 与 production `HttpsResourceFetcher`；调用者只能提交 T100 产出的
   `AdmittedFetchTarget`，transport 不再接收任意 URL string。成功返回 request-local owned exact bytes 与
   `VerifiedResourceBody`，不建立跨请求 cache。
2. **显式、非空 egress identity**：daemon 新增可重复 `--asset-fetch-allowed-ip`，启动时必须提供 1–16 个
   canonical、互异的 IPv4/IPv6 地址；Java Supervisor 从
   `renderweave.rendering.engine.process.asset-fetch-allowed-ips` 解析并逐项传递。每次 TCP connect 前重新 DNS，
   resolver 只向 connector 返回当次解析结果中首个 allowlisted socket address；空集、无匹配、非 canonical、重复
   或超量配置均在 bind/HTTP 前 fail closed。TLS SNI 与 hostname verification 始终使用原 canonical host，不能
   用获准 IP 替代证书身份。
3. **固定 TLS/HTTP 栈**：精确 pin `ureq 3.4.0`，`default-features=false` 且只启用 rustls；production roots
   固定为随依赖封存的 WebPKI roots，certificate verification 与 SNI 必须开启。每 attempt 创建新 Agent，显式
   `https_only=true`、`proxy=None`、`max_redirects=0`、idle pool=0、status-as-error=false、64 KiB response-header
   ceiling；不编译 cookie/gzip/brotli/charset/socks/platform-proxy。请求只由模块构造 GET，并固定
   `Accept-Encoding: identity`、`Connection: close`；无 caller header、Range、Authorization fallback 或 URL 替换。
4. **严格响应 envelope**：成功只接受 HTTP 200、无 `Transfer-Encoding`、零个或唯一 exact `identity`
   `Content-Encoding`、恰好一个不含逗号且可解析的十进制 `Content-Length`，其数值必须等于 manifest
   `byteLength`。redirect/1xx/3xx/4xx 以及 envelope 违约零重试；5xx 仅可进入冻结 retry 分支。失败信息不得携带
   URL、host、path、token、证书、DNS 地址、header、body 或底层错误文本。
5. **有界 streaming 与完整性**：body 以至多 1 MiB chunk 读取；所有实际读取字节（成功、失败及 retry body）
   共用既有 512 MiB physical budget。200 body 逐 chunk 累计 actual length 与 SHA-256，length/hash 任一不符立即
   返回稳定 `LENGTH_MISMATCH`/`HASH_MISMATCH` 且零 downstream output；transport 中断可 retry，但已读取字节
   不回退。成功 bytes 只在本请求内保留，terminal 后随请求对象释放。
6. **冻结 attempt/time 语义**：每资源最多 2 attempts，唯一 retry 前固定 100 ms、无 jitter，单 attempt 最多
   5 s，整个 resource phase 最多 20 s，并受原 Command absolute deadline 与原 lease expiry 同时约束。每 attempt
   与 retry wait 前后重检 deadline、lease 和累计预算；transport/5xx 才 retry，4xx、expiry、envelope、length、hash
   均零 retry。100 ms wait 分成两个不超过 50 ms 的 checkpoint slice；本票不宣称 daemon 的并发 CANCEL reader
   已接通。
7. **daemon manifest-order 接线**：Command/document/lease/target admission 与静态 preflight 通过后，daemon 按
   manifest encounter order调用 `ResourceFetcher`，首个失败映射到 closed resource/control problem 并进入既有
   exact terminal replay registry；全部资源 transport + integrity 通过后仍因 Profile 未注册返回既有
   `RENDER_INTERNAL_ERROR/COMMAND_ADMISSION`。测试 registry 注入 deterministic fake，默认测试禁止外网；另用
   loopback、自签 test root 和真实 rustls server 证明 TLS/SNI/固定 headers/5xx retry/200 bytes。
8. **诚实边界**：本票不实现 media/magic、IMAGE/FONT decode/descriptor、request-local decoded cache、scene/raster/
   JPEG/LayoutTrace、并发 queue/CANCEL、daemon RESULT、Profile registration、OpenAPI/Web/Product Editor route、
   formal records、physical certification、J1/A3/READY。完成后只可称 resource bytes
   `FETCHED_AND_INTEGRITY_VERIFIED`、transport `RUSTLS_HTTPS_AUTOMATED_VERIFIED`；Profile 仍
   `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、daemon output 与正式产品 route
   仍 `UNWIRED/CLOSED`。

## 验证与完成信号

- 先新增 shared fetch-transport vectors 与 Rust public-interface tests，使缺失的 egress/fetch Interface、header/
  retry/clock orchestration、daemon/Java arguments 精确 RED；Python stdlib 独立 replay 同一 corpus，不调用 Rust、
  Java URI 或 expected outcome 作为 oracle。
- GREEN 后运行 focused resource/daemon/Java tests、真实 loopback rustls integration、Rust fmt/check/clippy/tests、
  Python replay、Cargo offline/vendor/manifest identity、JSON inventory/SHA 与 `git diff --check`。
- 分级 gate：`render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。最高状态只可
  `automated_verified`；不运行 provider、不读取 API Key、不发送真实数据、不 push/tag/PR，不把 `/prototype`
  视为最终产品交付。

## Resolution evidence

- shared transport corpus 由 Rust primary 与 Python stdlib independent verifier 共同重放为 33/33 cases、115 checks，
  vector SHA-256 `e31976aa5483c66859b8f8cad480e742a9df812473286d2233d196ae194aca68`；真实 loopback
  rustls 测试证明固定 headers、503→200 单次重试、exact bytes 与 length/SHA 完整性。resource 10 unit + 2 public
  interface、daemon Windows 10/Linux 11、Java renderer 29 tests 均绿。
- Cargo offline/vendor/process identity 已封存：Cargo.lock SHA-256
  `5acd41e397411003ae3259820df73033cd9f7a048722eb38bee6c91a8cc71f82`，vendor tree SHA-256
  `7764c5ca80a9e3a66b42b49ab8001ca49730f19284746eff920aa3e4218fdf5e`、2718 files，process manifest
  SHA-256 `294ba3626fdb2753c571852e1e121adef671f5567a949b1b8be892cf9a4c0328`；独立 process replay
  7 vectors/110 checks，Windows workspace 与 Linux network-none/真实 UDS 均通过。
- 分级 A1 证据均为 exit 0：`render` `.sdlc/evidence/20260824-224815-render/`（37.422 秒）、affected
  `fast` `.sdlc/evidence/20260824-225028-fast/`（20.572 秒）、顺序 `server`
  `.sdlc/evidence/20260824-225057-server/`（1047.399 秒）与 17-step Goal `full`
  `.sdlc/evidence/20260824-230832-full/`（1610.188 秒）。full 中 App 347/0/0/15、Node 24 Web
  26 files/212 tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft browser journey 与 inference
  replay E2E 1/1 均通过；R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost/open authorization=0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260824-233759-fast/` 3 steps 均 exit 0（11.798 秒）。
- 完成边界保持诚实：resource bytes `FETCHED_AND_INTEGRITY_VERIFIED`、transport
  `RUSTLS_HTTPS_AUTOMATED_VERIFIED`；Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  `ABSENT`、daemon output `UNWIRED`、正式产品 route `CLOSED`。未运行 provider、读取 API Key、发送真实数据或
  push/tag/PR，也未把 `/prototype` 视为最终产品交付。
