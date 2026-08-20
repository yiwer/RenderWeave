# 物化 exact RenderDocument 合同、default 展开与跨语言 validator

Type: task
Status: resolved / automated_verified
Resolved by: Codex（single-writer）
Blocked by: 13, 21, 22（均已 resolved：exact RenderResource lease / Java seal / Rust process protocol）

## Question

如何把 Ticket 15 已冻结但 T21 明确留待 catalog 深化的 RenderDocument 合同真正物化：由同一机器可读
`RenderNodeContract` 驱动 Java sealer 展开全部语义 default、完成静态 kind/lowering 的 closed wire，并让
Rust daemon 在进入任何 layout/resource/raster 前以独立实现防御性验证相同 exact 文档；同时保持 Profile
`NOT_REGISTERED`、daemon 只返回 terminal problem，不提前实现 layout、图片或公开产品面？

## Answer（本票冻结的实施决定）

1. **单一机器合同**：深化 Rendering-owned `render-node-contract-v1.json`，固定 catalog identity、16 个
   RenderDSL static kind、每 kind closed emitted members、ContentModel、required/default/semantic-absence、
   placement union 与 DesignDSL→RenderDSL lowering 规则。Java sealer 与 Rust validator 读取同一 exact bytes；
   不引入通用 JSON Schema validator 或第二份 kind switch。
2. **Java default materialization**：在 Binding overlay 与结构展开后、mm→pt 量化前，由 catalog 将所有可表达
   default 物化为 concrete property tree；default 不创建可绑定 authored target。至少覆盖 common
   `visible/opacity/transform`、Canvas background/bleed、placement inset/margin/span/alignment、Frame/Stack/Grid
   appearance baseline、Stack/Grid、Text、Image、Path、QR/Barcode 的冻结 default。`fill/stroke/maxLines/min/max`
   等“语义缺席”保持 member omission，绝不写 `null` 或虚构 payload。
3. **lowering 完整性**：root Canvas 必须显式携带 `kind:"canvas"`；PACK、Repeat、Conditional、TemplateUse、
   nodeId/displayName/bindings/render、logical AssetRef 与动态值在 seal 前消失。`compositionViewport` 保留 host
   placement/visible/opacity/transform，`sourceCanvas` 展开 child Canvas background/default、无 bleed，并按冻结
   preorder 分配连续 opaque occurrenceId。资源引用与 manifest 保持一对一 encounter order。
4. **Rust specialized validator**：新增独立 document crate，执行 strict canonical top-level/kind/member/default/
   content-model/placement/occurrence/resource-bijection 与业务/动态字段零残留检查；它只产生 admitted request-local
   document 或 closed internal violation，不做 layout、shaping、fetch、decode、raster、encode 或 Scene 持久化。
   daemon 在已完成 Command digest admission 后调用该 validator，再维持 NOT_REGISTERED terminal fail-closed。
5. **共同语料与 TDD**：新增 exact RenderDocument vectors，覆盖 16 static kinds、default-explicit positive、每族
   default omission/unknown/null/dynamic residue/PACK/root-kind/content-model/occurrence/resource mismatch negative、
   compositionViewport 与 canonical document digest。先让 Java/Rust/Python replay RED，再实现 catalog/sealer/
   validator；`render` gate 扩展三实现重放与 Linux `--network none` daemon round-trip。
6. **保证边界**：本票只把 Evaluator→Engine 的 immutable handoff 做完整。manifest 仍返回空 renderer profile、
   `NOT_REGISTERED`/`NOT_CERTIFIED`/`rasterImplementation=ABSENT`；合法文档仍没有成功 RESULT。Rust layout、font
   shaping、resource fetch/decode、pixels、PNG/JPEG、公开 render/preview、Editor、物理 Linux/J1/A3/READY 均另票。

## 允许影响

`renderweave-rendering` catalog/sealer/materializer/测试与共享 vectors、`renderer/` document crate/daemon/manifest、
`render` gate与独立 verifier、architecture tests、CONTEXT/tracker/plan/log/NOTES/evidence。

## 禁止影响

layout/measure/arrange/shaping、resource HTTP fetch/decode/font parse、raster/pixels/PNG/JPEG 或 synthetic image；
Renderer/Output Profile registration；公开 render/preview/diagnostic route、OpenAPI/Web SDK、Editor 产品代码；
Ticket 19 formal records；物理 Linux 双 CPU-family、J1/A3/READY；provider、真实数据、API key、push/tag/PR。

## 局部验证

TDD RED/GREEN；Java exact seal/default/lowering vectors；Rust strict document validator property/negative tests；Python
独立 canonical/default/digest replay；daemon malformed document fail-closed 且零 RESULT；catalog/vector exact SHA；
focused Rendering/app tests与 `cargo fmt/clippy/test --locked --offline`。

## 受影响验证

扩展后的 `render` → focused `server`/`fast` → 最终 `full`。Linux replay 只用 pinned Docker、`--network none`、
只读源码 mount；输入未变的既有证据可按 RULE-VAL-001 复用，但最终提交前重新捕获整树 manifest。

## 保证等级与完成信号

工具捕获为 A1；Java/Rust/Python 对同一 exact catalog/vectors 的独立重放与 Linux daemon path 在文档合同输入边界
记 A2；无 A3/J1。完成时 Ticket 23 只能标为 `resolved / automated_verified`：RenderDocument 不再含 default
omission 或 authored/dynamic residue、Rust validator 已进入 daemon path、全部门控绿色、worktree clean 且形成
verified local commit；Renderer/Profile/Template v1 仍不 READY，不 push/tag/PR。

## Resolution（2026-08-21）

1. `render-node-contract-v1.json` 已深化为 `renderweave-render-node-contract/2`，由同一份 exact bytes 冻结
   16 个 static kind、common/kind/object/placement default、closed emitted members、source Canvas、
   RenderResource 与 authored/dynamic residue；catalog SHA-256 为
   `55e9062e2b988c1bc878d216c850e3ada7e6584b19ae2aca7ce2765d2a3b9752`。
2. Java `Materializer` 已在结构节点上应用 Binding overlay，并把 Repeat 的 PACK 语义确定性降低为
   Stack/Grid 层级；outer/per-item、Conditional frame 与 compositionViewport 等生成节点也计入冻结 static-node/
   occurrence 上限。`Sealer` 经 catalog 展开全部可表达 default、输出显式 root kind/空容器 children、保留
   compositionViewport host common fields、lower AssetRef 为 resourceId，并冻结连续 preorder occurrenceId。
3. 新增独立 Rust `renderweave-renderer-document` crate；daemon 在 Command digest admission 后、Profile lookup
   前执行 strict canonical/member/default/content/placement/occurrence/resource-bijection admission。digest-valid
   但合同非法的文档稳定返回 `RENDER_INTERNAL_ERROR/DOCUMENT_ADMISSION`，不会产生 RESULT。
4. 共同语料包含 2 个 positive 与 12 个 negative mutation；Java 生产端、Rust validator 与 Python 标准库
   verifier 共同重放。16-kind canonical content SHA-256 为
   `1b83a605c13837b0fa6d3a3cbf5e84fb97c71116ba8a81942cf97a3d7df9b031`，对应 RenderDocument digest 为
   `sha256:d059aa1303f0fa66c96ee186e2b4f763f5292dd6c63d53cd30eee7d34f2ba612`。
5. 首轮 `render` gate 捕获 Java HELLO capability 漏列 `render-document-v1`，补齐 app 协议常量后聚焦测试
   8/8 转绿；最终受影响证据为 render `.sdlc/evidence/20260821-005446-render/`、server
   `.sdlc/evidence/20260821-005605-server/`、fast `.sdlc/evidence/20260821-010746-fast/`。最终整树 `full`
   在所有记录冻结后捕获，其目录只在提交交接中报告，不反写本票以避免自指改变已验证输入。
6. manifest 继续明确空 profile、`NOT_REGISTERED`、`NOT_CERTIFIED`、raster `ABSENT`；无 layout、fetch/decode、
   shaping、pixels、codec、公开 route、J1/A3/READY 或外部 provider 副作用。
