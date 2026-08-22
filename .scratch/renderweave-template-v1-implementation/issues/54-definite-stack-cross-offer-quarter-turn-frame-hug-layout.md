# 实现 definite Stack cross-axis offer 下的 quarter-turn Frame HUG opposite-axis FILL 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 49, 50, 51, 52, 53（均已 resolved）

## Question

在多 main-axis FILL water filling、Grid cell offer、通用 constraint propagation、数值 tolerance、资源测量与
scene/raster 尚未物化时，如何继续深化同一个 Rust layout deep module：只把 definite Stack cross-axis interval
解析成 direct STACK Frame 的 resolved opposite outer size，使该 Frame 的 main-axis HUG 可以消费自身
opposite-axis FILL 并复用 T52/T53 quarter-turn affine 子闭包，同时保持所有需要回馈或二次测量的路径 fail closed？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；不新增 public API、crate、parser、route、Profile 或
   daemon success path。
2. **仅 definite Stack cross → main HUG**：ROW 只支持 direct STACK Frame 的 `widthMode=HUG_CONTENT` 且
   `heightMode=FILL`；COLUMN 只支持对称的 `heightMode=HUG_CONTENT` 且 `widthMode=FILL`。owning Stack 的
   ContentBox cross axis 必须已 definite；当前 HUG 轴不是 owning Stack main axis 时不消费本票 offer。
3. **先解析 cross outer size**：复用既有 Stack cross-axis writer，按
   `max(0,(parentContentSize-leadingMargin)-trailingMargin)` 求 resolved outer size，再按既有顺序应用 min、max
   clamp。signed margins 保持原样；负余量先成为正零，min 可形成 overflow，max 留出的空间仍只影响 alignment。
4. **typed offer 区分来源**：内部 HUG 测量上下文把 T53 的 ABSOLUTE parent ContentBox offer 与本票已经解析的
   Stack outer-size offer区分为 closed typed variants；Frame opposite FILL 只按对应 variant 读取 placement 语义，
   不把 STACK margin 当 ABSOLUTE inset，也不为调用方暴露新接口。
5. **复用 Frame ContentBox 与 affine writer**：resolved outer size 继续依次扣两次 inward stroke 与
   leading/trailing padding并逐步 floor-to-positive-zero，得到 direct ABSOLUTE child 的 cross-axis FILL offer；
   T52 inset/min-max 与 T51 exact-quarter-turn endpoint/AABB 顺序不变。
6. **不做主轴回馈或二次测量**：ROW child `widthMode=FILL,heightMode=HUG_CONTENT` 与 COLUMN 对称路径需要先分配
   main FILL 再重测 cross HUG，本票保持 unsupported；multiple main FILL、nested Stack cross offer、Grid cell/span
   offer、一般 `AT_MOST/EXACT` constraint propagation 均不接线。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/17`；先把 T53 保留的 Stack negative 转为
   positive，并覆盖 ROW/COLUMN 对称、signed-margin negative remainder + min、max clamp，以及 main-FILL→cross-HUG
   继续 fail closed。Rust primary 与 Python stdlib independent verifier 必须先共同 RED，再分别实现冻结语义；
   fixture bytes 不变时保持 fixture `/3`。
8. **诚实能力边界**：多 main-axis FILL、Grid offer、双向 HUG、非直角 rotation、Text/Image/Vector intrinsic、
   multiple AUTO/FRACTION、actual resource fetch/decode、world scene、paint/raster/JPEG、daemon RESULT、Profile/E6、
   formal records 与物理 Linux/J1/A3 均不在本票。

## 验证与完成信号

- 局部：逐 slice RED→GREEN；focused Cargo vector tests + Python stdlib independent replay；workspace fmt/clippy
  `-D warnings`/test、`py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → 顺序 `fast`/`server` → 完整 `full`；Maven gate 保持串行。
- 保证上限：Rust/kernel/gate A1，definite Stack cross outer offer 的 Rust+Python shared replay A2；不证明通用
  constraint engine、完整 Layout/Renderer、scene/pixel、daemon output、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit；不 push/tag/PR，
  不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `renderweave-renderer-layout` 已在同一 `layout_definite_resource_free` deep-module seam 内把 HUG opposite-axis
  测量上下文收敛为 closed typed variants：`AbsoluteParentContent` 与 `ResolvedOuter`。definite ROW/COLUMN Stack
  只为 direct STACK Frame 的 main-HUG/opposite-FILL 路径解析 cross outer size；signed margins、positive-zero、min
  后 max clamp 与 Frame inward-stroke/padding floor-zero 顺序均按冻结语义执行，再复用 T52/T53 quarter-turn AABB。
- shared definite-layout contract 已升级到 `/17`：83 laid-out + 14 unsupported，共 97/97、291 checks；Rust primary
  与 Python independent replay 同时通过，vector SHA-256 为
  `ea96632db65804335c35d16963096ea4605617876606bdef08a63b1ef6e2df97`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1 gate 已通过：`render` `.sdlc/evidence/20260822-170957-render/`、affected `fast`
  `.sdlc/evidence/20260822-171038-fast/`、顺序 `server` `.sdlc/evidence/20260822-171128-server/` 与 Goal
  `full` `.sdlc/evidence/20260822-173019-full/`。full metadata 为 `result=passed`，17 个 step 均 exit 0；其中
  Node 24 Web 26 files/212 tests、runtime canary、23 passed + 1 skipped Playwright E2E 与 browser journeys 均通过；
  resolution 后 final `fast` `.sdlc/evidence/20260822-180122-fast/` 也通过。
- main-FILL→cross-HUG 回馈、nested Stack/Grid offer、通用 constraint、非直角 rotation、resource
  fetch/decode、world scene/raster、daemon output 与 Profile 仍未实现；provider attempts/API Key
  reads/reservations/cost、真实数据与付费调用均为 0，未推进 A3/J1/READY，未 push/tag/PR。
