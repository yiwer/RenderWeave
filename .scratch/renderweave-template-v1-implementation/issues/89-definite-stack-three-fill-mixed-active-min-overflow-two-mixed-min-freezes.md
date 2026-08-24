# 实现 definite Stack 三 FILL mixed active-min overflow 两个 mixed-min freeze 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88（均已 resolved）

## Question

T88 已允许 exactly-three main FILL 的 mixed active min 自身大于 remaining 后，一个 second mixed child
冻结到 min，另一 terminal child 只携带 inactive max。若 terminal child 也携带合法 mixed min/max，且初始
proportional share 位于闭区间内，如何按三个 authored minima 固定终止，而不开放 mixed/min-only 组合、初始
multiple-active、post-overflow redistribution、four-or-more FILL、一般循环或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化 T88 early branch**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好三个 main-axis FILL，三项 weight 均 positive；第一轮恰好一个
   finite/nonnegative active min，active child 携带合法 finite/nonnegative min/max、`min <= max`，且 first min
   严格大于 `remaining`。另外两项都携带 finite mixed min/max，各自满足 `0 < additionalMin <= additionalMax`，
   且初始 proportional share 位于各自闭区间内。`share == min`、`share == max` 与 `min == max` 均接受。
3. **固定两个 additional min 后终止**：active child 取 first min，另外两项各取 authored min，按 authored
   position 显式提交三个 minima；每项 `min <= max` 保证最终 max inactive。因 first min 已严格大于 remaining 且
   两个 additional min 均为正，尺寸和必然 overflow；不计算负 residual、post-overflow weight/share、
   redistribution、第四轮、循环或 epsilon/tolerance。
4. **既有输出语义不变**：既有 occupied/free-space 公式令六种 `justifyContent` 退回零 extra/START 分布。
   signed margins、gap、cursor、cross alignment、每项至多一次 deferred cross-HUG remeasure、authored DFS
   first-error 与全有或全无 output 均不变。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/52`。把 T88 replacement negative 转为
   active-first positive，新增 active-middle、active-last（覆盖两个 additional mixed 的 min/max equality）、
   COLUMN 与 cross-HUG positives；以一个 additional mixed + 一个 positive min-only child 的 mixed/min-only
   negative 替换。能力值新增
   `OR_EXACT_THREE_FILL_MIXED_ACTIVE_MIN_OVERFLOW_TWO_MIXED_MIN_FREEZES_OVERFLOW`；目标 237 laid-out +
   16 unsupported、253 cases/757 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：两个 additional candidates 中任一 min-only/max-only/absent、非法 mixed bound、初始 share
   越界、首轮多个 active、active child 非 mixed 或 min 不大于 remaining、post-overflow redistribution、
   four-or-more active-bound FILL、一般多轮 water filling、Profile residual tolerance/public numeric error、
   HUG-main FILL cycle、rows→columns、任意非直角 rotation、Text/Image/compositionViewport、resource fetch/decode、
   scene/raster/JPEG、daemon RESULT/Profile、Java/OpenAPI/migration/Web/route、J1/A3/READY 与外部副作用均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/52` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  two-mixed-min-freezes case、同一 `STACK_MAIN_FILL` occurrence 共同 RED；再分别实现严格控制流并达到 exact-bit
  GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/check/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/52` 先只改 vectors/identity，使 Rust primary 与 Python independent verifier 在首个
  `stack-three-main-fills-mixed-active-min-overflow-two-mixed-min-freezes`、同一 `STACK_MAIN_FILL` occurrence
  `rwocc_0000000000000002` 共同 RED；两套独立控制流实现后 exact-bit GREEN。
- Rust 把 additional minimum freeze 分类为 authored-position `Vec`，保留既有单 candidate 分支，只在恰好两个
  candidates、两者都是合法 mixed min/max、active child 自身有 max 且不存在额外 max-only 时提交两个 authored
  minima；Python 以独立 `additional_minimum_candidates` 计数、all-mixed 判定和 position map 重放同一语义。
  replacement negative
  `stack-three-main-fills-mixed-active-min-overflow-second-mixed-min-freeze-terminal-min-only-remains-unsupported`
  因 mixed + min-only 不满足 all-mixed 继续 fail closed，未开放一般 redistribution 或 water filling。
- 最终 shared corpus 为 237 laid-out + 16 unsupported、253/253 cases、757 checks；vector SHA-256
  `a824ad5b378cd190319a59c1cb55893d39f922de6435a9d7ac207caeeb4f864e`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。focused Rust/Python、workspace
  fmt/check/clippy `-D warnings`/tests、`py_compile`、JSON inventory/SHA/unique 与 `git diff --check` 全绿。
- 分级 A1 证据 `render` `.sdlc/evidence/20260824-092128-render/`（22.779 秒）、affected `fast`
  `.sdlc/evidence/20260824-092158-fast/`（10.293 秒）、顺序 `server`
  `.sdlc/evidence/20260824-092216-server/`（1149.605 秒）与 `full`
  `.sdlc/evidence/20260824-094146-full/`（1851.526 秒）均 exit 0。
- `full` definite A2 replay 253/253、757 checks；App 344 tests/0 failures/0 errors/15 skipped，Node 24 Web
  26 files/212 tests、runtime canary、23 passed + 1 controlled skip Playwright、三种 prototype variant、Draft
  journey 与 inference replay E2E 1/1 均通过。R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0；
  R1 A2 为 60 cases/58 metrics，P0 A2 为 60 cases（20 holdout）/58 metrics。Profile/A3/J1/READY 未推进，未运行
  provider、读取 API Key 或发送真实数据，未 push/tag/PR。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260824-101515-fast/` 3 steps 均 exit 0（A1，12.161 秒）。
