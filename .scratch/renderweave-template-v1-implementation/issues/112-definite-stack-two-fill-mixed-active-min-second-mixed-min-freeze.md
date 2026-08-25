# 实现 definite Stack 两 FILL mixed active-min + second mixed-min freeze 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 10, 19, 23, 25, 26, 33, 38, 67, 68, 69, 72, 78（本切片前置均已 resolved）

## Question

T78 已允许 exactly-two main FILL 中，一个合法 mixed min/max child 首轮命中 active min，另一项为
min-only，且冻结 active min 后唯一余量严格低于第二项 min 时执行固定第二次 min freeze。若第二项也携带合法
mixed min/max，且它的首轮 share 与最终冻结 min 均不超过 max，如何接受同一固定两轮退化路径，同时不开放
第二项在冻结后仍 inactive、active-max、首轮 multiple-active、three-or-more FILL、一般 water filling 或
Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用
   `measure_and_allocate_stack_children` → `stack_main_fill_allocations`；不新增 public API、crate、parser、
   Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好两个 main-axis FILL，weight 均 finite/positive；首轮恰好一个
   active min。active child 携带合法 finite/nonnegative min/max、`min <= max`，share 严格低于 min；另一项也
   携带合法 finite/nonnegative min/max，首轮 share 位于闭区间 `[min,max]`。
3. **固定第二次 freeze**：先把 active child 冻结到 min；唯一未冻结项直接取得
   `remaining - activeMin`，不做第二轮 division。仅当该 offer 严格低于其 min 时，把它冻结到 authored min；
   由于 `min <= max`，最终 max 保持满足。最终 min-sum overflow 继续由既有 occupied/free-space 与 justify START
   fallback 消费；equality 接受，不引入 epsilon/tolerance。
4. **保持输出与错误纪律**：signed margins、gap、authored order、cross alignment、每项至多一次 deferred
   cross-HUG remeasure、authored DFS first-error 与全有或全无 output 均不变。失败 occurrence 仍为首个 authored
   main-FILL child。
5. **TDD 与独立重放**：shared definite-layout vector 升级为 `/55`。先把既有
   `stack-two-main-fills-mixed-active-min-second-mixed-min-freeze-remains-unsupported` 转为 positive，使 Rust primary
   与 Python independent verifier 在同一 occurrence 共同 RED；GREEN 后补 active-first、COLUMN、cross-HUG、
   `min == max` positives，并以“第二项在第一次 freeze 后仍 inactive”的 mixed/mixed case 替换 negative。
6. **诚实边界**：本票不开放 second offer 未命中 min、active-max mixed cascade、首轮多个 active、
   three-or-more/four-or-more 一般 active-bound FILL、循环/epsilon/tolerance、rows→columns、任意非直角 rotation、
   Text/Image/compositionViewport、native raster、Profile registration/certification、Java/OpenAPI/Web/正式产品
   route、J1/A3/READY 或外部副作用；`/prototype` 不计最终交付。

## 验证与完成信号

- focused Rust 与 Python independent verifier 先在同一 tracer case/occurrence RED，再由两套独立控制流 exact-bit
  GREEN；补齐回归 vectors 后检查 JSON inventory/SHA/unique。
- 局部：Cargo focused/workspace fmt/check/clippy `-D warnings`/tests、Python `py_compile`、`git diff --check`。
- 分级：canonical `render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`；Maven gate
  不并发。
- 最高只报 `automated_verified`；Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  `ABSENT`、正式产品 route `CLOSED`、native stack `BUILD_NOT_AUTHORIZED`；provider/API Key/费用/真实数据=0，
  不 push/tag/PR。

## Result

- shared definite-layout vectors 已升级为 `/55`。Rust primary 与 Python independent verifier 先在首个转正 case
  `stack-two-main-fills-mixed-active-min-second-mixed-min-freeze-overflow`、同一 `STACK_MAIN_FILL` occurrence
  `rwocc_0000000000000002` 共同 RED；独立实现后冻结为 252 laid-out + 16 unsupported，共 268/268 cases、
  802 independent checks。vector SHA-256 为
  `d9c5b29a51f27a19f914b21eaad0512a12367161852fb6a4d1b6506880461116`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- `stack_main_fill_allocations` 只在 exactly-two、首轮唯一 mixed active-min、另一 mixed child 首轮 inactive，且
  第一次 freeze 后唯一 offer 严格低于其 min 时提交两项 authored minima；active-first、COLUMN、deferred
  cross-HUG 与 `min == max` 均有 positive regression。第二项 freeze 后仍 inactive 的 replacement negative 继续
  `STACK_MAIN_FILL` fail closed；未引入循环、epsilon、tolerance 或一般 water filling。
- focused Rust 3/3、Python 268/268、workspace fmt/check/clippy `-D warnings`/tests、`py_compile`、JSON
  inventory/SHA/unique 与 `git diff --check` 全绿。

## Evidence

- canonical `render`：`.sdlc/evidence/20260825-131815-render/`（46.932 秒）。
- affected `fast`：`.sdlc/evidence/20260825-131909-fast/`（11.296 秒）。
- sequential `server`：`.sdlc/evidence/20260825-131931-server/`（788.337 秒），App 347 tests、0 failures、
  0 errors、15 skipped。
- Goal `full`：`.sdlc/evidence/20260825-133248-full/`（17/17 steps，1181.533 秒），覆盖 Node 24 Web
  26 files/212 tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft 与 inference browser
  journeys；provider attempts/API Key reads/reservations/cost/真实数据=0。
- 状态回填后的 resolution `fast`：`.sdlc/evidence/20260825-135544-fast/`（3/3 steps，11.633 秒）。
- Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、正式产品 route `CLOSED`、
  native stack `BUILD_NOT_AUTHORIZED`；`/prototype` 不计最终产品交付，未 push/tag/PR。
