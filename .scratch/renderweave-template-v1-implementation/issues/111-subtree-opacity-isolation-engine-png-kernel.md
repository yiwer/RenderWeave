# 接通子树 opacity isolation Engine PNG 内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 10, 16, 19, 92, 93, 94, 95, 96, 97, 99, 107, 109, 110（本切片前置均已 resolved）

## Question

T109/T110 已把 Canvas、solid fill 与 prepared IMAGE 统一到 fixed premultiplied RGBA8/source-over，但任何
`0 < node.opacity < 1` 仍 fail closed。如何按 Ticket 10 的精确绘制栈一次建立真实 subtree isolation，使
Rect/Image/Group/Frame/Stack/Grid 的 self、children 与 descendant clip 先在透明层内完成，再整体乘 opacity 合成，
同时避免错误的逐图元 opacity、每节点整面 surface 分配、partial output 或提前注册 Renderer Profile？

## Answer（本票冻结的实施决定）

1. **保持唯一 Engine deep Interface**：继续只暴露 `render_png` 与
   `render_png_with_prepared_resources`；不增加 caller pixels/surface/layer、test-only token、Profile bypass 或
   第二套 daemon 路径。T108 seal kernel 自动消费同一 Engine output。
2. **精确 opacity lowering**：从 admitted canonical 六位十进制读取 `opacity`；exact `0` 继续 suppress self +
   descendants，exact `1` 不建立层，其余值按
   `opacity8 = floor((opacityScaled6 * 255 + 500000) / 1000000)` 冻结为 `ROUND_HALF_UP(opacity × 255)`。
   authored partial opacity 即使量化为 0/255，仍保留 isolation 语义，不能退化为 exact 0/1 快路。
3. **真实 subtree isolation**：每个部分 opacity Node 在 self paint 前发出 layer begin，在 container self fill、
   descendant clip 下的全部 children（及后续可支持 stroke）完成后发出 layer end；层内继续使用既有 premultiplied
   source-over/authored preorder，end 时把层内 RGBA 四通道统一乘 `opacity8` 后一次 source-over 到父层或 Canvas。
4. **有界扫描行层栈**：不为每个 opacity Node 分配完整 surface。Engine 在全部 resource/layout/scene prepare 成功后，
   预先计算最大嵌套层深；只分配 `surfaceWidth × 4 × maxPartialOpacityDepth` 的透明 scanline scratch，并按行复用。
   每层记录本行 dirty 区间，只清理/合成实际触达区间；allocation、命令不平衡或索引错误均零 output。
5. **当前 scene 子闭包**：只深化现有 identity-transform、pixel-aligned、rectangular-clip 的
   Rect/Image/Group/Frame/Stack/Grid；solid color alpha 与 IMAGE pixel alpha 仍先在层内按 T109/T110 规则合成。
   `visible:false`/exact `opacity:0` 仍在完整 resource preparation 与 layout 后抑制绘制，后续 sibling order 不变。
6. **TDD 与独立重放**：先把 resource-free `partial-node-opacity-remains-fail-closed` 与 prepared IMAGE
   `partial-image-node-opacity-fails-closed` 转为 positive，使 Rust public Interface RED；新增能区分“正确 group
   isolation”与“逐图元 opacity”的 overlapping children、container fill→child、nested opacity、clip 与透明/不透明
   underlay vectors。两套 Python stdlib verifier 独立实现 decimal lowering、scanline layers、premultiplied scaling 与
   source-over，逐 byte 核对 PNG/pixels/hash。
7. **诚实边界**：本票不实现 subpixel/rounded/stroke/vector/Text/QR/Barcode coverage、resampling、transform、JPEG、
   LayoutTrace、Profile registration/certification、RequestRegistry 网络 success、Java/OpenAPI/Web/正式产品 route、
   native build、physical Linux certification、J1/A3/READY 或外部副作用；`/prototype` 不计最终交付。

## 验证与完成信号

- focused Rust vectors先 RED 后 GREEN；两套 Python independent replay、daemon prepared-result regression、fmt、
  clippy `-D warnings`、workspace tests、JSON/hash/inventory 与 `git diff --check` 全绿。
- 分级 gate：canonical `render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。
- 最高只报 `automated_verified`；Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、
  正式产品 route `CLOSED`、native stack `BUILD_NOT_AUTHORIZED`；provider/API Key/费用/真实数据=0，不 push/tag/PR。

## Result

- Engine 内部 scene 已从 flat paint stream 深化为 balanced `BeginOpacity` / `Paint` / `EndOpacity` 命令流；
  `Suppressed`、`FullOpacity` 与 `PartialOpacity(opacity8)` 明确区分 exact 0、exact 1 与 authored partial。
  authored `0.001`/`0.999` 即使量化为 0/255 仍保留 isolation layer，未错误折叠到 suppression/fast path。
- rasterizer 只按 `surfaceWidth × 4 × maxPartialOpacityDepth` 分配并逐行复用透明层 scratch；每层维护 dirty
  区间，在 self、clipped descendants 与 nested layers 完成后统一缩放 premultiplied RGBA 并一次 source-over。
  原有 prepare 原子性、authored preorder、solid/IMAGE alpha、rectangular clip 与 final single unpremultiply 不变。
- resource-free corpus 冻结为 23 rendered + 10 unsupported，共 33 cases/103 independent checks，vector SHA-256
  `b44706f739e08e464816d49a0a203050a36f0e8dba2d1b393796a7d69b9dc5a3`；prepared IMAGE corpus 为
  18 rendered + 3 unsupported，共 21 cases/135 independent checks，vector SHA-256
  `61b03097b1999942051785bb4754b488478ad3290de5f53faf2327db5ad5ea36`。daemon prepared-result integration
  3/3 覆盖 partial container IMAGE+Rect isolation 后再合成。
- 精确能力标签为
  `PREORDER_DEFINITE_IDENTITY_GROUP_FRAME_STACK_GRID_RECT_PIXEL_ALIGNED_SOLID_ALPHA_PREMULTIPLIED_SOURCE_OVER_SUBTREE_OPACITY_ROUND_HALF_UP_ISOLATION_RECTANGULAR_CLIP_VISIBILITY_ZERO_OPACITY_SUPPRESSION_PNG_KERNEL_PROFILE_GATED`；
  prepared IMAGE alpha arithmetic 为
  `STRAIGHT_TO_PREMULTIPLIED_MUL255_SOURCE_OVER_AUTHORED_ORDER_SUBTREE_OPACITY_ROUND_HALF_UP_255_SINGLE_FINAL_UNPREMULTIPLY`。

## Evidence

- canonical `render`：`.sdlc/evidence/20260825-122036-render/`（55.458 秒）。
- affected `fast`：`.sdlc/evidence/20260825-122142-fast/`（19.816 秒）。
- sequential `server`：`.sdlc/evidence/20260825-122210-server/`（860.526 秒）。
- Goal `full`：`.sdlc/evidence/20260825-123646-full/`（17/17 steps，1222.540 秒），覆盖 Renderer
  Windows/Linux UDS、8 个 Maven modules、Node 24 Web 26 files/212 tests、runtime canary、Playwright
  23 passed + 1 controlled skip、Draft 与 inference browser journeys；provider attempts/API Key reads/费用/真实数据=0。
- 状态回填后的 resolution `fast`：`.sdlc/evidence/20260825-125907-fast/`（12.432 秒）。
- Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、正式产品 route `CLOSED`、
  native stack `BUILD_NOT_AUTHORIZED`；`/prototype` 不计最终产品交付，未 push/tag/PR。
