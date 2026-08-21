# 实现 definite Stack 单主轴 FILL 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 34（均已 resolved）

## Question

如何在 `renderweave-layout/1.0` 的多 FILL weighted water-filling residual tolerance 仍无可执行数值时，继续深化
T33/T34 的真实资源无关 layout kernel：精确支持每个 definite Stack 中恰好一个 main-axis FILL child，并让
多个 main-axis FILL、HUG、资源、scene 与 daemon output 继续 fail closed，而不是猜测 tolerance 或暗示完整
Layout Profile 已实现？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：仅扩展 workspace-internal `renderweave-renderer-layout` 与
   `layout_definite_resource_free(&AdmittedRenderDocument)`；继续复用同一次 document admission/preflight、
   authored-preorder 全有或全无输出，不新增 parser、crate、route、Profile 或 daemon success path。
2. **精确退化子闭包**：一个 Stack 没有 main-axis FILL 时保持 T33 行为；恰好一个时，先按 authored order 从
   definite ContentBox 扣除固定 gap、全部 signed main margins 与其他 FIXED child size，得到
   `max(0, available - usedWithoutFill)`。唯一 FILL child 取得该值，再按既有顺序应用自身 min/max clamp。
   单 child 下 `fillWeight` 在冻结比例公式中相消，不参与额外舍入。
3. **clamp 后再 justify**：sole FILL 的 max clamp 留出的空间进入 T33 六种 `justifyContent`；min clamp 超出可用空间
   时允许 overflow，free space 为正零，不反向压缩。cross-axis FIXED/FILL、signed margin、alignment、ContentBox
   与 nested Frame/Stack/Grid 行为保持不变。
4. **多个 FILL 继续封闭**：同一 owning Stack 出现两个或以上 main-axis FILL 时仍返回 closed internal
   `STACK_MAIN_FILL`，定位到 authored preorder 的第一个 FILL occurrence；不实现 weight redistribution、bound
   freeze、residual tolerance 或借 singleton 结果宣称完整 water filling。
5. **错误顺序与诚实边界**：预量 sibling 不得改变第一个 authored DFS unsupported；任何失败不返回 partial layout。
   HUG、Grid AUTO/FRACTION、Group/compositionViewport、Text/Image、resource fetch/decode、world transform/scene、
   paint/raster/JPEG/RESULT、Profile registration 与公开 render/preview 均不在本票。

## 验证与完成信号

- TDD：先把 immutable vector identity 升级为 `/4`，把既有 singleton cases 改为 exact laid-out vectors，并增加
  row/column、min/max clamp、justify remainder、nested singleton 与 multiple-FILL first-occurrence case，使 Rust
  primary 与 Python independent replay 同时 RED；再实现两端算法。
- 局部：focused Cargo test + Python stdlib independent verifier → workspace fmt/clippy `-D warnings`/test。
- 受影响：`render` → `server`/`fast` → 完整 `full`；证据按局部到 Goal 扩大。
- 保证上限：Rust/kernel/gate A1、Rust+Python exact binary64 replay A2；不证明完整 water filling、HUG/Grid
  AUTO/FRACTION、scene/pixel、daemon output、物理 Linux certification、A3 或 J1。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；Profile 持续
  NOT_REGISTERED、certification NOT_CERTIFIED、world scene/raster ABSENT、daemon output UNWIRED；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `layout_definite_resource_free` 已在每个 definite Stack 中支持至多一个 main-axis FILL：先按固定顺序扣除
  materialized-adjacent gap、全部 signed main margins 与其他 definite child size，再以正剩余量 offer 唯一 FILL，
  最后复用既有 min/max clamp。max clamp 释放的空间进入 justify；min clamp 可 overflow 且 free 为正零。
- 多个 main-axis FILL 仍在第一个 authored FILL occurrence 返回 `STACK_MAIN_FILL`；sibling 预量不改变真实 authored
  DFS first error，任何失败均不返回 partial layout。HUG、AUTO/FRACTION、resource、scene/raster/RESULT 与 Profile
  registration 未被打开。
- immutable vector/verifier identity 升级为 `/4`，fixture identity 保持 `/3`；Rust primary 与 Python stdlib
  independent replay 覆盖 28 laid-out + 11 unsupported，39/39、120 checks。vector SHA-256 为
  `640c53afb1a19605ce7318fd40d991c5934de32dae6699c79306eee1e05227d4`，fixture SHA-256 为
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1/A2 证据：最终一致的 `render` `.sdlc/evidence/20260821-211731-render/`、`server`
  `.sdlc/evidence/20260821-205133-server/`、治理前 `fast` `.sdlc/evidence/20260821-210938-fast/`；resolution governance
  后的最终 Fast/Full 目录按不可自指策略只在 commit handoff 报告。
- 生命周期为 `resolved / automated_verified`，不外推完整 weighted water filling、Layout/Renderer/Profile/Template v1
  READY、physical Linux certification、A3 或 J1。Provider attempts/API Key reads/open authorization/paid external calls
  均为 0；未发送真实数据，未 push/tag/PR。
