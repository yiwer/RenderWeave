# 实现 Editor E6 权威预览纵切

Type: task
Status: in_progress
Claimed by: Codex `/root`（single-writer）
Blocked by: 09, 27, 28, 29, 30, 31, 32, 35, 36, 37, 114, 124（均已 resolved）

## Question

E1–E5 与 E7–E9 已形成真实 canonical Editor、保存/确认/冲突/reconciliation、Local recovery、导入与可访问问题定位，
T124 也已开放 server-selected Authoritative Preview HTTP seam；但产品 Editor 仍只有前置条件提示，没有真实输入样例、
输出选择、单槽 operation、完整响应核验、失败撤图或 save-and-preview。如何在不把本地画布/fixture/旧图冒充权威结果、
不持久化 RootDocument、不泄漏 Engine identity、也不提前开放正式 `/templates` route 的前提下闭合 E6？

## Answer（本票冻结的实施决定）

1. **Web-owned preview deep module**：新增单一 `TemplatePreviewTransport`，向 T124 的
   `POST /api/v1/templates/{templateId}/authoritative-preview` 原样发送本地 strict RenderInput UTF-8 JSON bytes；
   客户端不 parse/rewrite 后再发送，因此 duplicate member、number token 与 trailing content 仍由服务端 strict authority
   裁决。客户端只做空值、8 MiB、`PNG|JPEG`、DPI `1..600` 与 JPEG quality `1..100` 的安全前置。
2. **完整成功才可展示**：200 响应必须同时通过 exact media type、`Content-Length`、raw SHA-256
   `Content-Digest`、result/DSL version、public UUID v4 operation identity、Renderer/Layout/Output Profile、format、
   dimensions、DPI 与 JPEG-only quality 核验；任一缺失、矛盾、截断或 digest mismatch 均删除临时 bytes并形成客户端
   integrity problem。只有全部通过后才创建 object URL并原子替换唯一结果槽。
3. **closed failure**：非 200 只接受 T124 exact render-problem media 与 closed JSON envelope；network、malformed response、
   unsupported Web Crypto 或服务端 problem 都以具名摘要呈现并聚焦，旧图片立即撤下，不显示 partial bytes、placeholder、
   browser-rendered fallback 或旧成功结果。安全 location/limit/public operation ID 可展示；Engine requestId、
   RenderDocument、lease、digest 与 sidecar 不进入 UI。
4. **current-only basis 与 single-active**：basis 绑定 exact saved `{templateId, revision, contentHash}`、无本地内容分歧、
   preview generation、原始输入样例 bytes、format/DPI/JPEG quality。任何 authored edit/undo/redo/import/recovery、readiness/
   baseline、输入或参数变化都立即撤下 result并使 active operation失去展示资格；新请求先撤下旧槽、abort旧 fetch，迟到结果
   由 operation + basis generation guard丢弃。普通面板开合与节点选择不影响 basis。
5. **save-and-preview 顺序非原子**：clean READY 直接发起 preview；dirty 主动作先走既有完整 save，并把 preview intent绑定
   当前 canonical draft/generation与参数。只有 saved 或 reconciliation-adopted 的新 current仍为 READY 才独立发起 preview；
   conflict/unknown 的既有显式确认与重试纪律保持，INVALID commit/reject/delete/fail-closed不启动 preview。保存成功与随后
   preview失败分别呈现，后者绝不回滚已追加 revision。
6. **本地样例与诚实停止**：Editor 内显式显示并控制一份默认 `{"rootDocument":{}}` 本地输入样例；它不写入 DesignDSL、
   Template revision、Local recovery 或日志。T124 尚无 public cancel endpoint，因此“停止等待”只 abort浏览器 fetch、
   撤下 result并拒绝迟到响应，同时明确服务端 operation可能继续；本票不虚构 Engine cooperative cancel。
7. **产品 UI 边界**：沿 Canvas Focus 的中央独立 preview panel与 dock具名动作实现 44px 控件、可见 label、pending feedback、
   `role=alert` + 程序聚焦失败摘要、responsive image与文本化 metadata。Raw Repair/Compatibility 不出现 preview。
   本票不挂载 `App`/导航；E1–E9 与产品页 substrate 全部真实后，由下一独立激活票开放正式 routes并做浏览器验收。
8. **明确排除**：不修改 Java/OpenAPI/generated SDK/migration、Renderer/Profile registration/certification、daemon success、
   native build、LayoutTrace/public cancel、preview history/并行槽/自动重跑、输入持久化、fixture/fake raster、`/prototype`、
   provider/API Key/真实数据/生产/J1/A3/READY。

## TDD 与验证

- 先新增 pure transport/coordinator tests，让旧代码因 module/contract缺失 RED；覆盖 raw bytes/query、local admission、closed
  problem、success全部 headers + digest、truncation/mismatch、one-active/abort/generation guard所需 basis。
- 再新增 DOM RED，覆盖 clean preview、dirty save-and-preview、conflict/reconciliation延续、INVALID不预览、参数/编辑撤图、
  failure focus、停止等待/object URL revoke与非 Structured模式隐藏；然后实现最小 GREEN并重构。
- focused Node 24 → 全量 Editor/Web test/typecheck/lint/build → `web` → `fast` → Goal `full` → resolution `fast`；
  Maven串行、精确 staging，不纳入既有 Image-Only dirty work。最高只报 `automated_verified`。
