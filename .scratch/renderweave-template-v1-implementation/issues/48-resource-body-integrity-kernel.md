# 实现 RenderResource body 完整性内核

Type: task
Status: resolved
Claimed by: Codex `/root`（single-writer）
Blocked by: 13, 22, 23, 46, 47（均已 resolved；Ticket 19 的 physical fetch bytes cell 已冻结，formal record/认证仍 open）

## Question

在 Renderer 尚不能建立 canonical HTTPS/app-origin transport、也没有已注册 Profile、decoder、request-local raw
cache 或 daemon success path 时，如何先把 typed `AdmittedRenderResource` 的声明长度与 lowercase SHA-256 变成可由
真实 fetch Adapter 后续复用的纯流式完整性 invariant，并精确执行 Ticket 19“成功与允许 retry body 合计最多
512 MiB、每个 chunk 接受前原子预留”的物理字节预算，同时不把 caller-supplied test bytes 冒充实际下载？

## Answer（本票冻结的实施决定）

1. **新建 request-local resource-preparation deep module**：在 Rust workspace 内新增非占位模块，唯一 body
   Interface 消费 T46 已 typed 的 `AdmittedRenderResource`、共享 `PhysicalFetchBudget` 与有序 byte chunks；不重新
   解析 manifest，也不读取 URL、Clock、网络、Resolver、配置、Asset/Template 身份或外部状态。
2. **共享 physical-byte budget**：冻结最大值 `536_870_912` bytes 与 limitId
   `assetsAndFetch.physicalFetchBytesIncludingRetries`。每个收到的成功或可 retry response body chunk 都必须在接受前
   对共享 request-local counter 原子预留；exact limit 接受，超一 byte 拒绝且 counter 不变。非 200/retry body 可由
   后续 transport 直接使用同一 budget Interface，cache hit 不返还或降低已计物理字节。
3. **固定 body 完整性次序**：成功 body 的每个 chunk 先计入 physical-byte budget，再计入该 body 的实际长度与
   SHA-256；一旦实际长度超过声明值立即返回 `LENGTH_MISMATCH`。EOF 后先比较 exact length，只有长度相等才比较
   `sha256:` + 64 lowercase hex；不相等返回 `HASH_MISMATCH`。失败已接收的物理 bytes 仍计费，不重试、不泄漏
   length/hash/URL/bytes。
4. **稳定结果 seam**：成功只返回与 opaque `resourceId`、exact byteLength、exact digest 绑定的 verified token；失败
   只暴露 `RESOURCE_BUDGET_EXCEEDED | LENGTH_MISMATCH | HASH_MISMATCH`、固定
   `RESOURCE_PREPARATION` stage、opaque resourceId 与容量错误唯一 limitId。没有 raw bytes、自由文本、URL、hash、
   observed count 或输入内容进入 problem/evidence。
5. **共同语料与纵向 TDD**：新增 shared resource-body vectors；Rust primary 与 Python stdlib independent verifier
   分别覆盖 exact budget below/at/above、跨 chunk 原子拒绝、retry-body 累计、single/chunked exact body、short/
   long/hash mismatch、budget-before-integrity 与失败计费。按一个 Interface 行为一轮 RED→GREEN 推进，不测试私有
   hasher/counter 实现。
6. **明确不越界**：本票不建立 URL parser/allowlist、DNS/egress、HTTP client/header/status/retry/backoff、attempt-time
   Clock/expiry、magic/media/descriptor/decode、raw/decode cache、Text/Image measurement、scene/raster/JPEG/RESULT、
   Profile registration、public render/preview/E6 或认证；daemon 保持不调用本模块，`resourceBytes=UNFETCHED`、
   `daemonOutputPath=UNWIRED`。

## 验证与完成信号

- 局部：逐 slice RED→GREEN；shared-vector Rust tests + Python stdlib independent replay；workspace fmt/clippy
  `-D warnings`/test、`py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → `server`/`fast` → 完整 `full`；不新增 Java/OpenAPI/Web/migration、网络或外部 I/O。
- 保证上限：Rust/gate A1，shared exact body/budget vectors 的 Rust+Python replay A2；caller-supplied bytes、模块
  UNWIRED，不证明 actual fetch、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- 新增 workspace-internal `renderweave-renderer-resource` deep module；公开 seam 只消费 typed
  `AdmittedRenderResource`、共享 request-local `PhysicalFetchBudget` 与 caller-supplied ordered chunks。每个
  chunk 先原子预留 physical bytes，`536_870_912` exact limit 接受，越界/`u64` overflow 均 fail closed 且 counter
  不变；随后严格执行 declared length → lowercase SHA-256。
- 成功 token 只绑定 opaque resourceId、exact byteLength 与 digest；closed problem 只暴露
  `RESOURCE_BUDGET_EXCEEDED | LENGTH_MISMATCH | HASH_MISMATCH`、`RESOURCE_PREPARATION`、opaque resourceId，且
  仅容量错误携带 limitId。Debug/problem/evidence 不含 raw bytes、URL、observed count 或输入内容。
- shared resource-body vector `/1` 含 6 个 budget cases + 9 个 body cases；Rust primary 与 Python stdlib
  independent replay 为 15/15、34 checks，vector SHA-256 为
  `a7273a49325f79416795ad2a1ad953464dd2449ae2174af48b026263d2aa9c7d`。Cargo.lock SHA-256 为
  `7c7130d920fe5c680cffdd474de9f2383a75110ef71a127bdbdf1cb10349497b`，process manifest SHA-256 为
  `6fa063ac1584295852b96846ce634ce6408e804c9aae0a790a0270b4fe227607`，process exact replay 保持 110 checks。
- A1/A2 证据：`render` `.sdlc/evidence/20260822-091324-render/`、`server`
  `.sdlc/evidence/20260822-091356-server/`、治理前 `fast` `.sdlc/evidence/20260822-093316-fast/`；resolution
  governance 后的最终 Fast/Full 目录按不可自指策略只在 commit handoff 报告。
- 首轮最终 Full 在 15/17 后仅因 prototype audit cleanup 的全系统 `Get-CimInstance Win32_Process` 被取消
  （`0x80041032`）而失败；产品/E2E 断言与三 variant audit 均已通过。将 cleanup 收窄为已捕获 Vite PID +
  已知端口的 Node listener 后，定向 E2E `.sdlc/evidence/20260822-100547-e2e/` 为 23 passed/1 policy skip、
  A/B/C audit PASS 且进程正常退出。第二轮 Full `.sdlc/evidence/20260822-100800-full/` 的 server 8/8、App
  344/0 failure/error 全绿，随后 Node 24 `npm ci` 因首轮旧 cleanup 遗留的 orphan Vite/Node 精确映射
  `lightningcss` 而 `EPERM`；排他句柄 RED、模块列表精确归因并终止该单一 PID 后，同一检测 GREEN，完整
  `web` `.sdlc/evidence/20260822-103019-web/` 为 26 files/212 tests 与 production build 全绿。最终 Full 仍须在
  无旧 orphan 的完整状态重跑。
- 生命周期为 `resolved / automated_verified`。模块仍为 UNWIRED、resource bytes UNFETCHED、Profile
  NOT_REGISTERED、certification NOT_CERTIFIED、world scene/raster ABSENT、daemon output UNWIRED；未证明
  canonical HTTPS/actual fetch/decode/cache、A3/J1 或 READY。Provider attempts/API Key reads/paid external calls
  均为 0；未发送真实数据，未 push/tag/PR。
