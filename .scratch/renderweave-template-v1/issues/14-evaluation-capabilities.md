# 定义 Evaluation Capabilities 与请求内一致性

Type: grilling
Status: open
Blocked by: 02, 07, 13

## Question

Clock、Random 与 AssetResolver 的封闭能力接口、调用位置、请求内稳定性、预算、超时、审计和失败语义是什么；Expression 能否直接调用能力，还是只能读取 Evaluator 注入的值；未来新增能力如何版本化而不退化为任意工具执行？

## Inherited constraints

- RenderInput/AdmittedRenderInput 不包含可浏览 system map；tenant、actor、requestId、凭证及 Connector 状态不得进入 expression-visible context。
- Capability 不是 StaticSchema field、CustomDefinition、AssetRef 或隐式 lexical variable；任何可见值/调用都必须使用显式版本化 capability 合同。
- child invocation 的数据和 definition frame 隔离已冻结；本票据只能定义 capability 是否及如何沿 Template closure 传播，不能借 capability 暴露父 frame 或任意环境访问。
- Capability ValueSource 只允许出现在 ExpressionDefinition 的显式 `{alias, source}` inputs；Expression source 只能读取 `input.alias`，不能直接调用 capability，Binding/Mapping/Custom/default/child fill 也不能直接持有 capability source。
- capability call position 由 definitionId + input alias + 显式 invocation/loop frame 稳定标识；input 在单次 Expression evaluation 内惰性 memoize，未 materialize Definition 或未选择 expression branch 不得调用能力。
- Expression 1.0 的 grammar、类型与 lazy order 已冻结；本票据只能定义 Clock/Random closed payload、请求内稳定性、传播和预算，不能增加 eval、IO、任意函数或隐式环境读取。
- capability ERROR 不是 ABSENT，`coalesce` 不得吞掉；第一个实际 demanded capability ERROR 终止 Evaluation，之后不再调用其他能力或解析 Asset。
- AssetResolver 不是普通 Expression capability；它只在 concrete imageRef/fontRef 实际消费位置按票据 13 的内部合同运行。
- DesignDSL 与 Expression Profile 按 exact、只追加 compatibility pair 校验；新增 capability-visible expression syntax/type/function 必须形成新 Expression Profile 并经显式 migration/save，不能修改既有 pair。
- Expression source 是 Canonical DesignDSL 中的 exact string，source whitespace 参与 contentHash；capability runtime result 不进入 revision hash，完整 Evaluation identity 由本票据与 15/19 另行补足。
- 一旦 Expression Profile 被 revision 接纳，其 capability call-site/type/order 语义是永久兼容义务；底层 evaluator/library 更新不得改变旧 profile。
