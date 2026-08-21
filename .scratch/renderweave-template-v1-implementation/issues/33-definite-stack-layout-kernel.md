# 实现资源无关的确定尺寸 Stack 布局内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26（均已 resolved）

## Question

如何在 `renderweave-layout/1.0` 的 Stack water-filling residual tolerance 尚未冻结、资源/shaping/raster 依赖也
未落地时，继续深化 T26 的真实 box layout：支持资源无关、两轴 definite 的 Stack 容器和 STACK child
placement，同时完整实现不依赖 water filling 的 margin/gap/justify/cross-axis 规则，并让 HUG 与 main-axis
FILL 继续 fail closed，而不是猜测 tolerance、预建 scene 或伪造 daemon success？

## Answer（本票冻结的实施决定）

1. **深化唯一 deep module**：将 workspace-internal `renderweave-renderer-layout` 的唯一 arrange 入口更名为
   `layout_definite_resource_free(&AdmittedRenderDocument)`；它继续复用 T25 的同一次 parse/preflight 和
   immutable authored-preorder `DefiniteLayout`，不新增 crate、route、Profile 或第二套 parser authority。
2. **扩展真实闭包**：保留 T26 的 Canvas/Frame/8 种资源无关视觉叶子与 ABSOLUTE `FIXED | FILL`；新增
   ABSOLUTE definite Stack，以及 Stack 下 `STACK` placement 的递归 Frame/Stack/相同叶子。STACK child 两轴
   只允许 `FIXED | FILL`，但 owning direction 的 main-axis `FILL` 返回 closed internal
   `STACK_MAIN_FILL`，因为 weighted water filling 与 residual tolerance 不在本票选择。
3. **Stack box/arrange**：Stack ContentBox 与 Frame 一样先扣每侧 inward stroke、再扣 padding。ROW 固定
   左到右、COLUMN 固定上到下；cursor 按 authored order 累加 signed leading margin、child size、signed
   trailing margin，只有相邻 materialized child 才加 gap。occupied/free space 按冻结顺序计算，overflow 时
   free 为正零。
4. **完整非 water-fill 分布**：实现 `START | CENTER | END | SPACE_BETWEEN | SPACE_AROUND |
   SPACE_EVENLY`。distributed slots 依 authored order计算前 `n-1` 项，最后 slot 接收 binary64 remainder；
   single-child行为遵循冻结规格。cross-axis `FIXED` 使用 `alignSelf` 的 START/CENTER/END 和原始 signed-margin
   interval；cross-axis `FILL` 使用 `max(0, interval)` 后 min/max clamp，再按已展开且体现 `alignItems`
   继承/`alignSelf` 覆盖的 START/CENTER/END 在同一原始 interval 内定位。
5. **binary64 与错误次序**：decimal6 仍只在同一次 document parse 后进入 binary64；固定求值顺序、禁止
   `mul_add`/fast-math/中间量化。Stack 预量尺寸只为计算 distribution；任一 deferred unsupported 仍按完整
   authored DFS 返回第一个 occurrence，绝不因 sibling 预量改变 first-error 顺序，也不返回 partial layout。
6. **诚实边界**：HUG、Stack main-axis FILL、Group、Grid、compositionViewport、Text/Image、退化 content
   inset 与 parent/placement variant mismatch 继续 closed internal unsupported。没有 resource fetch/decode、
   shaping、world transform/bounds、clip/paint/scene、raster/JPEG/RESULT/Engine success；daemon capability 与
   Profile registry保持不变。

## 验证与完成信号

- TDD：先把 shared fixture/vector 升级为新的 immutable identity，加入 ROW/COLUMN、signed margin、全部
  justify、cross align/FILL、nested Stack 与 DFS unsupported 向量，并让 Rust/Python 同时 RED；再实现 kernel。
- 局部：focused Cargo test + Python stdlib independent verifier → workspace fmt/clippy `-D warnings`/test。
- 受影响：`render` → `server`/`fast` → 完整 `full`；证据按局部到 Goal 扩大。
- 保证上限：Rust/kernel/gate A1，Rust+Python exact binary64 replay A2；不证明 HUG/water filling、完整 Layout
  Profile、scene/pixel、daemon output、物理 Linux certification、A3 或 J1。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；Profile持续
  NOT_REGISTERED、certification NOT_CERTIFIED、world scene/raster ABSENT、daemon output UNWIRED；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- 唯一 arrange 入口已演进为 `layout_definite_resource_free(&AdmittedRenderDocument)`；同一 admitted tree 上的
  Canvas/Frame/Stack/8 种资源无关叶子会按 authored preorder 产生全有或全无的 local LayoutBox/ContentBox。
- definite Stack 已实现 ROW/COLUMN、signed margin、materialized-adjacent gap、全部六种 justify、single-child
  规则、binary64 remainder、cross-axis FILL/min/max/align 与递归 Stack；main-axis FILL 稳定返回
  `STACK_MAIN_FILL`，HUG/Grid/resource/world scene/raster/daemon output 继续 fail closed。
- shared fixture/vector identity 升级为 `/2`；Rust primary 与 Python stdlib independent replay 共同覆盖
  21 laid-out + 10 unsupported，31/31、97 checks，并验证 sibling 预量不改变 authored DFS first error。
- A1/A2 证据：`render` `.sdlc/evidence/20260821-160137-render/`、`server`
  `.sdlc/evidence/20260821-154146-server/`、`fast` `.sdlc/evidence/20260821-154123-fast/`；最终 exact-manifest
  `full` 目录按不可自指策略只在 commit handoff 报告。
- 生命周期为 `resolved / automated_verified`，但不外推 Renderer/Profile/Template v1 READY、physical Linux
  certification、A3 或 J1。Provider attempts/API Key reads/open authorization/paid external calls 均为 0；未
  push/tag/PR。
