# 实现 definite Stack 三 FILL 单 bound 一次重分配子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70（均已 resolved）

## Question

Ticket 10 §3/§7 冻结了 multiple Stack main-FILL 的 weighted iterative min/max water filling。T67–T70 已依次
实现无 bound、inactive bound、恰好两项的单 active freeze，以及两项 single-min overflow。如何实现恰好三个
FILL、唯一 active bound 且其余两项完全无 bound 时的一次冻结与稳定重分配，同时不开放第二次 freeze、一般迭代或
Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有三个 main-axis FILL；T67/T68 第一轮 weighted share 恰好命中一个
   finite、非负的 `min*Pt` 或 `max*Pt`；另外两项 owning-axis min/max 均 absent；frozen bound 不得大于本轮
   `remaining`。active child 可带与 frozen bound 相容的另一侧 inactive bound。
3. **一次冻结、一次重分配**：active child 取 authored bound；以 `remaining - frozenBound` 得到非负余量；另外两项
   仅按各自原始 positive `fillWeight` 重新求一次权重和，按 authored order 让第一项取 weighted share、第二项接收
   exact remainder。不得重查 bound、做第二次 freeze、循环、epsilon clamp 或 tolerance 判定。
4. **布局顺序不变**：staged allocations 完成后，signed margins、gap、occupied/free-space、justify、cross alignment 与
   cursor 公式保持既有顺序；每项按 authored order 至多执行一次 deferred cross-HUG remeasure；deeper error 继续保持
   authored DFS first-error 和全有或全无 output。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/34`。把现有 three-FILL active-bound negative 转
   positive，新增 active-middle/active-last、COLUMN 对称与 cross-HUG remeasure positives；新增 unfrozen inactive
   bound、两个 active bound、active min overflow 与四 FILL negatives。目标 149 laid-out + 15 unsupported、164 cases、
   491 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：恰好两项的 other-bound cascading/second freeze、三项中任一未冻结项带 bound、三项 active min
   大于 remaining、多个 active、四项及以上 active-bound FILL、一般多轮 water filling、Profile residual tolerance/
   public numeric error、HUG main-axis FILL cycle、rows→columns、任意非直角 rotation、Text/Image/
   compositionViewport、resource fetch/decode、scene/raster/JPEG、daemon RESULT/Profile 与 E6 均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、不发送
   真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/34` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  three-FILL case 共同 RED；再分别实现严格控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/34` 的 Rust primary 与 Python independent verifier 先在同一首个转正 case
  `stack-three-main-fills-active-first-max-single-redistribution` 共同 RED；分别实现严格控制流后达到
  149 laid-out + 15 unsupported、164/164 cases、491 checks。
- `stack_main_fill_allocations` 保留 T67–T70 的退化路径；只对 exactly-three、唯一 active finite/nonnegative
  bound、另外两项 owning-axis 无 bound 且 frozen bound 不超过 remaining 的输入，冻结 active child，并让另外
  两项按原 positive `fillWeight` 与 authored order 做一次 stable weighted-share + exact-remainder 重分配。控制流不
  重查 bound、不执行第二次 freeze/循环/epsilon/tolerance；每项随后仍至多一次 deferred cross-HUG remeasure。
- vector identity 为 `renderweave-definite-layout-vectors/34`，Python independent identity 为
  `renderweave-definite-layout-python-independent/34`；vector SHA-256 为
  `455f24b380886c205e09b37279a866a0253723717a029db75c1a3c5f29150558`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- focused Rust 3/3、Python independent 164/164、workspace fmt/clippy `-D warnings`/tests、`py_compile`、JSON
  inventory/SHA/unique 与 `git diff --check` 全绿；分级证据为 `render`
  `.sdlc/evidence/20260823-130323-render/`（2 steps，A1，20.147 秒）、affected `fast`
  `.sdlc/evidence/20260823-121321-fast/`（3 steps，A1，10.11 秒）、顺序 `server`
  `.sdlc/evidence/20260823-121339-server/`（1 step，A1，1121.09 秒）与 Goal `full`
  `.sdlc/evidence/20260823-130437-full/`。
- full 17 steps 均 exit 0、总耗时 1591.368 秒；App 344 tests/0 failures/0 errors/15 skipped，Node 24 Web
  26 files/212 tests、runtime canary、23 passed + 1 controlled skip Playwright、draft browser journey 与最终
  inference replay E2E 1/1 均通过。R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0。
- two-FILL other-bound second freeze、three-FILL unfrozen-bound/多 active/min-overflow、four-or-more
  active-bound FILL 与一般 water filling 继续在首个 authored FILL occurrence 返回 `STACK_MAIN_FILL`；Profile 仍
  `NOT_REGISTERED`、certification `NOT_CERTIFIED`、Raster `ABSENT`、daemon `UNWIRED`，未推进 A3/J1/READY。
- 本状态更新后的 resolution `fast` `.sdlc/evidence/20260823-133203-fast/` 3 steps 也均 exit 0
  （A1，10.368 秒）。
