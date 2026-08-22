# 实现 nested Stack resolved opposite offer 结构递归子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 61, 62（均已 resolved）

## Question

T55/T56 已能沿 owning Stack main-FILL 链做一次 cross-HUG 重测，T62 已能把首个方向切换 Stack 的 definite
cross outer 用于 main HUG；但 `measure_stack_child` 仍只把当前 measurement space 的 resolved opposite-axis FILL
outer 交给 Frame。如何让 direct nested Stack 也消费该 offer，并按自己的 direction 继续 main-HUG 或 cross-HUG，
同时保持结构终止、first-error 与零反向回写？

## Answer（本票冻结的实施决定）

1. **只深化既有 layout deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；不新增 public API、crate、parser、route、Profile、
   daemon success path 或第二套 Stack 算法。
2. **只传播 already-resolved physical-axis offer**：当 direct STACK child 的一个物理轴为 FILL、另一个物理轴为
   HUG，且 owning `StackMeasurementSpace` 已给出该 FILL 轴 definite available interval 时，按 signed margins、
   positive-zero 与 min→max 得到 child final outer；不从 HUG result、sibling、gap 或祖先反推 offer。
3. **Frame seam 扩为 Frame|Stack，不扩 Grid**：existing Frame opposite-offer path 只把 role predicate扩到 Stack；
   child Stack 收到 typed `ResolvedOuter` 后由自己的 direction 选择 T62 cross-definite→main-HUG 或 T55/T56
   main-definite→cross-HUG helper。Frame 行为不变，Grid 继续 fail closed。
4. **递归按 authored tree 严格下降**：每次递归都进入一个 direct child，树由 admission 保证有限且无 identity
   cycle；每层只测一次相应 HUG，并在 arrange 时复用相同 measurement seam。不做 convergence、fixed point、
   tolerance、双向 HUG 或 parent allocation 重算。
5. **ContentBox 与错误顺序不变**：每层 final outer 先扣 inward stroke/padding 并逐步 floor-to-positive-zero；
   exact-quarter-turn Frame terminal 继续复用 T51–T55。authored DFS、first unsupported occurrence 与全有或全无
   output 保持不变；任一 unresolved FILL 或 main-HUG/main-FILL cycle 直接 fail closed。
6. **边界固定**：direct Grid resolved-opposite offer、Grid-in-Stack 新组合、multiple Stack main FILL、跨多个 AUTO
   的平均 deficit、multiple FRACTION、一般 `UNBOUNDED/AT_MOST/EXACT`、非直角 rotation/tolerance 与 resource/
   scene/raster/RESULT/Profile 不在本票。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/26`。把 T62 保留的 second Stack link boundary
   negative 转为 positive；新增 COLUMN-root 对称 recursive positive、nested Stack margins/ContentBox/clamp positive，
   并新增 Grid terminal boundary negative。目标 117 laid-out + 15 unsupported，共 132 cases、395 checks；fixture
   bytes 不变时保持 fixture `/3`。
8. **诚实能力边界**：本票只证明 resource-free nested Stack resolved opposite offer 的结构递归；不证明一般
   constraint engine、任意 Layout/Renderer、world scene/pixel、daemon output、Profile/E6、formal records、物理
   Linux/J1/A3/READY。

## 验证与完成信号

- 局部：shared vectors 先使 Rust primary/Python independent verifier 在同一首个新增 positive 共同 RED；再分别
  实现，运行 focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/test、
  `py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → 顺序 `fast`/`server` → 完整 `full`；Maven gate 不并发。
- 保证上限：Rust/kernel/gate A1，nested Stack resolved opposite offer shared replay A2；不证明 Grid consumer、
  unresolved/cyclic FILL、一般 constraint/tolerance、完整 Renderer、scene/pixel、daemon output、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit；不 push/tag/PR，
  不运行 provider，不读取 API Key，不发送真实数据。

## Resolution evidence

- TDD：shared `/26` 的首个 recursive positive 先让 Rust primary 与 Python independent verifier 共同在
  `CHILD_ROTATION rwocc_0000000000000006` RED（Rust exit 101、Python exit 1）；实现后 focused Rust 3/3、
  Python 132/132 cases、395 checks 全绿。既有 second-Stack boundary 已转为 positive，并补齐 COLUMN 对称、
  margins/ContentBox/clamp positives；Grid terminal 仍以同一 first-error fail closed。
- 实施：`measure_stack_child` 仅把 resolved opposite-axis offer 的 closed role predicate 从 `Frame` 扩为
  `Frame | Stack`；Python verifier 以独立控制流对称复演。child Stack 继续按自身 direction 进入既有
  main-HUG/cross-HUG seam，递归严格沿 authored tree 下降；Frame 行为、public API、admission/preflight 与
  arrange 的全有或全无合同均未改变。
- identity：117 laid-out + 15 unsupported，shared vector/verifier identity `renderweave-definite-layout-vectors/26` /
  `renderweave-definite-layout-python-independent/26`；vector SHA-256
  `063f8d08e0411fce2ff82dd1177e436cda183937cc05845d52a7a56e6e505fcb`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- 局部验证：workspace `cargo fmt`、clippy `-D warnings`、全部 Cargo tests、Python `py_compile`、JSON inventory
  与 `git diff --check` 全绿。
- 分级 gate：`render` `.sdlc/evidence/20260823-025201-render/`、affected `fast`
  `.sdlc/evidence/20260823-025246-fast/`、顺序 `server` `.sdlc/evidence/20260823-025303-server/` 与 Goal `full`
  `.sdlc/evidence/20260823-031212-full/` 均通过。full 17 steps 均 exit 0，Node v24.12.0 Web 26 files/212 tests、
  runtime canary、23 passed + 1 controlled skip Playwright E2E、prototype/Draft/Inference browser journeys 与最终
  inference replay E2E 1/1 均通过；resolution 后 fast `.sdlc/evidence/20260823-034326-fast/` 的 3 steps 也均 exit 0。
- 边界：R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0；Profile `NOT_REGISTERED`、
  certification `NOT_CERTIFIED`、Raster `ABSENT`、daemon `UNWIRED`。Grid consumer、unresolved/cyclic FILL、
  general constraint/tolerance、scene/pixel 与完整 Renderer 仍未实现；最高仅 `automated_verified`，未推进
  A3/J1/READY，未 push/tag/PR。
