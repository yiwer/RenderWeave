# 实现 definite Stack 三 FILL mixed active-min overflow 第二 mixed-min freeze 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86（均已 resolved）

## Question

T86 已允许 exactly-three main FILL 的 mixed active min 自身大于 remaining 后，唯一 second min-only child 在
终止正零下冻结到正 min。若该 second child 还携带一个在初始 proportional share 与最终 second min 下都
inactive 的合法 max，如何复用同一固定 second-min freeze，而不开放额外 bounded child、初始 multiple-active、
post-overflow redistribution、第三次 freeze、一般 mixed cascade 或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化 T86 early branch**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好三个 main-axis FILL，三项 weight 均 positive；第一轮恰好一个
   finite/nonnegative active min，active child 携带合法 finite/nonnegative min/max、`min <= max`，且 first min
   严格大于 `remaining`。另外两项中恰好一项携带 finite mixed min/max，满足 `0 < secondMin <= secondMax`，
   初始 proportional share 位于闭区间 `[secondMin, secondMax]`；另一项 owning-axis min/max 全 absent。边界
   `share == secondMin`、`share == secondMax` 与 `secondMin == secondMax` 均接受。
3. **固定第二次 min freeze 后终止**：首个 mixed min 先冻结；second mixed child 随后只按 min freeze，最终
   `secondMin <= secondMax` 保证其 max 仍 inactive。按 authored position 显式提交 `firstMin/secondMin/0`；不计算
   负 residual、post-overflow weight/share、redistribution、第三次 freeze、循环或 epsilon/tolerance。
4. **既有输出语义不变**：accepted 尺寸和严格大于 remaining；既有 occupied/free-space 公式令六种
   `justifyContent` 退回零 extra/START 分布。signed margins、gap、cursor、cross alignment、每项至多一次 deferred
   cross-HUG remeasure、authored DFS first-error 与全有或全无 output 均不变。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/50`。把 T86 replacement negative 转为
   active-first positive，新增 active-middle、active-last（覆盖 min/max equality）、COLUMN 与 cross-HUG positives；
   以 second mixed child 之外再带一个 inactive max-only child 的 multiple-extra-bound negative 替换。能力值新增
   `OR_EXACT_THREE_FILL_MIXED_ACTIVE_MIN_OVERFLOW_SECOND_MIXED_MIN_FREEZE_OVERFLOW`；目标 227 laid-out +
   16 unsupported、243 cases/727 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：second min/max 非 finite、负值、`min <= 0`、`min > max`、初始 share 越过任一 bound、
   另一个 unfrozen child 也带任意 bound、第三个 min、首轮多个 active、active child 非 mixed 或 min 不大于
   remaining、post-overflow redistribution、第三次 freeze/cascade、four-or-more active-bound FILL、一般多轮 water
   filling、Profile residual tolerance/public numeric error、HUG-main FILL cycle、rows→columns、任意非直角 rotation、
   Text/Image/compositionViewport、resource fetch/decode、scene/raster/JPEG、daemon RESULT/Profile、Java/OpenAPI/
   migration/Web/route、J1/A3/READY 与外部副作用均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/50` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  second-mixed-min case、同一 `STACK_MAIN_FILL` occurrence 共同 RED；再分别实现严格控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/check/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/50` 先只改 vectors/identity，使 Rust primary 与 Python independent verifier 在首个
  `stack-three-main-fills-mixed-active-min-overflow-second-mixed-min-freeze-overflow`、同一
  `STACK_MAIN_FILL` occurrence `rwocc_0000000000000002` 共同 RED；两套独立控制流实现后 exact-bit GREEN。
- Rust 只接受唯一 second mixed candidate 的 finite/nonnegative `min/max`、`0 < min <= max` 与初始 share 闭区间
  guard；Python 独立分类同一语义。`share == min`、`share == max`、`min == max` 均由 positive vectors 覆盖，
  third child 再带 inactive max-only 的 replacement negative 仍 fail closed，未开放额外 bound 或一般 cascade。
- 最终 shared corpus 为 227 laid-out + 16 unsupported、243/243 cases、727 checks；vector SHA-256
  `bb8ba56c2130e72c5d0d14f42b96df777b827b8b7fb3582c597e0faabe886590`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。focused Rust/Python、workspace
  fmt/check/clippy `-D warnings`/tests、`py_compile`、JSON inventory/SHA/unique 与 `git diff --check` 全绿。
- 分级 A1 证据 `render` `.sdlc/evidence/20260824-064528-render/`（24.440 秒）、affected `fast`
  `.sdlc/evidence/20260824-064601-fast/`（10.434 秒）、顺序 `server`
  `.sdlc/evidence/20260824-064618-server/`（1166.890 秒）均 exit 0。首次 `full`
  `.sdlc/evidence/20260824-070557-full/` 的前 14 steps 全绿，最后 prototype E2E 因 Chromium 请求
  `@react-refresh` 命中 Windows `net::ERR_NO_BUFFER_SPACE` 而单用例超时；未改产品代码，精确用例隔离重跑
  `.sdlc/evidence/20260824-073509-t87-playwright-isolated/` 1/1 通过。权威重跑 `full`
  `.sdlc/evidence/20260824-073525-full/` 17 steps 全部 exit 0（1738.701 秒）。
- 权威 `full` 中 definite replay 243/243、727 checks，App 344 tests/0 failures/0 errors/15 skipped，Node 24 Web
  26 files/212 tests、runtime canary、23 passed + 1 controlled skip Playwright、Draft journey 与 inference replay
  E2E 1/1 均通过。R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0；R1 A2 为 60 cases/
  58 metrics，P0 A2 为 60 cases（20 holdout）/58 metrics。Profile/A3/J1/READY 未推进，未运行 provider、读取
  API Key 或发送真实数据，未 push/tag/PR。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260824-080607-fast/` 3 steps 均 exit 0（A1，14.968 秒）。
