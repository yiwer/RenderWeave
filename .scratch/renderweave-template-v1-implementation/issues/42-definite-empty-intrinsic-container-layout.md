# 实现 definite 空容器 HUG intrinsic 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 34, 41（均已 resolved）

## Question

在 `renderweave-layout/1.0` 的一般资源测量、direct-child transform conformance 与 binary64 residual tolerance
尚未物化时，如何继续深化同一个 Rust layout deep module：只实现冻结规格已经给出精确退化结果的空
Frame/Stack/Grid/Group HUG intrinsic，让合法的非空 HUG、资源依赖 HUG、跨多个 AUTO 的平均 deficit、多个
FRACTION/FILL 与 scene/raster 继续 fail closed？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；复用同一次 document admission/preflight、authored
   preorder 全有或全无输出，不新增 parser、crate、route、Profile 或 daemon success path。
2. **空容器才可 HUG**：只有 `children: []` 的 Frame、Stack、Grid 与 Group 可在本票消费
   `HUG_CONTENT`。任一非空 Frame/Stack/Grid 的 HUG 仍在实际 occurrence 返回 `HUG_CONTENT`；非空 Group 仍返回
   `GROUP`。Text/Image、自由 Vector intrinsic 与 compositionViewport 不进入本票。
3. **Frame/Stack 精确自然尺寸**：空 Frame/Stack 的某个 HUG 轴自然 outer size 为该轴两侧 inward stroke 加
   padding；binary64 求值固定为 leading padding → trailing padding → leading stroke → trailing stroke，随后只对
   该 HUG 轴按既有 min→max 顺序 clamp。另一轴保持既有 FIXED/FILL 语义；最终 ContentBox 严格按 stroke 后
   padding 逐项 floor-zero，不引入 epsilon 或中间量化。
4. **Grid 精确自然尺寸**：空 Grid 的 HUG 轴只含 preflight 已允许的 FIXED/AUTO tracks；FIXED 使用 authored
   value，AUTO 因无 contribution 为零，声明的相邻 gap 仍保留。求值按 authored track → 其后相邻 gap 重复，
   再按 leading padding → trailing padding → leading stroke → trailing stroke；随后应用该轴 min/max。
   FRACTION-on-HUG 继续由 preflight 拒绝；
   definite 另一轴仍只使用 T41 已验证的子闭包。
5. **Group 精确退化**：空 Group 的双轴自然 size 均为零；遵守 T25 已冻结的 Group 禁止 min/max 约束，不作
   clamp。Group 自身 transform 不反馈自身布局。它没有 ContentBox。任何非空 Group 都继续等待 direct-child
   transformed LayoutBox union
   与 normalization，不由本票猜测。
6. **稳定失败与数值边界**：decimal6 仍只在布局入口转成 IEEE-754 binary64 一次；求值使用显式逐项顺序，
   不作 FMA、中间量化、epsilon 或 tolerance。placement variant 可为 ABSOLUTE/STACK/GRID，但只有空容器的 HUG
   measure 可成功；authored DFS 首个未覆盖 occurrence 与既有 unsupported code 不变。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/8`，覆盖空 Frame、Stack、含 FIXED/AUTO/gap
   的 Grid、Frame 的 min/max clamp、零尺寸 Group，以及嵌套 STACK/GRID placement；先让 Rust primary 与 Python stdlib independent
   verifier 同时 RED，再实现两份独立语义。既有 fixture bytes 不变时保持 fixture identity `/3`。
8. **诚实能力边界**：一般 HUG/IntrinsicSize、非空 Frame/Stack/Grid/Group、child transform union、Text/Image
   resource measurement、Vector bounds、multiple Stack FILL、跨多 AUTO 平均、multiple FRACTION、world scene、
   paint/raster/JPEG、daemon RESULT、Profile registration 与公开 preview/render 均不在本票。

## 验证与完成信号

- 局部：focused Cargo vectors + Python stdlib independent verifier → workspace fmt/clippy `-D warnings`/test、
  `py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → `server`/`fast` → 完整 `full`；证据按局部到 Goal 扩大。
- 保证上限：Rust/kernel/gate A1，Rust+Python exact binary64 replay A2；不证明完整 Layout Profile、resource、
  scene/pixel、daemon output、physical Linux certification、A3 或 J1。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；Profile
  持续 NOT_REGISTERED、certification NOT_CERTIFIED、world scene/raster ABSENT、daemon output UNWIRED；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `layout_definite_resource_free` 现在只对 `children: []` 的 Frame、Stack、Grid 与 Group 消费 HUG：Frame/Stack
  按双侧 padding → inward stroke 的冻结逐项顺序得到自然尺寸，Grid 按 authored track → adjacent gap 后再加
  padding/stroke，Frame/Stack/Grid 随后只在 HUG 轴执行 min→max；空 Group 严格退化为 `0×0`、无 ContentBox。
- ABSOLUTE、STACK 与 GRID placement 均复用同一 empty-container measure。任一非空 Frame/Stack/Grid HUG 仍在
  实际 occurrence 返回 `HUG_CONTENT`，非空 Group 仍返回 `GROUP`；一般 intrinsic、transform union、资源与
  tolerance 路径未被放宽。
- immutable vector/verifier identity 升级为 `/8`，fixture identity 保持 `/3`；Rust primary 与 Python stdlib
  independent replay 覆盖 40 laid-out + 13 unsupported，53/53、160 checks。vector SHA-256 为
  `25e0a8d2ba97bcef3a3fb03a70fc140093b80c967ae64658b129c5d28a8decfb`，fixture SHA-256 为
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1/A2 证据：`render` `.sdlc/evidence/20260822-012213-render/`、`server`
  `.sdlc/evidence/20260822-012240-server/`、治理前 `fast` `.sdlc/evidence/20260822-014339-fast/`；resolution
  governance 后的最终 Fast/Full 目录按不可自指策略只在 commit handoff 报告。
- 生命周期为 `resolved / automated_verified`，不外推非空容器 HUG、child transform union、Text/Image/Vector
  measurement、multiple FILL/FRACTION、跨多 AUTO 平均、完整 Layout/Renderer/Profile/Template v1 READY、
  scene/raster/daemon RESULT、physical Linux certification、A3 或 J1。Provider attempts/API Key reads/open
  authorization/paid external calls 均为 0；未发送真实数据，未 push/tag/PR。
