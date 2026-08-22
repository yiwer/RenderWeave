# 实现 zero-rotation affine 非空 Frame HUG intrinsic 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 42, 43, 45（均已 resolved）

## Question

在 `renderweave-layout/1.0` 的任意 rotation 跨平台三角函数 tolerance、Group normalization、资源测量与
scene/raster 尚未物化时，如何继续深化同一个 Rust layout deep module：只实现 `rotationDeg == 0` 时可由
资源无关 direct ABSOLUTE child 精确求得的非空 Frame HUG intrinsic，同时让非零 rotation、Group、资源依赖
HUG 与 tolerance-dependent 分配继续 fail closed？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；复用同一次 admission/preflight、authored preorder
   与全有或全无输出，不新增 parser、crate、route、Profile 或 daemon success path。
2. **只开放 zero-rotation Frame intrinsic**：非空 Frame 的某个 HUG 轴，只接受 direct ABSOLUTE child 在该轴
   为 FIXED，或其 HUG 可由 T42/T43/T45/本票递归规则独立测量。父 HUG/子 FILL-inset cycle 继续由 T25
   preflight 拒绝；Text/Image、compositionViewport、Leaf HUG 与非空 Group 不进入本票。
3. **轴向 affine transform 精确顺序**：每个被测 direct child 必须满足 `rotationDeg` 精确等于正零；随后只在
   当前轴消费 nonzero scale 与 `[0,1]` origin。固定求值顺序为 `originOffset = originRatio × size` →
   `origin = position + originOffset` → `near = origin + scale × (position - origin)` →
   `far = origin + scale × ((position + size) - origin)`；取 `max(near, far)` 作为 transformed LayoutBox 该轴
   最远端。负 scale/flip 合法；非有限中间结果失败封闭，不做 FMA、epsilon、tolerance 或中间量化。
4. **Frame 稳定原点与 outer box**：Frame local ContentBox measure origin 固定为 `0`；按 authored child order
   取 `max(0, transformedEnd)`，负端只形成 overflow。content extent 后沿用 leading padding → trailing padding →
   leading inward stroke → trailing inward stroke，再只对 HUG 轴按 min→max clamp。
5. **递归与 arrange 复用**：资源无关 FIXED child 可使用既有 kind；HUG child 只可递归到已支持的空容器、
   Stack、Grid 或满足本票约束的 Frame。得到 Frame definite outer size 后继续复用既有 ContentBox 与 ABSOLUTE
   child arrange，不建立第二套 geometry writer；普通 child transform 仍不改变 emitted pre-transform LayoutBox。
6. **稳定 unsupported 边界**：一旦实际参与 Frame HUG 的 direct child `rotationDeg != 0`，在该 child 返回新增
   closed internal feature `CHILD_ROTATION`。它不外泄为 public Problem；固定 Frame、Stack/Grid sibling 分配与
   不参与 Frame HUG 的 transform 行为不变。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/12`，先把既有 nonempty Frame cases 转成
   positive，并新增 signed position、origin、positive/negative scale、单轴 HUG、recursive Frame、min/max 与
   nonzero-rotation fail-closed cases；先让 Rust primary 与 Python stdlib independent verifier 同时 RED，再实现
   两份独立语义。fixture bytes 不变时保持 fixture identity `/3`。
8. **诚实能力边界**：任意非零 rotation、Group union/normalization、Text/Image/Vector intrinsic、multiple Stack
   FILL、跨多 AUTO 平均、multiple FRACTION、world transform/scene、paint/raster/JPEG、daemon RESULT、Profile
   registration、公开 preview/E6、formal records 与物理 Linux/J1/A3 均不在本票。

## 验证与完成信号

- 局部：逐 slice RED→GREEN；focused Cargo vector tests + Python stdlib independent replay；workspace fmt/clippy
  `-D warnings`/test、`py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → `server`/`fast` → 完整 `full`；证据按局部到 Goal 扩大。
- 保证上限：Rust/kernel/gate A1，zero-rotation affine exact binary64 shared vectors 的 Rust+Python replay A2；
  不证明任意 rotation tolerance、完整 Layout/Renderer、scene/pixel、daemon output、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- 已在既有 `renderweave-renderer-layout` deep module 内实现 zero-rotation affine 非空 Frame HUG：direct
  ABSOLUTE child 的 definite/resource-free HUG size 按冻结的 binary64 加乘与 min/max 顺序消费
  position/scale/flip/origin，负端只形成 overflow；arrange 继续复用唯一 geometry writer。
- shared definite-layout vector/verifier identity 升级为 `/12`：57 个 laid-out cases + 12 个稳定 unsupported
  cases，共 69/69、209 checks；vector SHA-256
  `b77d4428d346af37d8f16974e522a507f07d38b092391d232c5e5a79e270a973`。Rust primary 与 Python stdlib
  independent replay 均通过，非零 rotation 以 closed internal `CHILD_ROTATION` fail closed。
- 分级 gate 全绿：`render` `.sdlc/evidence/20260822-112051-render/`、`server`
  `.sdlc/evidence/20260822-112132-server/`、受影响 `fast` `.sdlc/evidence/20260822-114137-fast/`、Goal 级
  `full` `.sdlc/evidence/20260822-114421-full/` 与治理后的 final `fast`
  `.sdlc/evidence/20260822-121536-fast/`。
- 生命周期为 `resolved / automated_verified`，不是 accepted/READY。Profile 仍为 NOT_REGISTERED、certification
  NOT_CERTIFIED、resource bytes UNFETCHED、world scene/raster ABSENT、daemon output UNWIRED；任意非零 rotation、
  Group、resource fetch/decode、multiple FILL/FRACTION、跨多 AUTO 平均与 tolerance-dependent 语义仍未实现。
- 无 migration、Java/OpenAPI/Web/route 或外部持久副作用；provider attempts、API Key reads、真实数据和付费调用
  均为 0；未 push/tag/PR。rollback 边界为本票单一 verified local commit。
