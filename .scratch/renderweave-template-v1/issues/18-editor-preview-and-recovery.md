# 定义编辑、校验、预览与恢复体验

Type: grilling
Status: open
Blocked by: 04, 15, 17

## Question

编辑器何时允许暂时无效状态、何时严格拒绝保存；autosave/显式保存、expectedRevision 冲突、历史恢复、无损 round-trip、权威预览刷新、capability 导致的变化、错误定位、键盘与可访问性体验应如何定义？

## Inherited constraints

- 导入和编辑可在本地 EditorSession 暂时无效；服务端只接受完整显式保存，不持久化 autosave/Patch。
- 依赖 ERROR 可经绑定精确问题集的二次确认保存为 INVALID；hard error 永远拒绝。INVALID/STALE 可继续编辑和看本地草稿画布，但不能权威预览或 Render。
- 打开编辑器必须重检最新 current；current report 不保留 revision 绑定且可能被旧异步结果覆盖，UI 不得把它冒充权威结论。
- 历史恢复只在 ACTIVE Template 中追加新 revision；DELETED 不可恢复，但其历史可只读、导出或复制。
- `displayName` 等 metadata 位于 DesignDSL，修改使用同一个 expectedRevision 并产生内容 revision。
- RootDocument/customValues 样例只保存在本地 EditorSession；未来 Workspace 输入预设不进入 DesignDSL 或 Template revision。本票据不得把样例保存伪装成 Template autosave。
- 编辑器必须区分 optional runtime ABSENT、Schema 中不存在的 field path、Custom default/definition graph hard error与 child fill 依赖 ERROR，并定位到稳定 definitionId/loopId/JSON Pointer。
- PUBLIC/PRIVATE CustomDefinition 与显式 child fill 是调用边界；UI 不得提供自动同名继承或让 external customValues 定向嵌套 Template。
- imported DesignDSL 的 node-local Binding 与 exact Expression source 必须无损 round-trip；本地可暂时保留 unknown/越界/重叠 target，但显式保存时这些结构问题是不可确认 hard error。
- 属性面板只使用全局 BindingPolicyCatalog 展示当前允许目标；Catalog 新增只扩展 UI 能力，旧 Template 无迁移，Template 内容不能保存 policyId、Catalog revision 或自报 target type。
- editor 对 array reorder 必须明确采用“保持数字下标”或原子重写 targetPropertyRef；不得依赖不存在的 item identity。删除 Binding 后继续显示原 authored static baseline。
- 本地 Expression parser/linter 不构成第二语义权威；server problem 必须映射 exact source 的零基 UTF-16 span，未来 profile 不能静默重写 source/whitespace 或自动升级。
- UI 必须把“无 Binding 使用 baseline”与“已有 Binding 但 ABSENT/ERROR/类型或属性约束失败”分开；后者不能显示为成功 fallback preview。
- “无损编辑”只承诺 DesignDSL 语义，不保留上传 JSON whitespace/object order/equivalent number lexeme；Editor 必须保留所有受支持 fields、semantic arrays、Unicode values 与 exact Expression source，并在 save 后接受服务端 canonical form。
- 不合法 UTF-8/JSON、duplicate key 或 unsupported exact profile 只能进入 local raw repair；支持 pair 且结构可识别的 hard/dependency-invalid content 可 best-effort 投影，但 hard error 未清零前服务端零写。
- 客户端创建 local entity 时生成 canonical UUID v4；server 不修复 missing/collision。copy local entity 必须 remap 引用，restore/whole Template copy 保留 IDs；canonical semantic problem 通过 entity ID + property path 映射，而非依赖无语义数组 index。
- 旧客户端若不完整理解 dslVersion/expressionProfile/Node wire 必须只读、export、raw repair 或显式 migration，不能 partial reserialize。Migration preview 必须绑定 source contentHash，用户确认后才按 expectedRevision 保存新 revision。
- 编辑器导入 exact export 时验证 exportVersion/contentHash，但不得信任或采用文件 templateId/revision/StaticSchemaRef；现有 Template 永久 Schema 优先，新建 Template 的 Schema 预填仍需用户确认。
- TemplateValidationReport pointer 指向 canonical current；parser problem 可指向 raw import buffer，后者不持久化。达到有界 problem limit 后必须明确展示 truncation，而不是假装只存在已列问题。
