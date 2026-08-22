# 实现 definite ABSOLUTE parent-offer 下的 quarter-turn Frame HUG opposite-axis FILL 传播子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 42, 43, 45, 49, 50, 51, 52（均已 resolved）

## Question

在一般约束传播、Stack/Grid cell offer、数值 tolerance、资源测量与 world scene/raster 尚未物化时，如何继续深化
同一个 Rust layout deep module：只在 ABSOLUTE 父级 ContentBox 已 definite 时，把 opposite-axis offer 传给
HUG Frame，使该 Frame 自身 opposite axis FILL 的 outer/content size 可供 T52 odd-quarter-turn child cross-axis
FILL 消费，并允许同类 ABSOLUTE Frame 链递归传播，同时保持所有不具备单向 definite 依赖的路径 fail closed？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；只给内部 resource-free HUG 测量上下文增加可选的
   opposite-axis parent ContentBox offer，不新增 public API、crate、parser、route、Profile 或 daemon success path。
2. **只接受 already-definite ABSOLUTE parent**：Canvas 或已 arrange 的 Frame ContentBox 可提供对应 cross-axis
   definite size；ABSOLUTE child 的 HUG Width 接收 parent ContentBox Height，HUG Height 对称接收 Width。没有该
   definite offer 时保持当前 unsupported，不从尚未求值的 parent HUG、Stack/Grid track/cell 或 world transform 猜测。
3. **owning Frame FILL 继续复用唯一 writer**：当 HUG Frame 的 opposite axis 是 FILL 且收到 parent offer 时，按
   `max(0,(parentContentSize-start)-endInset)` 求 outer size，再按既有顺序应用 min、max clamp；随后依次扣两次
   inward stroke 与 leading/trailing padding并逐步 floor-to-positive-zero，得到 T52 所需 cross ContentBox offer。
4. **允许无环 ABSOLUTE 链递归传播**：Frame 在测量 direct ABSOLUTE child transformed LayoutBox 时，把自己的
   definite cross ContentBox offer继续交给 child；若 child 当前轴 HUG、opposite axis FILL，则先确定 child opposite
   outer size，再递归测其 subtree。每条边都是 parent definite cross offer → child outer/content offer 的单向计算，
   不反算 parent、不迭代、不建立 fixed point。
5. **复用 T52 affine 与错误边界**：direct child odd quadrant cross-axis FILL 仍使用 T52 的 inset/min-max 与 T51
   clockwise quarter-turn endpoint/AABB；q0、half-turn、FIXED opposite axis、Group 与旧 independently measurable
   路径不漂移。当前 HUG 轴上的 child FILL 仍由 preflight cycle 拒绝。
6. **不扩张 Stack/Grid 与 constraint seam**：STACK child margin/cross offer、Grid cell/span offer、AUTO contribution、
   compositionViewport、`AT_MOST/EXACT` 通用 constraint-sensitive measure 均不在本票；它们需要各自的布局顺序与
   独立 ticket，不能借 ABSOLUTE helper 暗中接线。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/16`；先把 T52 保留的 owning opposite-axis FILL
   negative case 转为 positive，并覆盖 Width/Height 对称、owning FILL 的 floor-zero/min/max、嵌套 ABSOLUTE Frame
   递归、q0/half-turn regression，以及缺 parent offer/Stack/Grid context 继续 fail-closed。Rust primary 与 Python
   stdlib independent verifier 必须先共同 RED，再分别实现冻结语义；fixture bytes 不变时保持 fixture `/3`。
8. **诚实能力边界**：一般 `UNBOUNDED/AT_MOST/EXACT` constraint propagation、Stack/Grid offer、双向 HUG、非直角
   rotation、Text/Image/Vector intrinsic、multiple Stack FILL、跨多 AUTO 平均、multiple FRACTION、actual resource
   fetch/decode、world transform/scene、paint/raster/JPEG、daemon RESULT、Profile/E6、formal records 与物理
   Linux/J1/A3 均不在本票。

## 验证与完成信号

- 局部：逐 slice RED→GREEN；focused Cargo vector tests + Python stdlib independent replay；workspace fmt/clippy
  `-D warnings`/test、`py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → 顺序 `fast`/`server` → 完整 `full`；Maven gate 不再并发争用共享 `target`。
- 保证上限：Rust/kernel/gate A1，definite ABSOLUTE opposite-axis offer 的 Rust+Python shared replay A2；不证明
  通用 constraint engine、完整 Layout/Renderer、scene/pixel、daemon output、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit；不 push/tag/PR，
  不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `renderweave-renderer-layout` 已在同一 `layout_definite_resource_free` deep-module seam 内实现可选的
  `opposite_parent_content_offer` 测量上下文。already-definite Canvas/ABSOLUTE Frame ContentBox 的 cross offer
  只沿 direct ABSOLUTE Frame 链单向传播；owning opposite-axis FILL 复用 inset、positive-zero、min 后 max clamp，
  再按 inward-stroke/padding floor-zero 派生 T52 所需 ContentBox offer。缺 offer、Stack/Grid context 与双向 HUG
  继续 fail closed，不引入迭代或 fixed point。
- shared definite-layout contract 已升级到 `/16`：79 laid-out + 14 unsupported，共 93/93、279 checks；Rust
  primary 与 Python independent replay 同时通过，vector SHA-256 为
  `8b27b3c01bb8135bc62d08e33313e825b2bf3b55f6fca325e09a2aaa94c28f9b`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1 gate 已通过：`render` `.sdlc/evidence/20260822-160707-render/`、affected `fast`
  `.sdlc/evidence/20260822-160736-fast/`、顺序 `server` `.sdlc/evidence/20260822-160752-server/` 与 Goal
  `full` `.sdlc/evidence/20260822-162713-full/`。full metadata 为 `result=passed`，17 个 step 均 exit 0；其中
  Node 24 Web 26 files/212 tests、runtime canary、23 passed + 1 skipped Playwright E2E 与 browser journeys 均通过；
  resolution 后 final `fast` `.sdlc/evidence/20260822-165827-fast/` 也通过。
- 一般 constraint/Stack/Grid offer、非直角 rotation、resource fetch/decode、world scene/raster、daemon output 与
  Profile 仍未实现；provider attempts/API Key reads/reservations/cost、真实数据与付费调用均为 0，未推进
  A3/J1/READY，未 push/tag/PR。
