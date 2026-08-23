# 实现 definite Stack 两 FILL mixed active-max 第二 max freeze 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76（均已 resolved）

## Question

Ticket 10 §3/§7 冻结了 multiple Stack main-FILL 的 weighted iterative min/max water filling。T73 已允许恰好
两项 max-only 的固定两次 freeze，并把最终正余量交给 justify。如何接受首个 active-max child 同时带一个始终
inactive 的合法 min、第二项仍为 max-only 的退化路径，同时不开放 mixed active-min、第二项 mixed、多个首轮
active bound、一般循环或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有两个 main-axis FILL；两项 weight 均 positive。第一轮恰好一个
   active bound，且该 child 同时携带 finite/nonnegative `min` 与 `max`、`min <= max`，实际 share 严格高于
   `max`；因此其 `min` 在冻结到 `max` 前后都保持满足。另一 child 仅携带 finite/nonnegative `max`，第一轮
   share 不高于自身 max。
3. **固定第二次 freeze 与 justify**：先冻结 mixed active child 到 max；唯一未冻结 child 直接取得
   `remaining - firstMax`，不做 division。只有该 offer 严格高于其 max 时才准入并冻结第二 max；最终
   `maxSum < remaining` 的正余量继续由既有 occupied/free-space 与 `justifyContent` 公式消费。`min == max`
   合法且仍由严格 active-max hit 决定；不引入 epsilon/tolerance。
4. **布局顺序不变**：staged allocations 后的 signed margins、gap、occupied/free-space、justify、cross alignment、
   cursor 与 authored DFS first-error/全有或全无 output 不变；每项仍按 authored order 至多执行一次 deferred
   cross-HUG remeasure。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/40`。把既有 exactly-two mixed second-max
   negative 转 positive，新增 active-last、COLUMN、cross-HUG 与 `min == max` positives；新增 mixed active-min
   second-min negative。目标 177 laid-out + 16 unsupported、193 cases、577 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：mixed active-min、第二项 mixed、首轮多个 active、第二轮未严格超过 max、three-or-more
   cascade、terminal bound、active min/max overflow、four-or-more active-bound FILL、一般多轮 water filling、
   Profile residual tolerance/public numeric error、HUG-main FILL cycle、rows→columns、任意非直角 rotation、
   Text/Image/compositionViewport、resource fetch/decode、scene/raster/JPEG、daemon RESULT/Profile、Java/OpenAPI/
   migration/Web/route、J1/A3/READY 与外部副作用均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/40` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  mixed second-max case 共同 RED；再分别实现严格控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/40` 由 Rust primary 与 Python independent verifier 在同一首个 mixed active-max second-max 转正
  case 共同 RED 后，分别实现并达到 177 laid-out + 16 unsupported、193/193 cases、577 checks；vector
  SHA-256 为 `75c36c665d033189afba36e1cbf0ca544015172c83c579736dacff5d9d449516`，fixture `/3` SHA-256
  保持 `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- `stack_main_fill_allocations` 只在 exactly-two、首轮唯一 active-max child 的合法 `min <= max` 始终
  inactive、另一项 max-only 且 second offer 严格越过 max 时执行固定两次 max freeze；最终正余量继续交给
  既有 justify。能力值新增 `OR_EXACT_TWO_FILL_MIXED_ACTIVE_MAX_SECOND_MAX_FREEZE_FREE_JUSTIFY`；不做第二轮
  division、循环、epsilon/tolerance 或一般 water filling。
- focused Rust 3/3、Python independent 193/193、workspace fmt/clippy `-D warnings`/tests、`py_compile`、JSON
  inventory/SHA/unique 与 `git diff --check` 全绿；分级 A1 证据为 `render`
  `.sdlc/evidence/20260823-192740-render/`（20.459 秒）、affected `fast`
  `.sdlc/evidence/20260823-192810-fast/`（9.544 秒）、顺序 `server`
  `.sdlc/evidence/20260823-192828-server/`（1181.649 秒）与 17-step `full`
  `.sdlc/evidence/20260823-194822-full/`（1710.624 秒），均 exit 0。
- `full` 中 App 344 tests/0 failures/0 errors/15 skipped、Node 24 Web 26 files/212 tests、runtime canary、
  23 passed + 1 controlled skip Playwright、prototype/Draft browser journeys 与 inference replay E2E 1/1
  均通过；R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0。
- 诚实边界保持不变：Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、world transform/scene/raster
  `ABSENT`、daemon output `UNWIRED`；mixed active-min、第二项 mixed、terminal bound、首轮多个 active、
  four-or-more 与一般 water filling 继续 fail closed。未推进 A3/J1/READY，未 push/tag/PR。
- 本状态更新后的 resolution `fast` `.sdlc/evidence/20260823-201920-fast/` 3 steps 也均 exit 0
  （A1，9.915 秒）。
