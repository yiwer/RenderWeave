# 实现 exact-quarter-turn affine 非空 Frame/Group HUG AABB 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 42, 43, 45, 49, 50（均已 resolved）

## Question

在 `renderweave-layout/1.0` 的任意角度跨平台三角函数 tolerance、资源测量与 world scene/raster 尚未物化时，
如何继续深化同一个 Rust layout deep module：只实现容量边界 `[-360, 360]` 内精确 90 度倍数的
resource-free direct ABSOLUTE child transformed LayoutBox AABB，让非直角 rotation、cross-axis FILL、资源与
tolerance-dependent 语义继续 fail closed？

## Answer（本票冻结的实施决定）

1. **只深化既有 deep module**：继续使用 workspace-internal `renderweave-renderer-layout` 与唯一入口
   `layout_definite_resource_free(&AdmittedRenderDocument)`；复用同一次 admission/preflight、authored preorder
   与全有或全无输出，不新增 parser、crate、route、Profile 或 daemon success path。
2. **精确 classifier，不做 authored normalization**：只接受 lowering 后 binary64 精确等于
   `-360/-270/-180/-90/0/90/180/270/360` 的 rotation。RenderDocument 中的 authored value 保持原样；内核只为
   几何计算派生 quadrant。其他值（包括最接近但不相等的 decimal）继续在该 child 返回 closed internal
   `CHILD_ROTATION`，不调用三角函数、不引入 epsilon/tolerance。
3. **冻结 canvas 顺时针映射**：ABSOLUTE 左上坐标使用 `+x` 向右、`+y` 向下；先 scale 得到 delta `(u,v)` 后，
   quadrant `0/1/2/3` 分别映射为 `(u,v)`、`(-v,u)`、`(-u,-v)`、`(v,-u)`。这直接实现权威
   `origin + RotateClockwise × Scale × (p-origin)`，且 90 度倍数不需要近似三角函数。
4. **轴向精确求值与 q0 回归不漂移**：先按既有顺序测当前输出轴 position/size，再读取 rotation；偶数 quadrant
   只消费当前轴，q0 继续使用 T49/T50 的原求值顺序与 binary64 bit 结果。奇数 quadrant 再按 Width↔Height
   测 cross axis，分别求 target transform-origin 与 source endpoint scaled delta，按上表符号加到 target origin，
   以 `min(near,far)/max(near,far)` 得 interval。每个加减乘与最终 union/normalization 都 finite-check，不做 FMA
   或中间量化。
5. **只开放 independently measurable quarter-turn**：奇数 quadrant 的两轴必须各自为 FIXED，或其 HUG 可由
   T42/T43/T45/T49/T50/本票规则独立测得。若 cross axis 是 FILL，本子闭包不尝试从 owning Frame 的另一轴 offer
   建立新依赖，稳定返回 `CHILD_ROTATION`；HUG 轴自身的 FILL/cycle 仍由既有 preflight hard error 负责。
6. **Frame/Group 复用**：Frame 仍只取 transformed interval 最远正端，负端只 overflow；Group 仍从首 child
   建二维 union、size=`max-min`，并以 union min 归一化派生 child layout。container 自身 transform 不反馈自身
   intrinsic/normalization，仍不使用 PaintBounds。
7. **稳定错误顺序**：child kind/role、ABSOLUTE placement 与当前轴 resource-free size 仍先于 rotation 判断；奇数
   quadrant 的 cross-axis size 后于 rotation、先于 transform arithmetic。resource-dependent kind、viewport、嵌套
   intrinsic failure 与 authored-first unsupported 保持既有 DFS 优先级；不返回 partial layout。
8. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/14`；先加入 Frame 90、Group -90/180/270/360、
   signed position、origin、positive/negative scale、nested/cross-axis HUG、Frame/Stack/Grid consumption、Group
   normalization、cross-axis FILL 与 45 度 fail-closed，以及全部 q0 regression。Rust primary 与 Python stdlib
   independent verifier 必须同时先 RED，再分别实现语义；fixture bytes 不变时保持 fixture `/3`。
9. **诚实能力边界**：非直角 rotation、quarter-turn cross-axis FILL、Text/Image/Vector intrinsic、multiple Stack
   FILL、跨多 AUTO 平均、multiple FRACTION、actual resource fetch/decode、world transform/scene、paint/raster/
   JPEG、daemon RESULT、Profile registration、公开 preview/E6、formal records 与物理 Linux/J1/A3 均不在本票。

## 验证与完成信号

- 局部：逐 slice RED→GREEN；focused Cargo vector tests + Python stdlib independent replay；workspace fmt/clippy
  `-D warnings`/test、`py_compile`、JSON inventory 与 `git diff --check`。
- 受影响：`render` → `server`/`fast` → 完整 `full`；证据按局部到 Goal 扩大。
- 保证上限：Rust/kernel/gate A1，exact-quarter-turn affine Frame/Group HUG exact binary64 shared vectors 的
  Rust+Python replay A2；不证明任意 rotation tolerance、完整 Layout/Renderer、scene/pixel、daemon output、
  A3/J1/READY。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；不
  push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。

## Resolution

- `renderweave-renderer-layout` 已在同一 `layout_definite_resource_free` deep-module seam 内实现精确
  `-360/-270/-180/-90/0/90/180/270/360` authored rotation 的 clockwise quadrant AABB；q0 保持原求值路径，
  奇数 quadrant 只消费两轴 FIXED 或 independently resource-free HUG，cross-axis FILL 与非直角 rotation
  稳定返回 `CHILD_ROTATION`。
- shared definite-layout contract 已升级到 `/14`：69 laid-out + 14 unsupported，共 83/83、249 checks；
  Rust primary 与 Python independent replay 同时通过，vector SHA-256 为
  `1dcd984fc45eafdde55a3df9a8cb792e84431fe167bed9c1c751ac131a712335`，fixture `/3` bytes 未变。
- A1 gate 全绿：`render` `.sdlc/evidence/20260822-135159-render/`、`server`
  `.sdlc/evidence/20260822-135229-server/`、affected `fast`
  `.sdlc/evidence/20260822-141047-fast/`、Goal `full`
  `.sdlc/evidence/20260822-141108-full/`，以及 resolution 后 final `fast`
  `.sdlc/evidence/20260822-144154-fast/`。full metadata 为 `result=passed`，17 个 step 均 exit 0。
- Profile 仍 NOT_REGISTERED、certification NOT_CERTIFIED、resource bytes UNFETCHED、world scene/raster ABSENT、
  daemon output UNWIRED；provider attempts/API Key reads/reservations/cost、真实数据与付费调用均为 0，未
  push/tag/PR。
