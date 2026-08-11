# 定义 Evaluation 编译流水线与 RenderDocument 合同

Type: grilling
Status: open
Blocked by: 08, 10, 11, 12, 13, 14

## Question

输入验证、definition/binding、条件、循环、嵌套、Asset 解析与 RenderDSL lowering 的权威顺序是什么；各阶段如何限界和报错；RenderDocument 的版本、静态 kind、渲染级布局、具体载荷、资源清单、大小限制和请求生命周期如何与现有 `busbox-render-engine` 合同对齐？

## Inherited constraints

- Render 不能信任最终一致的 TemplateReadiness 或 current report；每个请求必须重新权威检查最新 root current、AssetRef 和全部嵌套 current。
- 根与可达 TemplateRef 必须冻结为一致 Template closure snapshot；任一 current 漂移时重试或失败。
- INVALID/STALE 或任何闭包依赖 ERROR 都不能产生成功 RenderDocument/RenderOutput；问题必须结构化返回。
- Asset 是刻意的例外：Template 闭包仍一致冻结，但每个实际执行到的 AssetRef 出现位置独立解析当时 Asset current；同一 assetId 可在一个 RenderDocument 中出现多个 contentVersion，资源身份必须区分精确版本。
- 任一晚到的 Asset 解析失败必须中止整个 Evaluation，不得留下部分 RenderDocument；已经解析的 ResolvedAsset 必须保持 exact content 可读取并携带受信 URL/hash/mediaType/length。
- 根入口必须先封闭解析 RenderInput、用 TemplateSnapshot 永久 StaticSchemaRef 每次权威验证 RootDocument，并按 last-wins 规则消解根 PUBLIC Custom override；任一准入错误都不得创建 frame 或执行 capability。
- Evaluator 只消费不可变 AdmittedRenderInput，不得重新解析原始 JSON或观察 StaticSchema 未声明字段；合法 optional missing 使用 typed ABSENT，null 已在准入边界拒绝。
- child context 使用 exact StaticSchemaRef typed-view proof，或由 Evaluator 构造固定 system-basic/system-empty context，不通过结构相似性重新验证；每个 child invocation 与 Loop frame 都遵守已冻结的隔离词法边界。
- 完整 Evaluation identity/cache key 可以由本票据与 capability/容量票据决定，但不能使 ignored unknown fields、duplicate loser 或 unknown/PRIVATE Custom override 重新成为表达式可观察数据。
- Java Evaluator 必须实现 `renderweave-expression/1.0` 的自有权威语义；Web/CEL/其他 library 只能通过同一 conformance corpus 提供非权威反馈，底层 library version 不能进入 DesignDSL 语义身份。
- save/recheck 已对全部 Definition、Expression/Mapping branch、Binding target/source、domain、cycle 与 literal 做静态完整检查；runtime 则 demand-driven，只求值实际 materialize node/binding、selected case/branch 与其显式 inputs。
- Definition graph 允许 forward reference 但必须无环；Mapping/Expression 在声明 invocation/loop frame 惰性 memoize，ordinary call left-to-right，`&&/||/if/coalesce` lazy。未选择分支不得调用 capability。
- 每个 Design Node 先形成合法 authored static property tree，再按 node-local、互不重叠 targetPropertyRef 应用 Binding overlay；结果必须 concrete、精确 target type 并通过全局 property validation，ABSENT/ERROR/失败都中止而不回退 baseline。
- BindingPolicyCatalog 只决定目标是否合法，Template 不携带 policy 版本；Evaluator 必须从同一全局追加式 Catalog 解析 nodeKind + targetPropertyRef，不能信任 Web 预检或 Template 自报 target type。
- runtime 第一个实际 demanded ERROR fail-fast，停止后续 capability 与 Asset resolution；只有全部求值、property validation、closure 与实际 Asset occurrence 成功后才能原子形成无 Binding/Expression/ABSENT 的 RenderDocument。
- 建立 TemplateSnapshot 前必须从 persisted DesignDSL 重新执行对应 dslVersion 的 canonicalization 并核对 domain-separated contentHash；mismatch 是内部 integrity failure，不能降级为 INVALID、重新 hash 或把内容交给 Evaluator。
- contentHash 只绑定 authored DesignDSL，不含 StaticSchemaRef、resolved TemplateRef current、Asset current、RenderInput 或 capability result，因此不能单独充当 Evaluation/Render cache key；本票据必须定义包含 closure exact revisions、Schema/input/capability 与实际 asset occurrence 的完整 identity。
- 根与每个 child revision 都按各自 exact、永久支持的 dslVersion/expressionProfile pair 解析；Evaluation 不做 read-time migration，浏览器或底层 expression library 也不能改变旧 profile 语义。
- Evaluator 消费的是服务端权威解析后的 Canonical DesignDSL semantic value，而非上传 bytes、数据库 serializer 文本、raw repair buffer 或客户端 AST。
