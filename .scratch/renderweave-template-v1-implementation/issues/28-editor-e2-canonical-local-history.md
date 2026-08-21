# 实现 Editor E2 canonical working copy、结构化撤销/重做与 preview guard

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 09, 27（均已 resolved：Editor J1 架构结论、E1 trusted canonical open 与三模式 shell）

## Question

如何把 T09 已验收的 E2 物化为真实且不越界的 Product Editor 增量：Structured 模式在 immutable canonical
baseline 之上建立 canonical working copy，以有界结构命令提供本地编辑、undo/redo 与 canonical dirty，并使
任何 working-copy 变化都失效旧 authoritative preview generation；同时在 save、preview endpoint、recovery
尚未实现时，不用按钮或假结果槽冒充后续 E3–E9 能力？

## Answer（本票冻结的实施决定）

1. **baseline 与 working copy 分离**：只对 E1 已完整验证的 Structured baseline 建立本地 working copy；baseline
   的 canonical bytes、parsed DesignDSL、revision 与 contentHash 永不修改。Raw Repair 与 Compatibility Read-only
   不获得结构化 working copy 或 history。
2. **首个真实结构命令**：本票只实现必需 top-level `displayName` 的 `set-template-display-name` 命令。输入按 Java
   `String.trim()` 同义边界移除两端 `U+0000..U+0020`，并按 Unicode code point 校验 1..128；对象成员递归按
   UTF-8 byte order 排序，decimal/int64 继续由 lossless value 保真，输出 compact canonical JSON。无效输入零变化，
   canonical no-op 不产生 history、不递增 generation。
3. **canonical dirty**：dirty 的唯一事实是 working canonical bytes 与 baseline canonical bytes 是否全等；不能以
   touched、焦点、表单草稿或 history 非空代替。所有画布、结构、资源、定义与 inspector 投影都读取 working copy，
   不再读取 baseline parsed object。
4. **结构化 history**：undo/redo 记录 closed command `{kind, before, after}`，最大 100 条；新有效编辑清空 redo，
   undo/redo 都重放 canonical value 而非文本 patch。跨 baseline identity（templateId/revision/contentHash）重建
   session 并清空 history；同 baseline 的 readiness 更新不得覆盖 dirty working copy。
5. **preview generation 与 eligibility guard**：每次有效 edit/undo/redo 都单调递增 `previewGeneration`，使此前任何
   future preview result 不再 eligible。guard 只有在 canonical clean 且 fresh readiness 为 `READY` 时标记
   current-only preview eligible；dirty、checking、unavailable 或 `INVALID` 均给出 closed blocker。本票只展示真实
   guard 状态，不增加 preview action、endpoint、结果槽或 synthetic image。
6. **E1 重检协作**：clean session 可继续执行 E1 完整 reopen/recheck；dirty session 不触发会替换 baseline 的 reopen，
   并明确说明本地草稿已保留。父组件对同 baseline identity 的新 readiness prop 只更新 authority 状态；只有 baseline
   identity 改变才 remount Structured session。
7. **发布边界**：Canvas Focus 顶栏显示 working name、canonical clean/dirty、undo/redo 与 preview guard；画布标为
   “本地草稿投影 · 非权威”。组件仍不接 `/templates/:templateId`，不实现 save/conflict/overwrite、dependency
   confirmation、reconciliation、authoritative preview、recovery、import、Asset picker 或 E9 problem projection。

## 验证与完成信号

- TDD：先以纯 session tests 捕获 canonical edit/no-op/validation/dirty/history/generation/guard 缺位 RED；再以 DOM
  tests 捕获真实 name edit、undo/redo、working projections、dirty 重检保护与同 baseline readiness 更新保留草稿 RED。
- 局部：focused Vitest → Web 全量 test/typecheck/lint/build；canonical test 必须覆盖 Unicode member order、lossless
  decimal/int64、Java trim 同义边界、100-history eviction 与 undo/redo branch。
- 受影响：`web`、`fast`，最后执行完整 `full` 收口；本票无 Java/OpenAPI/migration/route 差异。
- 可访问性：表单有显式 label/error，undo/redo 的 disabled 状态真实，状态文本进入克制的 polite live region；键盘与
  reduced-motion 规则沿用 E1。视觉遵循锁定 `design.md`，不引入装饰性持续动画或全局 z-index 竞争。
- 保证上限：组件/模型/Node 24 Web gate 为 A1；产品 route 浏览器观察、人工 Editor J1、A3 与 Renderer 认证不在本票。
- 完成：所有 gate 绿色后改为 `resolved / automated_verified`，形成一个 verified local commit 且 worktree clean；
  不 push/tag/PR，不运行 provider，不读取 API Key，不发送真实数据。Editor/Renderer/Template v1 仍不 READY。

## Resolution（2026-08-21）

- Structured session 已把 E1 baseline 冻结为不可变快照，并以独立 lossless canonical working copy 承载本地
  `set-template-display-name` 命令；Java `String.trim()` 边界、Unicode code-point 长度、UTF-8 member ordering、
  decimal/int64 token 均有精确回归，并继续执行 16 MiB canonical 上限。canonical no-op 和无效输入保持零
  history/零 generation。
- canonical dirty 只比较 working/baseline canonical bytes；100 条 closed-command history 支持 undo/redo、分支后
  清空 future 与最旧项逐出。每次有效 edit/undo/redo 都递增 preview generation；preview guard 对 dirty、recheck
  pending/unavailable/invalid 全部 fail closed，只在 clean + fresh `READY` 时标记 eligible。
- Canvas Focus shell 已接入真实本地名称表单、undo/redo、working-copy 五区投影、dirty/recheck 协作与可访问状态。
  同 baseline readiness 更新保留草稿；revision/contentHash 改变才重建 session。Raw Repair/Compatibility 行为不变，
  且仍无 save/preview/import/recovery placeholder、API 或产品 route。
- TDD 最终为 focused 4 files / 28 tests；正式 Node 24 Web 为 18 files / 104 tests，SDK generation、typecheck、lint、
  2144-module build 全绿。A1 受影响证据：`web` `.sdlc/evidence/20260821-085127-web/`、`fast`
  `.sdlc/evidence/20260821-085203-fast/`；最终 `full` 17/17 目录按不可自指策略只在提交交接中报告。
- 生命周期为 `automated_verified`：未发布产品 route，未执行人工 Editor J1、A3 或 Renderer certification；Provider
  attempts=0、API Key reads=0、open authorization=0，未发送真实数据，未 push/tag/PR。Editor/Renderer/Template v1
  均不声明 READY。
