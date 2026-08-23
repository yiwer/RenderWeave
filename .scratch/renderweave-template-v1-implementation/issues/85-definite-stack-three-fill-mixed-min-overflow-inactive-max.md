# 实现 definite Stack 三 FILL mixed min-overflow inactive max 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84（均已 resolved）

## Question

T84 已支持 exactly-three main FILL 的 mixed active-min overflow，并以 authored `min/0/0` 退化终止，但要求
另外两项 owning-axis bound 全 absent。若其中恰好一项只携带在初始 proportional share 下 inactive、且对终止正零
仍合法的 finite/nonnegative max，如何复用 T84 而不开放 unfrozen min、多个 bound、post-overflow redistribution、
第二次 freeze、一般循环或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有三个 main-axis FILL，三项 weight 均 positive；第一轮恰好一个
   finite/nonnegative active min，active child 携带合法 finite/nonnegative min/max、`min <= max`，且 min 严格大于
   `remaining`。另外两项中恰好一项只携带 finite/nonnegative max-only，初始 proportional share 不大于该 max；
   另一项 owning-axis min/max 全 absent。
3. **inactive max 后仍终止**：按 authored position 显式提交 `min/0/0`；max-only child 的终止值是正零，因 max
   finite/nonnegative 而继续满足。不得计算负 residual、post-overflow weight/share、redistribution、第二次 freeze、
   循环或 epsilon/tolerance；初始 `share == max` 合法。
4. **溢出、布局与错误顺序不变**：accepted 尺寸和可大于 remaining；既有 occupied/free-space 公式令六种
   `justifyContent` 退回零 extra/START 分布。signed margins、gap、cursor、cross alignment、每项至多一次 deferred
   cross-HUG remeasure、authored DFS first-error 与全有或全无 output 均不变。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/48`。把 T84 replacement negative 转为
   active-first positive，新增 active-middle、active-last（覆盖初始 `share == max`）、COLUMN 与 cross-HUG positives；
   以初始 inactive、但在终止正零下 active 的 unfrozen min-only negative 替换。能力值新增
   `OR_EXACT_THREE_FILL_MIXED_ACTIVE_MIN_OVERFLOW_INACTIVE_UNFROZEN_MAX`；目标 217 laid-out + 16 unsupported、
   233 cases/697 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：任一 unfrozen min、两个 unfrozen bounded children、unfrozen mixed bound、首轮多个 active、
   active min 不大于 remaining、second mixed freeze、terminal mixed、four-or-more active-bound FILL、一般多轮
   water filling、Profile residual tolerance/public numeric error、HUG-main FILL cycle、rows→columns、任意非直角
   rotation、Text/Image/compositionViewport、resource fetch/decode、scene/raster/JPEG、daemon RESULT/Profile、
   Java/OpenAPI/migration/Web/route、J1/A3/READY 与外部副作用均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/48` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  inactive-unfrozen-max case、同一 `STACK_MAIN_FILL` occurrence 共同 RED；再分别实现严格控制流并达到 exact-bit
  GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/48` 先只改 vectors/identity，Rust primary 与 Python independent verifier 在首个转正
  `stack-three-main-fills-mixed-active-min-overflow-inactive-unfrozen-max` case、同一 `STACK_MAIN_FILL` occurrence
  `rwocc_0000000000000002` 共同 RED；分别实现后达到 217 laid-out + 16 unsupported、233/233 cases、697 checks。
  vector SHA-256 为 `d3fe971d1bfd40224e371c7cd7fa2574204ea265c1b470d16ca0b7a575067156`，fixture `/3`
  SHA-256 保持 `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- `stack_main_fill_allocations` 只在 T84 exactly-three/mixed-active-min-overflow 路径接受零或一个初始 inactive 的
  finite/nonnegative max-only unfrozen bound，并要求初始 share 不大于 max；Python 以独立 loop/list 控制流重放。
  能力值新增 `OR_EXACT_THREE_FILL_MIXED_ACTIVE_MIN_OVERFLOW_INACTIVE_UNFROZEN_MAX`。active-last equality vector
  使用精确 90/30/100 authored shape，避免非 canonical decimal；replacement negative 证明初始 inactive、终止正零下
  active 的 unfrozen min 继续 fail closed，两个 bounded children、mixed unfrozen、多个 active、redistribution/freeze/
  cycle 也未开放。
- focused Rust 1/1、Python independent 233/233、workspace fmt/check/clippy `-D warnings`/tests、`py_compile`、JSON
  inventory/SHA/unique 与 `git diff --check` 全绿；分级 A1 证据为 `render`
  `.sdlc/evidence/20260824-044428-render/`（35.11 秒）、affected `fast`
  `.sdlc/evidence/20260824-044543-fast/`（9.566 秒）、顺序 `server`
  `.sdlc/evidence/20260824-044604-server/`（1180.857 秒）与 17-step `full`
  `.sdlc/evidence/20260824-050555-full/`（1710.988 秒），全部 exit 0。
- full 中 definite-layout independent replay 233/233、697 checks；App 344/0/0/15、Node 24 Web 26 files/212 tests、
  runtime canary、23 passed + 1 controlled skip Playwright、prototype/Draft journeys 与 inference replay E2E 1/1
  均通过。R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0；R1 A2 60 cases/58 metrics、J0，P0
  A2 60 cases（20 holdout）/58 metrics。Profile/scene/raster/daemon 与 A3/J1/READY 未推进，未 push/tag/PR。
- 本状态更新后的 resolution `fast` `.sdlc/evidence/20260824-053557-fast/` 的 3 steps 也均 exit 0
  （A1，9.689 秒）。
