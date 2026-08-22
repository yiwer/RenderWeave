# 实现 Grid cell offer → ROW Stack main-first cross HUG 单向传播子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 34, 38, 39, 40, 41, 43, 44, 45, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 60（均已 resolved）

## Question

T57/T60 已让 row-after-columns Grid cell 产生 final typed `ResolvedOuter` width，T55/T56 已让 ROW Stack 在
singleton main-FILL 分配后只重测一次 cross HUG，T58 又允许该路径末端组合 columns-first Grid。如何让 direct
GRID `kind=stack,direction=ROW,widthMode=FILL,heightMode=HUG_CONTENT` 消费 cell offer，同时不开放 COLUMN
方向切换、column-from-row feedback、multiple FILL water filling、一般 constraint engine 或 fixed point？

## Answer（本票冻结的实施决定）

1. **只深化既有 layout deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；不新增 public API、crate、parser、route、Profile、
   daemon success path 或第二套 Stack/Grid 算法。
2. **仅开放 row-after-columns 的 direct ROW Stack**：owning Grid 必须已完成 columns，当前求 row AUTO
   contribution；direct GRID child 必须 role=Stack、`direction=ROW,widthMode=FILL,heightMode=HUG_CONTENT`。
   COLUMN Stack、width HUG/height FILL 与 column AUTO future-row offer 不进入本票。
3. **cell outer → Stack ContentBox → main-first cross HUG**：先按 cell span、signed margins、positive-zero 与
   min→max 得到 Stack final outer width，再扣 inward stroke/padding 得到 definite main ContentBox width；复用
   T55/T56 同一 authored-order helper 完成至多一个 main FILL allocation，并只重测 cross HUG 一次。
4. **允许既有有限组合，不新增传播模型**：相同方向 nested ROW Stack 可沿 T56 逐层消费 main offer；末端 Grid
   可经 T58 以 final main outer width 严格 columns→rows。每层 cross 结果均不回写祖先/本层 main allocation、
   siblings、gaps、tracks 或 outer width。
5. **共享 closed predicate，防止 writer/arrange 漂移**：row AUTO contribution 与 final GRID cell arrange 必须
   复用同一个内部“可消费 resolved-width offer”判定；Frame/Grid 维持 T57/T60 行为，Stack 仅在 direction=ROW
   时加入。x/column 路径保持既有 Frame-only 判定。
6. **固定错误顺序与边界**：方向改变的 nested Stack、multiple main FILL、跨多个 AUTO 的平均 deficit、multiple
   FRACTION、双向 HUG、一般 `UNBOUNDED/AT_MOST/EXACT` constraint engine、非直角 rotation/tolerance、resource/
   scene/raster/RESULT/Profile 继续 fail closed；错误仍按 authored DFS first error，全有或全无输出不变。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/24`。把现有 Grid cell→Stack boundary negative
   转为 positive，并新增 margins/clamp/ContentBox、same-direction nested Stack chain、Stack→Grid columns-first
   组合三个 positive；新增 direction-changing nested Stack boundary negative。目标 111 laid-out + 15 unsupported，
   共 126 cases、377 checks；fixture bytes 不变时保持 fixture `/3`。

## 验证与完成信号

- 局部：shared vectors 先使 Rust primary/Python independent verifier 共同 RED；再分别实现，运行 focused Cargo
  vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/test、`py_compile`、JSON inventory 与
  `git diff --check`。
- 受影响：`render` → 顺序 `fast`/`server` → 完整 `full`；Maven gate 不并发。
- 保证上限：Rust/kernel/gate A1，Grid cell→ROW Stack typed offer 的 Rust+Python shared replay A2；不证明
  direction-changing Stack、一般 constraint/tolerance、完整 Layout/Renderer、scene/pixel、daemon output、
  A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit；不 push/tag/PR，
  不运行 provider，不读取 API Key，不发送真实数据。
