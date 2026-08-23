# 实现 definite Stack 两 max 第二次 freeze 与 justify 余量子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72（均已 resolved）

## Question

Ticket 10 §3/§7 冻结了 multiple Stack main-FILL 的 weighted iterative min/max water filling，并明确 max-sum
不足时把余量交给 justify。T72 已实现恰好两项 min-only 的固定两轮 overflow 路径。如何继续实现恰好两项
max-only、首轮唯一 active max 冻结后另一项也命中 max、最终保留正余量给 justify 的固定两轮退化路径，同时不开放
mixed bounds、三项 cascade、一般 N 项循环或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有两个 main-axis FILL；两项都只有 owning-axis authored max、没有
   min；两个 max 均 finite/nonnegative；第一轮 weighted share 恰好只有一项严格高于 max，另一项第一轮 share 不高于
   自身 max。
3. **固定第二轮与 justify**：先冻结 active child 到 max；唯一未冻结 child 直接取得
   `remaining - activeMax`，不做 division。只有该非负余量严格大于其 authored max 时才准入，并立即把第二项冻结到
   max；最终 `maxSum < remaining` 的正余量继续由既有 occupied/free-space 与 `justifyContent` 公式分配。
4. **布局顺序不变**：staged allocations 完成后，signed margins、gap、occupied/free-space、justify、cross alignment 与
   cursor 公式保持既有顺序；每项按 authored order 至多执行一次 deferred cross-HUG remeasure；deeper error 继续保持
   authored DFS first-error 和全有或全无 output。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/36`。把现有 second-max negative 转 positive，
   新增 active-last、COLUMN 对称与 cross-HUG remeasure positives；新增 mixed-bound exactly-two 与 exactly-three
   second-max negatives。目标 157 laid-out + 17 unsupported、174 cases、519 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：首轮多个 active、任一 min/max mixed authored bound、第二轮未严格超过 max、三个及以上 FILL 的
   cascade、一般多轮 water filling、Profile residual tolerance/public numeric error、HUG-main FILL cycle、
   rows→columns、任意非直角 rotation、Text/Image/compositionViewport、resource fetch/decode、scene/raster/JPEG、
   daemon RESULT/Profile 与 E6 均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、不发送
   真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/36` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  second-max case 共同 RED；再分别实现严格控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/36` 由 Rust primary 与 Python independent verifier 共同达到 157 laid-out + 17 unsupported、
  174/174 cases、519 checks；vector SHA-256 为
  `fba78341c5e46c67916dc2660c785c47b1c0e84204730d85bcd5074f9f7a1a01`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- focused Rust 3/3、Python independent 174/174、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check` 全绿；共同 RED 后由两套独立控制流转 GREEN。
- 初次 `render` `.sdlc/evidence/20260823-144753-render/` 因 gate-side boundary identity 尚未同步而正确
  fail closed；同步能力字面量后，分级 A1 证据 `render` `.sdlc/evidence/20260823-144845-render/`
  （20.040 秒）、affected `fast` `.sdlc/evidence/20260823-144913-fast/`（10.103 秒）、顺序 `server`
  `.sdlc/evidence/20260823-144933-server/`（1124.913 秒）与 17-step `full`
  `.sdlc/evidence/20260823-150914-full/`（1710.783 秒）全部 exit 0。
- `full` 中 App 344 tests/0 failures/0 errors/15 skipped，Node 24 Web 26 files/212 tests，runtime canary、
  23 passed + 1 controlled skip Playwright、Draft/browser journeys 与 inference replay E2E 1/1 均通过。
  R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0。
- 诚实边界保持不变：Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、world transform/scene/raster
  `ABSENT`、daemon output `UNWIRED`；mixed bounds、三个及以上 second freeze/cascade、一般 water filling 与
  tolerance 继续 fail closed。未推进 A3/J1/READY，未 push/tag/PR。
- 本状态更新后的 resolution `fast` `.sdlc/evidence/20260823-154131-fast/` 3 steps 也均 exit 0
  （A1，12.359 秒）。
