# 实现 definite Stack 无 bound 多 FILL stable remainder 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66（均已 resolved）

## Question

Ticket 10 §3/§7 已冻结 definite Stack 的 weighted iterative min/max water filling，以及所有 distributed
allocation 按 authored order 计算前 `n-1` 项、最后一项接收余量；当前 kernel 对第二个 main-axis FILL 仍在
第一个 FILL occurrence fail closed。如何只补齐不含 owning-axis min/max bound 的比例分配退化子闭包，同时不
选择 residual tolerance、不实现 bound freeze/redistribution，也不改变 main-first cross-HUG 的单向顺序？

## Answer（本票冻结的实施决定）

1. **只深化既有 Stack deep module**：继续使用 `measure_and_allocate_stack_children`、同一 admitted/preflight
   tree 与 authored DFS 全有或全无 writer；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：同一 definite Stack 可有多个 main-axis FILL，但只有全部 FILL child 在 owning axis 均无
   `min*Pt`/`max*Pt` 时进入本票算法。任一 owning-axis bound 存在时，仍在第一个 authored FILL occurrence 返回
   closed internal `STACK_MAIN_FILL`；singleton FILL 的既有 clamp/justify 行为不变。
3. **无 bound 的精确比例退化**：按 authored order 先扣 materialized-adjacent gap、全部 signed main margins 与
   非 FILL definite size，令 `remaining=max(0,available-usedWithoutFill)`。按 authored order稳定求正 finite
   `totalWeight`；前 `n-1` 个 FILL 各取 `remaining*weight/totalWeight`，最后一个接收
   `remaining-allocatedBeforeLast`。所有中间值与结果须 finite/nonnegative，`-0` 归零；异常继续
   `STACK_MAIN_FILL`，不猜 epsilon。
4. **main-first cross-HUG 仍只重测一次**：每个 FILL 获得 final main offer 后，才按 authored order各执行一次既有
   deferred cross-HUG remeasure；不反算 main、不做 fixed point。支持子闭包中的 child/resource/rotation 错误按
   authored DFS 暴露，旧 multiple-FILL aggregate boundary 不再掩盖已被支持路径内的更深错误。
5. **occupied 与 justify 保持权威顺序**：全部 final FILL size 加回 occupied；无 bound 时其总和等于正 remaining，
   overflow-zero 时全部为零。现有 margin/gap/cursor/六种 justify/cross alignment/arrange 逻辑不变。
6. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/30`。把两个既有 multiple-FILL negatives 转为
   positives，新增 three-way last remainder 与 overflow-zero positives，并新增 owning-axis bound negative；目标
   131 laid-out + 11 unsupported、142 cases、429 checks，fixture `/3` bytes 不变。
7. **固定能力边界**：iterative min/max bound freeze/redistribution、Profile residual tolerance/public numeric
   error、HUG main-axis FILL cycle、rows→columns、任意非直角 rotation、Text/Image resource、compositionViewport、
   resource fetch/decode、scene/raster/JPEG、daemon RESULT/Profile/E6 均不在本票。
8. **诚实状态**：最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、不发送
   真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/30` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  case 共同 RED；再分别实现冻结控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- TDD：shared `/30` 先把两个既有 multiple-FILL negatives 转为 positives，并加入 three-way last remainder、
  overflow-zero 与 owning-axis bounded negative；Rust primary 与 Python independent verifier 在同一首个转正 case
  共同 RED，分别实现冻结控制流后达到 131 laid-out + 11 unsupported、142/142 cases、429 checks exact-bit GREEN。
- 实施：`measure_and_allocate_stack_children` 只对所有 owning-axis FILL 均无 min/max 的多个 FILL 启用一次性
  proportional allocation；按 authored order 稳定求正 finite weight sum，前 `n-1` 项接收 weighted share、最后一项
  接收余量。每个已分配 FILL 仅做一次 deferred cross-HUG remeasure；bounded multi-FILL 仍在首个 authored FILL
  occurrence 返回 `STACK_MAIN_FILL`，singleton、arrange、justify、authored DFS first-error 与全有或全无 output 不变。
- identity：shared vector/verifier identity 为 `renderweave-definite-layout-vectors/30` /
  `renderweave-definite-layout-python-independent/30`；vector SHA-256
  `af92241729657fc2cd1170c86e1d09903284fcaea69c83bb69784cdde6dd3b33`；layout-preflight fixture `/3`
  SHA-256 保持 `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- 局部验证：focused Rust vector tests 3/3、Python independent 142/142 cases/429 checks、workspace `cargo fmt`、
  clippy `-D warnings`、全部 Cargo tests、Python `py_compile`、JSON inventory/hash/unique 与 `git diff --check`
  全绿。
- 分级 gate：`render` `.sdlc/evidence/20260823-075005-render/`、affected `fast`
  `.sdlc/evidence/20260823-075139-fast/`、顺序 `server` `.sdlc/evidence/20260823-075200-server/` 与 Goal `full`
  `.sdlc/evidence/20260823-081142-full/` 均通过。full 17 steps 均 exit 0、总耗时 1699.912 秒；Maven App
  344 tests/0 failures/0 errors/15 skipped，Node 24 Web 26 files/212 tests，runtime canary、23 passed +
  1 controlled skip Playwright、prototype/Draft/Inference browser journeys 与最终 inference replay E2E 1/1
  均通过；resolution 后 fast `.sdlc/evidence/20260823-085000-fast/` 的 3 steps 也均 exit 0。
- 边界：R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0；Profile `NOT_REGISTERED`、
  certification `NOT_CERTIFIED`、Raster `ABSENT`、daemon `UNWIRED`。bounded Stack water filling、rows→columns、
  general constraint/tolerance、resource/scene/pixel 与完整 Renderer 保持 fail closed；最高仅
  `automated_verified`，未推进 A3/J1/READY，未 push/tag/PR。
