# 实现同轴 nested Stack main offer → cross HUG 逐层单向传播子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 49, 50, 51, 52, 53, 54, 55（均已 resolved）

## Question

T55 已能在 owning Stack 的 singleton main-axis FILL 分配后，以最终 main outer size 对 direct Frame 的
cross-axis HUG 重测一次。如何继续按 `renderweave-layout/1.0` 已冻结的“主轴先解、cross HUG 后测一次且不反算”
顺序，把同一个 definite offer 沿同方向 nested Stack 链逐层传到 direct Frame，同时不引入通用 constraint
传播、双向 HUG、multiple-FILL water filling、residual tolerance 或 fixed point？

## Answer（本票冻结的实施决定）

1. **只深化既有 layout deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；不新增 public API、crate、parser、route、Profile、
   daemon success path 或第二套 Stack 算法。
2. **只支持同轴 nested Stack 链**：owning definite Stack 的 direct STACK child 必须是 Stack，且 placement 在
   owning direction 上为 `FILL`、cross axis 为 `HUG_CONTENT`；nested Stack 自身 direction 必须与 owning Stack
   相同。该规则可递归消费一条或多条同轴 Stack link，终点仍是 T55 已支持的 direct Frame。每层 main axis
   至多一个 FILL；方向改变、Grid link 与双向 HUG 不在本票。
3. **每层单向两阶段**：父层先按 T38 的 positive-zero、min 后 max 得到 nested Stack 最终 main outer size；
   nested Stack 依次扣 inward stroke 与 main leading/trailing padding并逐步 floor-to-zero，得到 definite main
   ContentBox offer。随后只在该层复用既有 Stack measure/allocation 一次，再重测 cross HUG 一次；cross 结果
   不反馈任何祖先/本层 main allocation、justify 或 sibling。
4. **共享纯 helper，禁止算法分叉**：实际 arrange 与 nested HUG measurement 必须复用同一 authored-order
   Stack child measurement/singleton-FILL allocation helper。helper 显式区分 definite cross extent 与 cross-HUG
   measurement，不能以零冒充未知 cross offer；HUG/FILL cycle 继续 hard fail。
5. **错误顺序与全有或全无不变**：所有 child measurement/re-measure 结果保存于原 authored slot；完成必要的
   allocation 后仍按 authored DFS 消费，multiple FILL 继续把首个 fill slot 改写为 `STACK_MAIN_FILL`。任一失败
   不返回 partial `DefiniteLayout`。
6. **复用既有 typed offer/affine 内核**：只把 T55 `ResolvedOuter` typed offer 交给 Stack HUG；终点 Frame 继续
   复用 T52/T53 的 ContentBox offer、inset/min-max 与 T51 exact-quarter-turn endpoint/AABB 固定求值顺序。
7. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/19`。把 T55 保留的 ROW nested Stack negative
   转为 positive，并新增 COLUMN 对称、padding/stroke + fixed sibling/gap/min-max、两层递归链；新增一个 nested
   Grid boundary negative。目标清单为 91 laid-out + 14 unsupported，共 105 cases、315 checks；fixture bytes 不变
   时保持 fixture `/3`。
8. **诚实能力边界**：multiple Stack FILL、Grid offer/columns-first row feedback、一般
   `UNBOUNDED/AT_MOST/EXACT` constraint engine、非直角 rotation、Text/Image/Vector intrinsic、resource
   fetch/decode、world scene、paint/raster/JPEG、daemon RESULT、Profile/E6、formal records 与物理 Linux/J1/A3
   均不在本票。

## 验证与完成信号

- 局部：shared vectors 先使 Rust primary/Python independent verifier 共同 RED；再分别实现，运行 focused Cargo
  vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/test、`py_compile`、JSON inventory 与
  `git diff --check`。
- 受影响：`render` → 顺序 `fast`/`server` → 完整 `full`；Maven gate 不并发。
- 保证上限：Rust/kernel/gate A1，同轴 nested Stack offer 的 Rust+Python shared replay A2；不证明通用 constraint、
  Grid/multiple-FILL、完整 Layout/Renderer、scene/pixel、daemon output、A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit；不 push/tag/PR，
  不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `renderweave-renderer-layout` 已在既有 `layout_definite_resource_free` deep-module seam 内抽取实际 arrange 与
  nested HUG 共用的 authored-order Stack measure/singleton-FILL allocation helper；typed
  `StackMeasurementSpace` 显式区分 definite cross extent 与 cross-HUG unknown，不再以零冒充未知 offer。
- 同 direction nested Stack 现在可逐层消费父层最终 main outer size：每层依次扣 inward stroke 与 main padding
  得到 ContentBox offer，复用同一 helper 完成至多一个 main FILL，再只重测 cross HUG 一次。结果不反算祖先或
  本层 main allocation/justify/sibling；错误仍写回原 authored slot，Grid link 与 multiple FILL 继续 fail closed。
- shared definite-layout contract 已升级到 `/19`：91 laid-out + 14 unsupported，共 105/105、315 checks；Rust
  primary 与 Python independent replay 同时通过，vector SHA-256 为
  `b83a7f8c13d94262d0c403b94d89f6b92d3b96d3b6fadfaf7c4211294140644d`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- A1 gate 已通过：`render` `.sdlc/evidence/20260822-192544-render/`、affected `fast`
  `.sdlc/evidence/20260822-192615-fast/`、顺序 `server` `.sdlc/evidence/20260822-192635-server/` 与 Goal
  `full` `.sdlc/evidence/20260822-194530-full/`。full metadata 为 `result=passed`，17 个 step 均 exit 0；其中
  Node 24 Web 26 files/212 tests、runtime canary、23 passed + 1 skipped Playwright E2E、Draft/Inference browser
  journeys 均通过，R0/R1/P0 independent replay 的 provider attempts 均为 0；resolution 后 fast
  `.sdlc/evidence/20260822-201742-fast/` 也通过。
- Grid/general constraint offer、multiple main FILL water filling、双向 HUG、非直角 rotation、resource
  fetch/decode、world scene/raster、daemon output 与 Profile 仍未实现；API Key reads/reservations/cost、真实数据与
  付费调用均为 0，未推进 A3/J1/READY，未 push/tag/PR。
