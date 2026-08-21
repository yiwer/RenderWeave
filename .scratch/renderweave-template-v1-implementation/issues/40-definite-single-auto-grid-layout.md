# 实现 definite Grid 单 AUTO 轨道的资源无关贡献

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 34, 39（均已 resolved）

## Question

如何在 `renderweave-layout/1.0` 的一般 intrinsic/resource measurement 尚未实现、多个 AUTO 的跨轨 deficit
分配与多 FRACTION residual tolerance 仍不进入当前闭包时，继续深化 T39 的 definite Grid solver：精确支持
每轴至多一个 AUTO track，并只接受能够从资源无关 FIXED child 尺寸确定的 contribution；同时让 HUG、多个
AUTO、多个 FRACTION、资源、scene 与 daemon output 继续 fail closed，而不是把固定尺寸贡献误判为必须等待
完整 measure/shaping？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；复用同一次 document admission/preflight、
   authored-preorder 全有或全无输出，不新增 parser、crate、route、Profile 或 daemon success path。
2. **每轴 singleton AUTO 子闭包**：definite Grid 每轴允许正值 FIXED、至多一个 AUTO、至多一个正 weight
   FRACTION，authored order 不受限制。两个及以上 AUTO 仍返回 `GRID_AUTO_TRACK`；两个及以上 FRACTION 仍返回
   `GRID_FRACTION_TRACK`。
3. **资源无关 contribution**：只有 span 覆盖该 AUTO 的 direct GRID child 参与该轴求解；该轴必须是
   `FIXED`，其 contribution 为 `max(0, fixedSize + leadingMargin + trailingMargin)`。单轨 contribution 取
   authored-order max；跨多轨约束按冻结的 `(spanLength,startIndex,materializedOrder)` 顺序处理，先扣跨度内
   FIXED sizes 与内部 gaps，FRACTION 在 AUTO 阶段为零，唯一 AUTO 接收正 deficit。无 contribution 的 AUTO
   为正零。HUG/intrinsic contribution 继续 `HUG_CONTENT` fail closed；跨 AUTO 的 FILL cycle 继续由 T25
   preflight 拒绝。
4. **保持阶段与排列顺序**：每轴严格 FIXED → AUTO → FRACTION，columns-first；AUTO 结算后，T39 singleton
   FRACTION 才取得 `max(0, available - fixed - auto - allDeclaredAdjacentGaps)`。track origin 仍按 authored
   order；零尺寸 AUTO/FRACTION 都不吞掉相邻 gap。
5. **复用既有 child arrange**：cell/span、signed margins、FIXED/FILL、min/max、alignment、overlap 与
   Frame/Stack/Grid 递归语义均保持不变；child min 不扩大 FIXED/FRACTION track，AUTO 只由上述 contribution
   增长。
6. **诚实能力边界**：多个 AUTO、HUG/资源 intrinsic、多个 FRACTION、multiple Stack FILL、Group/
   compositionViewport、Text/Image resource path、resource fetch/decode/font shaping、world transform/scene、
   paint/raster/JPEG/RESULT、Profile registration 与公开 render/preview 均不在本票。

## 验证与完成信号

- TDD：先把 immutable vector/verifier identity 升级为 `/6`；把空 AUTO 与 singleton FRACTION + AUTO row
  转为 laid-out expectations，并加入 signed-margin/multi-track singleton AUTO、multiple-AUTO stage 与
  HUG-contribution unsupported cases，使 Rust primary 与 Python independent replay 同时 RED；既有 fixture
  bytes 不变时保持 fixture identity `/3`。
- 局部：focused Cargo test + Python stdlib independent verifier → workspace fmt/clippy `-D warnings`/test。
- 受影响：`render` → `server`/`fast` → 完整 `full`；证据按局部到 Goal 扩大。
- 保证上限：Rust/kernel/gate A1、Rust+Python exact binary64 replay A2；不证明一般 AUTO/intrinsic、完整
  Layout Profile、scene/pixel、daemon output、物理 Linux certification、A3 或 J1。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；Profile
  持续 NOT_REGISTERED、certification NOT_CERTIFIED、world scene/raster ABSENT、daemon output UNWIRED；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `layout_definite_resource_free` 已在 definite Grid 每轴支持至多一个 AUTO，并允许与至多一个 singleton
  FRACTION 组合。AUTO 只读取 span 覆盖该轨的 direct GRID child 的 FIXED axis size 与 signed margins；约束按
  `(spanLength,startIndex,materializedOrder)` 排序，先扣跨度内已结算 tracks 与内部 gaps，唯一 AUTO 单调接收正
  deficit；空 AUTO 与无正 contribution AUTO 均保持正零。
- 每轴仍严格按 FIXED → AUTO → FRACTION，且 columns-first；AUTO 结算后 singleton FRACTION 才取得剩余空间。
  多 AUTO 继续 `GRID_AUTO_TRACK`，HUG contribution 精确定位 child 并返回 `HUG_CONTENT`，多 FRACTION 继续
  `GRID_FRACTION_TRACK`。authored origins、零尺寸相邻 gaps、GRID arrange/递归与全有或全无输出保持不变。
- immutable vector/verifier identity 升级为 `/6`，fixture identity 保持 `/3`；Rust primary 与 Python stdlib
  independent replay 覆盖 33 laid-out + 12 unsupported，45/45、137 checks。vector SHA-256 为
  `b54a2ac0795be29c7ac22764f7765cca52273b74af33de715e1dbeb61e9aac29`，fixture SHA-256 为
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1/A2 证据：`render` `.sdlc/evidence/20260821-230451-render/`、`server`
  `.sdlc/evidence/20260821-230527-server/`、治理前 `fast` `.sdlc/evidence/20260821-232407-fast/`；resolution
  governance 后的最终 Fast/Full 目录按不可自指策略只在 commit handoff 报告。
- 生命周期为 `resolved / automated_verified`，不外推一般 intrinsic/HUG、multiple AUTO/FRACTION、完整
  Layout/Renderer/Profile/Template v1 READY、scene/raster/daemon RESULT、physical Linux certification、A3 或
  J1。Provider attempts/API Key reads/open authorization/paid external calls 均为 0；未发送真实数据，未
  push/tag/PR。
