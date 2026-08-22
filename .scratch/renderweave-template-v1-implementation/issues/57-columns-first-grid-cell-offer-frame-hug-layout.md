# 实现 columns-first Grid cell outer offer → Frame HUG 单向传播子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 34, 39, 40, 41, 44, 45, 49, 50, 51, 52, 53, 56（均已 resolved）

## Question

T52/T53 已让 HUG Frame 消费 typed opposite-axis definite offer，T34/T39–T45 已完成当前无 tolerance 的
Grid track/cell 子闭包。如何按 `renderweave-layout/1.0` 已冻结的 columns-first 顺序，让 direct GRID Frame
消费已解析 cell outer offer，并让 row AUTO contribution 只从已经完成的 columns 单向读取宽度，同时不从
rows 反推 columns、不引入通用 constraint engine、cycle、fixed point 或 residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 layout deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；不新增 public API、crate、parser、route、Profile、
   daemon success path 或第二套 Grid 算法。
2. **最终 cell arrange 的 typed offer**：direct GRID child 仅为 Frame，且恰有一轴 `HUG_CONTENT`、opposite
   axis 为 `FILL` 时，先以该 opposite cell span、signed margins、positive-zero、min 后 max 求最终 outer
   size，再以 T53 `ResolvedOuter` 交给 HUG 轴。Frame 继续扣 inward stroke/padding并复用 T52/T51 的
   exact-quarter-turn FILL/AABB；结果不改变 track size、sibling 或另一轴。
3. **严格 columns-first 的 row contribution**：columns 完成后，row AUTO 对 direct Frame 的 height HUG
   contribution 可读取该 child 已解析的 column cell width outer offer；只允许 width FILL → height HUG 的
   单向路径。column AUTO 的 width HUG 不读取尚未求出的 rows，rows 也绝不反推 columns。
4. **不扩大 cycle/tolerance 边界**：跨任一 AUTO track 的该轴 FILL 继续由既有 preflight hard reject；跨多个
   AUTO 的平均 deficit、multiple FRACTION、multiple Stack FILL、nested Stack→Grid main offer、双向 HUG、
   一般 `UNBOUNDED/AT_MOST/EXACT` constraint engine 与非直角 rotation 均不在本票。
5. **固定求值与错误顺序**：columns 仍完整求解后才进入 rows；row constraints 仍按
   `(spanLength,startIndex,materializedOrder)` 稳定排序并只处理一次。offer 只经 closed typed value 传递；
   任一 measurement 错误保留 authored DFS first error，输出仍全有或全无。
6. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/20`。新增四个 positive cases，覆盖两轴对称
   fixed-cell arrange、columns-first row AUTO contribution、signed margins/stroke/padding/min clamp 与后续 row
   origin；新增 column AUTO 不得读取 future row offer 的 boundary negative。目标为 95 laid-out + 15 unsupported，
   共 110 cases、329 checks；fixture bytes 不变时保持 fixture `/3`。
7. **诚实能力边界**：nested Stack→Grid offer、column-from-row feedback、跨多 AUTO 平均、multiple
   FILL/FRACTION、一般 constraint/tolerance、Text/Image/Vector intrinsic、resource fetch/decode、world scene、
   paint/raster/JPEG、daemon RESULT、Profile/E6、formal records 与物理 Linux/J1/A3 均不在本票。

## 验证与完成信号

- 局部：shared vectors 先使 Rust primary/Python independent verifier 共同 RED；再分别实现，运行 focused Cargo
  vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/test、`py_compile`、JSON inventory 与
  `git diff --check`。
- 受影响：`render` → 顺序 `fast`/`server` → 完整 `full`；Maven gate 不并发。
- 保证上限：Rust/kernel/gate A1，Grid typed offer 的 Rust+Python shared replay A2；不证明 nested Grid、通用
  constraint/tolerance、完整 Layout/Renderer、scene/pixel、daemon output、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit；不 push/tag/PR，
  不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `renderweave-renderer-layout` 已在既有 `layout_definite_resource_free` deep-module seam 内加入 typed
  `GridAxisMeasurementSpace`：columns 先在 independent space 完整求解，rows 才能读取已完成的 column axis；
  resource-free owning Grid HUG 仍使用 independent space，因此没有把 offer 隐式扩散到 nested Stack→Grid。
- direct GRID Frame 的最终 arrange 现在会按 opposite cell span、signed margins、positive-zero、min 后 max 解析
  FILL outer size，并只把该 closed `ResolvedOuter` 交给 HUG 轴。row AUTO 对 width-FILL/height-HUG Frame 复用同一
  column outer offer；column AUTO 不读取 future row height，rows 不反推 columns，track/sibling 也不因 cross
  result 重算。Python independent verifier 以独立控制流镜像相同冻结语义。
- shared definite-layout contract 已升级到 `/20`：95 laid-out + 15 unsupported，共 110/110、329 checks；四个
  positive vectors 在 Rust/Python 先共同以 `CHILD_ROTATION` RED，完成后 exact-bit GREEN，column-from-row boundary
  negative 继续 fail closed。vector SHA-256 为
  `1ff3bc0fba97641322fdbdd04fbed53c332482787b4a07c749124187cdebcfa1`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1 gate 已通过：`render` `.sdlc/evidence/20260822-203630-render/`、affected `fast`
  `.sdlc/evidence/20260822-203701-fast/`、顺序 `server` `.sdlc/evidence/20260822-203717-server/` 与 Goal
  `full` `.sdlc/evidence/20260822-205619-full/`。full metadata 为 `result=passed`，17 个 step 均 exit 0；其中
  Node 24 Web 26 files/212 tests、runtime canary、23 passed + 1 skipped Playwright E2E、Draft/Inference browser
  journeys 均通过，R0/R1/P0 independent replay 的 provider attempts 均为 0；resolution 后 fast
  `.sdlc/evidence/20260822-212626-fast/` 也通过。
- nested Stack→Grid main offer、column-from-row feedback、跨多个 AUTO 的平均 deficit、multiple FILL/FRACTION、
  一般 constraint/tolerance、非直角 rotation、resource fetch/decode、world scene/raster、daemon output 与 Profile
  仍未实现；API Key reads/reservations/cost、真实数据与付费调用均为 0，未推进 A3/J1/READY，未 push/tag/PR。
