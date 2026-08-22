# 实现 Grid cell offer → columns-first nested Grid cross HUG 单向传播子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 34, 39, 40, 41, 44, 45, 49, 50, 51, 52, 53, 57, 59（均已 resolved）

## Question

T57 已让 columns-first Grid row AUTO 从已完成 columns 产生 typed cell `ResolvedOuter` offer，T59 已让 Grid 在
definite outer width 下扣除自身 ContentBox 并严格 columns→rows。如何让 direct GRID
`widthMode=FILL,heightMode=HUG_CONTENT` nested Grid 消费该 cell offer 并递归组合，同时不把 offer 扩散到
Grid→Stack、column-from-row feedback、一般 constraint engine、cycle、fixed point 或 residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 layout deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；不新增 public API、crate、parser、route、Profile、
   daemon success path 或第二套 Grid 算法。
2. **只开放 row-after-columns 的 direct Grid role**：仅当 owning Grid 已完成 columns、当前求 row AUTO
   contribution、direct GRID child role=Grid、`widthMode=FILL,heightMode=HUG_CONTENT` 时，才按 cell span、signed
   margins、positive-zero 与 min→max 得到 child final outer width，并以 typed `ResolvedOuter` 交给其 HUG height。
3. **nested Grid 仍严格 columns-first**：child Grid 先扣自身 inward stroke/padding 得到 content width，再完整解
   columns，最后以 `RowsAfterColumns` 解 rows；rows 不回写 child/parent columns、outer width、siblings 或 tracks。
4. **允许有限递归组合，不新增 writer**：相同形态的 Grid-in-Grid 链可沿有限 authored tree 逐层消费已解析
   `ResolvedOuter`；每层只执行一次 columns→rows。final cell arrange 继续复用同一 typed offer，不作 fixed point。
5. **固定错误顺序与边界**：column AUTO 不读取 future rows；Grid cell offer 不扩散到 owning Stack；跨多个 AUTO
   的平均 deficit、multiple FRACTION/Stack FILL、双向 HUG、一般 `UNBOUNDED/AT_MOST/EXACT` constraint engine、
   非直角 rotation/tolerance、resource/scene/raster/RESULT/Profile 继续 fail closed。任一错误仍按 authored DFS
   first error 返回，全有或全无输出不变。
6. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/23`。把既有 Grid-in-Grid boundary negative 转为
   positive，并新增 margins/clamp/ContentBox、recursive Grid chain、positive-zero→min width 三个 positive；新增
   Grid cell offer 不得扩散到 owning Stack 的 boundary negative。目标 107 laid-out + 15 unsupported，共
   122 cases、365 checks；fixture bytes 不变时保持 fixture `/3`。

## 验证与完成信号

- 局部：shared vectors 先使 Rust primary/Python independent verifier 共同 RED；再分别实现，运行 focused Cargo
  vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/test、`py_compile`、JSON inventory 与
  `git diff --check`。
- 受影响：`render` → 顺序 `fast`/`server` → 完整 `full`；Maven gate 不并发。
- 保证上限：Rust/kernel/gate A1，Grid cell→nested Grid typed offer 的 Rust+Python shared replay A2；不证明
  Grid→Stack、一般 constraint/tolerance、完整 Layout/Renderer、scene/pixel、daemon output、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit；不 push/tag/PR，
  不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `renderweave-renderer-layout` 已在既有 `layout_definite_resource_free` deep-module seam 内把
  `grid_auto_hug_opposite_axis_offer` 的 row-after-columns consumer 从 direct Frame 精确扩到 direct
  Frame|Grid；final GRID cell arrange 的 y-axis HUG opposite offer 也使用相同 closed role predicate。x-axis/
  column 路径仍只允许 Frame，因此 nested Grid 只能从已完成 columns 单向读取 final cell outer width，不能形成
  column-from-row feedback。
- nested Grid 继续复用唯一 columns-first 算法：cell span、signed margins、positive-zero 与 min→max 先解析
  final `ResolvedOuter`，child Grid 再扣 inward stroke/padding 得到 ContentBox，完整解 columns 后才解 rows；相同
  形态可沿有限 authored tree 递归组合。Python independent verifier 以独立实现同步收紧到同一 Frame|Grid
  y-axis seam，public API、admission/preflight、authored DFS 与全有或全无输出均未改变。
- shared `/23` 先让 Rust/Python 在首个新 positive 的 `CHILD_ROTATION rwocc_...0004` 共同 RED。第一次只扩
  row AUTO writer 后仍在同一点 RED，定位到 final cell arrange 的第二个 Frame-only guard；只对 y-axis 对称
  修正后双实现 GREEN。四个 positive 覆盖 owning Grid、margins/clamp/ContentBox、recursive Grid chain 与
  positive-zero→min width；`grid-cell-offer-does-not-propagate-into-owning-stack-cross-hug` 继续 fail closed。
- shared definite-layout contract 现为 107 laid-out + 15 unsupported，共 122/122、365 checks；focused Rust
  3/3、Python 122/122、365 checks、workspace fmt/clippy `-D warnings`/test、`py_compile`、JSON inventory 与
  `git diff --check` 均通过。vector SHA-256 为
  `3ad9f32967855079692c890a279c0570673be3a829e92fd189be5809e236d013`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1 gate 已通过：`render` `.sdlc/evidence/20260822-234434-render/`、affected `fast`
  `.sdlc/evidence/20260822-234500-fast/`、顺序 `server` `.sdlc/evidence/20260822-234518-server/` 与 Goal
  `full` `.sdlc/evidence/20260823-000315-full/`。full metadata 为 `result=passed`，17 个 step 均 exit 0；Node
  v24.12.0 Web 26 files/212 tests、runtime canary、23 passed + 1 skipped Playwright E2E、Draft/Inference
  browser journeys 均通过，R0/R1/P0 independent replay 的 provider attempts 均为 0，P0 `apiKeyReads=0`；
  resolution 后 fast `.sdlc/evidence/20260823-003224-fast/` 也通过。
- Grid cell→Stack、column AUTO 读取 future rows、跨多个 AUTO 的平均 deficit、multiple FILL/FRACTION、一般
  constraint/tolerance、非直角 rotation、resource fetch/decode、world scene/raster、daemon output 与 Profile
  仍未实现；reservations/cost、真实数据与付费调用均为 0，未推进 A3/J1/READY，未 push/tag/PR。
