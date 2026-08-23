# 实现 definite Stack 三 FILL mixed active-min overflow 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83（均已 resolved）

## Question

Ticket 10 §3/§7 冻结了 multiple Stack main-FILL 的 weighted iterative min/max water filling。T82 已支持
exactly-three 的唯一 active min 严格大于 remaining，并以 authored `min/0/0` 退化终止；T78 已证明 active-min
child 可以同时携带一个在冻结前后都 inactive 的合法 max。如何组合这两个既有闭包，同时不开放 unfrozen bound、
多个 active、post-freeze redistribution、一般循环或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有三个 main-axis FILL；三项 weight 均 positive；第一轮 share
   恰好一个 finite/nonnegative active min，且该 child 同时携带 finite/nonnegative min/max、`min <= max`，其
   min 严格大于本轮 `remaining`。另两项 owning-axis min/max 必须全部 absent。
3. **mixed min-overflow 后终止**：active child 直接取 authored min，另外两项按 authored position 取正零；max
   在初始 share 与冻结到 min 后均满足。不得计算负 `remaining-min`、post-freeze weight sum/share、redistribution、
   第二次 freeze、循环或 epsilon/tolerance；`min == max` 合法。
4. **溢出、布局与错误顺序不变**：accepted 尺寸和可大于 remaining；既有 occupied/free-space 公式令六种
   `justifyContent` 退回零 extra/START 分布。signed margins、gap、cursor、cross alignment、每项至多一次 deferred
   cross-HUG remeasure、authored DFS first-error 与全有或全无 output 均不变。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/47`。把 T82 留下的 mixed active-min overflow
   negative 转为 active-first positive，新增 active-middle、active-last（覆盖 `min == max`）、COLUMN 与 cross-HUG
   positives，并以 mixed active child + 一个 inactive unfrozen max 的 negative 替换。能力值新增
   `OR_EXACT_THREE_FILL_MIXED_ACTIVE_MIN_OVERFLOW`；目标 212 laid-out + 16 unsupported、228 cases/682 checks，
   fixture `/3` bytes 不变。
6. **固定能力边界**：任一 unfrozen bound、首轮多个 active、首轮 active min 不大于 remaining、second mixed
   freeze、terminal mixed、four-or-more active-bound FILL、一般多轮 water filling、Profile residual tolerance/
   public numeric error、HUG-main FILL cycle、rows→columns、任意非直角 rotation、Text/Image/
   compositionViewport、resource fetch/decode、scene/raster/JPEG、daemon RESULT/Profile、Java/OpenAPI/migration/
   Web/route、J1/A3/READY 与外部副作用均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/47` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  mixed-active-min-overflow case、同一 `STACK_MAIN_FILL` occurrence 共同 RED；再分别实现严格控制流并达到 exact-bit
  GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/47` 先只改 vectors/identity，Rust primary 与 Python independent verifier 在首个转正
  `stack-three-main-fills-mixed-active-min-overflows-remaining` case、同一 `STACK_MAIN_FILL` occurrence
  `rwocc_0000000000000002` 共同 RED；分别实现后达到 212 laid-out + 16 unsupported、228/228 cases、682 checks。
  vector SHA-256 为 `ac45adb070b1615cf217f393739b4ddb265f296b061ca7efe439f549fd5697da`，fixture `/3`
  SHA-256 保持 `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- `stack_main_fill_allocations` 只对 exactly-three/唯一 active min/mixed active bound 合法且 min-overflow/其余 owning-axis
  bound 全 absent 子集按 authored position 显式提交 `min/0/0`；Python 以独立控制流重放。能力值新增
  `OR_EXACT_THREE_FILL_MIXED_ACTIVE_MIN_OVERFLOW`；replacement negative 证明 inactive unfrozen max 继续 fail closed，
  多个 active、post-freeze redistribution、second/terminal mixed freeze、four-or-more 与一般循环也未开放。
- focused Rust 1/1、Python independent 228/228、workspace fmt/check/clippy `-D warnings`/tests、`py_compile`、JSON
  inventory/SHA/unique 与 `git diff --check` 全绿；分级 A1 证据为 `render`
  `.sdlc/evidence/20260824-034350-render/`（33.783 秒）、affected `fast`
  `.sdlc/evidence/20260824-034439-fast/`（9.703 秒）、顺序 `server`
  `.sdlc/evidence/20260824-034455-server/`（1161.575 秒）与 17-step `full`
  `.sdlc/evidence/20260824-040443-full/`（1734.292 秒），全部 exit 0。
- full 中 definite-layout independent replay 228/228、682 checks；App 344/0/0/15、Node 24 Web 26 files/212 tests、
  runtime canary、23 passed + 1 controlled skip Playwright、prototype/Draft journeys 与 inference replay E2E 1/1
  均通过。R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0；R1 A2 60 cases/58 metrics、J0，P0
  A2 60 cases（20 holdout）/58 metrics。Profile/scene/raster/daemon 与 A3/J1/READY 未推进，未 push/tag/PR。
- 本状态更新后的 resolution `fast` `.sdlc/evidence/20260824-043520-fast/` 的 3 steps 也均 exit 0
  （A1，14.011 秒）。
