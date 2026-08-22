# 实现 Stack main offer → columns-first Grid cross HUG 单向传播子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 34, 38, 39, 40, 41, 44, 45, 49, 50, 51, 52, 53, 54, 55, 56, 57（均已 resolved）

## Question

T55/T56 已让 ROW Stack 的 singleton main-FILL child 在最终宽度分配后执行一次 cross-HUG 重测，T57 已让
Grid 严格按 columns-first 用最终 column cell width 求 row AUTO contribution。如何把这两个有限过程组合起来，
让 direct STACK Grid 的已解析 main outer width 驱动其 cross-axis HUG，同时不把 offer 扩散到 ABSOLUTE parent、
不从 rows 反推 columns，也不引入通用 constraint engine、fixed point 或 residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 layout deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；不新增 public API、crate、parser、route、Profile、
   daemon success path 或第二套 Grid/Stack 算法。
2. **仅开放 ROW Stack 的必要延迟路径**：direct STACK child 仅在 role=Grid、parent direction=ROW、
   `widthMode=FILL`、`heightMode=HUG_CONTENT` 时，沿用 T55 singleton main-FILL allocation 后的一次 cross-HUG
   remeasure。COLUMN Stack 的 Grid width HUG 本来按 columns-first 独立可解，不进入新延迟路径。
3. **typed outer offer 到 Grid ContentBox**：重测只接受 T55/T56 已解析的 `ResolvedOuter`；按既有 inward
   stroke 与 padding floor-zero 规则把 Grid outer width 转成 content width。`AbsoluteParentContent` 不在本票，
   因而普通 ABSOLUTE Grid 的 width-FILL/height-HUG 不会被顺带开放。
4. **严格 columns-first 一次求值**：先以该 definite content width 完整求解 columns，再以
   `RowsAfterColumns` 一次求解 rows；row AUTO 仅沿 T57 对 direct width-FILL/height-HUG Frame 读取 final column
   cell outer width。rows 不回写 columns，cross result 不重算 Stack main allocation、siblings、gaps 或 tracks。
5. **递归组合但不扩大约束模型**：同 direction nested Stack 链可通过 T56 在末端组合本票 Grid；每层仍只有
   singleton main allocation 与一次 cross-HUG remeasure。multiple Stack FILL、multiple FRACTION、跨多 AUTO
   平均、双向 HUG、一般 `UNBOUNDED/AT_MOST/EXACT` constraint engine 与非直角 rotation 均不在本票。
6. **固定错误顺序与全有或全无**：Stack 继续 authored order 预测并在原 measurement slot 保存错误；Grid 继续
   columns 完成后才进入 rows，AUTO constraints 保持既有稳定顺序。任何 unsupported/invariant 仍返回 authored
   DFS first error，绝不产出 partial layout。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/21`。把现有 nested Stack→Grid boundary negative
   转为 positive，并新增 sibling/gap/margin + Grid stroke/padding/single-FRACTION、same-direction nested Stack
   组合、main-width clamp 三个 positive；新增 ABSOLUTE parent offer 不得扩散到 owning Grid 的 boundary negative。
   目标为 99 laid-out + 15 unsupported，共 114 cases、341 checks；fixture bytes 不变时保持 fixture `/3`。
8. **诚实能力边界**：ABSOLUTE parent→Grid、Grid-in-Grid owning offer、column-from-row feedback、跨多 AUTO 平均、
   multiple FILL/FRACTION、一般 constraint/tolerance、Text/Image/Vector intrinsic、resource fetch/decode、world
   scene、paint/raster/JPEG、daemon RESULT、Profile/E6、formal records 与物理 Linux/J1/A3 均不在本票。

## 验证与完成信号

- 局部：shared vectors 先使 Rust primary/Python independent verifier 共同 RED；再分别实现，运行 focused Cargo
  vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/test、`py_compile`、JSON inventory 与
  `git diff --check`。
- 受影响：`render` → 顺序 `fast`/`server` → 完整 `full`；Maven gate 不并发。
- 保证上限：Rust/kernel/gate A1，Stack→Grid typed offer 的 Rust+Python shared replay A2；不证明一般 Grid
  constraint/tolerance、完整 Layout/Renderer、scene/pixel、daemon output、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit；不 push/tag/PR，
  不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `renderweave-renderer-layout` 已在既有 `layout_definite_resource_free` deep-module seam 内把 ROW Stack 的
  singleton main-FILL deferred role 扩展到 direct Grid：最终 allocation 得到的 typed `ResolvedOuter` width
  只触发一次 cross-HUG remeasure；同 direction nested Stack 可继续经 T56 逐层组合，COLUMN Stack 不新增路径。
- Grid 只接受 `ResolvedOuter`，并按既有 inward stroke/padding floor-zero 规则把 outer width 转成 content width；
  columns 在该 definite content width 下完整求解后，rows 才以 `RowsAfterColumns` 求值。row AUTO 可继续经 T57
  读取 final column cell width；rows 不回写 columns，cross result 不重算 Stack main allocation、siblings、gaps
  或 tracks。Python independent verifier 以独立控制流只接受 `RESOLVED_OUTER`，因此 `AbsoluteParentContent`→Grid
  继续 fail closed。
- shared definite-layout contract 已升级到 `/21`：99 laid-out + 15 unsupported，共 114/114、341 checks；四个
  positive vectors 在 Rust/Python 先共同以 `CHILD_ROTATION` RED，完成后 exact-bit GREEN；ABSOLUTE parent offer
  不得扩散到 owning Grid 的 boundary negative 继续 fail closed。vector SHA-256 为
  `d656a94621b159d5ef00d16ec8bc2013d5d176b66e2f87e1ff6ecc0c0dc3cb3e`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1 gate 已通过：`render` `.sdlc/evidence/20260822-214121-render/`、affected `fast`
  `.sdlc/evidence/20260822-214159-fast/`、顺序 `server` `.sdlc/evidence/20260822-214217-server/` 与 Goal
  `full` `.sdlc/evidence/20260822-220227-full/`。full metadata 为 `result=passed`，17 个 step 均 exit 0；其中
  Node 24 Web 26 files/212 tests、runtime canary、23 passed + 1 skipped Playwright E2E、Draft/Inference browser
  journeys 均通过，R0/R1/P0 independent replay 的 provider attempts 均为 0；resolution 后 fast
  `.sdlc/evidence/20260822-223121-fast/` 也通过。
- ABSOLUTE parent→Grid、Grid-in-Grid owning offer、column-from-row feedback、跨多个 AUTO 的平均 deficit、
  multiple FILL/FRACTION、一般 constraint/tolerance、非直角 rotation、resource fetch/decode、world scene/raster、
  daemon output 与 Profile 仍未实现；API Key reads/reservations/cost、真实数据与付费调用均为 0，未推进
  A3/J1/READY，未 push/tag/PR。
