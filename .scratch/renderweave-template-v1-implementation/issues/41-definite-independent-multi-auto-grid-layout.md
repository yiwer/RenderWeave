# 实现 definite Grid 多 AUTO 的独立约束子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 34, 40（均已 resolved）

## Question

如何在 `renderweave-layout/1.0` 的一般 intrinsic/resource measurement、跨多个 AUTO track 的平均 deficit
分配与 Profile residual tolerance 尚未进入实现闭包时，继续深化 T40 的 definite Grid solver：允许同一轴存在
多个 AUTO track，但只执行每条 span constraint 至多覆盖一个 AUTO 的资源无关 FIXED-child 子问题；同时让跨
多个 AUTO、HUG、multiple FRACTION、资源、scene 与 daemon output 继续 fail closed？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；复用同一次 document admission/preflight、
   authored-preorder 全有或全无输出，不新增 parser、crate、route、Profile 或 daemon success path。
2. **多 AUTO、独立 constraint 子闭包**：definite Grid 每轴允许任意已受 T25 `64` track 上限约束的 AUTO，
   但每条参与该轴 AUTO 求解的 direct GRID child span 最多覆盖一个 AUTO。覆盖两个或以上 AUTO 的 constraint
   继续返回 closed internal `GRID_AUTO_TRACK`，不执行跨 AUTO 平均、不选择 residual tolerance。
3. **资源无关 contribution**：覆盖恰好一个 AUTO 的 child 在该轴必须为 `FIXED`；contribution 仍为
   `max(0, fixedSize + leadingMargin + trailingMargin)`。全部约束按冻结的
   `(spanLength,startIndex,materializedOrder)` 全局稳定排序；跨度内已结算 FIXED/AUTO 与内部 gaps 先计入
   occupied，FRACTION 在 AUTO 阶段保持零，唯一被覆盖的 AUTO 接收正 deficit。未被 constraint 覆盖的 AUTO
   保持正零；HUG/intrinsic contribution 继续定位实际 child 并以 `HUG_CONTENT` fail closed。
4. **保持阶段、轴与排列顺序**：每轴严格 FIXED → AUTO → FRACTION，columns-first；全部独立 AUTO 结算后，
   T39 singleton FRACTION 才取得正剩余。track origin 保持 authored order，零尺寸 AUTO/FRACTION 不吞掉相邻
   gap；跨任一 AUTO 的 FILL cycle 继续由 T25 preflight 拒绝。
5. **复用既有 child arrange**：cell/span、signed margins、FIXED/FILL、min/max、alignment、overlap 与
   Frame/Stack/Grid 递归语义不变；child min 不扩大 FIXED/FRACTION track，AUTO 只由上述 contribution 单调增长。
6. **诚实能力边界**：跨多个 AUTO 的平均 deficit、HUG/资源 intrinsic、多个 FRACTION、multiple Stack FILL、
   Group/compositionViewport、Text/Image resource path、resource fetch/decode/font shaping、world transform/scene、
   paint/raster/JPEG/RESULT、Profile registration 与公开 render/preview 均不在本票。

## 验证与完成信号

- TDD：先把 immutable vector/verifier identity 升级为 `/7`；加入多 AUTO 空轨 + 非首轨 FIXED/signed-margin
  contribution 的 laid-out case、跨两个 AUTO span 的 unsupported case，并把 columns-first 多 AUTO 后的 row
  multiple-FRACTION expectation 改为 `GRID_FRACTION_TRACK`，使 Rust primary 与 Python independent replay 同时
  RED；既有 fixture bytes 不变时保持 fixture identity `/3`。
- 局部：focused Cargo test + Python stdlib independent verifier → workspace fmt/clippy `-D warnings`/test。
- 受影响：`render` → `server`/`fast` → 完整 `full`；证据按局部到 Goal 扩大。
- 保证上限：Rust/kernel/gate A1、Rust+Python exact binary64 replay A2；不证明跨 AUTO 平均、一般
  AUTO/intrinsic、完整 Layout Profile、scene/pixel、daemon output、物理 Linux certification、A3 或 J1。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；Profile
  持续 NOT_REGISTERED、certification NOT_CERTIFIED、world scene/raster ABSENT、daemon output UNWIRED；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `layout_definite_resource_free` 已允许 definite Grid 每轴存在多个 AUTO。每条 direct GRID child constraint
  只要至多覆盖一个 AUTO，就按全局 `(spanLength,startIndex,materializedOrder)` 顺序结算；FIXED size 与
  signed margins 形成非负 contribution，跨度内已结算 tracks/gaps 先计入 occupied，目标 AUTO 只接收正
  deficit。未被约束覆盖的 AUTO 保持正零。
- 每轴仍严格按 FIXED → AUTO → FRACTION 且 columns-first；全部独立 AUTO 完成后才结算 singleton FRACTION。
  覆盖两个以上 AUTO 的 span 继续在 Grid occurrence 返回 `GRID_AUTO_TRACK`，HUG contribution 继续返回实际
  child 的 `HUG_CONTENT`，multiple FRACTION 继续 `GRID_FRACTION_TRACK`。
- immutable vector/verifier identity 升级为 `/7`，fixture identity 保持 `/3`；Rust primary 与 Python stdlib
  independent replay 覆盖 34 laid-out + 13 unsupported，47/47、142 checks。vector SHA-256 为
  `f1e02f5286fcad390b2c62a9e2899dfb9ac94668e9e1e73245217dfb8b3ccbfe`，fixture SHA-256 为
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1/A2 证据：`render` `.sdlc/evidence/20260822-001001-render/`、`server`
  `.sdlc/evidence/20260822-001028-server/`、治理前 `fast` `.sdlc/evidence/20260822-002942-fast/`；resolution
  governance 后的最终 Fast/Full 目录按不可自指策略只在 commit handoff 报告。
- 生命周期为 `resolved / automated_verified`，不外推跨多个 AUTO 的平均 deficit、一般 intrinsic/HUG、
  multiple FRACTION、完整 Layout/Renderer/Profile/Template v1 READY、scene/raster/daemon RESULT、physical
  Linux certification、A3 或 J1。Provider attempts/API Key reads/open authorization/paid external calls 均为
  0；未发送真实数据，未 push/tag/PR。
