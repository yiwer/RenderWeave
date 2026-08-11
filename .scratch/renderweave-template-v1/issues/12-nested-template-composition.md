# 定义嵌套 Template 组合语义

Type: grilling
Status: open
Blocked by: 04, 06, 07, 09, 10

## Question

父 Template 默认引用子 Template current revision 时，引用身份、DAG/循环防护、参数填充、数据/系统作用域继承、布局/裁剪、Asset 与 capability 传播、更新可见性和求值失败应如何定义？

## Inherited constraints

- “默认 current”已收紧为唯一 authored 模式：TemplateRef 只保存 templateId，不允许 exact revision selector。
- TemplateRef 图必须是 DAG；cycle 是不可确认 hard error。ACTIVE current projection 用于 incoming delete blocker 和子变更向父递归重检。
- 子 missing、DELETED 或 INVALID 是可确认保存父 INVALID 的依赖 ERROR，但不能成功 Render。
- Evaluation 必须生成一致 Template closure snapshot；闭包收集期间 current 漂移只能重试或失败，不能混合多个时刻。
- 每个 TemplateUse 创建隔离 child invocation frame；child 不继承父 RootDocument handle、Custom map、definitions、loop frame、siblings 或通用 system map。
- child context 必须由显式 selector 取得 exact-StaticSchema typed context：相同 invocation context、reference-typed subtree/loop item，或仅对 `system-empty@v1` 的显式 empty context；禁止 shape-based object 注入与重新 JSON 绑定。
- selector 指向可选且运行时 ABSENT 的 context 时，本票据必须在 `ERROR/SKIP` 中定义显式策略；不得隐式选择。
- child Custom 输入只能通过按其当前 PUBLIC definitionId 的显式 fill；fill 在父 TemplateUse 所在词法域、每个 loop item 独立求值并复制 typed value，省略或 ABSENT 使用 child default。
- duplicate target、child definition missing/PRIVATE 或类型不兼容是父 Template 依赖 ERROR；child current 的相关变化必须使父递归重检。外部根 customValues 对 unknown/PRIVATE 的静默忽略不能套用于 authored fill。
- child PRIVATE CustomDefinition 始终使用自身默认值。PUBLIC/PRIVATE 只约束 invocation 边界，不能建立自动同 ID/同名继承。
- child fill 使用父 invocation 中合法的 typed ValueSource，但不能直接使用 literal/capability；literal 应成为 child default 或 authored static value，capability 只能作为父 ExpressionDefinition 的显式 input。
- fill source 与 child PUBLIC CustomDefinition 必须精确同型；省略 fill 或合法 ABSENT 使用 child default，ERROR、错误类型与无效动态 AssetRef 不得回退 default。
- 父/子各自拥有隔离 definitions 与 node-local bindings；Expression input alias、definitionId、bindingId、targetPropertyRef 均不能跨 Template invocation 引用或合并。
- 父侧任何 node property Binding 仍只覆盖该父 Node 已存在静态 baseline；TemplateUse 的哪些属性可绑定由全局追加式 BindingPolicyCatalog 决定，而不是由 child Template 声明。
- TemplateUse 使用独立 client-generated canonical UUID v4 useId；父 Template local IDs 不与 child revision 的同名 namespaces 合并，runtime occurrence identity 必须由 closure/invocation path 另行形成。
- TemplateUse fills 是无语义集合并在 Canonical DesignDSL 中按 child definitionId 排序；任何具有 authored composition order 的 use/host/child arrays 必须由本票据明确保序 authority。
- authored TemplateRef `{templateId}` 进入父 Design content hash，但解析到的 child current revision/hash 不进入；child 漂移只影响 dependency/readiness/closure snapshot，不修改父 revision/contentHash。
- 每个 child revision 按自身 exact dslVersion/expressionProfile 永久解释；closure 不自动 migration，也不能因父版本较新而重写 child DesignDSL。
