# 实现 FIXED opposite-axis offer 下的 quarter-turn Frame HUG cross-axis FILL 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 42, 43, 45, 49, 50, 51（均已 resolved）

## Question

在 `renderweave-layout/1.0` 的一般 parent-offer 传播、数值 tolerance、资源测量与 world scene/raster 尚未物化时，
如何继续深化同一个 Rust layout deep module：只让另一轴为 FIXED 的 HUG Frame 为精确奇数 quarter-turn direct
ABSOLUTE child 提供 cross-axis FILL 的 definite ContentBox offer，同时让 Frame 自身另一轴 FILL、非直角 rotation、
资源与 tolerance-dependent 语义继续 fail closed？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；复用同一次 admission/preflight、authored preorder 与
   全有或全无输出，不新增 parser、crate、route、Profile 或 daemon success path。
2. **仅 Frame、仅 FIXED opposite axis**：当 owning Frame 正在测量 HUG Width 时，只在其 `heightMode=FIXED` 时
   派生 definite cross offer；测量 HUG Height 时对称要求 `widthMode=FIXED`。Group 两轴 HUG 且 direct child FILL
   已由静态合同禁止，不增加 Group 分支。
3. **offer 按既有 box 顺序派生**：从 owning Frame 的 authored FIXED outer size 开始，依次扣两次 inward stroke、
   leading padding、trailing padding，每一步继续使用既有 floor-to-positive-zero 规则，得到 cross ContentBox size；
   不从 transform、PaintBounds 或 child 反推 offer。
4. **cross FILL 复用唯一 definite writer**：奇数 quadrant 读取 child cross-axis position 后，以
   `max(0, (offer-start)-endInset)` 求 size，再按既有顺序应用 min、max clamp；随后才进入 T51 已冻结的
   clockwise quarter-turn affine endpoint/AABB 运算。当前 HUG 轴上的 FILL 仍是 preflight cycle。
5. **不扩张 parent-offer seam**：若 owning Frame 的 opposite axis 是 FILL，本票不从 Canvas/Frame/Stack/Grid
   placement 反向取得 offer，仍稳定返回 `CHILD_ROTATION`；opposite axis 为 HUG 且 child 同轴 FILL 仍由静态
   preflight 以 cycle 拒绝。该边界为后续独立 ticket 保留。
6. **保持旧路径与错误顺序**：q0、half-turn、Group、两轴 FIXED/independently resource-free HUG 继续走 T49–T51
   原路径和 binary64 求值顺序；只有 odd quadrant 遇到 cross-axis FILL 时消费新增 FIXED offer。child role、当前轴
   measurement、rotation classifier 及 authored DFS first-error 顺序不变。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/15`；先把 T51 的 cross-axis FILL negative case
   转为 positive，并覆盖 Width/Height 对称、±90/270、inset floor-zero、min/max、nested resource-free Frame 以及
   owning opposite-axis FILL 继续 fail-closed。Rust primary 与 Python stdlib independent verifier 必须先 RED，
   再分别实现冻结语义；fixture bytes 不变时保持 fixture `/3`。
8. **诚实能力边界**：Frame opposite-axis FILL offer propagation、非直角 rotation、Text/Image/Vector intrinsic、
   multiple Stack FILL、跨多 AUTO 平均、multiple FRACTION、actual resource fetch/decode、world transform/scene、
   paint/raster/JPEG、daemon RESULT、Profile registration、公开 preview/E6、formal records 与物理 Linux/J1/A3
   均不在本票。

## 验证与完成信号

- 局部：逐 slice RED→GREEN；focused Cargo vector tests + Python stdlib independent replay；workspace fmt/clippy
  `-D warnings`/test、`py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → `server`/`fast` → 完整 `full`；证据按局部到 Goal 扩大。
- 保证上限：Rust/kernel/gate A1，FIXED opposite-axis quarter-turn Frame HUG cross-axis FILL exact binary64 shared
  vectors 的 Rust+Python replay A2；不证明一般 parent-offer、完整 Layout/Renderer、scene/pixel、daemon output、
  A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `renderweave-renderer-layout` 已在同一 `layout_definite_resource_free` deep-module seam 内实现 FIXED
  opposite-axis ContentBox offer：HUG Frame 按既有 inward-stroke/padding floor-zero 顺序派生 offer，精确 odd
  quarter-turn direct ABSOLUTE child 的 cross-axis FILL 复用 inset、positive-zero、min 后 max clamp，再进入 T51
  clockwise affine AABB。q0、half-turn、Group 与 independently measurable 旧路径保持不变。
- shared definite-layout contract 已升级到 `/15`：74 laid-out + 14 unsupported，共 88/88、264 checks；Rust
  primary 与 Python independent replay 同时通过，vector SHA-256 为
  `464cf2eb85ad0b0a03970ceb3285f7b6a0e3dc545a7ee883f5e8d8ad9c5c8da0`，fixture `/3` bytes 未变。
- A1 gate 已通过：`render` `.sdlc/evidence/20260822-145743-render/`、affected `fast`
  `.sdlc/evidence/20260822-145813-fast/`、顺序 `server` `.sdlc/evidence/20260822-145856-server/` 与 Goal
  `full` `.sdlc/evidence/20260822-151938-full/`，以及 resolution 后 final `fast`
  `.sdlc/evidence/20260822-155132-fast/`。full metadata 为 `result=passed`，17 个 step 均 exit 0；首次并发
  `server` `.sdlc/evidence/20260822-145813-server/` 因与 fast 争用共享 Maven `target` 失败，顺序重放已排除源码回归。
- Frame opposite-axis FILL 的一般 parent-offer、非直角 rotation、resource fetch/decode、world scene/raster、
  daemon output 与 Profile 仍未实现；provider attempts/API Key reads/reservations/cost、真实数据与付费调用均为 0，
  未推进 A3/J1/READY，未 push/tag/PR。
