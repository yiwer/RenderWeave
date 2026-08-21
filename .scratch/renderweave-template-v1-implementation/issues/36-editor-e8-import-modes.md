# 实现 Editor E8 严格导入、Raw Repair/Compatibility 与 dirty replacement guard

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 09, 27, 28, 29, 32, 35（均已 resolved）

## Question

如何在不新增 API、migration、产品 route 或不透明 round-trip 的前提下，让当前 Template Editor 从本地字节严格导入
bare DesignDSL 或 exact revision export：保留目标 Template/StaticSchema 身份与原始 server baseline，通过 Structured、
Raw Repair、Compatibility 三种互斥模式如实表达理解程度，并让 import、recovery 与未来 migration 共享不会静默丢失
dirty working copy 的 replacement guard？

## Answer（本票冻结的实施决定）

1. **单一字节边界与严格 parser/canonicalizer**：新增 Web-owned `template-import` deep module，入口只接受
   `Uint8Array` 与可选文件名/media hint。以 fatal UTF-8、无 BOM、严格 JSON grammar、全层 duplicate-key 检测、
   lone surrogate 拒绝及 T08 冻结的 depth/member/item/value/string/token/16 MiB 上限解析；数字无损转为 plain decimal，
   `-0` 归一为 `0`，对象键按 unsigned UTF-8 排序，并应用 DesignDSL metadata trim 与已冻结 set-array sorting。
2. **两种输入合同**：接受 bare `application/vnd.renderweave.design+json`，以及 exact/closed
   `renderweave-template-revision-export/1.0` envelope。后者必须只有 `exportVersion`、`identity`、
   `staticSchemaRef`、`contentHash`、`designDsl`，且 hash 必须匹配 canonical DesignDSL。文件内 templateId、revision、
   schemaKey/versionTag 仅供显示与审计，永不替换当前编辑目标 identity 或 StaticSchema。
3. **互斥三模式**：当前 exact DesignDSL pair 且全部 wire member/union 可理解时进入 Structured；已知 wire 上的语义错误
   可作为 best-effort Structured 交给现有保存校验。非法 UTF-8/JSON、duplicate、超限、无法信任的 root/version 或不支持
   的 exact profile 进入 Raw Repair；完整 JSON、可信 exact pair 但含未知 wire member/union 时进入 Compatibility，保留
   原始完整字节，不局部反序列化或保存。
4. **Structured adoption 不写服务端**：接受导入后仍锚定原 server baseline，清空 undo/redo，递增 preview generation，
   将 canonical import 作为 dirty working copy；若 canonical 内容与 baseline 相同则为 no-op clean。导入不改变 readiness、
   current revision/hash 或 recovery authority，也不触发 PUT；之后只能走既有 normal save/conflict/reconciliation。
5. **共享 dirty replacement guard**：检查文件本身不改变 session；真正替换前，clean session 可直接确认，dirty session 必须
   明确选择“保存当前后继续”“导出/保留 recovery 后替换”“放弃当前后替换”或“取消”。save pending/unknown/
   reconciling/retryable/deleted/fail-closed、未处理 recovery offer 或 drift confirmation 期间不得替换；任何候选都绑定当前
   generation/identity，状态漂移后失效。replacement 接受时清历史并按 T35 生命周期更新或清理 recovery。
6. **可恢复且不伪造能力的模式 UI**：Raw Repair 保留原始 bytes，合法 UTF-8 时允许编辑文本、重新检查、下载原始/修复稿与
   显式丢弃；非法 UTF-8 只允许下载/替换/丢弃。Compatibility 只显示安全 metadata/reason、允许 exact 原样导出或换文件；
   当前没有 registered migration profile，因此只显示 truthful unavailable 说明，不渲染 migration action、Structured tree、
   preview 或 save。
7. **下载 seam 与边界**：导出经显式 `TemplateEditorDownload` adapter 交付 bytes/media type/filename，浏览器默认实现使用
   Blob/object URL，测试注入内存 adapter。Web-only；不修改 Java、OpenAPI、generated SDK、migration、API version、
   route 或 Renderer，不持久化 raw import bytes，不推进 E6/E9/formal records/J1/A3/READY，不运行 provider、读取 API Key、
   发送真实数据或调用付费外部服务。

## TDD、验证与完成信号

- Pure RED：严格 UTF-8/JSON/duplicate/limit、lossless decimal/canonical ordering/metadata normalization、bare 与 exact
  envelope/hash、外部 identity 不采纳、known-invalid Structured、unsupported profile Raw Repair、unknown wire
  Compatibility、manifest 全部 ADMITTED canonical vectors 仍为 Structured。
- Session/DOM RED：clean/no-op import、dirty 四分支 replacement guard、save 后继续与 generation drift 失效、无 PUT adoption、
  history/generation/baseline、Raw Repair repair/download/discard、Compatibility exact export/no migration action，以及 unknown/
  recovery 锁定。
- focused Node 24 tests → 完整 Editor tests → `web` → `fast` → 最终 `full`；无 Java/API/migration gate 增量。
- 全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；不 push/tag/PR，不升级
  Editor/Renderer/Template v1 READY。

## Resolution

- 新增 Web-owned `template-import` deep module，严格处理 fatal UTF-8/no-BOM、JSON grammar、全层 duplicate、
  Unicode scalar、九项预算、lossless decimal、metadata/set normalization、unsigned UTF-8 ordering、canonical cap 与
  domain hash；46 个 Java authority ADMITTED vectors 均保持 byte-identical Structured。
- bare DesignDSL 与 exact revision export（revision 0..signed-int64 max）均经 closed envelope/hash 核验；文件 identity/
  StaticSchema 仅展示。unknown closed wire 保留 exact bytes 进入 Compatibility，非法/超限/unsupported profile 进入
  Raw Repair，known-invalid wire 仍为 best-effort Structured。
- Structured adoption 锚定原 baseline/readiness 且不 PUT；dirty replacement 具备 save/export/discard/cancel 四分支，
  generation/identity、save unknown 与 recovery offer 均 fail closed。same-working no-op 不清 history/recovery。
- Raw Repair 支持文本重检、原始与修复稿下载、换文件/丢弃；Compatibility 只安全 metadata/exact export，当前无
  registered migration profile 或 action。下载统一走可注入 bytes adapter，raw bytes 不进入 recovery。
- TDD 为 focused 3 files/47 tests、完整 Editor 10 files/119 tests；Node 24 Web 24 files/195 tests、2144 modules build。
  A1 证据为 `.sdlc/evidence/20260821-190823-web/`、`.sdlc/evidence/20260821-190918-fast/` 与最终 full 17/17
  （exact-manifest 路径只在 commit handoff 报告）。
- 审计确认无 Java/OpenAPI/generated SDK/migration/API version/route/Renderer 增量；provider attempts、API Key
  reads、open authorization、paid external calls 均为 0。T36 只达到 `automated_verified`，不证明 E6/E9、产品
  route、Editor J1、Renderer/Profile/formal records/A3/READY；未 push/tag/PR。
