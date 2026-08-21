# 实现资源无关的 FIXED-track definite Grid 布局内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33（均已 resolved）

## Question

如何在 `renderweave-layout/1.0` 的 residual tolerance 尚未给出可执行数值、AUTO/HUG 又依赖资源测量时，继续深化
真实 layout kernel：落实规范已经完全确定的 ContentBox floor-zero，以及只含 FIXED tracks 的 definite Grid、
GRID child span/margin/FILL/alignment/递归语义，同时让 AUTO/FRACTION 与其他未覆盖面继续 fail closed？

## Answer（本票冻结的实施决定）

1. **继续深化唯一 deep module**：只扩展 workspace-internal `renderweave-renderer-layout` 与既有
   `layout_definite_resource_free(&AdmittedRenderDocument)`；复用同一 admitted/preflight tree、全有或全无的
   authored-preorder `DefiniteLayout`，不新增 parser、crate、route、Profile 或 daemon success path。
2. **先修正已冻结的 box 语义**：Frame/Stack/Grid 的 ContentBox 仍逐侧先扣 inward stroke、再扣 padding；每次扣除
   结果小于零立即 floor 为正零，不再返回 `DEGENERATE_CONTENT_INSET`。origin 仍按左/上 stroke 与 padding 的固定
   加法顺序计算，不缩放、不报错，也不引入 epsilon/tolerance。
3. **只支持无 residual 的 Grid 子闭包**：Grid 自身两轴必须 definite；rows/columns 必须全部为正值 FIXED track。
   AUTO 与 FRACTION 分别返回 closed internal unsupported，因为 AUTO 需要 constraint-sensitive measure，而多个
   FRACTION 的最后余数需要尚未数值化的 Profile tolerance。本票不借单轨特例暗示完整 FRACTION 支持。
4. **固定物理 track 与 cell**：track 从 ContentBox 左/上按 authored order 排列，相邻 track 保留声明 gap；容器剩余
   空间固定留在物理右/下端。spanned cell 包含内部 gaps，row/column 为零基，Grid overlap 合法且输出/paint 前序仍由
   authored children order 决定。
5. **GRID child arrange**：资源无关 Frame/Stack/Grid/八种叶子可递归作为 GRID child。两轴只支持 FIXED/FILL；
   signed margins 形成原始 interval。FIXED 按各轴 START/CENTER/END 定位，oversize 仍遵守 alignment；FILL 取
   `max(0, interval)` 后应用 min/max，并从 interval 起点放置。HUG 保持 `HUG_CONTENT` fail closed，child min 不扩大
   FIXED track。
6. **错误与能力边界**：固定 binary64 求值顺序、禁止 `mul_add`/fast-math/中间量化；首个 unsupported 按实际
   authored DFS/track 求解前置稳定返回，不泄漏 partial layout。Stack main-axis FILL、Grid AUTO/FRACTION、HUG、
   Group/compositionViewport、Text/Image、resource、world transform/scene/paint/raster/JPEG/RESULT 继续不实现。

## 验证与完成信号

- TDD：先升级 shared fixture/vector identity，加入 degenerate inset floor-zero、FIXED track/gap/span、signed margin、
  双轴 FIXED/FILL/min-max/alignment、overlap/nested Grid 与 AUTO/FRACTION/DFS unsupported cases，使 Rust primary 与
  Python independent replay 同时 RED；再实现两端算法。
- 局部：focused Cargo test + Python stdlib independent verifier → workspace fmt/clippy `-D warnings`/test。
- 受影响：`render` → `server`/`fast` → 完整 `full`，证据按局部到 Goal 扩大。
- 保证上限：Rust/kernel/gate A1、Rust+Python exact binary64 replay A2；不证明 AUTO/FRACTION/HUG、完整 Layout
  Profile、scene/pixel、daemon output、物理 Linux certification、A3 或新 J1。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；Profile 持续
  NOT_REGISTERED、certification NOT_CERTIFIED、world scene/raster ABSENT、daemon output UNWIRED；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- ContentBox 已按 frozen 顺序逐侧执行 inward stroke 后 padding，任何负剩余量立即 floor 为正零；不再把合法的
  degenerate content box 误报为 `DEGENERATE_CONTENT_INSET`，origin 加法顺序保持不变。
- definite Grid 已实现全 FIXED rows/columns、columns-first 求解、authored-order origins、内部 gap/span、signed
  margin、双轴 FIXED/FILL/min-max/alignment、overlap 与递归 Frame/Stack/Grid/资源无关叶子；输出继续是全有或
  全无的 authored preorder。AUTO/FRACTION/HUG 分别稳定 fail closed，未猜测 residual tolerance 或 intrinsic measure。
- shared fixture/vector/verifier identity 升级为 `/3`；Rust primary 与 Python stdlib independent replay 覆盖
  23 laid-out + 11 unsupported，34/34、105 checks。vector SHA-256 为
  `322adeb2181b872f722e10850bfc356288c0a2755123dabd40d207661fc9a5e6`，fixture SHA-256 为
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1/A2 证据：`render` `.sdlc/evidence/20260821-164544-render/`、`server`
  `.sdlc/evidence/20260821-164614-server/`、串行 `fast` `.sdlc/evidence/20260821-170305-fast/`；最终
  exact-manifest `full` 目录按不可自指策略只在 commit handoff 报告。首次与 server 并发的 fast 因共享 Maven
  `target` 被 `clean` 竞争而失效，不作为绿色证据。
- 生命周期为 `resolved / automated_verified`，但不外推完整 Grid/Layout Profile、Renderer/Profile/Template v1
  READY、physical Linux certification、A3 或新 J1。Provider attempts/API Key reads/open authorization/paid external
  calls 均为 0；未发送真实数据，未 push/tag/PR。
