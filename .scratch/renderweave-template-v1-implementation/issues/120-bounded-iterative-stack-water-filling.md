# 实现 bounded iterative Stack min/max water-filling 纵切

Type: task
Status: resolved
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 67–91, 112, 115, 117, 118（本切片前置均已 resolved）

## Question

T119 已接通首个真实 compositionViewport，但 shared Layout corpus 仍有 7 个 `STACK_MAIN_FILL` unsupported；
`stack_main_fill_allocations` 也已被逐票特例分支撑大。如何按 Ticket 10 §3/§7 的 authored-order、weighted
min/max water-filling 和 `fillChildCount + 1` 上限，一次收敛这些剩余 definite Stack 缺口，同时不猜测尚未
物化数值的 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **深化唯一既有 seam**：public `layout_definite_resource_free`、prepared-resource entry point、Engine 与
   daemon Interface 均不变；只把 private `stack_main_fill_allocations` 的逐形状分支收敛为一个 bounded state
   machine。Rust primary 与 Python independent verifier保留独立控制流，共享冻结 input/literal output vectors。
2. **固定 authored-order water filling**：先计算 `remaining=max(0,available-usedWithoutFill)`；每轮对全部未冻结
   child 按正 `fillWeight` 求稳定 weight sum，前 `n-1` 项按 `remainingForRound*weight/weightSum`，最后一项接收
   exact remainder。严格低于 min 或高于 max 的项按 authored order 同轮冻结到对应 bound，剩余空间进入下一轮；
   equality 不冻结。
3. **min overflow 与 max remainder**：同轮/后续 min freeze 使 frozen minima 超过 remaining 时，不缩小任何 min；
   其余未冻结 child 取自身合法 min（无 min 则 0）并终止，overflow 由既有 occupied/free-space START fallback
   消费。全部 max 截断后的正 remainder 不分给已冻结项，继续由既有 `justifyContent` 消费。
4. **预算与原子性**：每轮至少冻结一项，最多 `fillChildCount + 1` 轮；任一 weight/bound/share/sum 非 finite、
   非法 bound、无进展或超轮数都在提交 Layout 前返回首个 authored FILL occurrence 的 `STACK_MAIN_FILL`。
   全部 allocation 成功后才按 authored order执行现有一次 deferred cross-HUG remeasure。
5. **不猜 tolerance**：每轮 last remainder 若为负，或 `remaining-frozenSum` 为负但尚未进入确定的 min-overflow
   终点，继续 fail closed；不引入 epsilon、clamp-to-zero 或新的 public numeric-error语义。完整 residual tolerance
   仍需未来 exact Layout Profile 数值票。
6. **TDD 范围**：把现有 7 个 Stack unsupported 转为 literal expected LayoutBox，并补 ROW/COLUMN、四项、
   simultaneous min/max、multi-round、min-overflow、all-max remainder、deferred cross-HUG 与轮数边界回归；既有
   rotation/resource/error-order negatives 保持不变。
7. **诚实边界**：本票不实现 arbitrary rotation、rows→columns、Text shaping、world scene、subpixel/AA、IMAGE
   resampling、JPEG/LayoutTrace、daemon RequestRegistry success、Profile registration/certification、native build、
   public Rendering API、E6、正式产品 route、physical J1/A3/READY 或外部副作用；`/prototype` 不计交付。

## 验证与完成信号

- RED→GREEN：先让 Rust primary 与 Python independent verifier 在首个转正 vector 共同失败，再分别实现；expected
  boxes 均为 frozen literal，不从被测 helper生成。
- 局部：focused Rust/Python、Cargo fmt/check/clippy `-D warnings`/workspace tests、`py_compile`、JSON inventory/
  SHA/unique 与 `git diff --check`。
- 分级：canonical `render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`；Maven不并发。
- 最高只报 `automated_verified`；Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  `ABSENT`、native stack `BUILD_NOT_AUTHORIZED`、public Rendering API/E6/正式产品 route `CLOSED`；provider/API Key/
  费用/真实数据=0，不 push/tag/PR。

## Results

- Rust primary 与 Python independent verifier 已分别把逐形状分支收敛为同一冻结语义、不同控制流的 bounded
  authored-order weighted min/max state machine：每轮 stable share/last remainder、同轮 strict bound freeze、
  `fillChildCount + 1` 上限、min-overflow terminal 与 all-max remainder→justify 均已实现；非法/非 finite 输入、
  无进展和未解释负 residual 继续在首个 authored FILL occurrence fail closed，未引入 epsilon。
- 7 个既有 `STACK_MAIN_FILL` negatives 已全部转为 frozen literal positives，覆盖 ROW/COLUMN、simultaneous mixed
  min/max、multi-round、multiple-min overflow、four-FILL、all-max remainder 与 deferred cross-HUG remeasure。shared
  `/60` 为 278 laid-out + 10 unsupported、288/288 cases、868 checks，vector SHA-256
  `21267a5713ddcc68aa49d6c479e11c8776d54e14c08de74e2bb80e64ecaf3633`；fixture SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- focused/local Rust 与 Python、Cargo fmt/check/clippy `-D warnings`/workspace tests、`py_compile`、JSON inventory/
  unique/SHA 与 `git diff --check` 均通过。canonical `render`
  `.sdlc/evidence/20260825-223119-render/`（67.506 秒）、affected `fast`
  `.sdlc/evidence/20260825-223241-fast/`（13.578 秒）、sequential `server`
  `.sdlc/evidence/20260825-223302-server/`（729.121 秒）与 17-step Goal `full`
  `.sdlc/evidence/20260825-224520-full/`（1189.599 秒）均 passed/A1；full 覆盖 Windows/Linux Renderer、8 个
  Maven modules、Node 24 Web 28 files/217 tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft 与
  inference browser journeys，provider attempts/API Key reads/reservations/cost/open authorization=0。状态回填后的
  resolution `fast` `.sdlc/evidence/20260825-230732-fast/` 也以 3/3 steps、passed/A1、12.724 秒通过。
- 最终状态仅为 `resolved / automated_verified`。Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process
  raster `ABSENT`、native stack `BUILD_NOT_AUTHORIZED`、daemon success/public Rendering API/E6/正式产品 route
  `CLOSED`；Text shaping、world scene、JPEG 与最终产品接线继续后续 DAG，`/prototype` 不计交付，未推进
  J1/A3/READY，未 push/tag/PR。
