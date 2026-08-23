# 实现 definite Stack 三 FILL 第二 max freeze 末项 inactive max 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79（均已 resolved）

## Question

Ticket 10 §3/§7 冻结了 multiple Stack main-FILL 的 weighted iterative min/max water filling。T76 已允许恰好
三项、首轮唯一 active max、一次重分配后恰好一个第二 max active、最后一项无界的固定两次 freeze；T79
进一步允许末项 inactive min-only。如何接受末项携带一个在重分配 share 与最终 exact remainder 下均 inactive
的 max-only bound，同时不开放末项 mixed/active max、第三次 freeze、一般循环或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有三个 main-axis FILL；三项 weight 均 positive；第一轮恰好一个
   finite/nonnegative max-only bound active。第一次冻结后，另外两项中恰好一个 finite/nonnegative max-only
   bound active，唯一末项只携带 finite/nonnegative max-only bound，且在该次重分配 share 下 inactive。
3. **末项只复核、不再 freeze**：第二 max freeze 后继续以既有固定顺序计算唯一
   `lastShare = remaining - firstMax - secondMax`；只有 `lastShare <= terminalMax` 才提交 staged allocations，
   equality 接受。不做新的 division、第三次 freeze、循环、epsilon/tolerance、free-space 或 justify 特判。
4. **布局顺序不变**：signed margins、gap、occupied/free-space、justify、cross alignment、cursor 与 authored DFS
   first-error/全有或全无 output 不变；每项仍按 authored order 至多执行一次 deferred cross-HUG remeasure。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/43`。保留 T79 五个 terminal-min positives，并
   在同样的 active-first、active-middle、active-last、COLUMN 与 cross-HUG 形状上新增 terminal inactive-max
   positives；把既有 terminal-max negative 转 positive，新增 final remainder 越过 terminal max 的 negative。
   能力值新增 `OR_EXACT_THREE_FILL_SECOND_MAX_FREEZE_TERMINAL_INACTIVE_MAX`；目标 192 laid-out + 16
   unsupported、208 cases、622 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：terminal mixed/active max、首轮多个 active、第一次重分配后多个 active、首个 active min、
   second min/mixed、active-min overflow、second-min sum overflow、四项及以上 active-bound FILL、第三次
   freeze/cascade、一般多轮 water filling、Profile residual tolerance/public numeric error、HUG-main FILL cycle、
   rows→columns、任意非直角 rotation、Text/Image/compositionViewport、resource fetch/decode、scene/raster/JPEG、
   daemon RESULT/Profile、Java/OpenAPI/migration/Web/route、J1/A3/READY 与外部副作用均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/43` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个新增
  terminal-max case 共同 RED；再分别实现严格控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/43` 由 Rust primary 与 Python independent verifier 在同一首个 terminal inactive-max 转正 case、
  同一 `STACK_MAIN_FILL` occurrence 共同 RED 后，分别实现并达到 192 laid-out + 16 unsupported、208/208
  cases、622 checks；vector SHA-256 为 `c2d23177f9ea1ca745c2e604aea673ba098ee08066e48dcbc72f9da225ddbbe3`，
  fixture `/3` SHA-256 保持 `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- `stack_main_fill_allocations` 只在 exactly-three、首轮唯一 active max-only、第一次重分配后唯一 second
  max-only active、唯一末项仅携带 finite/nonnegative 且在重分配 share 下 inactive 的 max-only 时复用固定
  两次 max freeze 与 exact remainder；final share 不高于 terminal max 才提交，equality 接受。能力值新增
  `OR_EXACT_THREE_FILL_SECOND_MAX_FREEZE_TERMINAL_INACTIVE_MAX`；不新增 division、第三次 freeze、循环或
  tolerance。
- focused Rust exact-vector 1/1、Python independent 208/208、workspace fmt/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check` 全绿；分级 A1 证据为 `render`
  `.sdlc/evidence/20260823-233336-render/`（20.190 秒）、affected `fast`
  `.sdlc/evidence/20260823-233405-fast/`（12.343 秒）、顺序 `server`
  `.sdlc/evidence/20260823-233424-server/`（1189.469 秒）与 17-step `full`
  `.sdlc/evidence/20260823-235429-full/`（1786.332 秒），均 exit 0。
- 本状态更新后的 resolution `fast` `.sdlc/evidence/20260824-002615-fast/` 的 3 steps 也均 exit 0
  （A1，10.511 秒）。
- `full` 的独立 verifier identity 为 `renderweave-definite-layout-python-independent/43`；其中 App 344
  tests/0 failures/0 errors/15 skipped、Node 24 Web 26 files/212 tests、runtime canary、23 passed + 1
  controlled skip Playwright、prototype/Draft browser journeys 与 inference replay E2E 1/1 均通过；
  R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0。
- 诚实边界保持不变：Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、world transform/scene/raster
  `ABSENT`、daemon output `UNWIRED`；terminal mixed/active max、首轮或重分配后多个 active、four-or-more、
  第三次 freeze 与一般 water filling 继续 fail closed。未推进 A3/J1/READY，未 push/tag/PR。
