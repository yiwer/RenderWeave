# 实现 Editor E7 Local recovery 生命周期与跨刷新 Save reconciliation

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 09, 27, 28, 29, 32（均已 resolved）

## Question

如何在不新增服务端 autosave/Patch、不同步跨设备、也不提前开放产品 route 的前提下，把 Structured Editor 的
canonical working copy 作为当前设备上每个 Template 至多一份的 best-effort Local recovery draft：严格记录与
校验 base identity、完整草稿、最小编辑状态和更新时间，支持显式恢复/导出/放弃、7 天过期与 bounded debounce，
并让 E5 outcome-unknown attempt 在刷新后先恢复只读 reconciliation，而不自动提交或覆盖 trusted current？

## Answer（本票冻结的实施决定）

1. **单一 deep module 与版本化本地合同**：新增 Web-owned `template-recovery`，以
   `renderweave-template-local-recovery/1` envelope 和每个 `templateId` 唯一 key 封装存取、严格解析、完整性、
   过期、session 重建及导出；默认 adapter 使用当前浏览器 `localStorage`。配额、隐私模式或浏览器回收都可能让
   写入失败，因此结果必须在 UI 如实可见，Local recovery 不构成持久性承诺。
2. **最小且封闭的数据面**：记录 permanent Template/StaticSchema identity、base revision/contentHash、完整 canonical
   DesignDSL working copy、domain-separated draft hash、preview generation、更新时间和最小 UI 状态（入口、选中
   authored node、左右面板开合）；可选保存 E5 exact unknown attempt。合同拒绝 unknown member，不记录
   RootDocument、customValues、权威/本地预览图片、Asset bytes、RenderDocument、sidecar、lease 或其他运行输入。
3. **写入与清理纪律**：Structured authored command、undo/redo 或最小编辑状态变化后，对 dirty session 以固定
   500ms bounded debounce 原子替换同一 key；`beforeunload` 仅做平台允许的同步 best-effort flush。成功 save/adopt
   进入 clean、undo 回 clean 或作者明确放弃时清除；超过 7 天的记录读取时清除。观察到远端 DELETED 不自动删除
   未保存草稿（保留导出），与未来由作者完成的显式 Template 删除动作区分。
4. **严格恢复而非自动采用**：普通记录再次打开时只显示“恢复 / 导出 / 放弃”，不自动载入或提交。base revision/hash
   与 trusted current 相同可显式恢复为 dirty session；若 current 已前进，先持续显示旧/新 baseline identity，再经
   具名确认恢复。恢复后 history 清空，后续首次 save 必须先走既有 conflict overwrite offer/confirm，而不能以最新
   revision 静默覆盖。
5. **dirty replacement guard**：未处理的恢复 offer 先锁定 authored mutation；恢复动作再次核对当前 session 必须
   clean。已恢复草稿提供 exact bare DesignDSL 导出与显式放弃；放弃整体回到 trusted current、清 history、递增
   preview generation 并清理记录。损坏或不兼容记录绝不装入 Structured Editor，但若能安全取得原始 draft string，
   仍允许导出后显式清理。
6. **跨刷新 unknown 优先恢复**：E5 unknown 产生后立即写入 exact attempt，不等待 debounce，也不允许清 recovery。
   再次打开时先严格核验 envelope、draft hash、expected-current hash、attempt identity/generation/confirmation shape；
   通过后重建锁定的 working session并自动只读 GET trusted current，复用 E5 adopted/retryable/conflict/deleted/
   fail-closed 分类。该流程不自动重发 PUT；retry 仍只能由作者具名点击且继续受 expiry/identity 校验。
7. **边界**：Web-only；不修改 Java、OpenAPI、generated SDK、migration、API version 或 route。不实现 E6 preview
   endpoint/result、E8 import/migration、E9 完整问题定位/a11y、内部产品导航 blocker、Renderer/Profile/formal
   records/J1/A3/READY。无真实数据、provider、API Key 或外部付费调用。

## TDD、验证与完成信号

- Pure RED：version/exact keys、canonical/hash/identity、one-record replacement、500ms 调度所需 payload、7 天边界、
  storage quota/failure、普通 restore 同基线/漂移、损坏 fail closed、unknown attempt 完整性与 session 重建。
- DOM RED：首次打开不自动采用；恢复/导出/放弃；漂移确认；dirty/offer 锁；恢复后首存显式 overwrite；save clean
  清理；unknown 即时持久化与刷新后自动 reconciliation；`beforeunload` best-effort flush。
- focused Node 24 tests → `web` → `fast` → 最终 `full`；无服务端/migration/API gate 增量。
- 全部 gate 绿色后才改为 `resolved / automated_verified` 并形成一个 verified local commit；不 push/tag/PR，不升级
  Editor/Renderer/Template v1 READY。

## Resolution

- 新增 Web-owned `template-recovery` deep module，严格验证并深冻结 versioned per-Template envelope；覆盖 exact
  canonical/hash/identity、500ms debounce、7 天边界、storage failure、ordinary/drift restore、损坏 fail closed 与
  E5 exact unknown attempt 重建。不同 Template identity 使用独立 key，未知成员或不完整 attempt 均拒绝装载。
- Canvas Focus 提供显式恢复/导出/放弃、漂移具名确认、recovery mutation lock、clean/discard 清理和
  `beforeunload` best-effort；恢复草稿首次 save 复用既有 overwrite 流程。PUT 前 best-effort 立即持久化 unknown
  attempt，刷新后只读 GET reconciliation，绝不自动重发 PUT；remote DELETED 保留 exact draft export。
- TDD 结果：focused pure + DOM 2 files/17 tests，完整 Editor 8 files/79 tests。正式 Node 24 Web 为 22 files/156
  tests；证据为 `.sdlc/evidence/20260821-175915-web/`、`.sdlc/evidence/20260821-175957-fast/` 与最终 full
  17/17（exact-manifest 路径仅在 commit handoff 报告）。
- 审计确认无 Java/OpenAPI/generated SDK/migration/API version/route 增量，无禁区数据落盘；provider attempts、
  API Key reads、open authorization、paid external calls 均为 0。T35 只达到 `automated_verified`，不证明 E6/E8/E9、
  产品 route、Editor J1、Renderer/Profile/formal records/A3/READY；未 push/tag/PR。
