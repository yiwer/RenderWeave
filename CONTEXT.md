# RenderWeave 领域上下文

## 一句话

RenderWeave v1 让技术型设计者定义可变的 Schema Draft，把精确 revision 发布为不可变 StaticSchema，并通过确定性验证或带证据的 AI 推断获得可审核的数据结构；Template、数据适配和图片渲染属于后续版本。

## 统一语言

| 术语 | 精确定义 | 不代表什么 |
|---|---|---|
| Schema / Draft | 由 `schemaKey` 标识的可变工作定义；每次成功保存产生不可变 revision。 | 不是 JSON Schema，也不是发布版本。 |
| Draft revision | 某次保存后的完整 DSL 快照，编号从 0 递增；只用于历史、恢复和并发控制。 | 不能被其他 Schema 精确引用。 |
| StaticSchema | `{schemaKey, versionTag}` 标识的只读、不可变、不可删除发布物。 | 不是指向 Draft 最新内容的视图。 |
| RenderWeave DSL | Schema 设计的封闭领域语言和事实源。 | 不是任意 JSON Schema 的子集导入器。 |
| compiled JSON Schema | StaticSchema 发布时一次性生成并保存的 JSON Schema 2020-12 互操作产物。 | 不是产品内验证权威，也不会被重新生成。 |
| RootDocument | 根为 JSON object、待某个 Draft/StaticSchema 验证的聚合数据文档。 | 批量传输数组不属于 RootDocument。 |
| SchemaRef | `{schemaKey}`，在请求开始时解析到目标 Draft 当前 revision 的符号引用。 | 不表示 latest StaticSchema。 |
| StaticSchemaRef | `{schemaKey, versionTag}`，指向精确不可变发布物。 | 不允许缺失版本。 |
| Candidate Bundle | 一次 AI 推断产生的一根、零到多个子节点的可编辑候选图。 | 不是合法 Draft，也不能自动发布。 |
| Evidence | 候选项对应的图片区域、JSON Pointer 或推断来源。 | 不是业务事实保证。 |
| Inference Profile | 版本化的 provider/model/prompt/output schema/budget/eval 配置快照。 | 不使用 `latest` 语义。 |

## 限界上下文与依赖

```text
schema (DSL + lifecycle + reference graph + compiler)
  └── validation (RootDocument validator; depends only on schema public API)
        └── inference (job + candidate + evidence + provider adapter)
              └── app (HTTP, JDBC adapters, transactions, worker assembly)

web ── OpenAPI 3.1.2 / generated Fetch SDK ── app
```

- `schema` 不依赖数据库、Spring MVC、模型供应商或文件系统。
- `validation` 不能反向改变 Schema；通用 JSON Schema validator 只用于互操作测试。
- `inference` 只能通过窄 application command 原子创建新 Draft Bundle；没有发布、更新或删除能力。
- 模块共享只通过明确 public API；禁止建立无边界的 `common` dumping ground。

## 身份与路径

- Draft 字段身份：`schemaKey + fieldKey`。
- Static 字段身份：`schemaKey + versionTag + fieldKey`。
- 嵌套出现位置：再加从 RootDocument 根开始、正确转义的 JSON Pointer。
- 正式模型不保存 fieldId；Candidate 可使用 run-local opaque ID 维持审核关联，创建 Draft 时丢弃。

## 生命周期摘要

```text
Draft ACTIVE ──save──> ACTIVE(revision + 1)
     │                    │
     ├──publish saved revision──> StaticSchema(immutable)
     └──soft delete──> DELETED ──restore with full validation──> ACTIVE(new revision)

InferenceRun:
QUEUED → RUNNING → REVIEW_REQUIRED → APPLYING → COMPLETED
              └──────────────→ FAILED / CANCELLED
```

## 跨版本边界

- v1 不定义 Template DSL、映射语言、Workspace 或 Renderer API。
- v1 为未来消费者提供的唯一稳定接缝是精确 StaticSchema 标识、不可变 DSL 快照和已保存的 compiled JSON Schema。
- 任何未来模块不得让 v1 为尚未确定的渲染语义预建表、接口或空页面。

