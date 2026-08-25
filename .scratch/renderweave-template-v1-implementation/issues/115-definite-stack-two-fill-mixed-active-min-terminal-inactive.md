# 实现 definite Stack 两 FILL mixed active-min 后 mixed inactive 终止子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 10, 19, 23, 25, 26, 33, 38, 67, 68, 69, 72, 78, 112（本切片前置均已 resolved）

## Question

T112 已允许 exactly-two main FILL 中一个合法 mixed child 首轮命中 active min，另一 mixed child 首轮
inactive，且冻结 active min 后唯一 offer 严格低于另一项 min 时执行第二次 min freeze。若该唯一 offer 在
另一项 authored `[min,max]` 闭区间内，如何按 water-filling 的确定性终止路径接受它，同时不开放首轮多个
active bound、three-or-more/four-or-more 一般循环、residual tolerance 或 Renderer Profile registration？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用
   `measure_and_allocate_stack_children` → `stack_main_fill_allocations`；不新增 public API、crate、parser、
   Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好两个 main-axis FILL，weight 均 finite/positive；首轮恰好一个
   active min。active child 携带合法 finite/nonnegative min/max、`min <= max`，share 严格低于 min；另一项也
   携带合法 finite/nonnegative min/max，首轮 share 位于 `[min,max]`。
3. **一次 freeze 后终止**：把 active child 冻结到 min，唯一未冻结项直接取得
   `remaining - activeMin`，不做第二轮 division。只有该 offer 仍位于另一项 `[min,max]`（两端 equality 接受）
   才成功；两项之和保持 exact remaining，既有 occupied/free-space/justify、signed margin 与 gap 继续消费结果。
4. **布局与失败纪律不变**：authored order、cross alignment、每项至多一次 deferred cross-HUG remeasure、
   authored DFS first-error 与全有或全无 output 均不变；失败 occurrence 仍为首个 authored main-FILL child。
5. **TDD 与独立重放**：shared definite-layout vector 升级为 `/56`。先把既有
   `stack-two-main-fills-mixed-active-min-second-mixed-inactive-after-freeze-remains-unsupported` 转为 positive，
   使 Rust primary 与 Python independent verifier 在同一 occurrence 共同 RED；GREEN 后补 active-first、COLUMN、
   deferred cross-HUG 与 equality regression，并保留 exactly-two 首轮多个 active bound 的 negative。
6. **诚实边界**：本票不开放 offer 再命中 bound、active-max 新组合、首轮多个 active、three-or-more/
   four-or-more 一般 water filling、循环/epsilon/tolerance、rows→columns、任意非直角 rotation、Text/
   compositionViewport、native raster、Profile registration/certification、Java/OpenAPI/Web/正式产品 route、
   J1/A3/READY 或外部副作用；`/prototype` 不计最终交付。

## 验证与完成信号

- focused Rust 与 Python independent verifier 先在同一 tracer case/occurrence RED，再由两套独立控制流
  exact-bit GREEN；补齐回归 vectors 后检查 JSON inventory/SHA/unique。
- 局部：Cargo focused/workspace fmt/check/clippy `-D warnings`/tests、Python `py_compile`、`git diff --check`。
- 分级：canonical `render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`；Maven gate
  不并发。
- 最高只报 `automated_verified`；Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  `ABSENT`、正式产品 route `CLOSED`、native stack `BUILD_NOT_AUTHORIZED`；provider/API Key/费用/真实数据=0，
  不 push/tag/PR。

## Resolution evidence

- shared definite-layout vector 已升级为 `/56`：256 laid-out + 16 unsupported，共 272/272 cases、814 checks；
  Rust primary focused suite 3/3 与 Python independent verifier A2 均通过。vector SHA-256 为
  `59930774870ae972d0122d36cec779d64ebb62506a6732ce30fc4f1649c7f751`。
- 已覆盖 inactive offer 的区间内部、两端 equality、active-first、ROW/COLUMN 与 deferred cross-HUG remeasure；
  exactly-two 首轮多个 active bound 继续以 negative fail closed。Rust 与 Python 保持独立控制流。
- canonical `render` `.sdlc/evidence/20260825-172505-render/`、affected `fast`
  `.sdlc/evidence/20260825-172558-fast/`、sequential `server`
  `.sdlc/evidence/20260825-172621-server/` 与 17-step Goal `full`
  `.sdlc/evidence/20260825-174449-full/` 均通过；full 为 17/17 steps、1611.192 秒，Node 24 Web
  217/217、Playwright 23 passed + 1 controlled skip、runtime canary passed。
- 状态与证据回填后的 resolution `fast` `.sdlc/evidence/20260825-181334-fast/` 也以 3/3 steps exit 0。
- server/full 暴露了测试上下文关闭后 `TemplateAssetStaleConsumer` 继续轮询已停止 Testcontainers PostgreSQL
  的生命周期噪声；断言与 gate 仍通过，但该质量缺陷不伪装为已消失，转入下一独立纵切处理。
- Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、native stack
  `BUILD_NOT_AUTHORIZED`、public Rendering API 与正式产品 route `CLOSED`；未推进 J1/A3/READY，provider
  attempts/API Key reads/费用/真实数据=0，未 push/tag/PR，且未把 `/prototype` 当作最终交付。
