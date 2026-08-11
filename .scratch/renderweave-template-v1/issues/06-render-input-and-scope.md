# 定义渲染输入与词法作用域模型

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 03

## Question

一个 Template 求值接收一个聚合 RootDocument 还是多个命名输入；如何绑定并验证永久关联的 StaticSchema；Schema 外字段能否访问；Template 参数、系统上下文、循环 item/index 与嵌套 Template 输入如何形成封闭、可解释的词法作用域？

## Inherited constraints

- DesignDSL 对永久绑定 StaticSchema 的业务输入使用 StaticSchema field path；该概念不叫系统数据源，也不是独立可变目录。
- StaticSchemaRef 不在 DesignDSL 内，且既有 Template 永不改绑；跨 Schema 只通过创建或复制新 Template。
- Clock/Random 等若进入作用域，属于 Evaluation Capabilities，而不是 StaticSchema field path、AssetRef 或 Connector。

## Answer

一次根 Template Evaluation 只接收一个请求级 `RenderInput`：必填的单一聚合 `rootDocument` 与可省略的顶层 `customValues[]`。CSV、Excel、数据库、HTTP 等 Connector 必须在 Template 边界外聚合并归一化数据，不能成为第二数据根、DesignDSL 节点或运行时连接配置。RenderInput 不携带 StaticSchemaRef；权威目标只能来自请求开始时已冻结的根 TemplateSnapshot 永久 StaticSchemaRef。

RenderInput envelope 与每个 custom assignment 都是封闭 strict-JSON 结构，未知 member、重复 JSON object key、非对象 assignment 或词法非法的 definitionId 均拒绝。`rootDocument` 必填且根必须为 object；`customValues` 省略等价于空列表。每个 assignment 必须同时携带 definitionId 与一个 strict-JSON `value`。完整请求先受全局字节、深度和条目数预算约束；具体上限由安全容量票据冻结。

RootDocument 必须通过保留 decimal 等标量精度的无损原始 JSON 边界进入 RenderWeave validator，不能先经通用对象绑定改变数值或 key。每次 Evaluation 都按根 TemplateSnapshot 的精确 StaticSchemaRef 权威验证；调用方不能选择、重复或覆盖 Schema。StaticSchema 未声明字段继续按现有 validator 规则允许，但不会进入任何可观察求值作用域。声明的可选字段缺失形成具有静态类型的内部 `ABSENT`；声明字段显式 null 始终验证失败，字符串不做额外 trim 或 Unicode 归一化。

全部准入检查成功后，validation 边界形成不可变 `AdmittedRenderInput`：只包含精确 StaticSchemaRef、排除未知字段的 closed typed context view、字段 PRESENT/ABSENT 状态，以及已解析的根 Custom 值映射和必要类型证明。Evaluator 只消费该语义视图，不能重新解析或读取原始 RootDocument。任何 envelope、RootDocument 或有效 Custom override 错误都不创建 Evaluation frame、不执行 expression/capability，也不产生部分 RenderDocument；服务端可以返回有上限的多项结构化问题。原始 RootDocument/customValues 不进入普通日志、Template revision 或审计转储。

DesignDSL 顶层 `definitions[]` 是版本封闭的 definition union，其中 `kind: "custom"` 表示 `CustomDefinition`，而不是 DataSource 或 Connector。每个 CustomDefinition 具有稳定 definitionId、声明类型、必填且非 null 的 typed literal `defaultValue`，以及显式 `PUBLIC | PRIVATE` exposure；defaultValue 不能是 ValueSource 或 Expression。definitionId 在单个 Template 的全部 definitions 中唯一，definitions 数组顺序不构成求值顺序。重复 ID、非法 domain、默认值类型错误、词法越界或 definition 依赖环是不可通过 Invalid commit confirmation 绕过的自包含 hard error；具体类型集合、wire 与依赖规则由 ValueSource/Expression 和 DesignDSL envelope 票据冻结。

根 `customValues[]` 只覆盖根 Template 当前 PUBLIC CustomDefinition，按稳定 definitionId 寻址，不使用 displayName 或独立 externalKey，也不能定向嵌套 Template。列表重复 definitionId 使用最后一项，顺序除此之外没有语义。全部条目先完成 envelope 结构与全局预算检查，再按 definitionId 分组；被覆盖 loser 不进行值的声明类型/形状校验。最终 winner 若目标不存在或为 PRIVATE，则作为对外不可见定义静默忽略且不产生 warning；若目标为 PUBLIC，则必须存在 value、非 null 并通过声明类型与值级预算校验。由此每个根 invocation frame 在创建时一次性冻结有效 Custom map：PUBLIC 使用 winner 或默认值，PRIVATE 始终使用默认值，全部键都有具体值而没有 Custom ABSENT。

求值作用域由不可变词法 frame 构成。每个 Template invocation 建立一个 invocation frame，持有该 Template 的 typed context、有效 Custom map 与自身 definitions。每个实际循环项建立一个 loop frame，以单 Template 内唯一且稳定的 loopId 标识，公开 typed item 与从 0 开始的 index，并只链接同一 Template 内的词法祖先。CustomDefinition 固定属于 invocation domain；非 Custom 的 Computed Definition 必须显式声明 invocation 或某个 loopId 作为唯一 evaluation domain，可以读取自身域及同一 Template 的词法祖先，不能读取兄弟、后代、调用者或被调用者 frame。definitionId 与 loopId 是两个独立命名空间。

持久化 ValueSource、Computed Definition domain 与 TemplateUse ContextSelector 都必须显式指向 invocation 或具体 loopId；DSL 不保存会随消费位置改变含义的 `$current`、`$parent` 或 `$root`。编辑器可以展示“当前项”等便捷别名，但保存前必须归一为稳定 domain。StaticSchema field path 相对所选 domain、按精确 StaticSchemaRef 静态解析，区分大小写、保留 Unicode key 原样并使用 RFC 6901 转义；不允许 wildcard 或数字数组下标穿越集合，数组元素必须通过 Loop 建立新 domain。可选祖先运行时缺失使合法后代路径得到 typed ABSENT；Schema 中根本不存在的路径是 Template 依赖 ERROR，而不是运行时 ABSENT。空 pointer 只可由 ContextSelector 选择整个 typed context，不能把任意原始 object 暴露为通用 ValueSource。

Loop iterable 必须拥有静态封闭的 item 类型。标量项由 Evaluator 使用已验证 item 与零基 index 构造对应精确 `system-basic-text@v1`、`system-basic-decimal@v1`、`system-basic-date@v1`、`system-basic-time@v1` 或 `system-basic-boolean@v1` typed context，同时 loop frame 仍显式公开 item/index。只有来自已验证 StaticSchema `array(items: reference)` 的对象项才能携带被引用的精确 StaticSchemaRef，未知对象字段同样不可见；任意 expression/computed JSON object 不能因结构相似而成为 Schema context 或子 Template 输入。嵌套循环只能按 loopId 显式读取当前或祖先 frame，不能读取 sibling/descendant。

每次 TemplateUse 调用创建隔离的子 invocation frame。子 context 只能由显式 ContextSelector 取得：选择与子 Template 永久 StaticSchemaRef 完全相同的 invocation context、精确 reference-typed subtree/loop item，或在子绑定 `system-empty@v1` 时选择显式 empty context；禁止 shape-based object 构造和任意 JSON 注入。若合法 selector 指向可选但缺失的 context，TemplateUse 必须在嵌套组合票据中声明 `ERROR` 或 `SKIP`，不得隐式决定。传入的 typed subview 携带精确 Schema 证明并保持不可变，不经过 JSON 序列化或按形状重新验证；标量 system-basic context 由 Evaluator 按固定 Schema 构造。只有外部根 RootDocument 在每次 Evaluation 入口执行权威验证。

子 Custom 输入只能通过 TemplateUse 的显式 fill 列表按子当前 PUBLIC definitionId 赋值。每个 fill source 在父 TemplateUse 所在 invocation/loop 词法域中求值，因此循环内调用会逐 item 求值，然后把 typed value 复制进子 frame；子不能回读父 frame。省略 fill 或 fill source 得到 ABSENT 时使用子默认值，求值 ERROR 使 Evaluation 失败；重复 target、目标不存在/PRIVATE 或静态类型不兼容使父 Template 形成依赖 ERROR。外部 customValues 指向已删除或转为 PRIVATE 的根 definition 仍静默忽略，但持久化父 fill 指向同类变化必须经 TemplateRef 依赖重检使父 Template INVALID。子 PRIVATE definition 始终使用自身默认值，PUBLIC/PRIVATE 只约束 invocation 边界，两者在所属 Template 内均可被合法 definition/节点读取。子调用不继承父 custom、definition、RootDocument handle、loop frame、兄弟状态或通用系统 map；capability 传播另由 Evaluation Capabilities 与组合票据决定。

RootDocument、customValues、AdmittedRenderInput 和 frame 都是请求级值，不保存在 DesignDSL、Template revision、TemplateValidationReport 或普通审计日志中；编辑器样例属于本地 EditorSession。未来 Workspace 可以独立管理输入预设或 fixtures，但该能力不改变 Template/DesignDSL 的所有权边界。

本票据不冻结 ValueSource/Binding/Expression 的具体 wire 与完整类型系统、definition memoization、Loop 空集/缺失/上限、TemplateUse `ERROR/SKIP` 的完整组合行为、capability 注入、Evaluation identity/cache key、API problem code 与数值容量。它们分别由后续 07、11、12、14、15、18/19 票据继承以上不变量后决定。
