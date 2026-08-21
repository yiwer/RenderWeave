# 实现资源无关非空 Grid HUG intrinsic 子闭包

Type: task
Status: resolved
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 34, 40, 41, 42, 43, 44（均已 resolved）

## Question

在 `renderweave-layout/1.0` 的 residual tolerance、direct-child transform union、Text/Image/Vector
measurement 与 scene/raster 尚未物化时，如何继续深化同一个 Rust layout deep module：让非空 Grid 的 HUG
轴消费已经可独立求解的 FIXED/independent AUTO tracks 与资源无关 child contribution，同时让 FRACTION-on-HUG、
跨多个 AUTO 的平均 deficit、非空 Frame/Group HUG、资源依赖 HUG 与 multiple FILL/FRACTION 继续 fail closed？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；复用同一次 admission/preflight、authored preorder、
   FIXED → AUTO → FRACTION 与 columns-first，不新增 parser、crate、route、Profile 或 daemon success path。
2. **只开放非空 Grid intrinsic**：Grid 某个 HUG 轴只能含 preflight 已允许的 FIXED/AUTO tracks；该轴存在
   FRACTION 时继续在 preflight 以 `LAYOUT_CONSTRAINT_INVALID` 拒绝。另一轴继续既有 definite 子闭包，不扩大
   multiple FRACTION、跨 AUTO 平均或资源测量能力。
3. **自然 content extent 精确求解**：HUG 轴完整扫描 authored tracks；FIXED 使用 authored value，AUTO 严格
   复用 T40/T41/T44 的 independent constraint solver。每条 child span 仍至多覆盖一个 AUTO，贡献只接受
   FIXED 或 T42/T43/本票递归可测得的 resource-free HUG，并按
   `(spanLength,startIndex,materializedOrder)` 稳定求正 deficit。随后按 authored track size → 相邻声明 gap
   的固定 binary64 顺序累加；不使用父级 offer、epsilon、FMA、residual tolerance 或中间量化。
4. **outer box 与 clamp 顺序不变**：content extent 后固定执行 leading padding → trailing padding → leading
   inward stroke → trailing inward stroke，再只对该 HUG 轴按 min→max clamp；ContentBox 仍按 stroke 后 padding
   逐项 floor-zero。
5. **递归边界**：HUG child 只可进入 T42 空容器、T43 resource-free Stack 或满足本票同一约束的非空 Grid。
   非空 Frame/Group、Leaf/Text/Image/Vector、compositionViewport 与任何 unsupported child measurement 原样传播，
   全有或全无失败；Grid AUTO 上的 child FILL cycle 仍由 T25 preflight 拒绝。
6. **arrange 只复用现有路径**：Grid outer size 变 definite 后，继续由既有 columns-first track solver 与
   cell/span/margin/alignment/FILL、nested emit 完成安排；不建立第二套 track allocation 或 geometry writer。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/11`，先覆盖 FIXED tracks+gaps+padding/stroke、
   both-axis HUG + AUTO/HUG contribution 与 recursive Grid HUG contribution，使 Rust/Python 同时 RED，再独立实现；
   fixture bytes 不变时保持 fixture identity `/3`。
8. **诚实能力边界**：非空 Frame/Group HUG、direct-child transform union、Text/Image/Vector/resource measurement、
   multiple Stack FILL、跨多 AUTO 平均、multiple FRACTION、world scene、paint/raster/JPEG、daemon RESULT、
   Profile registration、公开 preview/render 与 Editor E6 均不在本票。

## 验证与完成信号

- 局部：focused Cargo vectors + Python stdlib independent verifier → workspace fmt/clippy `-D warnings`/test、
  `py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → `server`/`fast` → 完整 `full`；证据按局部到 Goal 扩大，输入未变可复用最近绿色证据。
- 保证上限：Rust/kernel/gate A1，Rust+Python exact binary64 replay A2；不证明完整 Layout Profile、resource、
  scene/pixel、daemon output、physical Linux certification、A3 或 J1。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；Profile
  持续 NOT_REGISTERED、certification NOT_CERTIFIED、world scene/raster ABSENT、daemon output UNWIRED；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `layout_definite_resource_free` 的资源无关 HUG 测量现接受非空 Grid；每个 HUG 轴复用同一 authored
  FIXED → independent AUTO track solver，并按 track size → 相邻 gap 的固定 binary64 顺序求 content extent，
  再沿用 padding → inward stroke → min→max 得到 outer size。Grid arrange 仍只走既有 cell/span 路径。
- T42 空容器、T43 Stack 与本票 Grid 可递归提供 resource-free HUG contribution；FRACTION-on-HUG 仍由
  preflight 拒绝，跨多个 AUTO 的 deficit 平均、非空 Frame/Group、resource/transform、multiple
  FILL/FRACTION、scene/raster/daemon output 继续 fail closed。
- immutable vector/verifier identity 升级为 `/11`，fixture identity 保持 `/3`；Rust primary 与 Python stdlib
  independent replay 覆盖 51 laid-out + 13 unsupported，64/64、193 checks。vector SHA-256 为
  `6d60decd4955ba2dad26c7bf827169145ee9cd4cb9424af5996d27c72ac53530`，fixture SHA-256 为
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1/A2 证据：`render` `.sdlc/evidence/20260822-050553-render/`、`server`
  `.sdlc/evidence/20260822-050639-server/`、治理前 `fast` `.sdlc/evidence/20260822-052538-fast/`；resolution
  governance 后的最终 Fast/Full 目录按不可自指策略只在 commit handoff 报告。
- 生命周期为 `resolved / automated_verified`，不外推完整 Layout/Renderer/Profile/Template v1 READY、physical
  Linux certification、A3 或 J1。Provider attempts/API Key reads/paid external calls 均为 0；未发送真实数据，
  未 push/tag/PR。
