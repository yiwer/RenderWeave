# 实现 definite Stack inactive-bound 多 FILL 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67（均已 resolved）

## Question

Ticket 10 §3/§7 已冻结 multiple Stack main-FILL 的 weighted iterative min/max water filling，但微小负残差仍
必须按尚未给出数值的 Layout Profile tolerance 判定。T67 已完成 owning-axis 无 bound 的一次 proportional
allocation。如何继续支持 authored min/max 存在、但第一轮 weighted share 已经满足全部 bound 的严格退化路径，
同时不实现 bound freeze/redistribution、不选择 epsilon，也不掩盖真正命中 bound 的 remaining boundary？

## Answer（本票冻结的实施决定）

1. **只深化既有 Stack allocation deep module**：继续使用 `measure_and_allocate_stack_children` 与 T67 的同一
   proportional allocation seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：同一 definite Stack 可有多个 main-axis FILL，且 FILL placement 可携带 owning-axis
   `min*Pt`/`max*Pt`；只有按 T67 固定顺序算出的每个 final share 都满足 `share >= min` 且 `share <= max` 时
   才进入本票支持路径。bound equality 是满足，不触发 freeze。
3. **active bound 继续失败封闭**：任一 share 严格小于 min 或严格大于 max 时，仍在第一个 authored FILL
   occurrence 返回 closed internal `STACK_MAIN_FILL`。不 clamp、不冻结、不重分配、不读取后续轮次，也不把
   singleton clamp 推广成 multiple-FILL water filling。
4. **数值与错误顺序不变**：remaining、weight sum、前 `n-1` weighted share、last remainder、finite/nonnegative
   guard 与 `-0` 归零完全复用 T67；全部 share 与 bound 先验证并 staged，之后才按 authored order 逐个执行一次
   deferred cross-HUG remeasure。支持路径内 deeper child/resource/rotation 错误继续按 authored DFS 暴露。
5. **occupied/justify/arrange 不变**：所有 accepted share 加回 occupied；inactive min/max 不改变任何 exact box bits、
   cursor、margin、gap、justify 或 cross alignment。singleton FILL 的既有 clamp 行为不变。
6. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/31`。把现有 inactive-min negative 转为 positive，
   新增 inactive-max 与 exact-equality positives，并新增 active-min/active-max negatives；目标 134 laid-out + 12
   unsupported、146 cases、440 checks，fixture `/3` bytes 不变。
7. **固定能力边界**：任何真正命中 bound 的 freeze/redistribution、多轮 water filling、Profile residual tolerance/
   public numeric error、HUG main-axis FILL cycle、rows→columns、任意非直角 rotation、Text/Image resource、
   compositionViewport、resource fetch/decode、scene/raster/JPEG、daemon RESULT/Profile 与 E6 均不在本票。
8. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/31` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  case 共同 RED；再分别实现冻结控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- TDD：shared `/31` 先把既有 inactive-min negative 转为 positive，并加入 inactive-max、exact-equality
  positives 与 active-min/active-max negatives；Rust primary 与 Python independent verifier 在同一首个转正 case
  共同 RED，分别实现冻结控制流后达到 134 laid-out + 12 unsupported、146/146 cases、440 checks exact-bit GREEN。
- 实施：`measure_and_allocate_stack_children` 先按 T67 stable weight sum 与 authored-order last remainder staged
  全部 shares，再验证每项 owning-axis optional min/max；equality 接受。只有全部 bounds 均 inactive 才逐项执行
  一次 deferred cross-HUG remeasure；任一 active bound 仍在首个 authored FILL occurrence 返回
  `STACK_MAIN_FILL`，不 clamp/freeze/redistribute，也不读取 residual tolerance。
- identity：shared vector/verifier identity 为 `renderweave-definite-layout-vectors/31` /
  `renderweave-definite-layout-python-independent/31`；vector SHA-256
  `959ead298d556d2bf0d4a9e713ef37199e8c56a22bd650a1be183fa0b35e4b8f`；layout-preflight fixture `/3`
  SHA-256 保持 `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- 局部验证：focused Rust vector tests 3/3、Python independent 146/146 cases/440 checks、workspace `cargo fmt`、
  clippy `-D warnings`、全部 Cargo tests、Python `py_compile`、JSON inventory/hash/unique 与 `git diff --check`
  全绿。
- 分级 gate：`render` `.sdlc/evidence/20260823-090018-render/`、affected `fast`
  `.sdlc/evidence/20260823-090045-fast/`、顺序 `server` `.sdlc/evidence/20260823-090110-server/` 与 Goal `full`
  `.sdlc/evidence/20260823-092021-full/` 均通过。full 17 steps 均 exit 0、总耗时 1725.698 秒；Maven App
  344 tests/0 failures/0 errors/15 skipped，Node 24 Web 26 files/212 tests，runtime canary、23 passed +
  1 controlled skip Playwright、prototype/Draft/Inference browser journeys 与最终 inference replay E2E 1/1
  均通过；resolution 后 fast `.sdlc/evidence/20260823-095202-fast/` 的 3 steps 也均 exit 0。
- 边界：R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0；Profile `NOT_REGISTERED`、
  certification `NOT_CERTIFIED`、Raster `ABSENT`、daemon `UNWIRED`。active-bound water filling、rows→columns、
  general constraint/tolerance、resource/scene/pixel 与完整 Renderer 保持 fail closed；最高仅
  `automated_verified`，未推进 A3/J1/READY，未 push/tag/PR。
