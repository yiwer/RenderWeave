# ADR-0005：表单和一层 Map 共享 EditorSession，坐标不进入领域数据

- 状态：accepted
- 日期：2026-08-07
- 关联：AC-013, AC-014, AC-017

## 背景与约束

表单适合精确编辑，树状/思维导图适合理解一根 Schema 的字段与引用。若两种视图各持有一份状态或持久化画布坐标，切换、undo、冲突和 AI review 会出现双真相源。

## 决策

- 一个 `EditorSession/useReducer` 持有 definition、selection、dirty、history 和 local diagnostics。
- Form 与 controlled @xyflow/react Map 只 dispatch semantic actions。
- 布局固定左到右并确定性计算；drag 只变字段顺序，不保存坐标或 edge。
- undo/redo 100 semantic actions，typing coalesce；Draft save 显式且不清 history。
- server state 由 TanStack Query 持有；SSE 只触发 refetch，不复制服务器对象到全局 store。
- revision conflict 保留 local session，显示 diff/export/reload；无 force/auto merge。
- map 所有功能都有 form/keyboard 等价路径；canvas 的无障碍兜底是完整表单。
- Candidate review 复用同一 editor primitives，但提供独立 command surface，不能保存/发布 Draft。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| 两个编辑器各自状态 | 局部实现简单 | 同步、undo、切换丢数据 | 双真相源不可接受 |
| 持久化任意节点坐标 | 用户可自由排版 | 与 field order 冲突、diff 噪音 | Schema 只有一层，deterministic layout 足够 |
| canvas-only | 视觉强 | 键盘/精确表单/1024 宽度差 | 必须有完整 form 路径 |
| local autosave Draft | 防丢 | 与显式 save/revision 语义冲突 | dirty guard + undo 更透明 |

## 后果与验证

- 正向：两种视图语义一致，Candidate 可复用交互构件，无坐标污染 DSL。
- 代价：reducer/action 设计必须稳定；大字段列表需实测后优化。
- 验证：reducer model tests、component tests、Playwright 1024/1280/1440、axe + J1 keyboard/visual review。
- 原型：`/prototype/schema-studio?variant=A|B|C`；选择后删除/吸收 losing variants。
- J1：用户于 2026-08-08 接受 A 默认 Form + B Map；C 不进入生产模式，其 compiled preview、搜索和高密度摘要由 A 吸收。
