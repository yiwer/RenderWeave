# 实现 definite Grid 跨多 AUTO span stable deficit 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 34, 40, 41, 44, 45, 65（均已 resolved）

## Question

Ticket 10 §3/§8 已冻结 Grid AUTO/span constraint 的稳定排序、跨多个 AUTO 的平均增量、每条 constraint
至多处理一次，以及 distributed allocation 的 authored-order 前 `n-1`/last-remainder 规则；当前 kernel 仍在
direct GRID child span 覆盖第二个 AUTO 时 fail closed。如何只补齐 definite、资源无关且 finite/nonnegative 的
这段单调过程，同时保持一般 constraint、Profile tolerance 与反向轴反馈关闭？

## Answer（本票冻结的实施决定）

1. **只深化既有 Grid axis deep module**：继续使用 `definite_grid_axis` 与现有 AUTO constraint collector，
   不新增 public API、crate、parser、preflight bypass、Profile 或第二套 solver。FIXED → AUTO → FRACTION、
   columns-first、arrange、authored DFS first-error 与全有或全无 output 不变。
2. **constraint 保存全部 covered AUTO**：把单一 `auto_index` 深化为按 physical/authored track order 保存的
   `auto_indices`。无 AUTO 的 span 仍允许 overflow；跨任一 AUTO 的该轴 FILL 仍由既有 preflight 作为 cycle
   拒绝；FIXED 与现有可独立测得的 resource-free HUG contribution 继续共用同一 collector。
3. **稳定排序且每条只处理一次**：全部 constraint 仍只按 `(spanLength,startIndex,materializedOrder)` 稳定排序；
   处理时以当时的 track sizes 与内部 gaps 求 occupied，令 `deficit=contribution-occupied`。非正 deficit 不写，
   不回访旧 constraint，也不做 convergence/fixed point。
4. **固定平均增量算法**：单 AUTO 行为保持不变；多 AUTO 对正 finite deficit 按 track order 分配：前 `n-1`
   各取 `deficit / autoCount`，最后一条接收 `deficit - allocatedBeforeLast`。share、累计值与更新后的 track
   必须 finite，last 必须非负，`-0` 归零；异常继续 closed internal `GRID_AUTO_TRACK`，本票不选择 epsilon。
5. **组合语义保持关闭**：每条增量完成后才处理下一条 stable-sorted constraint；AUTO 全部完成后才进入既有
   multi-FRACTION stage。FIXED tracks、FRACTION-at-AUTO-stage zero、signed margins、span internal gaps、
   min/max、alignment 与 overlap 行为不变。
6. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/29`。把现有跨两个 AUTO negative 转为
   positive，并新增三 AUTO last remainder、FIXED+gaps+signed-margin 混合 span、以及 authored broad-before-
   narrow 但按 spanLength 先处理的 stable-order positives；目标 127 laid-out + 12 unsupported、139 cases、
   419 checks，既有 fixture bytes 不变。
7. **固定能力边界**：rows→columns/future-row feedback、multiple Stack main FILL/min-max water filling、一般
   `UNBOUNDED/AT_MOST/EXACT` constraint engine、Profile residual tolerance/public numeric error、任意非直角
   rotation、Text/Image resource intrinsic、actual fetch/decode、world scene/raster/JPEG、daemon RESULT/Profile
   与 E6 均不在本票。
8. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/29` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个新
  positive 共同 RED；再分别实现固定语义并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- TDD：shared `/29` 先把既有跨两 AUTO span negative 转为 positive，并加入三 AUTO last remainder、
  FIXED+gaps+signed-margin 混合 span 与 broad-before-narrow stable-order positives；Rust primary 与 Python
  independent verifier 在同一首个新增 positive 共同 RED，分别实现冻结算法后达到 127 laid-out + 12
  unsupported、139/139 cases、419 checks exact-bit GREEN。
- 实施：`GridAutoConstraint` 现在按 physical/authored order 保存全部 `auto_indices`；stable-sorted constraint
  各处理一次，正 finite deficit 的前 `n-1` 个 AUTO 接收 equal share，最后一个接收稳定余量。singleton、
  FIXED → AUTO → FRACTION、columns-first、admission/preflight、arrange、authored DFS first-error 与全有或全无
  output 合同不变；finite/nonnegative guard 继续稳定映射为 internal `GRID_AUTO_TRACK`。
- identity：shared vector/verifier identity
  `renderweave-definite-layout-vectors/29` / `renderweave-definite-layout-python-independent/29`；vector SHA-256
  `9d49f578af85c73661cbee76b9115248ad6c5b966409ca86d00f43ad3b1f5435`，layout-preflight fixture `/3`
  SHA-256 保持 `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- 局部验证：focused Rust vector tests 3/3、Python independent 139/139 cases/419 checks、workspace `cargo fmt`、
  clippy `-D warnings`、全部 Cargo tests、Python `py_compile`、JSON inventory/hash/unique 与 `git diff --check`
  全绿。
- 分级 gate：`render` `.sdlc/evidence/20260823-064212-render/`、affected `fast`
  `.sdlc/evidence/20260823-064302-fast/`、顺序 `server` `.sdlc/evidence/20260823-064318-server/` 与 Goal `full`
  `.sdlc/evidence/20260823-070309-full/` 均通过。full 17 steps 均 exit 0、总耗时 1802.731 秒；Maven App
  344 tests/0 failures/0 errors/15 skipped，Node 24 Web 26 files/212 tests、runtime canary、23 passed +
  1 controlled skip Playwright、prototype/Draft/Inference browser journeys 与最终 inference replay E2E 1/1
  均通过；resolution 后 fast `.sdlc/evidence/20260823-073620-fast/` 的 3 steps 也均 exit 0。
- 边界：R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0；Profile `NOT_REGISTERED`、
  certification `NOT_CERTIFIED`、Raster `ABSENT`、daemon `UNWIRED`。Stack water filling、rows→columns、general
  constraint/tolerance、resource/scene/pixel 与完整 Renderer 仍未实现；最高仅 `automated_verified`，未推进
  A3/J1/READY，未 push/tag/PR。
