# 实现 ABSOLUTE parent offer → columns-first Grid cross HUG 单向传播子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 34, 39, 40, 41, 44, 45, 49, 50, 51, 52, 53, 57, 58（均已 resolved）

## Question

T53 已把 definite ABSOLUTE parent ContentBox 封装为 typed `AbsoluteParentContent`，T57/T58 已把 Grid 冻结为
columns-first 并能在 definite content width 下单向求 rows。如何让 ABSOLUTE `widthMode=FILL,heightMode=HUG_CONTENT`
Grid 消费 parent ContentBox width，同时正确应用自身 x/right inset、positive-zero、min/max、stroke/padding，而不把
offer 扩散到 Grid-in-Grid、rows→columns、一般 constraint engine 或 fixed point？

## Answer（本票冻结的实施决定）

1. **只深化既有 layout deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；不新增 public API、crate、parser、route、Profile、
   daemon success path 或第二套 Grid 算法。
2. **只消费 ABSOLUTE parent 的 definite width offer**：仅当 owning Grid 的 ABSOLUTE placement 为
   `widthMode=FILL` 且当前测量 `heightMode=HUG_CONTENT` 时，从 `AbsoluteParentContent(parentContent.width)` 开始；
   先按 ABSOLUTE `xPt/rightInsetPt` 求 `max(0,parentWidth-x-rightInset)`，再按 min→max clamp 得到最终 Grid outer width。
3. **outer → ContentBox 后严格 columns-first**：按既有 inward stroke/padding floor-zero 把最终 outer width 转为
   content width；先完整求 columns，再让 rows 以 `RowsAfterColumns` 求值。row AUTO 只能沿 T57 读取已完成 column
   cell width；rows 不回写 columns，也不重算 parent、siblings 或 owning Grid width。
4. **允许 ABSOLUTE Frame 链组合，不开放新 writer**：T53 已有的 ABSOLUTE Frame HUG 测量可把同一 parent
   ContentBox offer 传到其 direct ABSOLUTE Grid child，因此该有限链可组合；每层仍只做一次纯测量。Grid cell
   `ResolvedOuter` 只对 direct Frame 开放，Grid-in-Grid owning offer 继续 fail closed。
5. **固定错误顺序与全有或全无**：继续 authored DFS、columns-before-rows 与既有 AUTO constraint 顺序；任一
   unsupported/invariant 返回原稳定 first error，不产生 partial layout。
6. **明确排除**：Grid-in-Grid owning offer、column AUTO 读取 future rows、跨多 AUTO 平均、multiple Stack FILL、
   multiple FRACTION、一般 `UNBOUNDED/AT_MOST/EXACT` constraint engine、双向 HUG、非直角 rotation/tolerance、
   Text/Image/Vector intrinsic、resource fetch/decode、world scene/paint/raster/JPEG、daemon RESULT、Profile/E6、
   formal records、physical Linux/J1/A3/READY 与任何外部副作用均不在本票。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/22`。把既有 ABSOLUTE parent→Grid negative 转为
   positive，并新增 final inset/clamp + Grid ContentBox、nested ABSOLUTE Frame 组合、negative remainder + min clamp
   三个 positive；新增 Grid cell offer 不得扩散到 owning Grid 的 boundary negative。目标 103 laid-out + 15
   unsupported，共 118 cases、353 checks；fixture bytes 不变时保持 fixture `/3`。

## 验证与完成信号

- 局部：shared vectors 先使 Rust primary/Python independent verifier 共同 RED；再分别实现，运行 focused Cargo
  vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/test、`py_compile`、JSON inventory 与
  `git diff --check`。
- 受影响：`render` → 顺序 `fast`/`server` → 完整 `full`；Maven gate 不并发。
- 保证上限：Rust/kernel/gate A1，ABSOLUTE parent→Grid typed offer 的 Rust+Python shared replay A2；不证明一般
  Grid constraint/tolerance、完整 Layout/Renderer、scene/pixel、daemon output、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit；不 push/tag/PR，
  不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `renderweave-renderer-layout` 已在既有 `layout_definite_resource_free` deep-module seam 内深化
  `definite_grid_column_content_offer`：ABSOLUTE `widthMode=FILL,heightMode=HUG_CONTENT` Grid 现在可从 typed
  `AbsoluteParentContent(parentContent.width)` 按 x/right inset、positive-zero 与 min→max clamp 解析 final outer
  width，再扣除自身 inward stroke/padding 得到 definite content width；T53 ABSOLUTE Frame 链可直接组合。
- Grid 仍严格 columns-first：columns 完整求解后，rows 才以 `RowsAfterColumns` 求值；row AUTO 可继续经 T57
  单向读取 final column cell width，但 rows 不回写 columns、outer width、parent 或 siblings。Grid cell
  `ResolvedOuter` writer 仍只对 direct Frame 开放，Grid-in-Grid owning offer 继续 fail closed。Python independent
  verifier 以独立 source-specific 分支复演相同有限顺序。
- shared definite-layout contract 已升级到 `/22`：103 laid-out + 15 unsupported，共 118/118、353 checks；四个
  positive vectors 在 Rust/Python 先共同以 `CHILD_ROTATION` RED，完成后 exact-bit GREEN；Grid cell offer 不得
  扩散到 owning Grid 的 boundary negative 继续 fail closed。vector SHA-256 为
  `badb4f416c1d8720237e643d68d37b8c8e4df1e96c3d2b05fbfedeb6eb47e348`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1 gate 已通过：`render` `.sdlc/evidence/20260822-224329-render/`、affected `fast`
  `.sdlc/evidence/20260822-224357-fast/`、顺序 `server` `.sdlc/evidence/20260822-224415-server/` 与 Goal
  `full` `.sdlc/evidence/20260822-230253-full/`。full metadata 为 `result=passed`，17 个 step 均 exit 0；其中
  Node 24 Web 26 files/212 tests、runtime canary、23 passed + 1 skipped Playwright E2E、Draft/Inference browser
  journeys 均通过，R0/R1/P0 independent replay 的 provider attempts 均为 0；resolution 后 fast
  `.sdlc/evidence/20260822-233225-fast/` 也通过。
- Grid-in-Grid owning offer、column-from-row feedback、跨多个 AUTO 的平均 deficit、multiple FILL/FRACTION、
  一般 constraint/tolerance、非直角 rotation、resource fetch/decode、world scene/raster、daemon output 与 Profile
  仍未实现；API Key reads/reservations/cost、真实数据与付费调用均为 0，未推进 A3/J1/READY，未 push/tag/PR。
