# 实现 Renderer visible/zero-opacity 子树绘制抑制内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 92, 97（均已 resolved）

## Question

T97 已把 resource-free definite Group/Frame/Stack/Grid/Rect 接入同一真实 PNG Engine，但当前 paint admission
仍把 `visible:false` 与 `opacity:0` 当成 unsupported。怎样按冻结 Layout Profile 在完整资源/布局语义之后跳过
自身与全部 descendants 的 draw，同时不借不可见状态掩盖资源或 layout failure，也不提前选择 partial alpha、
source-over、AA、Skia 或正式 Profile 注册语义？

## Answer（本票冻结的实施决定）

1. **深化唯一 Engine Interface**：继续只暴露 `render_png(&AdmittedRenderDocument, dpi)`；不新增旁路 renderer、
   expected-pixel 输入或第二套 layout。Engine 仍先拒绝未实现的 resource manifest、完成 surface preflight，并以
   `layout_definite_resource_free` 对完整 materialized tree 生成权威 preorder LayoutBox。
2. **继承式 draw eligibility**：根 scene 以 draw-enabled 开始。对现有 Group/Frame/Stack/Grid/Rect 子集，任一祖先
   已 suppressed、当前 `visible:false` 或当前 `opacity` exact 0 都令当前 self + descendants draw-disabled；
   `visible:true` 且 opacity exact 1 才进入既有 paint/clip lowering。draw-enabled 的 0..1 中间 opacity 继续
   fail closed，不在本票选择 premultiply/source-over 次序。
3. **失败语义不被遮蔽**：resource manifest 检查与完整 definite layout 均先于 draw suppression；所有 occurrence
   仍逐项消费并核验权威 layout preorder identity。suppressed subtree 只跳过 paint-only transform/radius/stroke/
   color-alpha/device-edge/clip lowering，不跳过 strict RenderDocument、resource 或 layout admission。
4. **原子输出**：scene 全部 prepare 成功后才分配 surface；suppressed subtree 不写 paint item，后续可见 sibling
   仍按 authored preorder 绘制。任何未抑制的 unsupported paint、subpixel edge 或 partial alpha 继续零 output。
5. **共同语料纵向 TDD**：新增 `visible:false` Frame 与 `opacity:0` Frame 两个正例；两者 self/child 均被抑制，
   后续可见 Rect 的 exact PNG 必须逐 byte 等于既有单 Rect oracle。再新增 hidden IMAGE/resource 负例，证明
   不可见状态仍先命中 `RESOURCE_MANIFEST`；partial node opacity 继续 `RECT_PAINT`。目标为 13 rendered +
   13 unsupported、26 cases/82 checks。
6. **诚实边界**：不实现 partial opacity/blend、partial background alpha、rounded/Ellipse/Vector/Text/Image/QR/
   Barcode raster、fetch/decode/font/JPEG、compositionViewport、daemon RESULT、Profile registration、Java/OpenAPI/
   Web/E6 或产品 route。Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、daemon
   `UNWIRED`、product route `CLOSED`。

## 验证与完成信号

- TDD RED：先扩展 frozen shared vectors 与 Rust/Python expectations，现有实现应对两个正例分别返回
  `FRAME_PAINT`；记录 exact RED。
- TDD GREEN：focused Rust Engine vectors + 独立 Python replay → workspace fmt/check/clippy/tests → JSON/SHA/
  inventory 与 `git diff --check`。
- 分级：`render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。
- 最高状态只到 `automated_verified`；provider attempts/API Key reads/付费调用/真实数据保持 0；不 push/tag/PR，
  不开放最终产品 route，不把 `/prototype` 当作交付。

## Resolution

- RED 精确命中公开边界：新增两个正例时 Rust 均返回 `FRAME_PAINT`，独立 Python replay 对相同正例产生对应
  expectation drift；没有通过改 oracle 或读取 expected pixels 绕过失败。
- Engine 现以继承式 `ancestor_draw_enabled` / `node_draw_enabled` 消费完整 authoritative layout preorder；
  `visible:false` 或 exact `opacity:0` 会抑制 self + descendants 的 paint，但 resource admission、surface preflight
  与 definite layout 仍先执行。suppressed subtree 不产生 paint，后续 sibling 继续按 authored order 绘制；未抑制的
  partial opacity 仍 fail closed。
- shared vectors 最终为 13 rendered + 13 unsupported = 26/26 cases、82 checks；focused Rust Engine vectors
  2/2 与独立 Python replay 均绿。vector SHA-256 为
  `8c78f2863dba7033880158e409b506adf65b5a32e92379ce39988ed97a434c5a`。
- 分级 gate 全绿：`render` `.sdlc/evidence/20260824-200342-render/`、affected `fast`
  `.sdlc/evidence/20260824-200412-fast/`、顺序 `server` `.sdlc/evidence/20260824-200431-server/`、17-step
  `full` `.sdlc/evidence/20260824-202155-full/`。full 用时 1556.353 秒；App 344/0/0/15、Node 24 Web
  26 files/212 tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft browser journey 与
  inference replay E2E 1/1 均通过。状态回填后的 resolution `fast`
  `.sdlc/evidence/20260824-205049-fast/` 亦 exit 0（9.523 秒）。
- renderer boundary 为
  `PREORDER_DEFINITE_IDENTITY_GROUP_FRAME_STACK_GRID_RECT_PIXEL_ALIGNED_OPAQUE_RECTANGULAR_CLIP_VISIBILITY_ZERO_OPACITY_SUPPRESSION_PNG_KERNEL_UNWIRED`；
  Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、daemon `UNWIRED`、正式产品
  route `CLOSED`。provider attempts/API Key reads/费用/真实数据=0，未 push/tag/PR，也未把 `/prototype` 计为交付。
