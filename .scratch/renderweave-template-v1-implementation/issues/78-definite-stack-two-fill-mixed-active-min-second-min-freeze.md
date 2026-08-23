# 实现 definite Stack 两 FILL mixed active-min 第二 min freeze 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77（均已 resolved）

## Question

Ticket 10 §3/§7 冻结了 multiple Stack main-FILL 的 weighted iterative min/max water filling。T72 已允许恰好
两项 min-only 的固定两次 freeze 与 min-sum overflow。如何接受首个 active-min child 同时带一个始终 inactive
的合法 max、另一 child 仍为 min-only 的退化路径，同时不开放另一 child mixed、多个首轮 active bound、一般
循环或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有两个 main-axis FILL；两项 weight 均 positive。第一轮恰好一个
   active bound，且该 child 同时携带 finite/nonnegative `min` 与 `max`、`min <= max`，实际 share 严格低于
   `min`；因此其 `max` 在冻结到 `min` 前后都保持满足。另一 child 仅携带 finite/nonnegative `min`，第一轮
   share 不低于自身 min。
3. **固定第二次 freeze 与 overflow**：先冻结 mixed active child 到 min；唯一未冻结 child 直接取得
   `remaining - firstMin`，不做 division。只有该 offer 严格低于其 min 时才准入并冻结第二 min；最终
   `minSum > remaining` 的 overflow 继续由既有 occupied/free-space 与 justify START fallback 公式消费。
   `min == max` 合法且仍由严格 active-min hit 决定；不引入 epsilon/tolerance。
4. **布局顺序不变**：staged allocations 后的 signed margins、gap、occupied/free-space、justify、cross alignment、
   cursor 与 authored DFS first-error/全有或全无 output 不变；每项仍按 authored order 至多执行一次 deferred
   cross-HUG remeasure。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/41`。把既有 exactly-two mixed active-min
   negative 转 positive，新增 active-first、COLUMN、cross-HUG 与 `min == max` positives；新增另一 child 也为
   mixed 的 negative。能力值新增 `OR_EXACT_TWO_FILL_MIXED_ACTIVE_MIN_SECOND_MIN_FREEZE_OVERFLOW`；目标
   182 laid-out + 16 unsupported、198 cases、592 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：另一 child mixed、首轮多个 active、第二轮未严格低于 min、three-or-more cascade、
   second-min sum overflow for three FILL、active min overflow for three FILL、terminal bound、four-or-more
   active-bound FILL、一般多轮 water filling、Profile residual tolerance/public numeric error、HUG-main FILL
   cycle、rows→columns、任意非直角 rotation、Text/Image/compositionViewport、resource fetch/decode、scene/raster/
   JPEG、daemon RESULT/Profile、Java/OpenAPI/migration/Web/route、J1/A3/READY 与外部副作用均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/41` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  mixed active-min case 共同 RED；再分别实现严格控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/41` 由 Rust primary 与 Python independent verifier 在同一首个 mixed active-min second-min 转正
  case 共同 RED 后，分别实现并达到 182 laid-out + 16 unsupported、198/198 cases、592 checks；vector
  SHA-256 为 `5fb4ec185dd59e3f73ce1796eb93da65e280e21814885aa16c4580d40c72968d`，fixture `/3` SHA-256
  保持 `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- `stack_main_fill_allocations` 只在 exactly-two、首轮唯一 active-min child 的合法 `min <= max` 中 max
  始终 inactive、另一项 min-only 且 second offer 严格低于 min 时执行固定两次 min freeze；min-sum overflow
  继续交给既有 occupied/free-space 与 justify START fallback。能力值新增
  `OR_EXACT_TWO_FILL_MIXED_ACTIVE_MIN_SECOND_MIN_FREEZE_OVERFLOW`；不做第二轮 division、循环、epsilon/
  tolerance 或一般 water filling。
- focused Rust 3/3、Python independent 198/198、workspace fmt/clippy `-D warnings`/tests、`py_compile`、JSON
  inventory/SHA/unique 与 `git diff --check` 全绿；分级 A1 证据为 `render`
  `.sdlc/evidence/20260823-203006-render/`（19.590 秒）、affected `fast`
  `.sdlc/evidence/20260823-203033-fast/`（11.539 秒）、顺序 `server`
  `.sdlc/evidence/20260823-203054-server/`（1144.788 秒）与 17-step `full`
  `.sdlc/evidence/20260823-205008-full/`（1806.514 秒），均 exit 0。
- `full` 中 App 344 tests/0 failures/0 errors/15 skipped、Node 24 Web 26 files/212 tests、runtime canary、
  23 passed + 1 controlled skip Playwright、prototype/Draft browser journeys 与 inference replay E2E 1/1
  均通过；R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0。
- 诚实边界保持不变：Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、world transform/scene/raster
  `ABSENT`、daemon output `UNWIRED`；另一项 mixed、terminal bound、首轮多个 active、four-or-more 与一般
  water filling 继续 fail closed。未推进 A3/J1/READY，未 push/tag/PR。
- 本状态更新后的 resolution `fast` `.sdlc/evidence/20260823-212141-fast/` 3 steps 也均 exit 0
  （A1，10.985 秒）。
