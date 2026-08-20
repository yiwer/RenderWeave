# 实现资源无关的确定尺寸 ABSOLUTE box 布局内核

Type: task
Status: resolved / automated_verified
Resolved by: Codex `/root`（single-writer）
Blocked by: 23, 25（均已 resolved：exact RenderDocument admission 与静态布局预检）

## Question

如何在 `renderweave-layout/1.0` 的 binary64 tolerance 尚未冻结、资源/shaping/raster 依赖也未落地时，物化
第一段真实 measure/arrange 能力：只对完全 definite、资源无关、ABSOLUTE 定位的 Canvas/Frame/视觉叶子闭包
计算 pre-transform local LayoutBox/ContentBox，同时对所有尚未实现的合法布局显式返回 internal unsupported，
而不是猜测 HUG、Stack/Grid、资源 intrinsic、transform bounds 或 daemon success？

## Answer（本票冻结的实施决定）

1. **深化现有 deep module**：在 workspace-internal `renderweave-renderer-layout` crate 中新增
   `layout_definite_absolute(&AdmittedRenderDocument)`；入口先重跑 T25 `preflight_layout`，再返回 immutable
   preorder `DefiniteLayout` 或 closed internal `DefiniteLayoutError`。不新增 module、route、Profile 或第二套
   RenderDocument parser authority。
2. **首个真实可执行闭包**：支持根 Canvas，以及 Canvas/Frame ContentBox 下 `ABSOLUTE` placement 的
   `frame | rect | ellipse | line | polygon | polyline | path | qrCode | barcode`；每个非根 occurrence 的两轴
   只允许 `FIXED | FILL`。Frame 可递归嵌套；FIXED 使用 authored size，FILL 严格按
   `max(0, parentContentSize - start - endInset)` 后依次应用 min/max，位置为 parent ContentBox origin + x/y。
3. **box model**：Canvas LayoutBox/ContentBox 均为 `(0,0,widthPt,heightPt)`；Frame ContentBox 按固定顺序先
   扣每侧 inward stroke，再扣 padding。为避免在本票替未冻结的 degenerate inset origin 选语义，若任一扣除
   会小于零则返回 internal `DEGENERATE_CONTENT_INSET` unsupported；不会把合法文档误报为 public layout error。
4. **binary64 与可复核输出**：decimal6 token 只在布局入口按 IEEE-754 binary64 转换一次；运算顺序逐项
   固定，禁止 `mul_add`/fast-math 与中间量化。共同向量以 64-bit hex 锁定每个 x/y/width/height，而不是
   引入 epsilon 或 Layout tolerance；Rust primary 与 Python stdlib independent verifier 分别重放同一语料。
5. **明确的合法未覆盖面**：任一 HUG、Group、Stack、Grid、compositionViewport、Text/Image 或非 ABSOLUTE
   后代返回 closed internal unsupported feature 与 authored DFS occurrence；它不是
   `renderweave-render-problem/1.0`，不会进入 daemon/public response。普通 Node transform 不反馈本票的 local
   LayoutBox，但本票不产生 world transform、bounds、clip、paint 或 scene。
6. **容量与接线边界**：本票只产生 bounded box entries，数量必须与已访问 occurrence 一一对应；不自行发明
   measure/remeasure/operation 计数口径。没有 resource fetch/decode、font shaping、HUG intrinsic、Stack water
   filling、Grid allocation、composition lowering、transform AABB、paint/raster/codec、RESULT 或 Engine success。
   daemon 与 process capability/manifest inventory保持不变。

## 验证与完成信号

- TDD：先冻结 shared fixture/vector 与 Rust/Python RED；再实现 binary64 fixed/fill、min/max、Frame
  stroke/padding 与 preorder boxes；最后补 unsupported first-boundary 与 exact bit replay。
- 局部：focused Cargo test + Python independent verifier → workspace fmt/clippy `-D warnings`/test。
- 受影响：`render` → `server`/`fast` → 完整 `full`；证据按局部到 Goal 扩大。
- 保证上限：Rust/kernel/gate A1，Rust+Python exact binary64 vector replay A2；不证明完整 Layout Profile、pixel、
  daemon output、物理 Linux certification、A3 或 J1。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；Profile持续
  NOT_REGISTERED、certification NOT_CERTIFIED、raster ABSENT、daemon output UNWIRED；不 push/tag/PR，不运行
  provider，不读取 API Key，不发送真实数据。

## Resolution（2026-08-21）

1. 现有 `renderweave-renderer-layout` deep module 新增
   `layout_definite_absolute(&AdmittedRenderDocument)`；它与 T25 共用同一次 canonical document 解析和预检，
   避免 decimal token 的二次 binary64 转换，成功时只返回 immutable authored-preorder local boxes。
2. Canvas、递归 Frame 与 8 种资源无关视觉叶子的 ABSOLUTE `FIXED | FILL` 闭包已落地；FILL 严格执行
   `parent - start - endInset`、正零 floor、min 后 max，Frame 严格按双侧 inward stroke 后 padding 计算
   ContentBox。普通 transform 不反馈 local box。
3. 共享语料包含 6 laid-out + 9 unsupported cases，锁定 fixed/FILL、min/max、负 remainder 正零、嵌套
   stroke/padding、全部资源无关叶子、transform-ignore、DFS 首个 unsupported 与 8 个 closed feature 名；Rust
   primary 与 Python stdlib independent replay 均通过，后者 15/15、50 checks、A2。
4. `render` gate 升至 1.3 并硬断言 definite-layout report、exact SHA 与诚实边界；Windows/Linux Rust、实际
   UDS、Java 26 tests及全部 Python replay 通过于 `.sdlc/evidence/20260821-055509-render/`。受影响 `server`
   与 `fast` 分别通过于 `.sdlc/evidence/20260821-053225-server/`、
   `.sdlc/evidence/20260821-055925-fast/`。
5. 本票未改 daemon/process manifest/capability/公开 route；Profile 仍 NOT_REGISTERED，certification
   NOT_CERTIFIED，world scene/raster ABSENT，daemon output UNWIRED。没有 HUG/Stack/Grid/resource/shaping/
   raster/codec/RESULT、物理认证、J1/A3、provider、真实数据或 API Key 副作用。
6. 最终整树 `full` 在本 Resolution 与执行日志冻结后捕获；目录只在提交交接中报告，避免证据自指。
