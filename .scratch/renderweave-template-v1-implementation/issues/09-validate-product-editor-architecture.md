# 验证 Product Editor 状态、恢复与权威预览架构

Type: prototype
Status: resolved
Claimed by: Codex `/root`
Blocked by: 06, 07, 08

## Question

在真实 Template API/canonical baseline、Evaluator seam 与 Renderer protocol 可用后，怎样用不进入产品 route 的 throwaway prototype 验证 Product Editor 的 revision-aware state、Structured/Raw Repair/Compatibility 模式、dirty replacement guard、local recovery、save conflict/unknown reconciliation、current-only authoritative preview、failure withdrawal 和 accessibility flow，并明确哪些既有 Canvas Focus 视觉决定可复用、哪些内存 prototype 状态模型必须丢弃，才能把后续产品 Editor 实施拆成无占位的一会话纵切？

## Answer

T09 以 throwaway 逻辑原型（`web/src/prototype/editor-state-model/`，路由 `/prototype/editor-state-model`，
明确标注非产品代码、无持久化、无真实 API）把冻结编辑器规则（旧 map ticket 18）编码为确定性 fixture 状态
机，并通过浏览器自动化观察（A1）+ 人工 J1 验证；原型与结论已按 J1 验收，产品 Editor 不开放 route。

**原型形态**：`model.ts` 是纯状态机（fixture 服务器：revision/contentHash/canonical DSL、保存/冲突/
unknown/漂移/删除/完整性注入；Session：baseline/working draft/canonical dirty、三模式、undo/redo、
mutation 单飞锁、overwrite 确认（含 offeredRevision 漂移重确认）、invalid 二次确认、recovery draft、
preview 单槽单活跃 + generation guard、reconcile 五分类、问题面板聚焦模拟）；`EditorStateModelPrototype.tsx`
提供自由操作面板（每次动作全量状态刷新 + 事件日志）、10 个引导走查场景（每步带可执行断言）与结论页。
走查覆盖：打开与基线（重检完成前预览禁用）、编辑→保存成功（canonical baseline 重建/undo 清空）、冲突与
覆盖重确认（确认前/后再次漂移必须重新确认）、unknown→Save reconciliation 全分支（adopted/retryable/
conflict/deleted/fail closed）、预览 basis 失效与 generation guard（迟到结果丢弃）、保存并预览顺序非原子
（保存成功与预览失败分别呈现）、Local recovery 生命周期（base==current 直恢复，否则先显示基线变化再确认）、
dirty replacement guard（导入/恢复共用）、三模式切换（Raw Repair 无结构视图、Compatibility 只读、
Structured 显式进入）、失败撤下与可访问性流（旧权威图撤下 + 问题摘要聚焦 + hard error 零写）。

**验证**：`tools/editor_state_model_audit.py` 以 Playwright（独立 python 工具链）驱动
`/prototype/editor-state-model`：10 场景 37/37 断言全部通过、自由操作冒烟（打开→编辑→保存→状态面板
+ 键盘 Tab 焦点检查）、console/page 零错误；截图与 JSON 证据在 `.sdlc/evidence/t09-prototype-observation/`
（A1 工具捕获）。开发中修复的模型缺陷（原型自身验证价值）：场景间 fixture 未重置、编辑必须使在途预览
失去展示资格而非取消（generation guard 语义）、`serverDriftTo` 需按 canonical hash 对齐、
Raw Repair/Compatibility 模式判定顺序（malformed→raw repair、unsupported wire→compatibility、
unsupported profile→raw repair）。web typecheck/lint/unit tests（76/76）与 `fast`/`web` gates 绿。

**结论（Verdict，J1 验收）**：
- 可复用（T17 Canvas Focus）：固定物理画布居中 + 左导航（结构/节点/资产/定义/交换）+ 右检视器 + 底部
  dock；顶栏持续显示 Template 身份/永久 StaticSchema/readiness/revision/保存状态；画布只提供非权威编辑
  反馈、权威预览是独立动作；问题面板统一键盘可达、失败聚焦摘要。
- 必须丢弃（刷新即失/无契约的内存状态模型）：无 baseline（revision/contentHash/canonical）的裸 working
  copy 与表单 touched 式 dirty 判定；无 generation guard 的预览槽；无条件 last-write-wins / 无
  reconciliation 的保存；无模式边界的 single editor；场景切换器当正式导航；本地 UUID 职责不分。
- 验证通过的状态架构：单一 canonical baseline + 工作副本 + 结构命令 undo/redo（跨 baseline 清空）；
  mutation 单飞（unknown 期间禁止新 mutation/preview/清 recovery）；conflict overwrite 确认→重读→再提交
  →再漂移重新确认；invalid 确认绑定完整未截断问题集；preview current-only basis（revision/hash/无本地
  分歧/样例/format/DPI/quality）+ 单槽单活跃 + save-and-preview 顺序非原子 + generation guard；Local
  recovery 每 Template 一份（base+草稿+时间）、base==current 直恢复否则先显示基线变化再确认；dirty
  replacement guard 覆盖导入/migration 接受/恢复；三模式显式互斥。
- 后续产品 Editor 实施纵切（占位-free，逐会话，各自前置满足后按 single-writer 登记）：E1 open→baseline+
  重检+三模式骨架；E2 本地编辑+canonical dirty+undo/redo+preview 失效/guard；E3 save+conflict overwrite；
  E4 依赖 ERROR 二次确认+hard error 零写；E5 unknown→reconciliation 全分支+锁纪律；E6 save-and-preview+
  预览槽+失败撤下+问题面板聚焦；E7 Local recovery 生命周期；E8 import+Raw Repair/Compatibility+dirty
  guard；E9 a11y（WCAG 2.2 AA 键盘流/live region/200% zoom/不支持宽度状态）与问题定位投影。

保证等级：浏览器自动观察为 A1（Playwright 工具捕获、断言即模型断言），叠加人工 J1；无 A2 独立重放、
无 A3；不开放产品 route，无 Editor 产品代码。Ticket 19 open，Editor 未 READY；T12b 仍以 Template 依赖
投影票为 blocker，T13 以首个 Rendering 实现票为前置；push 待用户另行授权。
