# 实现 definite Stack 三 FILL 第二 min freeze 与末项余量子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74（均已 resolved）

## Question

Ticket 10 §3/§7 冻结了 multiple Stack main-FILL 的 weighted iterative min/max water filling。T74 已允许恰好
三项、首轮唯一 active bound、一次重分配后其余 bound 全部 inactive 的路径。如何继续接受第一次重分配后恰好
一个 min 变为 active、第二次 freeze 后只剩一个无界项的有限退化路径，同时不开放第三次 freeze、一般循环或
Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有三个 main-axis FILL；三项 weight 均 positive；第一轮 stable
   weighted shares 恰好只有一个 finite/nonnegative min-only bound active，且该 min 不大于 `remaining`。另外两项
   中恰好一项携带 finite/nonnegative min-only bound并在第一轮 inactive，最后一项 owning-axis 完全无 bound。
3. **固定第二次 freeze**：先冻结首轮 active min，再对另外两项按原 positive fillWeight/authored order 对
   `remaining - firstMin` 做一次 weighted share + exact remainder。只有 bounded share 严格低于其 min，且
   `firstMin + secondMin <= remaining` 时才冻结第二 min；唯一无界项直接取得
   `remaining - firstMin - secondMin`。不做第二轮 division、第三次 freeze、循环或 tolerance；equality 仍由 T74
   作为 inactive bound 接受。
4. **布局顺序不变**：staged allocations 完成后，signed margins、gap、occupied/free-space、justify、cross
   alignment 与 cursor 公式保持既有顺序；每项按 authored order 至多执行一次 deferred cross-HUG remeasure；
   deeper error 继续保持 authored DFS first-error 和全有或全无 output。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/38`。把既有 three-FILL second-min negative
   转 positive，并新增 active-middle、active-last、COLUMN 与 cross-HUG positives；新增 two-min-sum overflow
   negative。目标 167 laid-out + 16 unsupported、183 cases、547 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：首轮多个 active、首个 active max、第一次重分配后 second max/mixed/多个 active、两项 min
   总和大于 remaining、末项带 bound、active min 大于 remaining、四项及以上 active-bound FILL、第三次
   freeze/cascade、一般多轮 water filling、Profile residual tolerance/public numeric error、HUG-main FILL cycle、
   rows→columns、任意非直角 rotation、Text/Image/compositionViewport、resource fetch/decode、scene/raster/JPEG、
   daemon RESULT/Profile 与 E6 均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/38` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  second-min case 共同 RED；再分别实现严格控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/38` 由 Rust primary 与 Python independent verifier 在同一首个 second-min 转正 case 共同 RED 后，
  分别实现并达到 167 laid-out + 16 unsupported、183/183 cases、547 checks；vector SHA-256 为
  `3e0c9e682a357213ec72fcf29ec002c06939afc38def44554ec226d7da4eb15b`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- `stack_main_fill_allocations` 只对 exactly-three/first-active-min/second-active-min/terminal-unbounded 的固定
  子集执行两次 min freeze，并把最终 exact remainder 直接交给唯一无界项；不做第二轮 division、第三次
  freeze、循环、epsilon/tolerance 或一般 water filling。能力值新增
  `OR_EXACT_THREE_FILL_SECOND_MIN_FREEZE_LAST_REMAINDER`。
- focused Rust 3/3、Python independent 183/183、workspace fmt/clippy `-D warnings`/tests、`py_compile`、JSON
  inventory/SHA/unique 与 `git diff --check` 全绿；分级 A1 证据为 `render`
  `.sdlc/evidence/20260823-165723-render/`（30.252 秒）、affected `fast`
  `.sdlc/evidence/20260823-165806-fast/`（10.183 秒）、顺序 `server`
  `.sdlc/evidence/20260823-165823-server/`（1110.102 秒）与 17-step `full`
  `.sdlc/evidence/20260823-171703-full/`（1696.359 秒），全部 step 均 exit 0。
- `full` 中 App 344 tests/0 failures/0 errors/15 skipped、Node 24 Web 26 files/212 tests、runtime canary、
  23 passed + 1 controlled skip Playwright、prototype/Draft browser journeys 与 inference replay E2E 1/1 均通过；
  R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0。
- 诚实边界保持不变：Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、world transform/scene/raster
  `ABSENT`、daemon output `UNWIRED`；second max/mixed、two-min-sum overflow、terminal bound、首轮多个 active、
  四项及以上、第三次 freeze 与一般 water filling 继续 fail closed。未推进 A3/J1/READY，未 push/tag/PR。
- 本状态更新后的 resolution `fast` `.sdlc/evidence/20260823-174746-fast/` 3 steps 也均 exit 0
  （A1，9.934 秒）。
