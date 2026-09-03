# 领域分片：Schema 与 Inference（含 Document Vision 获取）

> 由 `CONTEXT-MAP.md` 路由加载。触碰 Schema/Draft/StaticSchema、验证、推断 run、图片识别获取
> （DocumentObservationIR / bounded inspection）或其 HTTP 面时读取本分片。

| 术语 | 精确定义 | 不代表什么 |
|---|---|---|
| Schema / Draft | 由 `schemaKey` 标识的可变工作定义；每次成功保存产生不可变 revision。 | 不是 JSON Schema，也不是发布版本。 |
| Draft revision | 某次保存后的完整 DSL 快照，编号从 0 递增；只用于历史、恢复和并发控制。 | 不能被其他 Schema 精确引用。 |
| StaticSchema | `{schemaKey, versionTag}` 标识的只读、不可变、不可删除发布物。 | 不是指向 Draft 最新内容的视图。 |
| System StaticSchema | 平台以保留`system-` schemaKey预置的一等StaticSchema；`system-empty@v1`无字段，五种`system-basic-*@v1`各有必填index/value且没有对应Draft。 | 不是Evaluator私有类型、可变目录、Template内定义、特殊Schema语法或不可用于创建Template的别名。 |
| RenderWeave DSL | Schema 设计的封闭领域语言和事实源。 | 不是任意 JSON Schema 的子集导入器。 |
| compiled JSON Schema | StaticSchema 发布时一次性生成并保存的 JSON Schema 2020-12 互操作产物。 | 不是产品内验证权威，也不会被重新生成。 |
| RootDocument | 根为 JSON object、待某个 Draft/StaticSchema 验证的聚合数据文档。 | 批量传输数组不属于 RootDocument。 |
| SchemaRef | `{schemaKey}`，在请求开始时解析到目标 Draft 当前 revision 的符号引用。 | 不表示 latest StaticSchema。 |
| StaticSchemaRef | `{schemaKey, versionTag}`，指向精确不可变发布物。 | 不允许缺失版本。 |
| Candidate Bundle | 一次 AI 推断产生的一根、零到多个子节点的可编辑候选图。 | 不是合法 Draft，也不能自动发布。 |
| Evidence | 候选项对应的图片区域、JSON Pointer 或推断来源。 | 不是业务事实保证。 |
| DocumentObservationIR | 图片识别 run 内对规范化 artifact 的版本化、供应商中立、临时感知事实，表达 observation 的几何、顺序、置信度与 provenance，并把 OCR text 视为不可信的 ephemeral 数据。 | 不是 OCR/layout 库 DTO、语义 hypothesis、Evidence、Candidate 或持久 checkpoint。 |
| AcquisitionPolicy | 生成某一 `DocumentObservationIR` 时冻结的感知合同身份，界定 exact local capability、坐标/顺序语义、canonicalization 与硬边界。 | 不是 Inference Profile、Provider 模型路由、外部授权或通用工具权限。 |
| BoundedVisualInspection | 在一次图片识别 run 中，由代码拥有并受固定 policy 约束的单轮局部视觉获取动作；它把已验证 request 确定性转换为最多两个 inspected views 的完整 outcome。 | 不是开放式 Agent、模型工具调用、`DocumentObservationIR` observation、递归搜索或第二套 workflow。 |
| InspectionRequest | OBSERVE 可提出的闭合声明式局部查看意图，只引用当前 verified view、有限 region 和固定 margin/resolution preset。 | 不是自由文本建议、工具命令、路径/URL、预算、循环或授权。 |
| AdaptiveInspectionPolicy | 冻结一次 bounded visual inspection 的 round、view、preset、像素、字节、token 与本地时间硬边界的版本化合同。 | 不是 Inference Profile、Provider 费用授权、fresh J1 或模型可修改的配置。 |
| InspectionOutcome | bounded visual inspection 的闭合结果，且只能是完整 `EXECUTED`、`REJECTED` 或 `EXHAUSTED` disposition。 | 不是 semantic grounding、Candidate、Evidence、持久图像或 live 资格。 |
