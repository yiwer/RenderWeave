# 实现 definite Stack 两 min 第二次 freeze overflow 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71（均已 resolved）

## Question

Ticket 10 §3/§7 冻结了 multiple Stack main-FILL 的 weighted iterative min/max water filling。T67–T71 已实现
无 bound、inactive bound、单 active freeze、single-min overflow 与三项单次重分配。如何继续实现恰好两项
FILL、首轮唯一 active min 冻结后另一项也命中 min、最终允许 min-sum overflow 的固定两轮退化路径，同时不开放
max/mixed cascade、一般 N 项循环或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有两个 main-axis FILL；两项都只有 owning-axis authored min、没有
   max；两个 min 均 finite/nonnegative；第一轮 weighted share 恰好只有一项严格低于 min；该 active min 不大于
   `remaining`，另一项第一轮 share 满足其 min。
3. **固定第二轮与 overflow**：先冻结 active child 到 min；唯一未冻结 child 直接取得
   `remaining - activeMin`，不做 division。只有该非负余量严格小于其 authored min 时才准入，并立即把第二项冻结到
   min；最终两项保持 authored min，即使 min-sum 大于可用空间也不缩小，后续既有 occupied/free-space 公式把 free
   置零并沿 overflow fallback 使用 START。
4. **布局顺序不变**：staged allocations 完成后，signed margins、gap、occupied/free-space、justify、cross alignment 与
   cursor 公式保持既有顺序；每项按 authored order 至多执行一次 deferred cross-HUG remeasure；deeper error 继续保持
   authored DFS first-error 和全有或全无 output。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/35`。把现有 second-freeze negative 转 positive，
   新增 active-first、COLUMN 对称与 cross-HUG remeasure positives；新增 exactly-two second-max 与 exactly-three
   second-min negatives。目标 153 laid-out + 16 unsupported、169 cases、505 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：首轮两个 active、任何 max/mixed cascade、active min 大于 remaining 且另一项带 bound、第二轮
   不命中 min、三个及以上 FILL 的 cascade、一般多轮 water filling、Profile residual tolerance/public numeric error、
   HUG main-axis FILL cycle、rows→columns、任意非直角 rotation、Text/Image/compositionViewport、resource
   fetch/decode、scene/raster/JPEG、daemon RESULT/Profile 与 E6 均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、不发送
   真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/35` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  second-min case 共同 RED；再分别实现严格控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/35` 由 Rust primary 与 Python independent verifier 共同达到 153 laid-out + 16 unsupported、
  169/169 cases、505 checks；vector SHA-256 为
  `3573ca66421733dd21b6d36f55011c218b07c3326dc55835d421c0861d928ff2`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- 局部 focused Rust 3/3、Python independent 169/169、workspace fmt/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check` 全绿；共同 RED 后由两套独立控制流转 GREEN。
- 分级 A1 证据全绿：`render` `.sdlc/evidence/20260823-134353-render/`（20.955 秒）、affected
  `fast` `.sdlc/evidence/20260823-134421-fast/`（10.530 秒）、顺序 `server`
  `.sdlc/evidence/20260823-134438-server/`（1119.224 秒）与 17-step `full`
  `.sdlc/evidence/20260823-140409-full/`（1710.808 秒）。
- `full` 中 App 344 tests/0 failures/0 errors/15 skipped，Node 24 Web 26 files/212 tests，runtime canary、
  23 passed + 1 controlled skip Playwright、Draft/browser journeys 与 inference replay E2E 1/1 均通过。
  R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0。
- 诚实边界保持不变：Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、world transform/scene/raster
  `ABSENT`、daemon output `UNWIRED`；max/mixed、三个及以上 cascade、一般 water filling 与 tolerance 继续
  fail closed。未推进 A3/J1/READY，未 push/tag/PR。
- 本状态更新后的 resolution `fast` `.sdlc/evidence/20260823-143436-fast/` 3 steps 也均 exit 0
  （A1，10.035 秒）。
