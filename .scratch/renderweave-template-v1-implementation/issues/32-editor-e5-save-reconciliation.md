# 实现 Editor E5 outcome-unknown Save reconciliation

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 09, 29, 30, 31（均已 resolved）

## Question

如何在 Template save、conflict overwrite 或 INVALID confirmation 已发出但 transport 结果不明时，保留 exact
working draft 与 authored mutation lock，并只依赖原 expectedRevision、proposed contentHash 和重新读取的 trusted
current，完整区分 adopted / retryable / conflict / deleted / fail-closed，避免盲重试、错误归因、可疑 baseline
adoption 或丢失作者草稿？

## Answer（本票冻结的实施决定）

1. **写前冻结 attempt**：任何可能发出 PUT 的路径都必须先计算 proposed contentHash，并冻结 exact expected current
   （Template/StaticSchema identity、revision、contentHash、canonical bytes）、working canonical、preview generation、
   期望 readiness 与可选 INVALID confirmation 凭据。无法先建立该上下文时不发写入；unknown result 必须携带它。
2. **只读 reconciliation**：unknown 后保持 mutation lock，先 GET trusted current；不自动重发 PUT，不把 timeout/
   malformed success/未知 HTTP 报成成功或失败。GET 继续复用 E1 strict JSON/canonical/hash integrity parser。
3. **五分类**：
   - current revision 大于原 expectedRevision，且 contentHash 与 canonical bytes 都等于 proposed draft：采用 trusted
     current，清空 history，报告“内容已在服务器确认”，但不声称是某一具体请求提交；
   - current 精确等于 expectedRevision，且仍匹配写前 trusted current：保持草稿锁，只开放具名显式重试；
   - current 已前进且 hash 不同：转入既有 conflict overwrite offer，并绑定最新 revision + 原 draft/generation；
   - GET 精确返回 `410/TEMPLATE_DELETED`：进入不可编辑、不可保存的 DELETED 只读/导出状态；
   - revision 回退、同 revision 内容漂移、identity mismatch、integrity mismatch、hash 相同但 canonical 不同、revision
     耗尽或其他无法解释状态：fail closed，继续锁定并保留可导出草稿。
4. **暂时不可读**：network/503 只保持 unknown，允许重新核验 current；401/403/404/500、非合同 410 或 malformed
   current 不伪装成临时网络故障。Abort 只用于组件卸载，不改变领域分类。
5. **显式 exact retry**：retry 复用被冻结的 expectedRevision/body/可选 confirmation token；按钮点击前重新校验
   attempt 与当前 session/draft/generation/proposed hash。新的 200/409/422/unknown 仍走 E3/E4/E5 同一 closed result
   interpreter；不增加 command-key、auto retry 或 generic force。
6. **锁与逃生路径**：pending、unknown/reconciling、retryable、deleted、fail-closed 都禁止 edit/undo/redo/save/preview。
   unknown 系列提供 bare canonical DesignDSL 下载，文件不携带权限或目标 identity；这是本票必需的草稿逃生路径，
   不实现 E8 import/revision envelope/migration。
7. **边界**：Web-only，复用现有 GET/PUT 与 `410`；不修改 Java、migration、OpenAPI、generated SDK、API version 或
   route。不实现 E6 preview action/result、E7 Local recovery 持久化与跨刷新 resume、E8 import、E9 完整 locator/a11y、
   copy/restore、Renderer/Profile/formal records/J1/A3/READY。产品 route 继续关闭。

## TDD、验证与完成信号

- 先写 pure RED：三种 mutation 均携带 attempt、五分类、network/503 保持 unknown、删除精确 code、rollback/
  identity/integrity/canonical mismatch fail closed、显式 retry exact wire 与 invalid token expiry。
- 再写 DOM RED：unknown 自动只读核验、不可编辑/保存、retryable 只有具名重试、adoption 文案不归因、conflict
  复用重确认、DELETED/fail-closed 锁定，以及各未知终态的 exact draft export。
- focused Node 24 tests → `web` → `fast` → 最终 `full`；无服务端/migration/API gate 增量。
- 完成后改为 `resolved / automated_verified`，更新 map/plan/log/NOTES，形成单一 verified local commit 且 worktree
  clean；不 push/tag/PR，不升级 Editor/Renderer/Template v1 READY。

## 完成结果（2026-08-21）

- `template-save` 现在在任何 PUT 前复核 write-time trusted baseline hash，并冻结 expected current identity/revision/
  contentHash/canonical、exact draft、proposed hash、generation、required readiness 与可选 confirmation。正常 save、
  overwrite 与 INVALID confirmation 的 network/unknown HTTP/untrusted 200 都携带同一个可核验 attempt。
- 新 reconciliation deep module 只读 GET trusted current，lossless 比较 int64 revision，并完成 adopted/retryable/
  conflict/deleted/fail-closed 五分类；network/503 保持 unknown，只有精确 `410/TEMPLATE_DELETED` 进入删除态。
  rollback、同 revision 漂移、identity/integrity/canonical mismatch 与 revision exhaustion 全部 fail closed。
- retryable 只开放作者具名点击，复用 exact expectedRevision/body/可选 token；attempt、session、draft、generation、hash、
  token/expiry 任一失配都在 I/O 前失效。重试后的 200/409/422/unknown 复用 E3/E4/E5 closed interpreter。
- Canvas Focus 在 unknown 后自动核验 current；pending/unknown/reconciling/retryable/deleted/fail-closed 均保持编辑、
  undo/redo/save 锁。收敛采用 trusted current、清空 history，并明确“不代表具体请求归属”；后续 readiness 未重检前
  仍阻止权威 preview。未知/删除/fail-closed 可下载 exact bare canonical draft。
- E5 pure + DOM focused 为 3 files/44 tests；正式 Node 24 `web` 为 20 files/139 tests，SDK generation、typecheck、lint
  与 2144-module production build 全绿：`.sdlc/evidence/20260821-145017-web/`。`fast` 证据为
  `.sdlc/evidence/20260821-145156-fast/`；最终 exact-manifest `full` 目录只在 commit handoff 记录。
- 无 Java、migration、OpenAPI、generated SDK、API version 或 route 差异；未实现 E6–E9、跨刷新 Local recovery、
  Renderer/Profile/formal records/J1/A3/READY。Provider attempts/API Key reads/open authorization 均为 0；未发送真实
  数据、未调用付费外部模型，未 push/tag/PR。
