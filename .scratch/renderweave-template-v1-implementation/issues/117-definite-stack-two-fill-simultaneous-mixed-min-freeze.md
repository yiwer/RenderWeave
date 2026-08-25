# 实现 definite Stack 两 FILL simultaneous mixed-min freeze 子闭包

Type: task
Status: resolved
Resolution: automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 10, 19, 23, 25, 26, 33, 38, 67, 68, 69, 72, 78, 112, 115（本切片前置均已 resolved）

## Question

T115 已允许 exactly-two main FILL 中首轮唯一 mixed active-min 的确定性终止路径；若两项合法 mixed child
在首轮 weighted share 下同时严格低于各自 min，如何按 authored order 一次冻结两个 minima 并接受确定的
min-sum overflow，同时不开放 simultaneous min/max、三个以上 FILL、一般循环或 residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用
   `measure_and_allocate_stack_children` → `stack_main_fill_allocations`；不新增 public Interface、crate、parser、
   Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好两个 main-axis FILL，weight 均 finite/positive；两项都携带
   合法 finite/nonnegative min/max、`min <= max`，且首轮 authored-order weighted share 都严格低于各自 min。
3. **同轮双 min freeze**：按 authored position 把两项直接冻结到各自 min；两项 max 必须继续满足。min 总和可
   超过 remaining，既有 occupied/free-space/justify START fallback 负责 overflow；不计算负 residual，不做第二轮
   division、redistribution、epsilon 或 tolerance。
4. **布局与失败纪律不变**：signed margin、gap、cross alignment、每项至多一次 deferred cross-HUG remeasure、
   authored DFS first-error 与全有或全无 output 均不变；失败 occurrence 仍为首个 authored main-FILL child。
5. **TDD 与独立重放**：shared definite-layout vector 升级为 `/57`。先把既有 simultaneous-min negative 转为
   positive，使 Rust primary 与 Python independent verifier 在同一 occurrence 共同 RED；GREEN 后补 authored
   reverse、COLUMN、deferred cross-HUG 与 `min == max` regression，并新增 simultaneous min/max replacement
   negative。目标 261 laid-out + 16 unsupported、277 cases/829 checks。
6. **诚实边界**：本票不开放 simultaneous min/max 或 max/max、three-or-more/four-or-more 一般 water filling、
   循环/epsilon/tolerance、rows→columns、任意非直角 rotation、Text/compositionViewport、native raster、Profile
   registration/certification、Java/OpenAPI/Web/正式产品 route、J1/A3/READY 或外部副作用；`/prototype` 不计
   最终交付。

## 验证与完成信号

- focused Rust 与 Python independent verifier 先在同一 tracer case/occurrence RED，再由两套独立控制流
  exact-bit GREEN；补齐回归 vectors 后检查 JSON inventory/SHA/unique。
- 局部：Cargo focused/workspace fmt/check/clippy `-D warnings`/tests、Python `py_compile`、`git diff --check`。
- 分级：canonical `render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`；Maven gate
  不并发。
- 最高只报 `automated_verified`；Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  `ABSENT`、正式产品 route `CLOSED`、native stack `BUILD_NOT_AUTHORIZED`；provider/API Key/费用/真实数据=0，
  不 push/tag/PR。

## Closeout

- TDD RED：shared tracer `stack-two-main-fills-simultaneous-mixed-min-freeze-overflow` 先令 Rust primary 与
  Python independent verifier 同时在 `rwocc_0000000000000002` 以 `STACK_MAIN_FILL` 失败；最小实现只深化
  `stack_main_fill_allocations`，没有引入第二 solver、循环、第二次 division 或 tolerance。
- GREEN：两套独立控制流都按 authored position 冻结两个合法 mixed minima，仅在 min-sum 严格大于 remaining
  时接受 terminal overflow。authored reverse、COLUMN、deferred cross-HUG 与 `min == max` positives 均通过；
  simultaneous mixed min/max replacement negative 仍 fail closed。
- shared vector 为 `renderweave-definite-layout-vectors/57`：261 laid-out + 16 unsupported、277 个唯一案例、
  829 checks；vector SHA-256
  `26af3dceeab1dded86828037851af19d076ec3a45b65cc69f54ef0225b63ff94`，fixture SHA-256
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- local fmt/check/Clippy `-D warnings`/workspace tests、Python `py_compile`、JSON inventory/unique/SHA 与
  `git diff --check` 均绿。canonical `render`：
  `.sdlc/evidence/20260825-191413-render/`（2/2 steps，42.272 秒）；affected `fast`：
  `.sdlc/evidence/20260825-191504-fast/`（3/3 steps，10.747 秒）；sequential `server`：
  `.sdlc/evidence/20260825-191522-server/`（passed/A1，354 tests、0 failures、0 errors、15 controlled skips）。
- Goal `full`：`.sdlc/evidence/20260825-192555-full/`，17/17 steps、passed/A1、1036.002 秒；Node 24 Web
  28 files/217 tests + production build、runtime canary、Playwright 23 passed + 1 controlled skip、Draft 与
  inference browser journeys 均通过，provider attempts/API Key reads/reservations/cost/open authorization=0。
- 状态与证据回填后的 resolution `fast`：`.sdlc/evidence/20260825-194552-fast/`，3/3 steps、
  passed/A1、12.559 秒。
- 生命周期状态为 `resolved / automated_verified`。Profile `NOT_REGISTERED`、certification
  `NOT_CERTIFIED`、process raster `ABSENT`、native stack `BUILD_NOT_AUTHORIZED`、public Rendering API 与
  正式产品 route `CLOSED`；最终产品 Template-v1 页面与真实功能仍未交付，`/prototype` 不计交付，未推进
  J1/A3/READY，未 push/tag/PR。
