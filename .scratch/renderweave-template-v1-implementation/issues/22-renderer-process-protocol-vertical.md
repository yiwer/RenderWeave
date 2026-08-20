# 物化首个 Rust Renderer process protocol 纵切与 `render` gate

Type: task
Status: resolved
Lifecycle: automated_verified
Resolved by: Codex（single-writer）
Blocked by: 08, 13, 21（均已 resolved：ADR-0045 / Renderer-only fetch lease / sealed RenderDocument+Command）

## Resolution（2026-08-20）

按 Answer 完整物化仓库内 offline Cargo workspace、strict process protocol、常驻 Linux UDS daemon 与内存
request registry、Java `RenderEngine` process Adapter/Supervisor、exact machine manifest、三实现向量重放及
`--network none` Linux UDS round-trip，并将新的 `render` step 纳入 17-step `full`。合法 Command 在空
Profile manifest 下稳定返回 terminal problem；未产生 image、partial result、公开 route 或 READY 状态。

分级证据：`render` `.sdlc/evidence/20260820-211102-render/`（Java 22、独立 Python 110 checks / 7 vectors、
Windows/Linux Rust workspace 与 Linux UDS 通过），`server` `.sdlc/evidence/20260820-211336-server/`（8/8
Reactor，0 failure/error），`fast` `.sdlc/evidence/20260820-212527-fast/`。最终 `full` 以本收口记录和完整
产品 diff 为输入执行，其不可自指的最终 evidence 目录在提交/交接记录中报告。保证上限为 A1/A2；Profile
仍为 `NOT_REGISTERED`、certification 为 `NOT_CERTIFIED`、raster 为 `ABSENT`，无 A3/J1。

## Question

如何按 ADR-0044/0045 与冻结 Renderer 规格物化首个真实 process 纵切：仓库内 Rust daemon、UDS 常驻连接、
严格握手/帧 codec、内存 request registry、Java `RenderEngine` process Adapter、machine manifest identity、
跨语言 exact vectors 与 Linux UDS replay，并把 `render` gate 纳入 `full`；同时在 raster/layout/output Profile
尚未实现和认证时保持合法 Command 稳定失败封闭，不制造 synthetic image、公开 render route 或 READY 假象？

## Answer（本票冻结的实施决定）

1. **workspace 与供应链**：新增独立 `renderer/` Cargo workspace（不进入 Maven reactor），固定
   `rust-toolchain.toml`、`Cargo.lock`、仓库内 vendor 与 offline Cargo 配置。`renderer/process-manifest.json`
   是握手的 exact machine identity，列出 wire version、frame table、依赖/checksum、支持的 production target
   policy、空 renderer profile 集合、`profileAvailability=NOT_REGISTERED` 与
   `certificationStatus=NOT_CERTIFIED`；manifest SHA-256 由 gate 从 exact bytes 计算，不自称 certified raster
   artifact。
2. **wire**：process contract 固定为 `renderweave-renderer-process/1.0`。frame 是
   `uint32be(length = 1 + payload bytes) || uint8(type) || payload`；type table 固定为
   `CLIENT_HELLO=0x01`、`SERVER_HELLO=0x02`、`COMMAND=0x10`、`CANCEL=0x11`、
   `RESULT_METADATA=0x20`、`RESULT_IMAGE=0x21`、`PROBLEM=0x30`。JSON payload 必须 strict UTF-8、closed、
   duplicate/unknown 拒绝并与固定字段序 canonical bytes 完全相等；frame 上限由 Adapter/daemon 显式配置，
   本票不替 Ticket 19 猜产品容量数值。
3. **握手**：Java 连接后先发 CLIENT_HELLO（wire version、expected manifest SHA-256、required protocol
   capabilities）；daemon 只在 exact manifest identity/版本/capability 相等时回 SERVER_HELLO，否则关闭连接。
   SERVER_HELLO 明确返回空 renderer profile 集合与 NOT_REGISTERED；协议 capability 不等于 Renderer Profile
   available。
4. **Command/Result/Problem**：COMMAND 使用冻结字段集与顺序；`requestId` 仅 canonical UUID v4，
   `deadlineAt` 仅 RFC3339 UTC 毫秒 `Z`，`document` 保留 exact canonical JSON，daemon 重算
   `renderDocumentDigest`，并计算 domain-separated command digest。RESULT_METADATA 使用冻结 closed metadata；
   RESULT_IMAGE payload 为 `16-byte UUID network order || exact image bytes`，从而在单连接上安全 multiplex；
   metadata 与 image length/SHA-256 必须同时吻合后才产生 `RenderOutput`。PROBLEM 使用冻结 closed shape，Java
   穷尽映射为 ENGINE-stage `RenderingProblem`；malformed/manifest/profile 内部违约折叠
   `RENDER_INTERNAL_ERROR`。
5. **daemon 行为**：真实 Linux UDS daemon 常驻、单 app connection、requestId multiplex、内存 registry。
   同 requestId+同 command digest 重放同一 terminal outcome；同 requestId+异 digest 返回
   `RENDER_REQUEST_CONFLICT`；CANCEL/terminal tombstone 是 closed 状态。由于本票 manifest 的 renderer profile
   集为空，任一通过 syntax/digest/deadline admission 的合法 COMMAND 在 COMMAND_ADMISSION 返回稳定
   `RENDER_INTERNAL_ERROR` terminal problem；不得产生图片、placeholder、partial RESULT 或 profile fallback。
6. **Java Adapter 与监督**：`renderweave-app` 物化 production `RenderEngine` Adapter，使用一个持久 UDS
   connection、同步写锁、reader dispatch 与 requestId future registry；I/O/daemon crash/无法确认 terminal
   outcome 返回 `Unknown`，协议 terminal problem 返回 `TerminalProblem`，完整 RESULT 才返回三种成功态。
   app 仅在显式配置 executable/socket/exact manifest 时装配；Supervisor 用固定 backoff 启动/重启并校验
   handshake。默认未配置即没有 Engine bean，保持失败封闭；本票无公开 route。
7. **TDD/replay**：先落 Java/Rust/独立 verifier 共同消费的 exact vector manifest，证明 RED；再实现 codec、
   registry、daemon、Adapter 与 supervisor。`render` gate 顺序为 repository diff → Java primary codec/Adapter
   tests → Rust locked/offline tests → 独立 Python frame/canonical/digest replay → `--network none` Linux 容器
   UDS round-trip；summary 必须记录 manifest/vector digest、case counts、profile NOT_REGISTERED、
   certification NOT_CERTIFIED、provider/key/cost=0。`full` 新增该 step。

## 允许影响

`renderer/**`、`renderweave-rendering` 的 request identity/engine contract hardening、`renderweave-app` process
Adapter/配置/测试、共享 protocol vectors、`tools/run-render-gate.ps1`/独立 verifier/`run-gate.ps1`、受影响
architecture/public-surface 测试、CONTEXT/tracker/plan/log/NOTES/evidence。

## 禁止影响

layout/shaping/resource decode/raster/PNG/JPEG 实现或 synthetic output；Skia/FreeType/libjpeg Profile 注册；
公开 render/preview/diagnostic route、OpenAPI/Web SDK、Editor 产品代码；Ticket 19 容量数值；物理 Linux
x86_64/aarch64 认证、J1/A3/READY；付费 provider、真实数据、API key；push/tag/PR。

## 局部验证

TDD RED/GREEN；UUID/deadline/strict JSON/duplicate/unknown/noncanonical/frame truncation/oversize；manifest/version/
capability mismatch；same-command replay/conflict/cancel；problem/result two-frame/hash/length 映射；断连 Unknown；
Supervisor restart；Rust daemon Linux UDS round-trip；stdout/stderr payload 泄漏防线。

## 受影响验证

`render`、focused rendering/app tests、`server`、`fast`，最后完整 `full`（新增 render step）。Rust 仅
`--locked --offline`，Linux replay 容器 `--network none`；输入未变的既有 exact replay 可按 RULE-VAL-001
复用，最终 full 仍须重新捕获整树 manifest。

## 保证等级与完成信号

仓库工具捕获为 A1；Java/Rust/Python 对同 exact vectors 的独立实现 replay 与 Linux UDS round-trip 在该
协议输入边界记 A2；Docker/Windows/WSL 都不等于双物理 Linux 认证，无 A3/J1。完成时 Ticket 22 只能标为
`resolved / automated_verified`：`render` 已入 full、daemon/Adapter/manifest/replay 全部真实可执行、合法
Command 在 NOT_REGISTERED 状态确定性失败封闭、worktree clean 且形成 verified local commit；不 push/tag/PR。
