# 实现 nested Stack resolved opposite offer → columns-first Grid terminal 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 34, 38, 39, 40, 41, 43, 44, 45, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63（均已 resolved）

## Question

T59/T60 已能让 `widthMode=FILL,heightMode=HUG_CONTENT` Grid 消费 typed `ResolvedOuter` width 并严格
columns→rows，T63 已能把 resolved physical-axis offer 沿 nested Stack 结构递归；但 `measure_stack_child` 的
resolved-opposite consumer 仍排除 Grid。如何把两条既有 seam 有限组合到 direct STACK Grid terminal，同时不
开放 rows→columns feedback、一般 constraint engine、cycle/fixed point 或 tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 layout deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；不新增 public API、crate、parser、route、Profile、
   daemon success path 或第二套 Grid/Stack 算法。
2. **只开放 columns-first 有意义的 Grid terminal**：仅当 direct STACK child role=Grid、owning Stack
   direction=COLUMN、child `widthMode=FILL,heightMode=HUG_CONTENT`，且 `StackMeasurementSpace.width` 已
   definite 时，才按 signed horizontal margins、positive-zero 与 min→max 得到 Grid final outer width，并产生
   typed `ResolvedOuter`。Frame|Stack 既有行为不变。
3. **严格复用 Grid columns→rows consumer**：Grid 先扣自身 inward stroke/padding 得到 ContentBox width，完整解
   columns，再以 `RowsAfterColumns` 解 rows；row AUTO 可继续消费已完成 column cell width。rows 不回写 Grid/
   ancestor columns、outer width、Stack allocation、siblings、gaps 或 tracks。
4. **不做对称 rows→columns 扩展**：ROW Stack 下 `widthMode=HUG_CONTENT,heightMode=FILL` Grid 不以 resolved
   height 反求 width；Grid width HUG 仍只走 independent columns。任何需要 future rows 才能决定 columns 的形态
   继续按原 authored DFS first error fail closed。
5. **递归只沿 authored tree 下降**：T63 的 nested Stack 链可在末端组合本票 Grid，每层只消费已解析 offer、
   Grid 只执行一次 columns→rows；不做 convergence、fixed point、tolerance、双向 HUG 或 parent remeasure。
6. **固定能力边界**：multiple Stack main FILL、跨多个 AUTO 的平均 deficit、multiple FRACTION、一般
   `UNBOUNDED/AT_MOST/EXACT` constraint engine、非直角 rotation/tolerance、Text/Image/Vector intrinsic、
   resource fetch/decode/cache、world scene/raster/JPEG、daemon RESULT/Profile 与 E6 均不在本票。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/27`。把 T63 的 Grid-terminal boundary negative
   转为 positive，新增 margins/ContentBox/clamp positive，并新增 resolved-height 不得驱动 rows→columns 的
   boundary negative。目标 119 laid-out + 15 unsupported，共 134 cases、401 checks；fixture bytes 不变时保持
   fixture `/3`。
8. **诚实能力边界**：本票只证明 resource-free nested Stack resolved width offer 到 columns-first Grid terminal
   的有限组合；不证明一般 Layout/Renderer、scene/pixel、daemon output、Profile/E6、formal records、物理
   Linux/J1/A3/READY。

## 验证与完成信号

- 局部：shared vectors 先使 Rust primary/Python independent verifier 在同一首个新增 positive 共同 RED；再分别
  实现，运行 focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/test、
  `py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → 顺序 `fast`/`server` → 完整 `full`；Maven gate 不并发。
- 保证上限：Rust/kernel/gate A1，nested Stack→columns-first Grid shared replay A2；不证明 rows→columns、一般
  constraint/tolerance、完整 Renderer、scene/pixel、daemon output、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit；不 push/tag/PR，
  不运行 provider，不读取 API Key，不发送真实数据。

## Resolution evidence

- TDD：shared `/27` 的首个 Grid-terminal positive 先让 Rust primary 与 Python independent verifier 共同在
  `CHILD_ROTATION rwocc_0000000000000006` RED（Rust exit 101、Python exit 1）；实现后 focused Rust 3/3、
  Python 134/134 cases、401 checks 全绿。既有 Grid-terminal boundary 已转为 positive，并补齐
  margins/ContentBox/clamp positive；resolved-height rows→columns 仍以同一 first-error fail closed。
- 实施：`measure_stack_child` 仅把 COLUMN branch resolved-opposite consumer 的 closed role predicate 从
  `Frame | Stack` 扩为 `Frame | Stack | Grid`；ROW branch 不变。Python verifier 以独立控制流做同一非对称
  扩展；Grid 严格复用既有 ContentBox 与 columns→rows kernel，public API、admission/preflight、authored DFS 与
  arrange 的全有或全无合同均未改变。
- identity：119 laid-out + 15 unsupported，shared vector/verifier identity
  `renderweave-definite-layout-vectors/27` / `renderweave-definite-layout-python-independent/27`；vector SHA-256
  `59af024709bddec8eefb5cae666b6a2fddeb39589ff908f357a27d8ad8e41dc5`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- 局部验证：workspace `cargo fmt`、clippy `-D warnings`、全部 Cargo tests、Python `py_compile`、JSON inventory
  与 `git diff --check` 全绿。
- 分级 gate：`render` `.sdlc/evidence/20260823-044653-render/`、affected `fast`
  `.sdlc/evidence/20260823-035754-fast/`、顺序 `server` `.sdlc/evidence/20260823-035812-server/` 与 Goal `full`
  `.sdlc/evidence/20260823-044748-full/` 均通过。full 17 steps 均 exit 0，Node v24.12.0 Web 26 files/212 tests、
  runtime canary、23 passed + 1 controlled skip Playwright E2E、prototype/Draft/Inference browser journeys 与最终
  inference replay E2E 1/1 均通过；resolution 后 fast `.sdlc/evidence/20260823-051519-fast/` 的 3 steps 也均 exit 0。
- 边界：R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0；Profile `NOT_REGISTERED`、
  certification `NOT_CERTIFIED`、Raster `ABSENT`、daemon `UNWIRED`。rows→columns、general constraint/tolerance、
  resource/scene/pixel 与完整 Renderer 仍未实现；最高仅 `automated_verified`，未推进 A3/J1/READY，未
  push/tag/PR。
