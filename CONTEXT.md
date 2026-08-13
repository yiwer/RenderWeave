# RenderWeave 领域上下文

## 一句话

RenderWeave v1 让技术型设计者定义可变的 Schema Draft，把精确 revision 发布为不可变 StaticSchema，并通过确定性验证或带证据的 AI 推断获得可审核的数据结构；Template、数据适配和图片渲染属于后续版本。

## 统一语言

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
| Goal authority epoch | 一段由明确决策创建、具有独立上限且只追加 reservation 的视觉评测预算权威；它通过不可变 lineage 连接前序消费事实。 | 不是清零历史消费、复用旧 J1、重新打开旧 ledger 或自动获得 Provider 调用权限。 |
| Quarantined charged baseline | 精确逐条账本不可恢复时，把经多源确认的前序累计消费按最坏上界继续计费并终结调度歧义的不可变基线。 | 不是伪造 SETTLED usage、活动 RESERVED、预算退款或可再次消费的余额。 |
| Inference Profile | 版本化的 provider/model/prompt/output schema/budget/eval 配置快照。 | 不使用 `latest` 语义。 |
| Product Profile catalog | 当前允许创建新 live run 的、顺序固定且 ID 精确的 immutable Inference Profile 集合。 | 不是质量认证、`latest` alias，也不会改变历史 run snapshot。 |
| Profile readiness | 启动时探测到的本地 capability 是否精确满足某个 Inference Profile 的 payload-free admission 事实。 | 不是模型质量、Provider 凭据、费用授权或对缺失能力的静默降级。 |
| Template | Template Design 上下文中面向有限二维画板、以全局唯一不透明`templateId`标识的可变聚合；拥有不可变ownerScope、永久StaticSchemaRef、current revision和`ACTIVE/DELETED`生命周期，且没有发布状态，DELETED为不可恢复终态。 | 不是Template revision、DesignDSL、可跨scope移动或复用的人类业务key、独立metadata聚合、编辑器画布状态或最终图片；更换StaticSchema必须创建或复制为另一个Template。 |
| Template revision | 以 `{templateId, revision}` 精确标识、从 revision 0 单调追加的不可变完整 DesignDSL 历史快照；每次被接受的显式保存都会追加 revision，即使内容 hash 与 current 相同，content hash 也只证明完整性。 | 不是差异补丁、独立 metadata 版本、`TemplateVersion`、`PublishedTemplate` 或 `StaticTemplate`，也不承诺跨次求值可重放。 |
| Template current | Template 唯一的当前内容 revision，始终是最新成功追加的 revision；恢复历史会复制旧 DesignDSL 并追加新 revision，而不回拨指针或产生分支。 | 不是 latest StaticSchema、可移动标签或多个并存分支。 |
| Template save | 以 `expectedRevision` 为前提，把一份结构合法的完整 DesignDSL 经权威校验后原子追加为新 revision 并更新 current 的显式操作；依赖类 ERROR 可经绑定精确问题集的二次确认提交为 INVALID。 | 不是 JSON Patch、autosave、会话锁、最后写入者覆盖或可绕过结构/版本/循环/安全规则的强制保存。 |
| Template copy | 在来源ownerScope内从精确`{templateId,revision}`创建独立Template的操作；复制来源DesignDSL、不复制revision历史或来源关系，新Template从revision 0开始，并可显式改绑另一StaticSchemaRef；依赖类ERROR可经二次确认创建为INVALID。 | 不是跨scope迁移、跟随来源current的fork、历史克隆、可追溯lineage、原地Schema改绑、自动字段迁移或损坏DesignDSL的导入通道。 |
| Template revision restore | 在 ACTIVE Template 上把某个精确历史 revision 的 DesignDSL 复制并追加为新 current 的操作；依赖类 ERROR 可经二次确认恢复为 INVALID。 | 不是回拨 current、修改历史 revision 或恢复 DELETED 生命周期。 |
| Template delete | 携带 `expectedRevision`、在一致性边界确认无 ACTIVE incoming TemplateRef 后，把 ACTIVE Template 终结为 DELETED 的软删除操作；永久保留身份、Schema、current、revision 与审计事实，只允许只读查看、导出和复制精确历史。 | 不是物理删除、级联删除、可跳过引用检查的管理操作或隐藏后仍可恢复的归档；DELETED 不能编辑、保存、引用、预览或 Render。 |
| Invalid commit confirmation | 保存、复制或历史恢复在结构合法但存在 StaticSchema field path、AssetRef 或 TemplateRef 等依赖类 ERROR 时使用的二阶段确认；短期 token 绑定操作、Design content hash、来源与目标、依赖快照和问题 fingerprint，漂移后必须重新确认。 | 不是裸 `force=true`、前端自证、永久豁免或绕过 DSL 版本、结构、TemplateRef DAG、安全与预算不变量的能力。 |
| DesignDSL | Template revision 保存的版本化作者内容 JSON 事实源，包含 metadata、definitions 与唯一 DesignRoot；在线设计器是其语义无损投影，求值或渲染产物只能由已保存 revision 派生。 | 不携带 Template 身份、StaticSchemaRef、current/lifecycle/readiness、运行输入或 EditorSession 状态，也不是历史项目格式的兼容别名。 |
| DesignDSL Profile | 由 exact `dslVersion` 标识、永久冻结某一代 DesignDSL wire、Node property identity、default、validation 与 canonicalization 语义的合同。 | 不是 SemVer range、latest 标记、Expression Profile、BindingPolicyCatalog snapshot 或可在旧 revision 上热替换的 parser。 |
| DesignRoot | 一份 DesignDSL 中唯一的 authored visual root；`renderweave-design/1.0` 中必须直接是唯一 Canvas Node，并由其 nested children 承载全部 visual tree。 | 不是业务 RootDocument、编辑器 canvas state、RenderDocument、平行 top-level element collection 或可由 Template envelope 替换的第二内容根。 |
| Local authored identity | DesignDSL 内以 canonical UUID v4 表示、分别由 nodeId/definitionId/bindingId/loopId/useId namespace 定位的作者实体身份。 | 不是跨 Template 全局身份、人类业务 key、数组位置或由普通服务端保存静默生成的 ID。 |
| OccurrencePath | 一次Evaluation内按root/TemplateUse invocation、Repeat的loopId与原输入index、合成role及source node组成的请求级出现位置，只保留在Rendering诊断边界。 | 不是authored identity、业务key、revision事实、item/context内容、跨请求稳定地址或Engine输入，也不写入DesignDSL/RenderDocument。 |
| Render occurrence ID | RenderDocument seal时按最终静态树先序分配、以`rwocc_`加16位小写十六进制序号编码的请求内opaque occurrenceId。 | 不是OccurrencePath、nodeId、业务身份、跨请求地址、随机UUID或Template信息的hash。 |
| Canonical DesignDSL | DesignDSL 经版本化 metadata normalization、无语义集合排序、精确 decimal 与 canonical JSON encoding 后得到的唯一内容表示。 | 不是原始上传字节、业务 default 展开、自动修复结果或由数据库 serializer 偶然产生的 JSON 文本。 |
| Design content hash | 对 Canonical DesignDSL 作 domain-separated SHA-256 得到的 revision 内容完整性标识；不包含 Template/Schema 身份或解析后的外部依赖版本。 | 不是签名、来源证明、语义等价、发布版本、去重键、可重放承诺或可单独使用的 Render cache identity。 |
| Template revision export | 把 exact Template revision identity、永久 StaticSchemaRef、Design content hash 与完整 DesignDSL 组合成的版本化可移植 envelope。 | 不是可移动 current 快照、跨部署身份声明、Template copy、Schema 改绑命令或可被导入方信任的授权证明。 |
| DesignDSL migration | 从 exact source DesignDSL/Profile/hash 纯转换并预览为另一个 exact profile 的显式作者操作；接受后通过普通保存追加新 revision。 | 不是读取时升级、历史重写、依赖修复、普通 save 的 ID 生成器或允许未知旧格式静默导入的兼容层。 |
| TemplateRef | DesignDSL中只按`templateId`跟随同ownerScope目标Template current的引用；引用图必须是DAG，目标current/readiness/lifecycle变化会经current-only反向索引触发父Template递归重检。 | 不是authored exact revision、latest selector、动态模板选择、cross-scope引用或可在一次Evaluation中重新解析的指针；精确revision只用于历史、复制与恢复。 |
| Template dependency projection | ACTIVE Template current中每个authored AssetRef atom与TemplateUse logical TemplateRef occurrence的事务性只读投影；current改变时整体替换，用于反向失效、删除阻塞与影响proof。 | 不包含历史revision、运行时剪枝或resolved current，不能聚合丢失occurrence、成为DesignDSL之外的引用事实源或跨上下文共享持久化模型。 |
| TemplateReadiness | Template current 针对已知依赖事实的最终一致界面投影，取值为 `READY`、`INVALID` 或 `STALE`；依赖变化先进入 STALE，再由重检进入 READY/INVALID，确认提交依赖 ERROR 会直接进入 INVALID。 | 不是 Render 授权事实、发布、归档或新的 Template revision；INVALID/STALE 可编辑但不能成功 Render，编辑器打开和每次 Render 请求都会权威重检最新 current。 |
| TemplateValidationReport | 只面向 Template current、且不保留 revision 绑定的一份可替换有界问题投影；最近完成的检查可直接覆盖它，问题项使用稳定 code、severity、DesignDSL JSON Pointer/目标、dependency ref 和 message args。 | 不是历史 revision 报告、Render 授权事实、自由文本日志、原始 DesignDSL/输入转储或仅有 true/false 的标记；编辑器打开和 Render 请求必须重新检查而不能信任该投影。 |
| TemplateSnapshot | Rendering请求开始时，将一个已权威重检的Template current解析为精确身份/revision、ownerScope、永久StaticSchemaRef、DesignDSL快照及content hash后形成的不可变交接值。 | 不是新的Template revision，不含current指针、删除状态、编辑会话或持久化模型，也不得信任TemplateReadiness投影或在一次Evaluation中重新读取可变Template。 |
| Template closure snapshot | 一次Evaluation对根Template及全部authored可达TemplateRef current形成的一致请求级TemplateSnapshot集合；相同templateId只冻结一次，但每条use edge和实际invocation仍独立。 | 不是运行时命中分支集合、逐个时刻读取的混合闭包、持久化Draft closure、共享invocation结果或对相关Template的长时间锁定。 |
| Template problem | Template 命令或重检返回的稳定 code 与有界结构化问题集合，用于区分不存在、DELETED、revision/并发、依赖/确认、incoming reference、DAG 和临时不可用等失败。 | 不是供客户端解析的中英文 prose；失败命令不会留下部分 revision、current、dependency projection、readiness 或报告写入。 |
| Connector | 在 Template 边界之外获取 CSV、Excel、数据库或 HTTP 等外部数据并归一化为渲染输入的集成组件。 | 不叫 `DataSource`，不是 DesignDSL 节点，也不能把凭证、SQL、文件或网络能力带入表达式求值。 |
| RenderInput | 一次根 Evaluation 接收的封闭 strict-JSON envelope，由必填单一 `rootDocument` 与可省略的根级 `customValues[]` 组成；Schema 目标只来自 TemplateSnapshot。 | 不叫 `DataSource`，不是 Connector、ValueSource、多个命名数据根或可由调用方选择 Schema 的载体，也不保存在 DesignDSL/Template revision 中。 |
| AdmittedRenderInput | RenderInput 经 envelope 检查、精确 StaticSchema 权威验证和 Custom override 消解后形成的请求级不可变语义值；包含 closed typed context、PRESENT/ABSENT 状态、有效 Custom map 与类型证明。 | 不是原始 JSON、持久化输入记录、Workspace fixture、通用对象 map 或允许部分 Evaluation 的容器；Evaluator 不得越过它重读 RootDocument。 |
| AdmittedAssetValue | 外部PUBLIC Custom override中的imageRef/fontRef经same-scope、ACTIVE、kind及调用者asset.read检查后形成的请求级授权值。 | 不是持久化AssetRef、精确contentVersion、child fill权限检查或可免除实际消费位置current解析的ResolvedAsset。 |
| ABSENT | 一个 StaticSchema 已声明可选值在具体 typed context 中未出现时的内部有类型状态；合法路径遇到缺失可选祖先也传播该状态。 | 不是 JSON null、空字符串、默认值、Schema 外字段、无效 field path 或可由调用方直接提交的 literal。 |
| CustomDefinition | DesignDSL 顶层 `definitions[]` 中 `kind: "custom"` 的有类型 invocation 输入定义；以 Template 内唯一 definitionId 标识，拥有必填非 null literal 默认值和 `PUBLIC/PRIVATE` exposure。 | 不叫 TemplateParameter 或 DataSource，不是 Connector、第二 RootDocument、Computed Definition 或会自动跨嵌套 Template 继承的全局变量。 |
| Custom fill | TemplateUse在父lexical frame中求值一个ValueSource，并按child PUBLIC definitionId显式填充该次child invocation输入的作者事实。 | 不是同名自动继承、父Custom map透传、双向绑定、child default的持久修改或跨Template definition引用。 |
| Invocation frame | 一次具体 Template 调用创建的请求级不可变词法帧，持有该 Template 的精确 typed context、有效 CustomDefinition 值与自身 definitions。 | 不是父 Template/RootDocument 的共享 map、持久化会话或允许子调用回读调用者状态的动态作用域。 |
| Loop frame | Repeat 的某个实际输入项在所属 Template invocation 内创建、由稳定 loopId 定位的请求级不可变词法帧；持有 typed item、原集合零基 index，并只链接同 Template 的词法祖先。 | 不是 authored NodeKind、`$parent/$root` 漫游句柄、子 Template 继承环境、可变迭代器或任意 JSON object 上下文。 |
| Scalar item context | Evaluator为scalar Repeat item构造、精确符合对应`system-basic-*@v1` StaticSchema的不可变typed context；暴露必填`/index`与`/value`，可成为匹配TemplateUse的完整child context。 | 不是私有Context Contract、通用system map、任意object包装、shape推断或向reference业务Schema注入index。 |
| StaticSchema field path | DesignDSL 相对显式 invocation/loop domain、按精确 StaticSchemaRef 静态解析的业务字段路径；区分大小写、使用 RFC 6901 转义且不能以数字下标或 wildcard 穿越数组。 | 不叫系统数据源，不是 AssetRef、外部 Connector、动态 `$current/$parent/$root`、Schema 外字段或独立可变目录；不存在的 Schema 路径不是运行时 ABSENT。 |
| ValueType | Template 求值域中值的封闭类型身份；基础类型为 `text/decimal/boolean/date/time/color/imageRef/fontRef`，enum 与同质一维 `list<T>` 是派生类型；具体消费者可进一步限制可接纳的 `T`。 | 不是任意 JSON shape、StaticSchema constraint subtype 或可隐式互转的显示格式。 |
| ValueSource | DesignDSL 中对一个 typed value 来源的封闭描述，可指向 literal、显式 context path、loop index、命名 definition 或获准 capability。 | 不叫 `DataSource`，不是实际值、RenderInput、任意 JSON path 或外部 Connector。 |
| Computed Definition | MappingDefinition 与 ExpressionDefinition 的统称，表示在显式词法 domain 中产生一个声明类型值的命名 Definition。 | 不是 DesignDSL wire kind、CustomDefinition、inline calculation 或按消费者位置变化的动态变量。 |
| MappingDefinition | 以一个显式 ValueSource 为输入、按有序 first-match cases 与 required otherwise 产生声明类型值的低代码 Computed Definition。 | 不是无序字典、通用规则引擎或含任意表达式条件的脚本。 |
| ExpressionDefinition | 使用版本化 RenderWeave Expression Profile、只读取显式输入 alias 并产生声明类型值的 Computed Definition。 | 不是 JavaScript、inline Binding、任意对象查询或拥有隐式环境访问的函数。 |
| Expression Profile | 冻结某一代 RenderWeave expression grammar、类型、函数、求值顺序与错误语义的语言合同。 | 不是底层表达式库版本、自动升级的 latest 标记或浏览器本地语义分支。 |
| NodeContractCatalog | 按 exact DesignDSL version 唯一权威定义全部 NodeKind、property tree、derived ValueType、required/default、validation、array item 与 ContentModel 的全局封闭合同。 | 不是 Web 控件配置、Evaluator/Renderer 各自的 switch、Template-local schema、运行时插件表或 BindingPolicyCatalog。 |
| Node Property Identity | 永久的 `(nodeKind, propertyPathPattern)` 身份；其 ValueType、结构角色、default 与 validation 一经引入便跨全部 DesignDSL version 不可改变。 | 不是 slotId、UUID、UI label、具体数组下标、BindingPolicy ID 或可随 NodeContract revision 重解释的字段。 |
| Node ContentModel | NodeContract 对某个 NodeKind 是否允许 children、允许哪些 child kind、要求哪种 placement variant 及数组顺序语义的闭合结构规则。 | 不是 CSS display、编辑器拖放提示、Template 自定义 slot 或 Renderer 可自行放宽的建议。 |
| BindingPolicy | 全局 Node 定义者针对一个既有 Node Property Identity 追加的“可作为 Binding target”授权事实；类型、default 与 validation 始终从 NodeContractCatalog 派生。 | 不是 Template 可创建或覆盖的配置、ValueSource 来源白名单、属性赋值模式或另一份属性合同。 |
| BindingPolicyCatalog | 对全部 Template 全局生效、只追加且不允许 target 重叠的 BindingPolicy 集合；已有授权不可修改或删除，数组 pattern 只使用 `[*]`。 | 不保存在 Template 中，不是按 Template 定制的权限表、动态插件注册表、DesignDSL 版本快照或 target type 注册表。 |
| Binding | 位于消费 Design Node 上、以 ValueSource 求值结果覆盖一个已存在静态属性叶子的可选动态赋值。 | 不是顶层连线表、静态值的运行时 fallback、可创建属性的 JSON Patch 或必须存在的属性模式。 |
| TargetPropertyRef | Binding 在其宿主 Design Node 内定位一个已存在、全局允许绑定的具体属性出现位置的结构化引用。 | 不包含 nodeId 或 slotId，不是业务 StaticSchema field path、动态数组查询或任意 JSON Pointer。 |
| ConsumerPropertyRef | Rendering用与TargetPropertyRef同形的结构化路径定位一个materialized Node上的实际Asset消费属性，不要求该属性拥有BindingPolicy。 | 不是DesignDSL Binding target、JSON Pointer、Asset identity或跨Node定位器。 |
| Expression | RenderWeave Expression Profile 定义的封闭、版本化、显式输入且强类型的动态值语言；只能经 Evaluator 的受控 Evaluation Capabilities 访问获准环境值。 | 不是 JavaScript，也不拥有任意 IO、网络、文件、反射或隐式类型转换能力。 |
| Design Node | DesignDSL authored tree 中由 NodeContractCatalog 定义的视觉或结构实例；使用 client-owned nodeId、扁平 typed properties、node-local bindings，并由 ContentModel 约束 children。 | 不是 Template 自定义属性类型、通用 properties bag、Slot/SlotRegistry、任意插件代码、HTML/CSS/SVG DOM 或运行时自行注册的 kind。 |
| Repeat | 迭代一份静态可证明的 typed collection、为每个输入项建立 Loop frame，并用同一 authored item subtree 产生有序 occurrence 的结构 Design Node；同时拥有 nodeId 与 loopId。 | 不叫 Loop Node，不按 StaticSchema 自动选择视觉 Template，也不在 DesignDSL 中保存每项展开副本、filter/sort/key 或动态分页。 |
| Item subtree | Repeat 的有序 `children[]`，在每个 Loop frame 中分别求值；需要嵌套 Template 时由其中显式 TemplateUse 选择。 | 不是 Template 类型、自动 Schema-to-Template 映射、运行时复制后的持久化子树或 `itemLayout`。 |
| TemplateUse | DesignDSL中按logical TemplateRef调用一个same-scope child Template current的结构Design Node；用显式ContextSelector和Custom fills建立隔离child invocation，并在RenderDocument前完全展开。 | 不是Frame、Slot、sidecar host/use表、exact revision selector、动态Template推断、继承父frame或RenderEngine callback。 |
| ContextSelector | TemplateUse从显式invocation/loop lexical domain选择exact StaticSchema typed context，或为`system-empty@v1`选择显式empty context的封闭作者合同。 | 不是ValueSource、任意JSON path、shape转换、`$current/$parent/$root`别名或可省略的数据继承。 |
| Conditional | 只有 true branch 的结构 Design Node；其 condition 为 false 时整个 subtree 在后续 Binding、layout、Asset 与 output 前被剪枝，true 时降低为无外观 Frame。 | 不是 `visible`/`opacity`、双分支 if/else、CSS display 或会创建新 lexical frame 的容器。 |
| Canvas Node | `renderweave-design/1.0` 的唯一 DesignRoot kind，拥有以 mm 表示的有限二维物理画板、可选背景/bleed 与有序 children；不能嵌套。 | 不是 Editor viewport、DPI/像素画布、普通 Frame、分页集合或可 Binding 的输出尺寸。 |
| Placement | 每个 non-Canvas Design Node 必填的 shallow closed union；由父 ContentModel选择 ABSOLUTE、STACK、GRID 或仅用于 Repeat direct child 的 PACK，并携带该 variant 允许的尺寸合同及几何叶子。 | 不是 CSS、任意约束求解语言、Node transform、zIndex 或可整体 Binding 的 object。 |
| PACK placement | Repeat direct child 的专用浅层 Placement；每轴只允许 FIXED/HUG_CONTENT，由 Repeat 的 itemLayout 解释，因此切换 STACK/GRID packing 不重写 child placement。 | 不是普通 STACK/GRID placement、FILL、margin、坐标/cell hint 或可动态切换的 union discriminator。 |
| RepeatPackingSpec | Repeat 的 `itemLayout` 与 `instanceLayout` 共用的封闭 STACK/GRID 简化排布合同：前者排列一个 item 的 surviving direct children，后者排列 surviving item instances。 | 不是子 Template 选择、普通 Stack/Grid Node、responsive breakpoint、implicit track、masonry 或 per-item 动态排布。 |
| Text Run | Text Node 有序 `runs[]` 中一个完整的文本与 inline style 单元；没有 runId，以 revision-local index 定位，纯文本仍用一个完整 Run 表达。 | 不是 top-level plainText alias、隐式字体继承、独立 Node、稳定跨重排身份或可任意缺省样式的字符串片段。 |
| Evaluation Capabilities | exact Expression Profile拥有、Rendering向Expression显式input提供的封闭环境能力集合；首批仅为Clock与Random，部署必须完整支持该Profile。 | 不是AssetResolver、Template/request allowlist、调用者授权、通用网络、SQL、文件、脚本或凭证接口，也不默认保证新Evaluation重放旧结果。 |
| Capability contract | 由exact Expression Profile永久映射的一项closed capability/operation、输出类型、调用身份、结果与错误语义。 | 不是通用`name/args`工具协议、Template声明、部署动态插件、latest capability或调用者可协商版本。 |
| Evaluation Clock snapshot | 一次Evaluation共享的单一UTC整秒时刻，只能经Clock capability投影为`date`或`time`显式Expression input。 | 不是服务器/浏览器本地时区、每call-site重新读取的时间、datetime/text值、业务日期输入或可由调用者覆盖的时钟。 |
| Evaluation Random nonce | CapabilityState中服务端产生、只用于按exact CapabilityCallPosition确定性派生`[0,1)`随机decimal的请求级256-bit秘密。 | 不是作者seed、安全令牌来源、业务身份、可公开重放值、顺序推进PRNG状态或可进入DesignDSL/RenderInput的字段。 |
| CapabilityState | 全部请求准入成功后，为一个逻辑Evaluation及其closure静态声明的能力合同建立、绑定renderRequestId与evaluationFingerprint的不可变Clock snapshot和/或server-only Random nonce；同请求恢复必须重放，新请求重新建立。 | 不是DesignDSL/RenderInput、调用者seed/time、长期历史、跨请求Template状态或RenderDocument内容。 |
| CapabilityCallPosition | 一个demanded capability input在其root/TemplateUse exact revision路径及截至declaration domain的零基Repeat frame、definitionId、input alias与exact capability contract中的请求级逻辑身份。 | 不是第N次物理调用、Expression AST/consumer Node位置、item内容或业务key、跨请求公共ID或Asset occurrence。 |
| Capability demand | 一个CapabilityCallPosition在其Expression input首次实际读取时发生的逻辑调用与预算单位；同alias重读和同declaration frame的Definition memoized重用不重复计数。 | 不是CapabilityState初始化、底层Clock/HMAC执行次数、静态声明数量或可以因物理缓存而省略的预算。 |
| Capability result digest | 一次成功Evaluation按实际demand顺序对exact capability contract、operation、call position、输出类型及canonical typed result作domain-separated hash得到的身份成分。 | 不是Clock/Random原值、Random nonce、完整调用transcript、Design content hash、授权或能在Evaluation前计算的cache key。 |
| Evaluator | Rendering上下文中，按固定准入与consumer顺序把Template closure snapshot、AdmittedRenderInput和获准capability求值为静态节点及已解析资源的唯一动态语义权威。 | 浏览器反馈、RenderEngine、TemplateReadiness或可持久化中间Scene不构成第二权威。 |
| Evaluation | Rendering上下文中，Evaluator把Template closure snapshot、AdmittedRenderInput及按需存在的exact CapabilityState降低并原子seal为一个请求级RenderDocument的一次根运行；同一文档的多种输出编码仍属于这一次运行。 | 不是编辑、保存、batch records、跨请求replay、RenderEngine布局或最终图片编码。 |
| Evaluation fingerprint | 一次Evaluation在执行前对授权上下文、closure、admitted input、exact contracts与有效预算作domain-separated hash得到的内部恢复冲突指纹。 | 不是renderRequestId、成功结果身份、contentHash、缓存键或可公开值，也不包含Clock/Random/Asset选择结果。 |
| Evaluation result digest | RenderDocument成功seal后，对scope、closure、admitted input、exact render合同及实际capability/Asset选择摘要作domain-separated hash得到的成功语义身份。 | 不是授权、RenderDocument传输摘要、Renderer输出参数、跨scope cache许可或失败时可形成的partial digest。 |
| Evaluation diagnostic sidecar | Rendering在活跃请求内保存的、由opaque occurrenceId/resourceId回接完整OccurrencePath与授权诊断定位的容量受限映射。 | 不是RenderDocument metadata、持久历史、普通日志、cache identity或Engine可见Template信息。 |
| RenderDSL | Rendering拥有、以`renderweave-render/1.0`起始的Engine-facing版本化静态语言；表达具体文本、封闭静态Node、渲染级布局规则和一对一RenderResource manifest。 | 不是DesignDSL、宽松properties bag或旧`haibo.dsl/1.0`别名，也不含Binding、Expression、循环、Template引用、current语义或capability调用。 |
| RenderNodeContractCatalog | 按exact RenderDSL version冻结每个静态kind的closed payload、default、ContentModel与DesignDSL lowering edge的全局合同。 | 不是Design NodeContract的复用类、Engine插件注册表、通用properties schema或Java/Rust各自维护的switch。 |
| RenderDocument | Evaluation成功后原子seal、只向RenderEngine交接的一份请求级strict-JSON RenderDSL文档；包含opaque occurrenceId、展开default的有序静态Node、exact Layout Profile与一对一RenderResource manifest，但保留布局规则而没有final geometry。 | 不叫MaterializedScene，不是可持久化/公开/跨请求复用的Artifact、浏览器权威预览、partial builder、最终几何场景或RenderOutput。 |
| RenderDocument digest | 对包含短期fetch lease的完整Canonical RenderDocument bytes作domain-separated SHA-256得到、由Engine核验的请求内传输完整性摘要。 | 不是Evaluation result digest、cache key、日志字段、跨请求稳定身份或可公开下载校验值。 |
| Renderer Command | Rendering向RenderEngine发送的一次内部、closed、可canonical化请求；以`renderweave-render-command/1.0`起始，携带一个RenderDocument、不可延长deadline、exact Renderer/Output Profile及一张图片的有效输出参数。 | 不是公共Render API、batch/page、RenderDocument内容、调用方可自制DSL、Template/Evaluation身份容器或Engine回读动态事实的入口。 |
| Active render request | RenderEngine以内部requestId与canonical Command identity线性化的一次有deadline执行；同内容重发join/replay，取消与atomic output seal在同一生命周期竞争。 | 不是公共renderOperationId、跨请求cache、持久job、Artifact、可续期lease或允许换参数重跑的幂等键。 |
| compositionViewport | RenderDocument中把一个已完全展开的静态child artboard以固定CONTAIN/CENTER和双层clip规则映射进parent host LayoutBox的布局原语。 | 不是DesignDSL TemplateUse、nested Canvas、child revision句柄、栅格快照、跨Template callback或final-coordinate scene。 |
| Layout Profile | 由 exact 标识冻结 RenderEngine 的 measure/arrange/shaping、box、transform、clip、paint order 与确定性数值语义的兼容合同；首个为 `renderweave-layout/1.0`。 | 不是调用方可选的质量档位、DPI、DesignDSL Profile、浏览器 CSS 版本或可原地热修复的 latest 算法。 |
| Renderer Profile | 由 exact 标识冻结Engine对资源、orientation、颜色、字体解析、raster、sampling、blend及QR/Barcode像素算法的兼容合同；首个为`renderweave-renderer/1.0`。 | 不是Layout Profile、图片格式/quality、engine build version、GPU/CPU开关、调用方质量档位或环境默认集合。 |
| Output Profile | 对已经确定的像素冻结某种图片格式的encoder、bitstream和metadata合同；首批为`renderweave-output-png/1.0`与`renderweave-output-jpeg/1.0`。 | 不是layout/raster算法、任意codec option、文件名、持久化策略、caller可协商的latest或多格式集合。 |
| 画板内约束自适应布局 | 在固定物理 Canvas 内，Stack 依 fillWeight、Grid 依 FRACTION、HUG/FILL 依父约束重新分配空间的布局能力。 | 不是网页 responsive breakpoint、viewport/percent unit、媒体查询或随输出 DPI 改变物理布局。 |
| IntrinsicSize | Node 在一组 `UNBOUNDED/AT_MOST/EXACT` 轴约束和精确资源下由内容纯测量得到的自然尺寸。 | 不是 persisted width/height、PaintBounds、浏览器 `scrollSize` 或可以打破 HUG/FILL cycle 的猜测值。 |
| LayoutBox | RenderEngine arrange 后、Node transform 前的 border box；placement width/height 指向它且不含 margin。 | 不是 ContentBox、MarginExtent、PaintBounds、像素边界或可写回 DesignDSL 的坐标。 |
| ContentBox | LayoutBox 向内扣除适用的 inward border stroke 与 padding 后的非负子内容区域；Text 只扣 padding，因为其 stroke 属于 glyph。 | 不是 padding clip boundary、PaintBounds、margin box 或所有 Node 都拥有的 authored object。 |
| MarginExtent | Stack/Grid 排布时由 LayoutBox 加 signed margin 形成的轴区间。 | 不是绘制 box、Absolute placement 能力、可裁剪区域或会折叠的 CSS margin。 |
| PaintBounds | Node/subtree 应用 world transform 后、尚未受自身/祖先/surface clip 影响的保守世界坐标 AABB。 | 不是 LayoutBox、精确 clip path、layout input 或内容必须被限制在其中的保证。 |
| EffectivePaintBounds | PaintBounds 与全部有效 ClipRegion/surface 相交后的保守世界坐标 AABB。 | 不是精确像素 coverage、布局反馈或替代真实 clip path 的权威几何。 |
| ClipRegion | Engine 绘制栈中的实际 box、rounded shape 或 ancestor/surface 裁剪区域。 | 不是其诊断 AABB、padding 边界、overflow 标志或浏览器 DOM clip。 |
| LaidOutScene | RenderEngine 根据 RenderDocument 完成资源准备、measure/arrange、最终约束 shaping 后形成的请求内最终几何绘制场景。 | 不是跨上下文持久化合同、DesignDSL、RenderDocument、MaterializedScene 或用户产品输出。 |
| LayoutTrace | 绑定单次请求和 exact Layout Profile、容量受限且经过授权的布局诊断投影，可含 occurrence box、transform、bounds、clip kind、paint index 与 overflow flag。 | 不是 revision 事实、RenderOutput、缓存 identity、完整 Scene，且不能转储原始文本、输入、DesignDSL 或 Asset bytes。 |
| Renderer / RenderEngine | Rendering 上下文中把 RenderDocument 经布局、资源准备、文本 shaping、绘制和编码转换为 RenderOutput 的组件。 | 不重新解释 DesignDSL、ValueSource、Expression、循环或 Template 引用。 |
| RenderOutput | RenderEngine为一条Command原子产生的一张完整PNG/JPEG及closed安全metadata；正式输出和权威预览都使用该请求瞬态结果。 | 不是RenderDocument、LaidOutScene、partial transport、batch、一组图片、浏览器本地画布、自动持久化Artifact或Workspace历史。 |
| Authoritative Preview | 使用与正式输出相同的 RenderEngine 生成并展示的 RenderOutput。 | 不是浏览器对 DesignDSL 或 RenderDSL 的本地解释；本地画布反馈只能是非权威草稿。 |
| Asset | Asset Management 中以服务端生成的全局唯一canonical UUID v4 `assetId`标识的稳定聚合；拥有不可变ownerScope/kind、可变metadata、current content与`ACTIVE/DELETED`可恢复生命周期。 | 不是内容版本、Blob、泛化`Resource`、外部数据源、任意URL或可跨scope移动的共享对象。 |
| Asset content revision | 以 `{assetId, contentVersion}` 精确标识、经某个 AssetAcceptanceProfile 接纳的不可变原始内容及技术描述；旧内容恢复会复用 Blob 并追加新版本。 | 不叫 `AssetVersion`，不是 metadata 历史、可改写文件、current 指针或独立用户资源。 |
| Asset current | Asset 唯一的当前 Asset content revision；每个实际消费位置在自己的线性化点独立读取。 | 不是closure admission冻结值、一次Evaluation内自动memoize的版本，也不是可由DesignDSL精确选择的历史版本。 |
| AssetAcceptanceProfile | Asset Management 拥有的版本化格式、特性和单内容上限合同；首个 exact ID 为 `renderweave-asset-acceptance/1.0`，只接纳静态 PNG/JPEG/WebP 与非集合、非 variable 的 TTF/OTF。 | 不是对在线 Renderer capabilities 的动态代理、任意文件白名单或内容转换配置。 |
| Asset Blob | ownerScope 内按 SHA-256 内容寻址、可被多个 Asset content revision 复用的内部不可变字节对象。 | 不是 Asset 身份、contentVersion、跨 scope 全局去重结果或可写入 DesignDSL 的引用。 |
| AssetRef | DesignDSL中封闭的`{assetId}`逻辑current选择器；声明ValueType给出预期kind，每个实际Node property消费位置独立解析。 | 不是精确contentVersion、Asset聚合、ResolvedAsset、文件名/hash/URL、动态selector或一次Evaluation内稳定值。 |
| Asset fetch lease | AssetResolver为一次已固定的exact内容选择签发、绑定render request/resource/audience且自动过期的Renderer-only读取授权。 | 不是公共/长期URL、Asset read权限、current选择器、可续签能力或跨请求credential。 |
| ResolvedAsset | 单个实际Asset消费位置解析后形成的Rendering内部不可变值；钉死resource/occurrence locator、Asset exact content、技术描述与fetch lease。 | 不是可变Asset、RenderResource、已加载字节、公共URL、长期transcript或按assetId共享的memoized选择。 |
| RenderResource | ResolvedAsset一对一投影到RenderDocument manifest的Renderer输入；以请求级resourceId携带exact内容校验、技术描述和fetch lease，但删除Asset业务身份。 | 不是AssetRef、Asset/contentVersion身份、可持久化资源、公共下载描述或可跨occurrence合并的内容缓存项。 |
| AssetResolver | Asset Management向Rendering提供、按实际消费位置线性化选择同scope ACTIVE Asset current并签发exact fetch lease的内部能力。 | 不使用调用者asset.read权限，不是Expression capability、通用HTTP/文件读取接口、current cache或让Rendering访问Asset持久化模型的后门。 |
| Asset selection digest | 一次Evaluation按资源消费顺序对全部exact Asset选择及安全occurrence身份作domain-separated hash得到的身份成分。 | 不是Design content hash、资源授权、完整选择transcript、URL/token摘要或按unique内容去重的cache key。 |
| Asset deletion confirmation | 删除 Asset 前绑定操作者、scope、assetRevision 与完整 current-reference fingerprint 的短期单次确认。 | 不是 `force=true`、删除许可缓存、级联删除或可忽略引用漂移的 UI 确认框。 |
| AssetReferenceAuthority | Template Design 发布的 current-only AssetRef proof 与 reservation 合同，用于让 Template current 变更和 Asset 删除确认按 assetId 线性化。 | 不是共享表、跨上下文聚合、数据库外键或历史 Template revision 索引。 |
| Asset problem | Asset 命令、接纳或解析返回的 namespaced 稳定 code 与有界结构化字段。 | 不是可供客户端解析的自然语言、原始文件内容、完整请求或 actor-specific TemplateReadiness。 |

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
