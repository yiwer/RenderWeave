# 实现 Layout Profile 静态可判定预检内核

Type: task
Status: resolved / automated_verified
Resolved by: Codex `/root`（single-writer）
Blocked by: 22, 23（均已 resolved：真实 daemon seam 与 exact RenderDocument admission）

## Question

如何在不发明 Layout tolerance、不提前实现 measure/arrange、也不注册 Renderer Profile 的前提下，把冻结
Ticket 10 要求 Engine 对每个 concrete occurrence 重做的结构与可判定布局约束物化为独立 Rust deep kernel：
只消费 T23 已准入的 immutable RenderDocument，拒绝 size/min-max、HUG/FILL、Stack/Grid、Text 与 QR 的
静态矛盾，产出有界摘要，并让 Rust primary 与 Python independent verifier 重放同一语料？

## Answer（本票冻结的实施决定）

1. **deep kernel seam**：新增 workspace-internal `renderweave-renderer-layout` crate；唯一入口消费
   `AdmittedRenderDocument`，返回 immutable `LayoutPreflight` 或 closed `LayoutProblem`。内部可重解析其 canonical
   bytes，但调用方不能绕过 T23 admission。结果只含 occurrence/edge/Grid/track/cell/depth 等计数，不含 box、
   glyph、resource、paint item、trace 或伪造 scene。
2. **静态可判定约束**：按 materialized authored DFS 稳定顺序验证 `renderweave-layout/1.0` 的 kind/size-mode
   capability、FIXED 正值与 min/max、Image 双 HUG、Group 双 HUG、Frame/Group/Stack 的 HUG↔child FILL cycle、
   Stack direction/main-axis `fillWeight`、Grid row/column/span 边界、每轴最多 64 tracks、HUG 轴 FRACTION、
   FILL child 跨 AUTO track、FIXED/FRACTION 正值、`VISIBLE + maxLines`，以及双 FIXED QR 的 exact square。
   只有依赖 final measure/arrange 才能决定的 QR/FILL、transform bounds 与 overflow 留给后续真实 layout。
3. **exact numeric boundary**：本票只比较 T23 lowering 应产生的 canonical plain decimal6，使用 checked
   decimal6 定点而非 binary float；非 plain、超过六位、溢出或约束比较不可表示均为
   `LAYOUT_NUMERIC_ERROR`。不选择、实现或暗示 Ticket 10 尚未给出数值的 Profile tolerance。
4. **problem 与 daemon 接线**：kernel 内部 stable code 仅为
   `LAYOUT_CONSTRAINT_INVALID | LAYOUT_CYCLE | LAYOUT_NUMERIC_ERROR | LAYOUT_BUDGET_EXCEEDED`，绑定 stable
   occurrence/property，capacity 只公开 closed `limitId`。daemon 在 strict RenderDocument admission 后调用该
   preflight；由于这些输入理论上已被 Java authority 保证，失败仍按 malformed sealed invariant 折叠为
   `RENDER_INTERNAL_ERROR`/`DOCUMENT_ADMISSION`，零 resource/layout/output；通过后仍进入现有空 Profile
   registry 的 terminal fail-closed 路径，不产生 RESULT。
5. **共同语料与 TDD**：新增 closed vector manifest；每个 negative mutation 必须先证明仍通过 T23 document
   admission，再由 layout preflight 精确拒绝。至少覆盖上述每族约束、stable DFS first problem、sourceCanvas
   traversal 与 64/65 track boundary。先让缺位 crate/API/vector verifier RED，再实现 Rust；Python 标准库独立
   解析、遍历、decimal6 比较和重放，不调用 Rust 或共享 semantic helper。
6. **明确排除**：本票不做 binary64 measure/arrange、IntrinsicSize、Stack water filling、Grid track allocation、
   Text shaping/reflow、resource fetch/decode、font/image/vector intrinsic bounds、transform/clip/paint/raster、
   PNG/JPEG、QR/Barcode 编码或 final-device constraints；不做 daemon success/RESULT、公开 render/preview、
   OpenAPI/Web/Editor、Profile/capability registration、formal Case/Oracle、physical Linux CPU-family certification、
   J1/A3/READY、provider/真实数据/API Key。

## 验证与完成信号

- 局部：Rust crate focused tests（先 RED）+ Python independent verifier → workspace fmt/clippy/test。
- 受影响：`render` → `server`/`fast` → 完整 `full`，按局部到 Goal 扩大并保留原始 evidence。
- 保证上限：Rust/kernel/daemon/gate 为 A1；Rust 与独立 Python 对共同 exact preflight vectors 为 A2；
  Windows 与 no-network Docker 不构成物理 Linux certification，无 A3/J1。
- 完成：Ticket 25 仅在全部 gate 绿色后改为 `resolved / automated_verified`，形成一个 verified local commit、
  worktree clean；不 push/tag/PR，且 Profile 持续 NOT_REGISTERED、certification NOT_CERTIFIED、raster ABSENT、
  daemon output UNWIRED。

## Resolution（2026-08-21）

1. 新增 workspace-internal `renderweave-renderer-layout` crate；唯一 public seam
   `preflight_layout(&AdmittedRenderDocument)` 在 T23 strict admission 后重解析 canonical document，按 authored
   DFS 返回 immutable occurrence/edge/depth/Grid/track/cell counts 或首个 closed `LayoutProblem`，从未构造
   box、scene、resource、glyph、paint item 或 trace。
2. Rust kernel 已以 checked decimal6/`i128` 物化 frozen kind/mode、FIXED/min-max、Image/Group、
   Frame/Group/Stack HUG→FILL cycle、Stack fillWeight、Grid range/track/HUG-FRACTION/FILL-AUTO、Text
   `VISIBLE + maxLines` 与双 FIXED QR exact-square 预检；每轴 64 tracks 接受、65 tracks 以 closed
   `designDsl.gridTracksPerAxis` limit 拒绝。
3. 共享语料包含 7 positive + 25 negative；所有 negative 在 Rust vector test 中先通过 T23 document admission，
   再由 T25 精确拒绝。语料显式锁定 64/65 boundary、sourceCanvas traversal 与两个独立错误时 authored DFS
   首错；Python stdlib independent verifier 32/32、77 checks，向量 SHA-256 为
   `8ed92c072917d94d00370c1dc3536548aa074d007b1145ea152dceb44fb8aa64`。
4. daemon 已在 document admission 后、Profile lookup 前调用 preflight；布局不变量失败折叠为
   `RENDER_INTERNAL_ERROR`/`DOCUMENT_ADMISSION`，通过后仍走空 Profile registry 的
   `COMMAND_ADMISSION` terminal problem，不产生 RESULT。`render` gate 1.2 已硬断言该边界并通过于
   `.sdlc/evidence/20260821-042237-render/`。
5. 受影响 `server` 与 `fast` 分别通过于 `.sdlc/evidence/20260821-035919-server/`、
   `.sdlc/evidence/20260821-041051-fast/`；最终整树 `full` 在本 Resolution 冻结后捕获，目录只在提交交接中
   报告以避免证据自指。
6. Cargo.lock/process manifest/HELLO SHA 链已同步，但 capability inventory 未增加；`rendererProfiles:[]`、
   `NOT_REGISTERED`、`NOT_CERTIFIED`、raster `ABSENT`、daemon output `UNWIRED` 继续由 gate 硬断言。没有
   measure/arrange/resource/shaping/raster/codec/公开 route/formal record/physical certification/J1/A3/provider/
   真实数据/API Key 副作用。
