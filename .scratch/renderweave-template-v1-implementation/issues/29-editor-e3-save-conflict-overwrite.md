# 实现 Editor E3 lossless save 与 conflict overwrite 重确认

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 09, 28（均已 resolved：Editor J1 状态合同、E2 canonical working copy/history/guard）

## Question

如何在不扩张 E4–E9 的前提下，把既有 Template append API 接入未发布 Product Editor：只保存 exact canonical
working copy，以 lossless expectedRevision 做并发控制；成功后采用服务器核验的下一 baseline 并清空 history；冲突时
让覆盖确认绑定远端 current revision 与本地 draft generation，确认后先重读 trusted current，任何再次漂移都必须
重新确认；而 transport 结果不明时绝不谎报失败、盲重试或提前伪造 E5 reconciliation？

## Answer（本票冻结的实施决定）

1. **沿用现有服务端合同**：本票只消费既有
   `PUT /api/v1/templates/{templateId}?expectedRevision={int64}` 与 GET current，不修改 Java、OpenAPI、migration 或
   persistence。生成 SDK 把 int64 映射成 JS `number`，故 Editor save transport 必须以已验证的十进制 revision token
   直接构造 query，并以 `application/vnd.renderweave.design+json` 发送 working canonical UTF-8。
2. **初次 save**：仅 Structured + canonical dirty + mutation idle 时显示并允许真实保存。请求期间锁定 name edit、
   undo/redo 与重复 save，且请求体、expectedRevision、previewGeneration 均绑定发起时 session；clean 不发送无意义
   save，同一次 mutation 不并发。
3. **成功采用**：200 必须是 lossless READABLE current，重新校验 contentHash；templateId、permanent StaticSchema、
   canonical DesignDSL 必须与请求一致，revision 必须精确等于 expected+1，readiness 必须为 `READY | INVALID`。全部
   满足才建立新 immutable baseline/working copy，清空 history/conflict，canonical clean 且 preview generation 重置；
   malformed/opaque/drifted success 视为 outcome unknown，不能冒充成功。
4. **conflict offer**：只有 409 + `TEMPLATE_REVISION_CONFLICT` + lossless `currentRevision` 形成覆盖 offer；offer 绑定
   `{offeredRevision, draftCanonical, previewGeneration}`。任何本地 edit/undo/redo 都使 offer 失效并要求重新发起 save；
   无 currentRevision 的 readable conflict 保留草稿但 fail closed，不提供覆盖动作。
5. **显式覆盖与重确认**：作者确认后先 GET 并完整校验 trusted current；若 revision 不等于 offeredRevision，只更新
   offer 并要求再次确认，绝不 PUT。若相等，才以该 revision 和原样本地 canonical 再 PUT；第二次 409 同样生成新
   offer。GET 暂不可用没有写副作用，可保留 offer供显式重试；取消只清 offer，不改 draft。
6. **结果不明边界**：PUT 的网络异常、500/503、malformed/不一致 2xx 都进入真实 `outcome-unknown` mutation lock，
   保留本地 draft，不报告成功/失败，不允许 save/overwrite/preview 或替换 baseline。本票只显示“需 E5 reconciliation”
   的闭合说明，不提供虚假 retry/adopt/reconcile action；E5 才实现 trusted-current 五分类解锁。
7. **已知零写拒绝**：400/401/403/404/409 非 revision-conflict/410/413/415/422 只显示有限 code/message并保留 dirty draft；
   不在本票投影完整问题集、依赖 ERROR 二次确认或 pointer focus（E4），也不实现 Local recovery/import/preview。
8. **发布边界**：save CTA、pending 状态、conflict confirmation/cancel 与 unknown/rejected status 都是真实行为，但组件
   继续不接 `/templates/:templateId` 产品 route；无 save-and-preview、reconciliation、recovery/import、E9 问题面板
   或 disabled future placeholder。

## 验证与完成信号

- TDD：先纯 save coordinator RED，覆盖 int64 query/body、成功 identity/hash/+1/adoption、409 offer、确认 GET→PUT
  顺序、确认前/后漂移重确认、draft generation 失效、known rejection、unknown lock；再以 DOM RED 覆盖真实 save、
  pending lock、success clean/history reset、conflict confirm/cancel/reconfirm 与 unknown 无盲重试。
- 局部：focused Vitest → Web 全量 test/typecheck/lint/build；不调用真实服务或 provider。
- 受影响：`web`、`fast`，最后完整 `full`；OpenAPI generated output 必须无差异。
- 可访问性：save/pending/conflict/unknown 使用明确文字与 polite/alert 语义；确认按钮写明覆盖的 revision，锁定控件有真实
  disabled 状态，键盘可完成确认或取消；视觉继续遵循锁定 `design.md`，不引入装饰动效或全局 z-index。
- 保证上限：Web/model/contract gate 为 A1；组件仍未发布 route，人工 Editor J1、E4–E9、A3、Renderer certification
  不在本票。
- 完成：所有 gate 绿色后改为 `resolved / automated_verified`，形成一个 verified local commit 且 worktree clean；
  不 push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。Editor/Renderer/Template v1 仍不 READY。

## Result

- 新增 closed save transport/coordinator：lossless int64 query、canonical media/body、严格 200 adoption、409 offer、
  confirm 前 trusted GET、revision 漂移重确认、known no-write rejection 与 conservative outcome-unknown lock 均已实现。
- Canvas Focus shell 已接入真实 save/pending/conflict/reconfirm/cancel/rejected/unknown 状态；pending 与 unknown 会锁定
  edit/undo/redo/save，成功采用新 baseline 并清 history，unknown 不暴露盲重试、adopt 或 reconciliation 动作。
- focused Vitest 为 5 files / 43 tests；正式 Node 24 Web 为 19 files / 119 tests，SDK generation 无差异，
  typecheck、lint 与 2144-module production build 全绿。
- `web` `.sdlc/evidence/20260821-094726-web/`、`fast` `.sdlc/evidence/20260821-094809-fast/` 与最终
  `full` `.sdlc/evidence/20260821-094832-full/` 全绿；Full 17/17 steps，1048.706s，R0/R1/P0 provider attempts=0。
- 未新增 Java/OpenAPI/migration/generated SDK/API/route，未实现 E4–E9；无 provider、真实数据、API Key、push/tag/PR。
  生命周期上限为 `automated_verified`，不声明 Editor、Renderer 或 Template v1 READY。
