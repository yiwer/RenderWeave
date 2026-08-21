# 实现资源无关非空 Stack HUG intrinsic 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 34, 38, 42（均已 resolved）

## Question

在 `renderweave-layout/1.0` 的 residual tolerance、direct-child transform union、Text/Image/Vector
measurement 与 scene/raster 尚未物化时，如何继续深化同一个 Rust layout deep module：只实现非空 Stack
可由资源无关 child measurement 精确求得的 HUG intrinsic，同时让非空 Frame/Grid/Group、资源依赖 HUG、
multiple FILL/FRACTION 与跨多个 AUTO 的平均 deficit 继续 fail closed？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；复用同一次 document admission/preflight、authored
   preorder 全有或全无输出，不新增 parser、crate、route、Profile 或 daemon success path。
2. **只开放 Stack intrinsic**：非空 Stack 的某个 HUG 轴，只有当每个 direct STACK child 在该轴为 FIXED，
   或其 HUG 可由 T42 空容器规则/本票递归 Stack 规则独立测量时才成功。父 HUG/子 FILL cycle 已由 T25
   preflight 拒绝；非空 Frame/Grid/Group、Leaf HUG、Text/Image/Vector 与 compositionViewport 不进入本票。
3. **主轴精确自然尺寸**：ROW/width 或 COLUMN/height 的 HUG content extent 从稳定 origin `0` 开始，按
   authored child order 逐项执行 leading signed margin → child size → trailing signed margin → 非末项固定 gap；
   每次加法后更新最远正端，负向 cursor 只形成 overflow。禁止 FILL、水位、epsilon、FMA 或 tolerance。
4. **交叉轴精确自然尺寸**：ROW/height 或 COLUMN/width 的每个 direct child 形成
   `leading signed margin + measured child size + trailing signed margin` 的 MarginExtent；按 authored order 取
   `max(0, farthestEnd)`。Stack child transform 按冻结规格不影响 parent HUG、分配或 sibling 位置。
5. **outer box 与约束顺序**：content extent 后固定执行 leading padding → trailing padding → leading inward
   stroke → trailing inward stroke，再只对该 HUG 轴按 min→max clamp。另一轴继续既有 FIXED/FILL/已支持 HUG
   语义；最终 ContentBox 仍按 stroke 后 padding 逐项 floor-zero。
6. **递归与安排复用**：HUG child 仅可递归到满足同一闭包的 Stack，或 T42 已支持的空
   Frame/Stack/Grid/Group；FIXED child 可为现有资源无关 kind。得到 parent definite outer size 后，继续复用既有
   Stack cursor、justifyContent、alignSelf/alignItems、signed margins、gap 与嵌套 emit，不建立第二套 arrange。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/9`，覆盖 ROW/COLUMN 双轴 HUG、signed
   negative margin 的稳定 origin/最远正端、padding/stroke/min-max、单轴 HUG + 另一轴 definite、空容器 child
   与递归 nested Stack；先让 Rust primary 与 Python stdlib independent verifier 同时 RED，再实现两份独立语义。
   既有 fixture bytes不变时保持 fixture identity `/3`。
8. **诚实能力边界**：非空 Frame/Grid/Group HUG、direct-child transform union、Grid HUG AUTO contribution、
   Text/Image/Vector measurement、multiple Stack FILL、跨多 AUTO 平均、multiple FRACTION、world scene、paint/
   raster/JPEG、daemon RESULT、Profile registration 与公开 preview/render 均不在本票。

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

- `layout_definite_resource_free` 现在可在无需资源的闭包内测量非空 Stack HUG：主轴从 stable-zero cursor
  开始，按 leading signed margin → child size → trailing signed margin → 非末项 gap 逐项更新最远正端；交叉轴
  取每个 direct child MarginExtent 的最远正端，再统一加 padding/inward stroke 并执行 HUG min→max clamp。
- child measurement 只接受该轴 FIXED、T42 空容器 HUG 或递归 Stack HUG；父 HUG/子 FILL cycle 仍由 preflight
  拒绝。Stack transform 不反馈 intrinsic；非空 Frame/Grid/Group、Leaf/resource HUG、multiple FILL/FRACTION、
  跨多个 AUTO 平均与 scene/raster/daemon output 继续 fail closed。
- immutable vector/verifier identity 升级为 `/9`，fixture identity 保持 `/3`；Rust primary 与 Python stdlib
  independent replay 覆盖 46 laid-out + 13 unsupported，59/59、178 checks。vector SHA-256 为
  `da3d08b54943b6bb7707301a067e98688651a1adeda1524a2463be5dddeca6d2`，fixture SHA-256 为
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1/A2 证据：`render` `.sdlc/evidence/20260822-022705-render/`、`server`
  `.sdlc/evidence/20260822-022750-server/`、治理前 `fast` `.sdlc/evidence/20260822-024756-fast/`；resolution
  governance 后的最终 Fast/Full 目录按不可自指策略只在 commit handoff 报告。
- 生命周期为 `resolved / automated_verified`，不外推完整 Layout/Renderer/Profile/Template v1 READY、physical
  Linux certification、A3 或 J1。Provider attempts/API Key reads/paid external calls 均为 0；未发送真实数据，
  未 push/tag/PR。
