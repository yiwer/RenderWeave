# 实现 definite Stack 三 FILL 重分配后 inactive bound 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73（均已 resolved）

## Question

Ticket 10 §3/§7 冻结了 multiple Stack main-FILL 的 weighted iterative min/max water filling。T71 已实现恰好
三项、首轮唯一 active bound、其余两项无 bound 的一次冻结与重分配。如何继续接受未冻结项带 authored bound、
但重分配后的 share 仍全部满足这些 bound 的有限退化路径，同时不开放第二次 freeze、一般循环或 Profile residual
tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有三个 main-axis FILL；第一轮 stable weighted shares 恰好命中
   一个 finite/nonnegative owning-axis min 或 max，frozen bound 不大于 `remaining`；另外两项可各自携带 optional
   finite/nonnegative min/max，但第一轮均未命中。active child 仍可带与 frozen bound 相容的另一侧 inactive bound。
3. **一次重分配后只复核、不再 freeze**：冻结 active child 后，以 `remaining - frozenBound` 对另外两项按原
   positive fillWeight/authored order 做一次 weighted share + exact remainder；随后逐项检查新 share 仍满足
   `share >= min && share <= max`，equality 接受。全部满足才提交 staged allocations；任一严格越界仍在首个
   authored FILL occurrence 返回 `STACK_MAIN_FILL`，不执行第二次 freeze 或重分配。
4. **布局顺序不变**：signed margins、gap、occupied/free-space、justify、cross alignment 与 cursor 公式保持既有
   顺序；每项按 authored order 至多执行一次 deferred cross-HUG remeasure；deeper error 继续保持 authored DFS
   first-error 和全有或全无 output。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/37`。把既有 unfrozen-inactive-bound negative
   转 positive，并新增 active-middle、active-last、COLUMN 与 cross-HUG positives；既有 second-min/second-max
   negatives 继续证明重分配后 active bound fail closed。目标 162 laid-out + 16 unsupported、178 cases、532
   checks，fixture `/3` bytes 不变。
6. **固定能力边界**：首轮多个 active、重分配后任一 active bound、active min 大于 remaining、四项及以上
   active-bound FILL、第二次 freeze/cascade、一般多轮 water filling、Profile residual tolerance/public numeric
   error、HUG-main FILL cycle、rows→columns、任意非直角 rotation、Text/Image/compositionViewport、resource
   fetch/decode、scene/raster/JPEG、daemon RESULT/Profile 与 E6 均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/37` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  case 共同 RED；再分别实现严格控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/37` 由 Rust primary 与 Python independent verifier 共同达到 162 laid-out + 16 unsupported、
  178/178 cases、532 checks；vector SHA-256 为
  `d864606199a87879c618e110ac439308c53eb8ec63e8cd85de6245c0736e9138`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- focused Rust 3/3、Python independent 178/178、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check` 全绿；共同 RED 后由两套独立控制流转 GREEN。
- 分级 A1 证据 `render` `.sdlc/evidence/20260823-155209-render/`（22.619 秒）、affected `fast`
  `.sdlc/evidence/20260823-155240-fast/`（10.020 秒）、顺序 `server`
  `.sdlc/evidence/20260823-155256-server/`（1137.272 秒）与 17-step `full`
  `.sdlc/evidence/20260823-161201-full/`（1714.113 秒）全部 exit 0。
- `full` 中 App 344 tests/0 failures/0 errors/15 skipped，Node 24 Web 26 files/212 tests，runtime canary、
  23 passed + 1 controlled skip Playwright、Draft/browser journeys 与 inference replay E2E 1/1 均通过。
  R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0。
- 诚实边界保持不变：Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、world transform/scene/raster
  `ABSENT`、daemon output `UNWIRED`；重分配后 active bound、多个首轮 active、四项及以上与一般 water filling
  继续 fail closed。未推进 A3/J1/READY，未 push/tag/PR。
- 本状态更新后的 resolution `fast` `.sdlc/evidence/20260823-164256-fast/` 3 steps 也均 exit 0
  （A1，9.949 秒）。
