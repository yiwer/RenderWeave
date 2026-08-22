# 实现方向切换 nested Stack cross offer → main HUG 单向传播子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 49, 50, 51, 52, 53, 54, 55, 56, 57, 61（均已 resolved）

## Question

T55/T56/T61 已能把 owning Stack 的 singleton main-FILL 最终 outer size 沿同方向 Stack 链传播并只重测一次
cross HUG；T54 已能让 definite Stack cross interval 驱动 direct Frame 的 main-HUG/opposite-FILL。如何把这两个
已冻结子闭包组合起来，让方向切换的 direct nested Stack 把父层 main outer 当作自己的 definite cross outer，
再一次性求自己的 main HUG，同时不引入一般 constraint engine、双向回写、fixed point 或 tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 layout deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；不新增 public API、crate、parser、route、Profile、
   daemon success path 或第二套 Stack 算法。
2. **只开放 direct 方向切换 link**：owning ROW Stack 的 direct STACK child 必须是 COLUMN，且相对 owning
   Stack 为 `widthMode=FILL,heightMode=HUG_CONTENT`；COLUMN→ROW 使用对称的
   `heightMode=FILL,widthMode=HUG_CONTENT`。owning 层仍须至多一个 main FILL；同方向链继续复用 T56。
3. **父 main outer 等于子 cross outer**：owning 层先按 T38/T55 的 signed margin、positive-zero、min→max 得到
   nested Stack 最终 main outer size。方向切换后，该值只作为 nested Stack 自己的 definite cross outer；依次扣
   nested Stack inward stroke 与 cross-axis padding并逐步 floor-to-positive-zero，得到 definite cross ContentBox。
4. **cross 已知、main 自然测量一次**：nested Stack 的 main HUG 只在上述 cross ContentBox 下按 authored order
   测量一次；direct STACK Frame 必须在 nested Stack 的 cross axis 为 FILL、main axis为 HUG，复用 T54 的
   margin/clamp、Frame ContentBox 与 exact-quarter-turn affine 子闭包。结果不回写父层 allocation、siblings、gap、
   justify 或任何祖先。
5. **typed space 不用零冒充未知**：给内部 `StackMeasurementSpace` 增加 closed 的“definite cross、main unknown”
   构造；`ResolvedOuter` 仍表示调用方已解析的 opposite outer size。实际 arrange 与 HUG writer 共享
   `measure_stack_child`，不复制 placement 或 clamp 算法。
6. **错误顺序与边界固定**：child measurement 仍按 authored DFS；方向切换后的第二个 nested Stack link、nested
   Stack main-HUG 内 main-axis FILL、Grid link、multiple main FILL、rows→columns、双向 HUG、一般
   `UNBOUNDED/AT_MOST/EXACT`、fixed point、非直角 rotation/tolerance 与 resource/scene/raster/RESULT/Profile
   继续 fail closed；任一失败不返回 partial layout。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/25`。把 T61 保留的 Grid cell→ROW→COLUMN
   boundary negative 转为 positive；新增 COLUMN→ROW 对称 positive、cross ContentBox + signed margin/clamp
   positive，以及第二个方向切换 Stack link boundary negative。目标 114 laid-out + 15 unsupported，
   共 129 cases、386 checks；fixture bytes 不变时保持 fixture `/3`。
8. **诚实能力边界**：多 Stack FILL、跨多个 AUTO 的平均 deficit、multiple FRACTION、任意非直角 rotation、
   Text/Image intrinsic、actual resource fetch/decode、world scene、paint/raster/JPEG、daemon RESULT、Profile/E6、
   formal records 与物理 Linux/J1/A3 均不在本票。

## 验证与完成信号

- 局部：shared vectors 先使 Rust primary/Python independent verifier 共同 RED；再分别实现，运行 focused Cargo
  vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/test、`py_compile`、JSON inventory 与
  `git diff --check`。
- 受影响：`render` → 顺序 `fast`/`server` → 完整 `full`；Maven gate 不并发。
- 保证上限：Rust/kernel/gate A1，direct direction-changing Stack cross offer 的 Rust+Python shared replay A2；
  不证明递归方向切换、一般 constraint/tolerance、完整 Layout/Renderer、scene/pixel、daemon output、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit；不 push/tag/PR，
  不运行 provider，不读取 API Key，不发送真实数据。
- 完成证据：shared vector/verifier identity 已升级为 `/25`；Rust primary 与 Python independent verifier 先在首个
  新增 positive 的同一 `CHILD_ROTATION rwocc_...0006` 边界共同 RED，再共同 GREEN。最终 114 laid-out + 15
  unsupported、129/129 cases、386 checks；vector SHA-256
  `c44896325fa6ff0cd85c2143d9d9c2a4dbeeeea98153deede21e013772069bb5`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。focused Cargo/Python、fmt、clippy
  `-D warnings`、workspace tests、`py_compile`、JSON inventory 与 `git diff --check` 均通过。
- 分级门控：`render` `.sdlc/evidence/20260823-015141-render/`、affected `fast`
  `.sdlc/evidence/20260823-015207-fast/`、顺序 `server` `.sdlc/evidence/20260823-015224-server/` 与 Goal `full`
  `.sdlc/evidence/20260823-021028-full/` 全绿。full metadata 17/17 steps exit 0，Node v24.12.0 Web 26 files/212
  tests、runtime canary、23 passed + 1 controlled skip Playwright E2E、Draft/Inference browser journeys 与最终
  inference replay E2E 1/1 均通过；resolution 后 `fast` `.sdlc/evidence/20260823-024051-fast/` 的 3 steps 也均
  exit 0。R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0；Renderer Profile 仍
  `NOT_REGISTERED`、certification `NOT_CERTIFIED`、Raster `ABSENT`、daemon `UNWIRED`。最高
  `automated_verified`；未推进 A3/J1/READY，未 push/tag/PR。
