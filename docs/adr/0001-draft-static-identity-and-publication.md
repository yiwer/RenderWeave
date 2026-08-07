# ADR-0001：以业务 Key 标识 Draft，以精确 Tag 标识不可变 StaticSchema

- 状态：accepted
- 日期：2026-08-07
- 关联：AC-001–AC-007

## 背景与约束

嵌套可变定义会让上层含义随依赖修改而漂移；渲染链未来需要一个不会变化的输入合同。同时，字段业务身份必须对设计者可读，不能依赖无业务含义的 UUID。

## 决策

- Draft 由不可变 `schemaKey` 标识，保存产生从 0 递增的不可变完整 revision。
- 正式字段没有 fieldId；Draft 字段身份是 `schemaKey + fieldKey`，Static 再加 `versionTag`。
- `SchemaRef {schemaKey}` live 解析 Draft；`StaticSchemaRef {schemaKey,versionTag}` 精确解析 Static。
- 保存态不允许悬空引用或图循环；有 active incoming refs 时不能删除 Draft。
- Draft 软删除、key 永不复用；restore 重新校验并产生新 revision。
- 发布只消费 exact saved revision，必须显式 tag 和全 Static refs；不隐式保存、不自动 latest。
- StaticSchema 内容、DSL、compiled artifact 永不改写/删除/重编译；同 revision 可发布多个 tag。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| persisted field UUID | rename 稳定 | UI/API/AI 需维护第二身份；偏离业务主键 | `schemaKey + fieldKey` 已足够，rename 明确是 delete+add |
| 允许 Draft 悬空引用 | 离线编辑自由 | Saved 不再代表可执行定义；发布前修复复杂 | 用户明确要求不可保存 |
| 自动绑定 latest Static | 操作少 | 非确定、竞态、不可审计 | 与不可变消费边界冲突 |
| 删除后复用 key | 资源简单 | 旧语义可能被静默复活 | key tombstone 更安全 |

## 后果与验证

- 正向：身份可读、Draft 始终合法、Static 可作为未来 Template 的稳定输入。
- 代价：改 key 只能复制；发布前多一次明确 pin 操作；revision 会持续增长。
- 验证：graph property tests、PostgreSQL 并发/删除/发布 fault tests（A1，release A2）。
- 恢复：Draft 可用新 revision 恢复；Static 错误只能发布新 tag，不能修补旧产物。

