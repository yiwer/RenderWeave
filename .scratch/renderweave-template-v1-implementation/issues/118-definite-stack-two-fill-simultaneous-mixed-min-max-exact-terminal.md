# 实现 definite Stack 两 FILL simultaneous mixed min/max exact-terminal 子闭包

Type: task
Status: resolved
Resolution: automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 10, 19, 23, 25, 26, 33, 38, 67, 68, 69, 73, 77, 115, 117（本切片前置均已 resolved）

## Question

T117 已允许 exactly-two main FILL 的两个合法 mixed child 在首轮同时命中 min 时形成确定 overflow；当前首个
unsupported case 是一项首轮 share 严格低于 min、另一项严格高于 max，且两个命中 bound 的和精确等于
remaining。如何冻结这个无需 residual 的终止路径，同时不开放 bound-sum 不等于 remaining、三个以上 FILL、
一般循环或 Profile tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用
   `measure_and_allocate_stack_children` → `stack_main_fill_allocations`；不新增 public Interface、crate、parser、
   Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好两个 main-axis FILL，weight 均 finite/positive；两项都携带
   合法 finite/nonnegative min/max、`min <= max`。首轮 authored-order weighted share 恰好一项严格低于其
   min、另一项严格高于其 max。
3. **精确终止条件**：按 authored position 将 active-min child 冻结到 min、active-max child 冻结到 max；只在
   两个 frozen bound 的 binary64 顺序和精确等于 `remaining` 时成功。不得使用 epsilon/tolerance、第二轮
   division、redistribution 或 residual clamp。
4. **布局与失败纪律不变**：两项 allocation 之和保持 exact remaining；既有 signed margin、gap、occupied/
   free-space/justify、cross alignment、每项至多一次 deferred cross-HUG remeasure、authored DFS first-error 与
   全有或全无 output 均不变。失败 occurrence 仍为首个 authored main-FILL child。
5. **TDD 与独立重放**：shared definite-layout vector 升级为 `/58`。先只把既有 simultaneous mixed min/max
   negative 转为 positive，使 Rust primary 与 Python independent verifier 在同一 occurrence 共同 RED；最小
   GREEN 后再逐个补 authored reverse、COLUMN、deferred cross-HUG 与 exact-bound regression，并保留
   bound-sum overflow/free-space replacement negatives。
6. **诚实边界**：本票不开放 simultaneous mixed min/max 的非零 residual、simultaneous max/max、三个以上/
   四个以上一般 water filling、循环/epsilon/tolerance、rows→columns、任意非直角 rotation、Text/
   compositionViewport、native raster、Profile registration/certification、Java/OpenAPI/Web/正式产品 route、
   J1/A3/READY 或外部副作用；`/prototype` 不计最终交付。

## 验证与完成信号

- seam：只经 public `layout_definite_resource_free` 与独立 Python definite-layout replay 观察 exact LayoutBox；不测
  private helper、不 mock 内部模块。
- focused Rust 与 Python independent verifier 先在同一 tracer case/occurrence RED，再按独立控制流 exact-bit
  GREEN；每个后续回归按一条 vertical slice 加入。
- 局部：Cargo focused/workspace fmt/check/clippy `-D warnings`/tests、Python `py_compile`、JSON inventory/SHA/
  unique、`git diff --check`。
- 分级：canonical `render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`；Maven
  不并发。
- 最高只报 `automated_verified`；Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  `ABSENT`、正式产品 route `CLOSED`、native stack `BUILD_NOT_AUTHORIZED`；provider/API Key/费用/真实数据=0，
  不 push/tag/PR。

## Closeout

- TDD RED：先只把 shared tracer `stack-two-main-fills-simultaneous-mixed-min-and-max-exact-terminal` 转为
  laid-out，Rust primary 与 Python independent verifier 都在 `rwocc_0000000000000002` 以
  `STACK_MAIN_FILL` 失败；最小实现只深化 `stack_main_fill_allocations`，未引入第二 solver、循环、第二次
  division、redistribution 或 tolerance。
- GREEN：两套独立控制流都只接受 exactly-two、合法 mixed bounds、首轮恰好一项 MIN hit 与一项 MAX hit，
  且按 authored position 顺序累加的两个 frozen bound binary64 exact-equal remaining。authored reverse、
  COLUMN、deferred cross-HUG 与 `min == max` positives 均通过；bound-sum overflow 与 free-residual replacement
  negatives 仍 fail closed。
- shared vector 为 `renderweave-definite-layout-vectors/58`：266 laid-out + 17 unsupported、283 个唯一案例、
  846 checks；vector SHA-256
  `e556839c623a820c3dfe9277e604a8b9b79cde02f67e764c4dc7c36a0b55219f`，fixture SHA-256
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- local focused/workspace Rust tests、fmt/check/Clippy `-D warnings`、Python `py_compile` 与 independent replay、
  JSON inventory/unique/SHA、`git diff --check` 均绿。canonical `render`：
  `.sdlc/evidence/20260825-200559-render/`（2/2 steps，passed/A1，42.299 秒）；affected `fast`：
  `.sdlc/evidence/20260825-200708-fast/`（3/3 steps，passed/A1，11.493 秒）。
- sequential `server`：`.sdlc/evidence/20260825-200727-server/`（passed/A1，653.722 秒；App 354 tests、
  0 failures、0 errors、15 controlled skips）。Goal `full`：
  `.sdlc/evidence/20260825-201835-full/`（17/17 steps、passed/A1、1069.938 秒）；Node 24 Web 28 files/
  217 tests + production build、runtime canary、Playwright 23 passed + 1 controlled skip、Draft 与 inference
  browser journeys 均通过，provider attempts/API Key reads/reservations/cost/open authorization=0。
- 状态与证据回填后的 resolution `fast`：`.sdlc/evidence/20260825-203838-fast/`，3/3 steps、passed/A1、
  11.064 秒。
- 生命周期状态为 `resolved / automated_verified`。Profile `NOT_REGISTERED`、certification
  `NOT_CERTIFIED`、process raster `ABSENT`、native stack `BUILD_NOT_AUTHORIZED`、public Rendering API 与
  正式产品 route `CLOSED`；最终产品 Template-v1 页面与真实功能仍未交付，`/prototype` 不计交付，未推进
  J1/A3/READY，未 push/tag/PR。
