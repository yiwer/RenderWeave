# 实现 definite Stack 两 FILL 单 bound-freeze 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68（均已 resolved）

## Question

Ticket 10 §3/§7 已冻结 multiple Stack main-FILL 的 weighted iterative min/max water filling，但一般多轮
redistribution 仍会进入尚未给出数值的 Layout Profile residual tolerance。T68 已接受所有首轮 share 均不命中
bound 的路径。如何继续实现“首轮恰好一个 active bound”的最小严格子闭包，同时不实现一般 water filling、
min overflow 或第二个 bound freeze？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` 的同一 staged
   allocation seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有两个 main-axis FILL。先完全复用 T67/T68 的 positive-weight
   sum、前项 weighted share 与 authored last remainder；若全部 bound inactive，行为仍由 T68 覆盖。若恰好一项
   share 严格小于 min 或严格大于 max，且另一项在 owning axis 同时省略 min/max，才进入本票候选路径。
3. **单次冻结与精确余量**：active child 冻结到命中的 min/max；冻结值必须 finite、非负且 `<= remaining`。
   唯一未冻结 child 直接接收 `remaining - frozenBound`，不再做 division、weight sum、clamp 或第三轮。allocation
   仍按 authored child order staged，之后每项至多执行一次 deferred cross-HUG remeasure。
4. **严格 fail-closed 边界**：active min 大于 remaining、两项都 active、另一项即使首轮 bound inactive 但仍携带
   owning-axis bound、三个或更多 FILL 中任一 bound active、或任何非 finite/negative residual，均继续在第一个
   authored FILL occurrence 返回 `STACK_MAIN_FILL`。不把 min overflow 的 `max(0, ...)`、多未冻结项重分或第二次
   freeze 混入本票。
5. **occupied/justify/arrange 不变**：accepted 两项尺寸之和严格等于本轮 remaining，因此不产生新的 justify
   free space；signed margins、gap、cross alignment、authored DFS first error 与全有或全无 output 保持既有语义。
6. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/32`。把现有 active-min 与 active-max negatives
   转 positive，新增 owning Height positive、active allocation 后 cross-HUG remeasure、min 等于 remaining 的
   zero-remainder positive；新增 min overflow、另一项也有 bound、三 FILL active-bound negatives。目标 139 laid-out
   + 13 unsupported、152 cases、457 checks，fixture `/3` bytes 不变。
7. **固定能力边界**：一般 N-FILL redistribution、多 active/cascading bound、多轮 water filling、min overflow、
   Profile residual tolerance/public numeric error、HUG main-axis FILL cycle、rows→columns、任意非直角 rotation、
   Text/Image resource、compositionViewport、resource fetch/decode、scene/raster/JPEG、daemon RESULT/Profile 与 E6
   均不在本票。
8. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/32` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  case 共同 RED；再分别实现冻结控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/32` 的 Rust primary 与 Python independent verifier 先在同一首个转正 case
  `stack-two-main-fills-active-min-single-freeze` 共同 RED；分别实现后达到 139 laid-out + 13 unsupported、
  152/152 cases、457 checks。
- `measure_and_allocate_stack_children` 继续通过单一 staged allocation seam 调用重命名后的
  `stack_main_fill_allocations`：保留 T68 inactive-bound fast path；只对恰好两项 FILL、恰好一个 active bound、
  另一项 owning-axis 无 bound 且冻结值 finite/nonnegative/不超过 remaining 的输入执行一次 freeze + exact
  remainder；每项随后至多一次 deferred cross-HUG remeasure。
- vector identity 为 `renderweave-definite-layout-vectors/32`，Python independent identity 为
  `renderweave-definite-layout-python-independent/32`；vector SHA-256 为
  `5dfe649d0583b71e342eb9ec0a6c464214fa4cbb138b0d12daaf5be582866234`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- focused Rust 3/3、Python independent 152/152、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check` 全绿；分级证据为 `render`
  `.sdlc/evidence/20260823-100349-render/`、affected `fast` `.sdlc/evidence/20260823-100422-fast/`、顺序
  `server` `.sdlc/evidence/20260823-100439-server/` 与 Goal `full`
  `.sdlc/evidence/20260823-102333-full/`；resolution 后 fast `.sdlc/evidence/20260823-105540-fast/` 的
  3 steps 也均 exit 0（A1，12.618 秒）。
- full 17 steps 均 exit 0、总耗时 1673.539 秒；App 344 tests/0 failures/0 errors/15 skipped，Node 24 Web
  26 files/212 tests、runtime canary、23 passed + 1 controlled skip Playwright、browser journeys 与最终
  inference replay E2E 1/1 均通过。R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0。
- min overflow、另一项带 bound、三个及以上 active-bound FILL、多 active/cascading freeze 与一般 water
  filling 仍在首个 authored FILL occurrence 返回 `STACK_MAIN_FILL`；Profile 仍 `NOT_REGISTERED`、
  certification `NOT_CERTIFIED`、Raster `ABSENT`、daemon `UNWIRED`，未推进 A3/J1/READY。
