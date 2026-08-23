# 实现 definite Stack 三 FILL 第二 max freeze 与末项余量子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75（均已 resolved）

## Question

Ticket 10 §3/§7 冻结了 multiple Stack main-FILL 的 weighted iterative min/max water filling。T75 已允许恰好
三项、首轮唯一 active min、一次重分配后恰好一个第二 min active、末项无界的固定两次 freeze。如何接受其
max-only 对偶：第一次重分配后恰好一个 max 变为 active、第二次 freeze 后只剩一个无界项，同时不开放第三次
freeze、一般循环、justify 特判或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有三个 main-axis FILL；三项 weight 均 positive；第一轮 stable
   weighted shares 恰好只有一个 finite/nonnegative max-only bound active。另两项中恰好一项携带
   finite/nonnegative max-only bound并在第一轮 inactive，最后一项 owning-axis 完全无 bound。
3. **固定第二次 freeze**：先冻结首轮 active max，再对另外两项按原 positive fillWeight/authored order 对
   `remaining - firstMax` 做一次 weighted share + exact remainder。只有 bounded share 严格高于其 max 时才冻结
   第二 max；唯一无界项直接取得 `remaining - firstMax - secondMax`。两个 max 都由严格 active share 保证不超过
   当轮 remaining，因此末项余量有限且非负。不做第二轮 division、第三次 freeze、循环、justify 特判或
   tolerance；equality 仍由 T74 作为 inactive bound 接受。
4. **布局顺序不变**：staged allocations 完成后，signed margins、gap、occupied/free-space、justify、cross
   alignment 与 cursor 公式保持既有顺序；末项消费全部余量，既有 justify 自然看到 zero free space。每项按
   authored order 至多执行一次 deferred cross-HUG remeasure；deeper error 继续保持 authored DFS first-error 和
   全有或全无 output。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/39`。把既有 three-FILL second-max negative
   转 positive，并新增 active-middle、active-last、COLUMN 与 cross-HUG positives；新增 terminal-bound negative。
   目标 172 laid-out + 16 unsupported、188 cases、562 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：首轮多个 active、首个 active min、第一次重分配后 second min/mixed/多个 active、末项带
   bound、active min/max overflow、四项及以上 active-bound FILL、第三次 freeze/cascade、一般多轮 water filling、
   Profile residual tolerance/public numeric error、HUG-main FILL cycle、rows→columns、任意非直角 rotation、
   Text/Image/compositionViewport、resource fetch/decode、scene/raster/JPEG、daemon RESULT/Profile 与 E6 均不在本票。
   exactly-two mixed-bound second max 继续单独 fail closed，不借本票放宽 bound-shape admission。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/39` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  second-max case 共同 RED；再分别实现严格控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/39` 由 Rust primary 与 Python independent verifier 在同一首个 second-max 转正 case 共同 RED 后，
  分别实现并达到 172 laid-out + 16 unsupported、188/188 cases、562 checks；vector SHA-256 为
  `c6f59cfa5bfceb366b708245b296934da8de7bf9bafe11c1806bd2ac9e99098e`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- `stack_main_fill_allocations` 只对 exactly-three/first-active-max/second-active-max/terminal-unbounded 的固定
  子集执行两次 max freeze，并把最终 exact remainder 直接交给唯一无界项；不做第二轮 division、第三次
  freeze、循环、justify 特判、epsilon/tolerance 或一般 water filling。能力值新增
  `OR_EXACT_THREE_FILL_SECOND_MAX_FREEZE_LAST_REMAINDER`。
- focused Rust 3/3、Python independent 188/188、workspace fmt/clippy `-D warnings`/tests、`py_compile`、JSON
  inventory/SHA/unique 与 `git diff --check` 全绿；分级 A1 证据为 `render`
  `.sdlc/evidence/20260823-175710-render/`（19.746 秒）、affected `fast`
  `.sdlc/evidence/20260823-175805-fast/`（10.082 秒）、顺序 `server`
  `.sdlc/evidence/20260823-175822-server/`（1143.440 秒）与成功的 17-step `full`
  `.sdlc/evidence/20260823-184512-full/`（1746.114 秒），均 exit 0。
- 首次 `full` `.sdlc/evidence/20260823-181731-full/` 的前 14 steps 均 exit 0，唯一失败是并行
  `prototype-e2e` 中恢复页动态 import abort 的 5 秒时序抖动；固定 Node 24、单 worker 精确用例连续 3/3
  通过，随后上述完整 `full` 重跑 17/17 steps 全绿，未为此改动 Web 产品或测试代码。
- 成功 `full` 中 App 344 tests/0 failures/0 errors/15 skipped、Node 24 Web 26 files/212 tests、runtime
  canary、23 passed + 1 controlled skip Playwright、prototype/Draft browser journeys 与 inference replay E2E
  1/1 均通过；R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0。
- 诚实边界保持不变：Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、world transform/scene/raster
  `ABSENT`、daemon output `UNWIRED`；exactly-two mixed-bound、terminal bound、首轮多个 active、四项及以上、
  第三次 freeze 与一般 water filling 继续 fail closed。未推进 A3/J1/READY，未 push/tag/PR。
- 本状态更新后的 resolution `fast` `.sdlc/evidence/20260823-191642-fast/` 3 steps 也均 exit 0
  （A1，11.044 秒）。
