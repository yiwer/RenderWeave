# 实现 definite Stack 三 FILL mixed active-min overflow 第二 mixed-min freeze terminal inactive max 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87（均已 resolved）

## Question

T87 已允许 exactly-three main FILL 的 mixed active min overflow 后，唯一 second mixed child 冻结到 min，
第三项完全 unbounded。若第三项只携带一个在初始 proportional share 与终止正零下都 inactive 的合法 max-only
bound，如何组合 T85 的 inactive max 与 T87 的固定终止，而不开放 second min-only + terminal max、第三个 min、
post-overflow redistribution、第三次 freeze、一般 mixed cascade 或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化 T87 early branch**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好三个 main-axis FILL，三项 weight 均 positive；第一轮恰好一个
   finite/nonnegative active min，active child 携带合法 finite/nonnegative min/max、`min <= max`，且 first min
   严格大于 `remaining`。另外两项中恰好一项携带 finite mixed min/max，满足 `0 < secondMin <= secondMax`，
   初始 proportional share 位于闭区间 `[secondMin, secondMax]`；另一项只携带 finite/nonnegative max，且初始
   share `<= terminalMax`。`share == secondMin`、`share == secondMax`、`secondMin == secondMax` 与
   `share == terminalMax` 均接受。
3. **固定第二次 min freeze 后终止**：首个 mixed min 先冻结，second mixed child 随后冻结到 min；terminal
   max-only child 取正零，因 `terminalMax >= 0` 仍满足 max。按 authored position 显式提交
   `firstMin/secondMin/0`；不计算负 residual、post-overflow weight/share、redistribution、第三次 freeze、循环或
   epsilon/tolerance。
4. **既有输出语义不变**：accepted 尺寸和严格大于 remaining；既有 occupied/free-space 公式令六种
   `justifyContent` 退回零 extra/START 分布。signed margins、gap、cursor、cross alignment、每项至多一次 deferred
   cross-HUG remeasure、authored DFS first-error 与全有或全无 output 均不变。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/51`。把 T87 replacement negative 转为
   active-first positive，新增 active-middle、active-last（覆盖 second mixed 与 terminal max equality）、COLUMN 与
   cross-HUG positives；以 terminal max-only 再携带初始 inactive、终止正零 active 的 positive min 的 third-freeze
   negative 替换。能力值新增
   `OR_EXACT_THREE_FILL_MIXED_ACTIVE_MIN_OVERFLOW_SECOND_MIXED_MIN_FREEZE_OVERFLOW_TERMINAL_INACTIVE_MAX`；目标
   232 laid-out + 16 unsupported、248 cases/742 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：second min-only + terminal max、terminal min/mixed bound、非法或初始 active terminal max、
   second mixed bound 非 finite/负值/`min <= 0`/`min > max`/初始 share 越界、首轮多个 active、active child 非
   mixed 或 min 不大于 remaining、post-overflow redistribution、第三次 freeze/cascade、four-or-more active-bound
   FILL、一般多轮 water filling、Profile residual tolerance/public numeric error、HUG-main FILL cycle、
   rows→columns、任意非直角 rotation、Text/Image/compositionViewport、resource fetch/decode、scene/raster/JPEG、
   daemon RESULT/Profile、Java/OpenAPI/migration/Web/route、J1/A3/READY 与外部副作用均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/51` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  terminal-inactive-max case、同一 `STACK_MAIN_FILL` occurrence 共同 RED；再分别实现严格控制流并达到 exact-bit
  GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/check/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/51` 先只改 vectors/identity，使 Rust primary 与 Python independent verifier 在首个
  `stack-three-main-fills-mixed-active-min-overflow-second-mixed-min-freeze-terminal-inactive-max`、同一
  `STACK_MAIN_FILL` occurrence `rwocc_0000000000000002` 共同 RED；两套独立控制流实现后 exact-bit GREEN。
- Rust 以 `second_minimum_is_mixed` 显式标记唯一 second-min candidate，只有它为合法 mixed bound 时才允许
  另一项为一个初始 inactive 的 finite/nonnegative max-only bound；Python 以独立 `(position, min, is_mixed)`
  candidate 分类重放同一语义。second min-only + terminal max 仍由代码形状拒绝；replacement negative
  `stack-three-main-fills-mixed-active-min-overflow-second-mixed-min-freeze-terminal-mixed-third-min-remains-unsupported`
  因产生第二个 min candidate 继续 fail closed，未开放第三次 freeze 或一般 cascade。
- 最终 shared corpus 为 232 laid-out + 16 unsupported、248/248 cases、742 checks；vector SHA-256
  `0585d2a5564f4a6c71d50291f2d7a8b2d30b849868bb1a57e21f370165a40bc8`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。focused Rust/Python、workspace
  fmt/check/clippy `-D warnings`/tests、`py_compile`、JSON inventory/SHA/unique 与 `git diff --check` 全绿。
- 分级 A1 证据 `render` `.sdlc/evidence/20260824-081743-render/`（19.377 秒）、affected `fast`
  `.sdlc/evidence/20260824-081808-fast/`（14.211 秒）、顺序 `server`
  `.sdlc/evidence/20260824-081828-server/`（1172.144 秒）与 `full`
  `.sdlc/evidence/20260824-083810-full/`（1732.566 秒）均 exit 0。
- `full` definite replay 248/248、742 checks；App 344 tests/0 failures/0 errors/15 skipped，Node 24 Web
  26 files/212 tests、runtime canary、23 passed + 1 controlled skip Playwright、三种 prototype variant、Draft
  journey 与 inference replay E2E 1/1 均通过。R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0；
  R1 A2 为 60 cases/58 metrics，P0 A2 为 60 cases（20 holdout）/58 metrics。Profile/A3/J1/READY 未推进，未运行
  provider、读取 API Key 或发送真实数据，未 push/tag/PR。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260824-090830-fast/` 3 steps 均 exit 0（A1，10.480 秒）。
