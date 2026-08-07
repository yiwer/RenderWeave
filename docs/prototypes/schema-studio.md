# Schema Studio 原型决策包

- 状态：J1 accepted（2026-08-08）；生产方向锁定为 A+B，C 仅作为特征来源
- 路由：`/prototype/schema-studio?variant=A|B|C`
- 性质：throwaway prototype；用于确认信息架构和交互，不是生产组件承诺
- 共享状态：三方案消费同一个内存 `EditorSession` reducer，切换不丢编辑内容

## 要验证的问题

1. 一层字段结构的主编辑体验应以表单、树状图还是账本为中心？
2. `fieldKey`、类型、存在性、约束、引用和 AI evidence 是否能在高密度桌面界面中同时被理解？
3. Form/Map 是否能成为同一编辑会话的无损投影，而不是两套数据模型？
4. 1024px 最小宽度、键盘替代操作、显式保存和只读 compiled preview 是否成立？

## 三种方案

| 方案 | 核心结构 | 优势 | 代价 | 建议 |
|---|---|---|---|---|
| A — Column Workbench | 左资源栏 / 中字段表单 / 右检查器 | 字段编辑路径最短；约束和 evidence 有稳定位置；适合日常工作 | 关系全貌较弱 | 作为默认编辑模式 |
| B — Map Studio | 一层根节点图 / 右检查器 / 底部诊断 | 引用、必填和整体结构一眼可见；适合理解和巡检 | 精细编辑依赖检查器；必须提供键盘/表单替代 | 作为同一 EditorSession 的“树状图”切换模式 |
| C — Schema Ledger | 密集表格 / 右侧 compiled preview | 批量比较快；编译结果与 DSL 并列 | 对首次设计偏重；与用户明确要求的两模式形成第三心智模型 | 不单列为 v1 模式；吸收账本密度和 preview 到 A |

## 推荐组合

采用 A+B 双模式：A 是默认且完整可操作的表单模式，B 是一层思维导图式结构视图；二者使用同一 `EditorSession`、selection、undo/redo 和 dirty 状态。把 C 的以下优点合并回 A：

- 可折叠的 compiled JSON Schema 只读预览；
- 密集字段摘要与搜索；
- 批量问题/AI evidence 状态条。

不保存图坐标；Map 的拖拽仅改变当前会话布局，字段排序必须有明确按钮和键盘路径。屏幕小于 1024px 时不提供缩水编辑器，而显示明确的桌面宽度要求。

## J1 结论

用户已接受推荐组合，并要求在生产实现中继续优化样式与人类可读性。P1–P4 因此按以下边界推进：

- A（Column Workbench）成为默认、完整可操作的 Form 模式；
- B（Map Studio）成为共享 `EditorSession` 的一层结构视图；
- C 不成为第三种生产模式，其 compiled preview、搜索、高密度摘要与状态提示合并进 A；
- throwaway variant route 仅保留为决策证据，生产路由不暴露 A/B/C 实验命名。

## 已执行的浏览器验证

`tools/prototype_audit.py` 与 Playwright smoke 覆盖：

- A：添加字段、显式保存、revision 更新；
- B：React Flow 根/字段节点渲染、添加字段后重新 fit view；
- C：语义 table、compiled preview、存在性切换；
- 三方案：路由启动、无 console/page error、键盘焦点、reduced-motion、1440×900 截图；
- 边界：1024px 可编辑且无水平溢出，1000px 显示 unsupported-width 提示。

截图位于本地 A1 证据目录 `.sdlc/evidence/prototype-audit/`，不作为产品素材提交。

## 后续人工验收

本轮结构方向 J1 已完成。P3 完整实现后仍需针对实际视觉层级、键盘路径和 1024/1280/1440 布局做产品体验验收；该后续门禁不推翻本次已锁定的信息架构。
