# 实现 definite Grid 单 FRACTION 轨道子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 34, 38（均已 resolved）

## Question

如何在 `renderweave-layout/1.0` 的多 FRACTION residual tolerance 仍无可执行数值、AUTO/HUG 又依赖资源测量时，
继续深化 T34/T38 的真实资源无关 layout kernel：精确支持 definite Grid 每轴至多一个 FRACTION track，并让
AUTO、多 FRACTION、资源、scene 与 daemon output 继续 fail closed，而不是猜测 tolerance 或暗示完整 Layout
Profile 已实现？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：仅扩展 workspace-internal `renderweave-renderer-layout` 与
   `layout_definite_resource_free(&AdmittedRenderDocument)`；继续复用同一次 document admission/preflight、
   authored-preorder 全有或全无输出，不新增 parser、crate、route、Profile 或 daemon success path。
2. **每轴单 FRACTION 退化闭包**：definite Grid 的每个轴只允许正值 FIXED tracks，或在其中至多包含一个正
   `weight` FRACTION track；不允许 AUTO。单 FRACTION 的 weight 在冻结比例公式中相消，其尺寸严格为
   `max(0, available - fixedSizes - allDeclaredAdjacentGaps)`，按 binary64 固定顺序求值，不做 FMA、fast-math、
   中间量化或 tolerance 判断。
3. **保持阶段与错误顺序**：每轴严格按 FIXED → AUTO → FRACTION 阶段判定；同一轴即使 authored FRACTION
   早于 AUTO，也先返回 `GRID_AUTO_TRACK`。无 AUTO 后，两个及以上 FRACTION 返回 `GRID_FRACTION_TRACK`。
   columns-first 保持不变；任一失败不返回 partial layout。
4. **零剩余与物理排列**：FIXED sizes 加全部相邻 gaps 已占满或超出 available 时，唯一 FRACTION 为正零；
   track origin 仍按 authored order 排列，gap 不因零宽/高轨道消失。没有 FRACTION 的额外空间继续留在物理右/下端。
5. **复用既有 GRID child arrange**：cell/span、signed margins、FIXED/FILL、min/max、alignment、overlap 与
   Frame/Stack/Grid 递归语义均保持 T34/T38 行为；child min 不扩张 FIXED/FRACTION track。
6. **诚实能力边界**：AUTO、每轴多个 FRACTION、HUG、Group/compositionViewport、Text/Image、resource
   fetch/decode/font shaping、world transform/scene、paint/raster/JPEG/RESULT、Profile registration 与公开
   render/preview 均不在本票。

## 验证与完成信号

- TDD：先把 immutable vector/verifier identity 升级为 `/5`，加入双轴 singleton FRACTION、overflow-to-zero、
  AUTO-before-FRACTION 阶段顺序与 multiple-FRACTION columns-first unsupported cases，使 Rust primary 与 Python
  independent replay 同时 RED；再实现两端算法。既有 fixture bytes 不变时保持 fixture identity `/3`。
- 局部：focused Cargo test + Python stdlib independent verifier → workspace fmt/clippy `-D warnings`/test。
- 受影响：`render` → `server`/`fast` → 完整 `full`；证据按局部到 Goal 扩大。
- 保证上限：Rust/kernel/gate A1、Rust+Python exact binary64 replay A2；不证明 AUTO、多 FRACTION、HUG、完整
  Layout Profile、scene/pixel、daemon output、物理 Linux certification、A3 或 J1。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；Profile 持续
  NOT_REGISTERED、certification NOT_CERTIFIED、world scene/raster ABSENT、daemon output UNWIRED；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `layout_definite_resource_free` 已在每个 definite Grid 轴上支持至多一个 FRACTION track：按固定 binary64
  顺序累计全部 FIXED sizes 与相邻 gaps，唯一 FRACTION 取得 `max(0, available-used)`；track origin 仍按 authored
  order 物理排列，零尺寸不吞掉 gap，既有 GRID child arrange 与递归语义保持不变。
- 每轴仍按 FIXED → AUTO → FRACTION 判定：AUTO 即使 authored 较晚也先返回 `GRID_AUTO_TRACK`；无 AUTO 后，
  多个 FRACTION 返回 `GRID_FRACTION_TRACK`。columns-first、全有或全无输出、DFS first error 均保持不变；HUG、
  resource、scene/raster/RESULT 与 Profile registration 未被打开。
- immutable vector/verifier identity 升级为 `/5`，fixture identity 保持 `/3`；Rust primary 与 Python stdlib
  independent replay 覆盖 30 laid-out + 12 unsupported，42/42、128 checks。vector SHA-256 为
  `6e2c08f42b0aa7bc237dd9ad5d24a53584a59489fa432ed75192e8ceca99edfb`，fixture SHA-256 为
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1/A2 证据：最终一致的 `render` `.sdlc/evidence/20260821-215633-render/`、`server`
  `.sdlc/evidence/20260821-215659-server/`、治理前 `fast` `.sdlc/evidence/20260821-221708-fast/`；resolution governance
  后的最终 Fast/Full 目录按不可自指策略只在 commit handoff 报告。
- 生命周期为 `resolved / automated_verified`，不外推 AUTO、multiple-FRACTION residual distribution、完整
  Layout/Renderer/Profile/Template v1 READY、physical Linux certification、A3 或 J1。Provider attempts/API Key
  reads/open authorization/paid external calls 均为 0；未发送真实数据，未 push/tag/PR。
