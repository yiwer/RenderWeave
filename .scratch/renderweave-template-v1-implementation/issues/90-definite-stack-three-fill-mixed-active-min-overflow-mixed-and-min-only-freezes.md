# 实现 definite Stack 三 FILL mixed active-min overflow mixed + min-only freezes 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89（均已 resolved）

## Question

T89 已允许 exactly-three main FILL 的 mixed active min 自身大于 remaining 后，另外两项都是初始 inactive
的合法 mixed min/max，并固定到三个 authored minima。若其中一项仍为 mixed min/max、另一项只携带 positive
min，且二者在初始 proportional share 下都 inactive，如何固定到三个 minima，而不开放两个 additional
min-only、初始 multiple-active、post-overflow redistribution、four-or-more FILL、一般循环或 Profile residual
tolerance？

## Answer（本票冻结的实施决定）

1. **只深化 T89 early branch**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好三个 main-axis FILL，三项 weight 均 positive；第一轮恰好一个
   finite/nonnegative active min，active child 携带合法 finite/nonnegative min/max、`min <= max`，且 first min
   严格大于 `remaining`。另外两项都携带 finite positive min，各自初始 proportional share `>= min`；本票新增
   的形状恰好一项为合法 mixed min/max 且初始 share `<= max`，另一项为 min-only。既有 two-mixed 形状继续有效，
   但 two-min-only、max-only、absent 或非法 mixed 继续拒绝。`share == min`、`share == max` 与 `min == max` 均接受。
3. **固定两个 additional min 后终止**：active child 取 first min，另外两项各取 authored min，按 authored
   position 显式提交三个 minima；mixed 项 `min <= max` 保证最终 max inactive。因 first min 已严格大于 remaining
   且两个 additional min 均为正，尺寸和必然 overflow；不计算负 residual、post-overflow weight/share、
   redistribution、第四轮、循环或 epsilon/tolerance。
4. **既有输出语义不变**：既有 occupied/free-space 公式令六种 `justifyContent` 退回零 extra/START 分布。
   signed margins、gap、cursor、cross alignment、每项至多一次 deferred cross-HUG remeasure、authored DFS
   first-error 与全有或全无 output 均不变。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/53`。把 T89 replacement negative 转为
   active-first positive，新增 active-middle、active-last equality、COLUMN 与 cross-HUG positives；以两个
   additional positive min-only children 的 negative 替换。能力值新增
   `OR_EXACT_THREE_FILL_MIXED_ACTIVE_MIN_OVERFLOW_MIXED_AND_MIN_ONLY_FREEZES_OVERFLOW`；目标 242 laid-out +
   16 unsupported、258 cases/772 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：两个 additional candidates 都是 min-only、任一 max-only/absent、非法 mixed bound、初始
   share 越界、首轮多个 active、active child 非 mixed 或 min 不大于 remaining、post-overflow redistribution、
   four-or-more active-bound FILL、一般多轮 water filling、Profile residual tolerance/public numeric error、
   HUG-main FILL cycle、rows→columns、任意非直角 rotation、Text/Image/compositionViewport、resource fetch/decode、
   scene/raster/JPEG、daemon RESULT/Profile、Java/OpenAPI/migration/Web/route、J1/A3/READY 与外部副作用均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/53` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  mixed-and-min-only case、同一 `STACK_MAIN_FILL` occurrence 共同 RED；再分别实现严格控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/check/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/53` 先只改 vectors/identity，使 Rust primary 与 Python independent verifier 在首个
  `stack-three-main-fills-mixed-active-min-overflow-mixed-and-min-only-freezes`、同一 `STACK_MAIN_FILL`
  occurrence `rwocc_0000000000000002` 共同 RED；两套独立控制流实现后 exact-bit GREEN。
- Rust 将原 two-mixed shape 判定深化为 two-additional-minimum shape，并只把 `all(is_mixed)` 放宽为
  `any(is_mixed)`；Python 独立统计 `additional_mixed_minimum_count >= 1`。因此恰好 one-mixed + one-min-only
  可按 authored position 固定为三个 minima，而 replacement negative
  `stack-three-main-fills-mixed-active-min-overflow-two-min-only-freezes-remain-unsupported` 仍因 mixed count 为零
  fail closed；未开放 post-overflow redistribution、循环、water filling 或 tolerance。
- 最终 shared corpus 为 242 laid-out + 16 unsupported、258/258 cases、772 checks；vector SHA-256
  `f2c7fbaca44f55e1396c24f25f6a4d401ee2ada012709402c5a9ff8f30fd7762`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。focused Rust/Python、workspace
  fmt/check/clippy `-D warnings`/tests、`py_compile`、JSON inventory/SHA/unique 与 `git diff --check` 全绿。
- 分级 A1 证据 `render` `.sdlc/evidence/20260824-102959-render/`（31.128 秒）、affected `fast`
  `.sdlc/evidence/20260824-103044-fast/`（10.794 秒）、顺序 `server`
  `.sdlc/evidence/20260824-103101-server/`（1217.038 秒）与 `full`
  `.sdlc/evidence/20260824-105141-full/`（1880.817 秒）均 exit 0。
- `full` definite A2 replay 258/258、772 checks；App 344 tests/0 failures/0 errors/15 skipped，Node 24 Web
  26 files/212 tests、runtime canary、23 passed + 1 controlled skip Playwright、三种 prototype variant、Draft
  journey 与 inference replay E2E 1/1 均通过。R0/R1/P0 provider attempts=0，P0 API Key
  reads/reservations/cost/open authorization=0；R1 A2 为 60 cases/58 metrics，P0 A2 为 60 cases（20 holdout）/
  58 metrics。Profile/A3/J1/READY 未推进，未运行 provider、读取 API Key 或发送真实数据，未 push/tag/PR。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260824-112506-fast/` 3 steps 均 exit 0（A1，
  11.288 秒）；同目录 `t90-final-a2.json` 再确认 258/258 cases、772 checks、provider attempts 0。
