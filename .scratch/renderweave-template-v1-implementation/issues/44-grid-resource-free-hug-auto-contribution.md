# 实现 definite Grid 资源无关 HUG child AUTO contribution 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 34, 40, 41, 42, 43（均已 resolved）

## Question

在 `renderweave-layout/1.0` 的 residual tolerance、direct-child transform union、Text/Image/Vector
measurement 与 scene/raster 尚未物化时，如何让 definite Grid 的 AUTO track 消费 T42/T43 已可独立测得的
资源无关 HUG child contribution，同时让跨多个 AUTO 的平均 deficit、非空 Frame/Grid/Group HUG、资源依赖
HUG 与 multiple FILL/FRACTION 继续 fail closed？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；复用同一次 admission/preflight、authored preorder、
   FIXED → AUTO → FRACTION 与 columns-first，不新增 parser、crate、route、Profile 或 daemon success path。
2. **只开放可独立测量的 HUG contribution**：definite Grid 某轴的 AUTO constraint 遇到 non-FILL child 时，
   FIXED 继续读取 authored size；HUG 只可调用 T42 空容器或 T43 递归 Stack intrinsic。非空 Frame/Grid/Group、
   Leaf/Text/Image/Vector、compositionViewport 与任何其他 unsupported measurement 原样传播并全有或全无失败。
3. **贡献与边距顺序不变**：先得到 child 的该轴 resource-free intrinsic，再按固定 binary64 顺序执行
   `size + leading signed margin + trailing signed margin`，最后取 `max(0, ...)`；不因 HUG 引入 offer 猜测、
   transform、epsilon、FMA 或中间量化。
4. **只复用 independent AUTO closure**：每条 constraint 的 span 至多覆盖一个 AUTO；继续按
   `(spanLength,startIndex,materializedOrder)` 稳定排序，扣除已结算 FIXED/AUTO track 与内部 gap 后，只让该
   AUTO 接收正 deficit。跨多个 AUTO 仍返回 `GRID_AUTO_TRACK`，不实现平均分配或 residual tolerance。
5. **阶段与错误顺序**：完整 track scan 后严格执行 FIXED → AUTO → singleton FRACTION；columns 在 rows 前。
   HUG child 的 unsupported occurrence 按 authored constraint scan 传播；多 AUTO span 仍在测量前由 owning Grid
   fail closed，既有 multiple FRACTION 与 DFS first-error 纪律不变。
6. **arrange 复用**：AUTO sizes 求得后继续走既有 GRID cell/span/margin/alignment/FILL 与递归 emit；本票不开放
   owning Grid 的非空 HUG axis，也不改变 Stack/Frame/Group intrinsic 或 child transform 对布局的规则。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/10`，先把既有
   `grid-auto-track-hug-child-contribution` 与新增 empty/recursive Stack、signed-margin/multi-track case 写成
   Rust/Python 同时 RED，再独立实现；fixture bytes 不变时保持 fixture identity `/3`。
8. **诚实能力边界**：非空 Frame/Grid/Group HUG、direct-child transform union、跨多 AUTO 平均、multiple
   Stack FILL/FRACTION、Text/Image/Vector/resource measurement、world scene、paint/raster/JPEG、daemon RESULT、
   Profile registration、公开 preview/render 与 Editor E6 均不在本票。

## 验证与完成信号

- 局部：focused Cargo vectors + Python stdlib independent verifier → workspace fmt/clippy `-D warnings`/test、
  `py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → `server`/`fast` → 完整 `full`；证据按局部到 Goal 扩大，输入未变可复用绿色证据。
- 保证上限：Rust/kernel/gate A1，Rust+Python exact binary64 replay A2；不证明完整 Layout Profile、resource、
  scene/pixel、daemon output、physical Linux certification、A3 或 J1。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；Profile
  持续 NOT_REGISTERED、certification NOT_CERTIFIED、world scene/raster ABSENT、daemon output UNWIRED；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `layout_definite_resource_free` 的 independent Grid AUTO solver 现在可消费 T42 空容器或 T43 递归 Stack 的
  resource-free HUG intrinsic；贡献仍以 `size + leading signed margin + trailing signed margin` 的固定 binary64
  顺序求值并取非负值，constraint 排序、正 deficit、columns-first 与 FIXED → AUTO → singleton FRACTION 不变。
- 每条 constraint 仍至多跨一个 AUTO；跨多个 AUTO、非空 Frame/Grid/Group HUG、transform union、resource
  measurement、multiple FILL/FRACTION 与 scene/raster/daemon output 继续 fail closed，unsupported occurrence
  仍按既有 authored scan/DFS 纪律传播。
- immutable vector/verifier identity 升级为 `/10`，fixture identity 保持 `/3`；Rust primary 与 Python stdlib
  independent replay 覆盖 48 laid-out + 13 unsupported，61/61、184 checks。vector SHA-256 为
  `9b0b94366bd160ae89525f6dc180196e7c495c7dee245c1815754f6871a562a5`，fixture SHA-256 为
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1/A2 证据：`render` `.sdlc/evidence/20260822-040342-render/`、`server`
  `.sdlc/evidence/20260822-040418-server/`、治理前 `fast` `.sdlc/evidence/20260822-042308-fast/`；resolution
  governance 后的最终 Fast/Full 目录按不可自指策略只在 commit handoff 报告。
- 生命周期为 `resolved / automated_verified`，不外推完整 Layout/Renderer/Profile/Template v1 READY、physical
  Linux certification、A3 或 J1。Provider attempts/API Key reads/paid external calls 均为 0；未发送真实数据，
  未 push/tag/PR。
