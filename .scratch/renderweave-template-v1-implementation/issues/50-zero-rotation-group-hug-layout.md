# 实现 zero-rotation affine 非空 Group HUG union/normalization 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 42, 43, 45, 49（均已 resolved）

## Question

在 `renderweave-layout/1.0` 的任意 rotation 跨平台三角函数 tolerance、资源测量与 world scene/raster 尚未物化时，
如何继续深化同一个 Rust layout deep module：只实现 `rotationDeg == 0` 时资源无关 direct ABSOLUTE child 的
非空 Group transformed LayoutBox 二维 union、union-min 归一化与 placement，同时让非零 rotation、资源依赖
HUG 与 tolerance-dependent 分配继续 fail closed？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；复用同一次 admission/preflight、authored preorder
   与全有或全无输出，不新增 parser、crate、route、Profile 或 daemon success path。
2. **只开放 zero-rotation Group intrinsic**：非空 Group 的两轴仍只允许 T25 已冻结的 `HUG_CONTENT`；每个
   direct ABSOLUTE child 在当前轴必须为 FIXED，或其 HUG 可由 T42/T43/T45/T49/本票递归规则独立测量。
   Group direct child FILL 继续由 preflight 拒绝；Text/Image、compositionViewport 与 Leaf HUG 不进入本票。
3. **复用仿射区间而非 PaintBounds**：把 T49 的固定 binary64 计算扩为 transformed axis interval：按
   `originRatio × size` → `position + originOffset` → transformed near/far，返回
   `[min(near, far), max(near, far)]`。只接受 `rotationDeg == 0`，scale 可为任意非零值（含 flip）；所有中间值
   finite-check，不做 FMA、epsilon、tolerance 或中间量化。
4. **Group union 与自然尺寸**：非空 Group 每轴以第一个 direct child interval 初始化 union，随后按 authored
   child order 更新 min/max；不注入 Frame 的稳定零原点。该轴自然尺寸固定为 `unionMax - unionMin`，结果必须
   finite 且非负；空 Group 继续复用 T42 的 `0 × 0`。Group 没有 padding、stroke、ContentBox 或 min/max clamp。
5. **union-min 归一化只影响派生布局**：Group 的 LayoutBox 左上角仍由 owning placement 决定；direct child
   arrange 使用固定顺序 `normalizedParentOrigin = groupLayoutOrigin - unionMin`，再由既有 ABSOLUTE writer 执行
   `normalizedParentOrigin + authoredPosition`。这样 direct child transformed union 的左上角恰好落到 Group
   LayoutBox 原点；Group 自身 transform 不反馈自身 intrinsic 或 normalization，Engine 不写回 authored placement。
6. **递归与复用**：支持资源无关 nested Group，以及 Group 被 Frame/Stack/Grid HUG、Grid AUTO contribution
   或普通 definite placement 消费；继续复用同一 occurrence preorder 与唯一 geometry writer，不建立第二套 box tree。
7. **稳定 unsupported 边界**：实际参与 Group union 的任一 direct child `rotationDeg != 0` 时，在该 child 返回
   既有 closed internal `CHILD_ROTATION`。resource-dependent kind 与 compositionViewport 仍先按 authored child
   role fail closed；普通 Stack/Grid sibling 分配中不参与 Group/Frame HUG 的 transform 行为不变。
8. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/13`；先把既有 nonempty Group case 转成
   positive，并覆盖 signed/all-negative union、多 child gap、origin/positive-negative scale、nested Group、
   Group→Frame/Stack/Grid consumption、own transform non-feedback 与 nonzero child rotation。Rust primary 与
   Python stdlib independent verifier 必须同时先 RED，再分别实现语义；fixture bytes 不变时保持 fixture `/3`。
9. **诚实能力边界**：任意非零 child rotation、Text/Image/Vector intrinsic、multiple Stack FILL、跨多 AUTO
   平均、multiple FRACTION、actual resource fetch/decode、world transform/scene、paint/raster/JPEG、daemon RESULT、
   Profile registration、公开 preview/E6、formal records 与物理 Linux/J1/A3 均不在本票。

## 验证与完成信号

- 局部：逐 slice RED→GREEN；focused Cargo vector tests + Python stdlib independent replay；workspace fmt/clippy
  `-D warnings`/test、`py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → `server`/`fast` → 完整 `full`；证据按局部到 Goal 扩大。
- 保证上限：Rust/kernel/gate A1，zero-rotation affine Group union/normalization exact binary64 shared vectors 的
  Rust+Python replay A2；不证明任意 rotation tolerance、完整 Layout/Renderer、scene/pixel、daemon output、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- 已在既有 `renderweave-renderer-layout` deep module 内实现 zero-rotation affine 非空 Group HUG：对
  resource-free direct ABSOLUTE child 计算 transformed axis interval，按 authored order 从首个 child 建立二维
  union，并以 `groupLayoutOrigin - unionMin` 归一化派生 child layout；Group 自身 transform 不反馈 intrinsic。
- shared definite-layout vector/verifier identity 升级为 `/13`：64 个 laid-out cases + 13 个稳定 unsupported
  cases，共 77/77、232 checks；vector SHA-256
  `423db2e4c40095887e6be25ac921b449ec96a2105a9fa2a5be14e986288eb6d6`。Rust primary 与 Python stdlib
  independent replay 均通过，非零 direct-child rotation 稳定以 closed internal `CHILD_ROTATION` fail closed。
- 分级 gate 全绿：`render` `.sdlc/evidence/20260822-123909-render/`、`server`
  `.sdlc/evidence/20260822-123938-server/`、受影响 `fast` `.sdlc/evidence/20260822-130014-fast/` 与 Goal 级
  `full` `.sdlc/evidence/20260822-130154-full/`，以及治理后的 final `fast`
  `.sdlc/evidence/20260822-133232-fast/`。
- 生命周期为 `resolved / automated_verified`，不是 accepted/READY。Profile 仍为 NOT_REGISTERED、certification
  NOT_CERTIFIED、resource bytes UNFETCHED、world scene/raster ABSENT、daemon output UNWIRED；任意非零 rotation、
  resource fetch/decode、multiple FILL/FRACTION、跨多 AUTO 平均与 tolerance-dependent 语义仍未实现。
- 无 migration、Java/OpenAPI/Web/route 或外部持久副作用；provider attempts、API Key reads、真实数据和付费调用
  均为 0；未 push/tag/PR。rollback 边界为本票单一 verified local commit。
