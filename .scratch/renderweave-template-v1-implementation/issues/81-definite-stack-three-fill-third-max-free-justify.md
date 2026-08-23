# 实现 definite Stack 三 FILL 第三 max freeze 与 free justify 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80（均已 resolved）

## Question

Ticket 10 §3/§7 冻结了 multiple Stack main-FILL 的 weighted iterative min/max water filling。T80 已允许恰好
三项、固定两次 max freeze 后末项 max 在重分配 share 与 final exact remainder 下均 inactive。如何接受末项在
重分配 share 下仍 inactive、但在第二次 freeze 后的 final exact remainder 下严格 active 的 max-only bound，
同时不开放 simultaneous active bounds、第四项、一般循环或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有三个 main-axis FILL；三项 weight 均 positive；第一轮恰好一个
   finite/nonnegative max-only bound active。第一次冻结后，另外两项中恰好一个 finite/nonnegative max-only
   bound active；唯一末项只携带 finite/nonnegative max-only bound，在该次重分配 share 下 inactive，但在第二
   max freeze 后得到的 final exact remainder 下严格 active。
3. **第三次 freeze 后终止**：三项都已有唯一确定 allocation，末项直接冻结到 terminal max；不得再选 active
   项、再做 division、第四次 freeze、循环或 epsilon/tolerance。三项 allocation 总和小于 available 时，正
   free-space 只交给既有六种 `justifyContent` 分配，不写 allocation 特判；equality 继续走 T80 inactive 路径。
4. **布局顺序不变**：signed margins、gap、occupied/free-space、justify、cross alignment、cursor 与 authored DFS
   first-error/全有或全无 output 不变；每项仍按 authored order 至多执行一次 deferred cross-HUG remeasure。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/44`。保留 T80 五个 terminal inactive-max
   positives；把既有 terminal active-max negative 转为 active-first/justify、active-middle、active-last、COLUMN
   与 cross-HUG positives，并以 terminal mixed active-max negative 替换。能力值新增
   `OR_EXACT_THREE_FILL_THIRD_MAX_FREEZE_FREE_JUSTIFY`；目标 197 laid-out + 16 unsupported、213 cases、637
   checks，fixture `/3` bytes 不变。
6. **固定能力边界**：terminal mixed/min、首轮多个 active、第一次重分配后多个 active、首个 active min、
   second min/mixed、active-min overflow、second-min sum overflow、四项及以上 active-bound FILL、一般多轮
   water filling、Profile residual tolerance/public numeric error、HUG-main FILL cycle、rows→columns、任意非
   直角 rotation、Text/Image/compositionViewport、resource fetch/decode、scene/raster/JPEG、daemon RESULT/
   Profile、Java/OpenAPI/migration/Web/route、J1/A3/READY 与外部副作用均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/44` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个新增
  third-max case、同一 `STACK_MAIN_FILL` occurrence 共同 RED；再分别实现严格控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/44` 由 Rust primary 与 Python independent verifier 在同一首个 third-max case
  `stack-three-main-fills-third-max-freeze-justify-end`、同一 `STACK_MAIN_FILL` occurrence 共同 RED 后，分别实现并
  达到 197 laid-out + 16 unsupported、213/213 cases、637 checks；vector SHA-256 为
  `4690f1ed0493b9140b288d5648fa8cb54b1c9b9e909362233d01b1822e9e8f32`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- `stack_main_fill_allocations` 只在 exactly-three、首轮唯一 active max-only、第一次重分配后唯一 second
  max-only active、唯一末项仅携带 finite/nonnegative max-only 且只在 final exact remainder 下严格 active 时，
  把末项冻结到 terminal max；由此留下的正 free-space 只走既有 `justifyContent`。能力值新增
  `OR_EXACT_THREE_FILL_THIRD_MAX_FREEZE_FREE_JUSTIFY`；不新增 active 选择、division、第四次 freeze、循环或
  tolerance，terminal mixed 继续 fail closed。
- focused Rust exact-vector 1/1、Python independent 213/213、workspace fmt/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check` 全绿；分级 A1 证据为 `render`
  `.sdlc/evidence/20260824-004107-render/`（31.562 秒）、affected `fast`
  `.sdlc/evidence/20260824-004148-fast/`（10.429 秒）、顺序 `server`
  `.sdlc/evidence/20260824-004206-server/`（1209.609 秒）与 17-step `full`
  `.sdlc/evidence/20260824-010225-full/`（1662.402 秒），均 exit 0。
- `full` 的独立 verifier identity 为 `renderweave-definite-layout-python-independent/44`；其中 App 344
  tests/0 failures/0 errors/15 skipped、Node 24 Web 26 files/212 tests、runtime canary、23 passed + 1
  controlled skip Playwright、prototype/Draft browser journeys 与 inference replay E2E 1/1 均通过；R0/R1/P0
  provider attempts=0，P0 API Key reads/reservations/cost=0。R1 A2 为 60 cases/58 metrics、visual diff J0；P0
  A2 为 60 cases/20 holdout/58 metrics。
- 本状态更新后的 resolution `fast` `.sdlc/evidence/20260824-013106-fast/` 的 3 steps 也均 exit 0
  （A1，10.322 秒）。
- 诚实边界保持不变：Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、world transform/scene/raster
  `ABSENT`、daemon output `UNWIRED`；terminal mixed/min、首轮或重分配后多个 active、four-or-more 与一般
  water filling 继续 fail closed。未推进 A3/J1/READY，未 push/tag/PR。
