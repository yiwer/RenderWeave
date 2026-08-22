# 实现 singleton Stack main-axis FILL 后 cross-axis Frame HUG 单次重测子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 49, 50, 51, 52, 53, 54（均已 resolved）

## Question

在 multiple main-axis FILL water filling 仍依赖 Profile tolerance、Grid/nested Stack offer 仍须单独冻结时，如何按
`renderweave-layout/1.0` 已确定的 Stack 顺序继续深化同一个 Rust layout deep module：先完成唯一 main-axis
FILL 分配，再只用该最终 main outer size 对 direct STACK Frame 的 cross-axis HUG 做一次重测，使 quarter-turn
ABSOLUTE child 的 opposite-axis FILL 可复用 T51–T54 affine 子闭包，同时不反算主轴、不做一般 fixed point？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；不新增 public API、crate、parser、route、Profile 或
   daemon success path。
2. **仅 singleton main FILL → cross HUG direct Frame**：ROW 只支持 direct STACK Frame 的
   `widthMode=FILL,heightMode=HUG_CONTENT`；COLUMN 只支持对称的
   `heightMode=FILL,widthMode=HUG_CONTENT`。只复用 T38 已实现的唯一 main-axis FILL 分配；两个及以上 main FILL
   仍以首个 authored occurrence 返回 `STACK_MAIN_FILL`。
3. **严格两阶段、只重测一次**：初始 Stack measure 只延迟上述 direct Frame 的 cross HUG，不猜测其尺寸；先扣
   gap、signed main margins 与全部非 FILL size，按 positive-zero、min 后 max 得到唯一 FILL 的最终 outer size；
   然后以 typed `ResolvedOuter` offer 重测 cross HUG 一次。cross 结果不反馈 main allocation、justify 或 sibling。
4. **错误顺序仍按 authored DFS**：延迟项的重测结果保存回原 measurement slot；不得因在分配阶段提前 `?`
   而越过更早 sibling 的错误。multiple-FILL 的既有首项错误优先级、全有或全无 `DefiniteLayout` 均保持不变。
5. **复用既有 box/affine writer**：Frame 用最终 main outer size 依次扣 inward stroke 与 leading/trailing padding，
   逐步 floor-to-positive-zero，得到 direct ABSOLUTE child 的 FILL offer；T52 inset/min-max 与 T51 exact-quarter-turn
   endpoint/AABB 顺序不变。main min overflow 合法，main max 剩余仍只进入既有 justify。
6. **不扩展其他反馈路径**：nested Stack/Grid cell/span offer、双向 HUG、multiple main FILL、一般
   `UNBOUNDED/AT_MOST/EXACT` constraint propagation 与固定点迭代均不接线。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/18`；先把 T54 保留的
   `stack-main-fill-to-cross-hug-frame-remains-unsupported` 转为 positive，并覆盖 ROW/COLUMN 对称、main min overflow、
   main max + justify，以及 nested Stack 同类路径继续 fail closed。Rust primary 与 Python stdlib independent
   verifier 必须先共同 RED，再分别实现冻结语义；fixture bytes 不变时保持 fixture `/3`。
8. **诚实能力边界**：非直角 rotation、Text/Image/Vector intrinsic、multiple Stack FILL、Grid/nested Stack
   offer、跨多 AUTO 平均、multiple FRACTION、actual resource fetch/decode、world scene、paint/raster/JPEG、daemon
   RESULT、Profile/E6、formal records 与物理 Linux/J1/A3 均不在本票。

## 验证与完成信号

- 局部：逐 slice RED→GREEN；focused Cargo vector tests + Python stdlib independent replay；workspace fmt/clippy
  `-D warnings`/test、`py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → 顺序 `fast`/`server` → 完整 `full`；Maven gate 保持串行。
- 保证上限：Rust/kernel/gate A1，singleton Stack main-FILL→cross-HUG 的 Rust+Python shared replay A2；不证明
  multiple-FILL water filling、通用 constraint engine、完整 Layout/Renderer、scene/pixel、daemon output、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit；不 push/tag/PR，
  不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `renderweave-renderer-layout` 已在既有 `layout_definite_resource_free` deep-module seam 内实现严格两阶段测量：
  singleton main FILL 先按 T38 得到最终 outer size，再只对 direct Frame 的 cross HUG 以 typed
  `ResolvedOuter` 重测一次。重测结果写回原 authored measurement slot；cross size 不反馈 main allocation、
  justify 或 sibling，multiple main FILL 仍按既有 `STACK_MAIN_FILL` fail closed。
- shared definite-layout contract 已升级到 `/18`：87 laid-out + 14 unsupported，共 101/101、303 checks；Rust
  primary 与 Python independent replay 同时通过，vector SHA-256 为
  `e21f55d9a2ac308512c4fd2b59d6e05fe7e12ff328ff9cb4f68a68d9f7dbbf0b`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1 gate 已通过：`render` `.sdlc/evidence/20260822-181454-render/`、affected `fast`
  `.sdlc/evidence/20260822-181539-fast/`、顺序 `server` `.sdlc/evidence/20260822-181559-server/` 与 Goal
  `full` `.sdlc/evidence/20260822-183516-full/`。full metadata 为 `result=passed`，17 个 step 均 exit 0；其中
  Node 24 Web 26 files/212 tests、runtime canary、23 passed + 1 skipped Playwright E2E、Draft/Inference browser
  journeys 均通过，R0/R1/P0 independent replay 的 provider attempts 均为 0；resolution 后 fast
  `.sdlc/evidence/20260822-190500-fast/` 也通过。
- nested Stack/Grid offer、双向 HUG、multiple main FILL water filling、通用 constraint、非直角 rotation、
  resource fetch/decode、world scene/raster、daemon output 与 Profile 仍未实现；API Key reads/reservations/cost、
  真实数据与付费调用均为 0，未推进 A3/J1/READY，未 push/tag/PR。
