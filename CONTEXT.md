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
| Template | Template Design 上下文中面向有限二维画板、以全局唯一不透明 `templateId` 标识的可变聚合；拥有永久 StaticSchemaRef、current revision 和 `ACTIVE/DELETED` 生命周期，且没有发布状态，DELETED 为不可恢复终态。 | 不是 Template revision、DesignDSL、可修改或复用的人类业务 key、独立 metadata 聚合、编辑器画布状态或最终图片；更换 StaticSchema 必须创建或复制为另一个 Template。 |
| Template revision | 以 `{templateId, revision}` 精确标识、从 revision 0 单调追加的不可变完整 DesignDSL 历史快照；每次被接受的显式保存都会追加 revision，即使内容 hash 与 current 相同，content hash 也只证明完整性。 | 不是差异补丁、独立 metadata 版本、`TemplateVersion`、`PublishedTemplate` 或 `StaticTemplate`，也不承诺跨次求值可重放。 |
| Template current | Template 唯一的当前内容 revision，始终是最新成功追加的 revision；恢复历史会复制旧 DesignDSL 并追加新 revision，而不回拨指针或产生分支。 | 不是 latest StaticSchema、可移动标签或多个并存分支。 |
| Template save | 以 `expectedRevision` 为前提，把一份结构合法的完整 DesignDSL 经权威校验后原子追加为新 revision 并更新 current 的显式操作；依赖类 ERROR 可经绑定精确问题集的二次确认提交为 INVALID。 | 不是 JSON Patch、autosave、会话锁、最后写入者覆盖或可绕过结构/版本/循环/安全规则的强制保存。 |
| Template copy | 从精确 `{templateId, revision}` 创建独立 Template 的操作；复制来源 DesignDSL、不复制 revision 历史或来源关系，新 Template 从 revision 0 开始，并可显式改绑到另一 StaticSchemaRef；依赖类 ERROR 可经二次确认创建为 INVALID。 | 不是跟随来源 current 的 fork、历史克隆、可追溯 lineage、原地 Schema 改绑、自动字段迁移或损坏 DesignDSL 的导入通道。 |
| Template revision restore | 在 ACTIVE Template 上把某个精确历史 revision 的 DesignDSL 复制并追加为新 current 的操作；依赖类 ERROR 可经二次确认恢复为 INVALID。 | 不是回拨 current、修改历史 revision 或恢复 DELETED 生命周期。 |
| Template delete | 携带 `expectedRevision`、在一致性边界确认无 ACTIVE incoming TemplateRef 后，把 ACTIVE Template 终结为 DELETED 的软删除操作；永久保留身份、Schema、current、revision 与审计事实，只允许只读查看、导出和复制精确历史。 | 不是物理删除、级联删除、可跳过引用检查的管理操作或隐藏后仍可恢复的归档；DELETED 不能编辑、保存、引用、预览或 Render。 |
| Invalid commit confirmation | 保存、复制或历史恢复在结构合法但存在 StaticSchema field path、AssetRef 或 TemplateRef 等依赖类 ERROR 时使用的二阶段确认；短期 token 绑定操作、Design content hash、来源与目标、依赖快照和问题 fingerprint，漂移后必须重新确认。 | 不是裸 `force=true`、前端自证、永久豁免或绕过 DSL 版本、结构、TemplateRef DAG、安全与预算不变量的能力。 |
| DesignDSL | Template revision 保存的版本化作者内容 JSON 事实源，包含 metadata、definitions 与唯一 DesignRoot；在线设计器是其语义无损投影，求值或渲染产物只能由已保存 revision 派生。 | 不携带 Template 身份、StaticSchemaRef、current/lifecycle/readiness、运行输入或 EditorSession 状态，也不是历史项目格式的兼容别名。 |
| DesignDSL Profile | 由 exact `dslVersion` 标识、永久冻结某一代 DesignDSL wire、Node property identity、default、validation 与 canonicalization 语义的合同。 | 不是 SemVer range、latest 标记、Expression Profile、BindingPolicyCatalog snapshot 或可在旧 revision 上热替换的 parser。 |
| DesignRoot | 一份 DesignDSL 中唯一的 authored visual root，承载该 Template 的 Node 与结构内容。 | 不是业务 RootDocument、编辑器 canvas state、RenderDocument 或可由 Template envelope 替换的第二内容根。 |
| Local authored identity | DesignDSL 内以 canonical UUID v4 表示、分别由 nodeId/definitionId/bindingId/loopId/useId namespace 定位的作者实体身份。 | 不是跨 Template 全局身份、人类业务 key、数组位置或由普通服务端保存静默生成的 ID。 |
| Canonical DesignDSL | DesignDSL 经版本化 metadata normalization、无语义集合排序、精确 decimal 与 canonical JSON encoding 后得到的唯一内容表示。 | 不是原始上传字节、业务 default 展开、自动修复结果或由数据库 serializer 偶然产生的 JSON 文本。 |
| Design content hash | 对 Canonical DesignDSL 作 domain-separated SHA-256 得到的 revision 内容完整性标识；不包含 Template/Schema 身份或解析后的外部依赖版本。 | 不是签名、来源证明、语义等价、发布版本、去重键、可重放承诺或可单独使用的 Render cache identity。 |
| Template revision export | 把 exact Template revision identity、永久 StaticSchemaRef、Design content hash 与完整 DesignDSL 组合成的版本化可移植 envelope。 | 不是可移动 current 快照、跨部署身份声明、Template copy、Schema 改绑命令或可被导入方信任的授权证明。 |
| DesignDSL migration | 从 exact source DesignDSL/Profile/hash 纯转换并预览为另一个 exact profile 的显式作者操作；接受后通过普通保存追加新 revision。 | 不是读取时升级、历史重写、依赖修复、普通 save 的 ID 生成器或允许未知旧格式静默导入的兼容层。 |
| TemplateRef | DesignDSL 中只按 `templateId` 跟随目标 Template current 的引用；引用图必须是 DAG，Evaluation 请求开始时一次解析并冻结，目标 current/readiness/lifecycle 变化会经 current-only 反向索引触发父 Template 递归重检。 | 不是 authored exact revision 引用、latest StaticSchema 或可在一次 Evaluation 中重新解析的动态指针；精确 revision 只用于历史、复制与恢复。 |
| Template dependency projection | ACTIVE Template current 中 AssetRef 与 TemplateRef 的事务性只读投影；每次 current 改变时整体替换，用于反向失效、Template 删除阻塞与 Asset 删除影响 proof。 | 不包含历史 revision，不能成为 DesignDSL 之外的引用事实源，也不跨上下文共享持久化模型。 |
| TemplateReadiness | Template current 针对已知依赖事实的最终一致界面投影，取值为 `READY`、`INVALID` 或 `STALE`；依赖变化先进入 STALE，再由重检进入 READY/INVALID，确认提交依赖 ERROR 会直接进入 INVALID。 | 不是 Render 授权事实、发布、归档或新的 Template revision；INVALID/STALE 可编辑但不能成功 Render，编辑器打开和每次 Render 请求都会权威重检最新 current。 |
| TemplateValidationReport | 只面向 Template current、且不保留 revision 绑定的一份可替换有界问题投影；最近完成的检查可直接覆盖它，问题项使用稳定 code、severity、DesignDSL JSON Pointer/目标、dependency ref 和 message args。 | 不是历史 revision 报告、Render 授权事实、自由文本日志、原始 DesignDSL/输入转储或仅有 true/false 的标记；编辑器打开和 Render 请求必须重新检查而不能信任该投影。 |
| TemplateSnapshot | Rendering 请求开始时，将一个已权威重检的 Template current 解析为精确身份/revision、永久 StaticSchemaRef、DesignDSL 快照及内容 hash 后形成的不可变交接值。 | 不是新的 Template revision，不含 current 指针、删除状态、编辑会话或持久化模型，也不得信任 TemplateReadiness 投影或在一次 Evaluation 中重新读取可变 Template。 |
| Template closure snapshot | 一次 Evaluation 对根 Template 及全部可达 TemplateRef 的一致、请求级 TemplateSnapshot 集合；解析期间任一 current 漂移都会使本次收集重试或失败。 | 不是逐个时刻读取形成的混合闭包、持久化 Draft closure 或对相关 Template 的长时间锁定。 |
| Template problem | Template 命令或重检返回的稳定 code 与有界结构化问题集合，用于区分不存在、DELETED、revision/并发、依赖/确认、incoming reference、DAG 和临时不可用等失败。 | 不是供客户端解析的中英文 prose；失败命令不会留下部分 revision、current、dependency projection、readiness 或报告写入。 |
| Connector | 在 Template 边界之外获取 CSV、Excel、数据库或 HTTP 等外部数据并归一化为渲染输入的集成组件。 | 不叫 `DataSource`，不是 DesignDSL 节点，也不能把凭证、SQL、文件或网络能力带入表达式求值。 |
| RenderInput | 一次根 Evaluation 接收的封闭 strict-JSON envelope，由必填单一 `rootDocument` 与可省略的根级 `customValues[]` 组成；Schema 目标只来自 TemplateSnapshot。 | 不叫 `DataSource`，不是 Connector、ValueSource、多个命名数据根或可由调用方选择 Schema 的载体，也不保存在 DesignDSL/Template revision 中。 |
| AdmittedRenderInput | RenderInput 经 envelope 检查、精确 StaticSchema 权威验证和 Custom override 消解后形成的请求级不可变语义值；包含 closed typed context、PRESENT/ABSENT 状态、有效 Custom map 与类型证明。 | 不是原始 JSON、持久化输入记录、Workspace fixture、通用对象 map 或允许部分 Evaluation 的容器；Evaluator 不得越过它重读 RootDocument。 |
| ABSENT | 一个 StaticSchema 已声明可选值在具体 typed context 中未出现时的内部有类型状态；合法路径遇到缺失可选祖先也传播该状态。 | 不是 JSON null、空字符串、默认值、Schema 外字段、无效 field path 或可由调用方直接提交的 literal。 |
| CustomDefinition | DesignDSL 顶层 `definitions[]` 中 `kind: "custom"` 的有类型 invocation 输入定义；以 Template 内唯一 definitionId 标识，拥有必填非 null literal 默认值和 `PUBLIC/PRIVATE` exposure。 | 不叫 TemplateParameter 或 DataSource，不是 Connector、第二 RootDocument、Computed Definition 或会自动跨嵌套 Template 继承的全局变量。 |
| Invocation frame | 一次具体 Template 调用创建的请求级不可变词法帧，持有该 Template 的精确 typed context、有效 CustomDefinition 值与自身 definitions。 | 不是父 Template/RootDocument 的共享 map、持久化会话或允许子调用回读调用者状态的动态作用域。 |
| Loop frame | 某个实际循环项在所属 Template invocation 内创建、由稳定 loopId 定位的请求级不可变词法帧；持有 typed item、零基 index，并只链接同 Template 的词法祖先。 | 不是 `$parent/$root` 漫游句柄、子 Template 继承环境、可变迭代器或任意 JSON object 上下文。 |
| StaticSchema field path | DesignDSL 相对显式 invocation/loop domain、按精确 StaticSchemaRef 静态解析的业务字段路径；区分大小写、使用 RFC 6901 转义且不能以数字下标或 wildcard 穿越数组。 | 不叫系统数据源，不是 AssetRef、外部 Connector、动态 `$current/$parent/$root`、Schema 外字段或独立可变目录；不存在的 Schema 路径不是运行时 ABSENT。 |
| ValueType | Template 求值域中值的封闭类型身份；基础类型为 `text/decimal/boolean/date/time/color/imageRef/fontRef`，enum 与同质 scalar list 是派生类型。 | 不是任意 JSON shape、StaticSchema constraint subtype 或可隐式互转的显示格式。 |
| ValueSource | DesignDSL 中对一个 typed value 来源的封闭描述，可指向 literal、显式 context path、loop index、命名 definition 或获准 capability。 | 不叫 `DataSource`，不是实际值、RenderInput、任意 JSON path 或外部 Connector。 |
| Computed Definition | MappingDefinition 与 ExpressionDefinition 的统称，表示在显式词法 domain 中产生一个声明类型值的命名 Definition。 | 不是 DesignDSL wire kind、CustomDefinition、inline calculation 或按消费者位置变化的动态变量。 |
| MappingDefinition | 以一个显式 ValueSource 为输入、按有序 first-match cases 与 required otherwise 产生声明类型值的低代码 Computed Definition。 | 不是无序字典、通用规则引擎或含任意表达式条件的脚本。 |
| ExpressionDefinition | 使用版本化 RenderWeave Expression Profile、只读取显式输入 alias 并产生声明类型值的 Computed Definition。 | 不是 JavaScript、inline Binding、任意对象查询或拥有隐式环境访问的函数。 |
| Expression Profile | 冻结某一代 RenderWeave expression grammar、类型、函数、求值顺序与错误语义的语言合同。 | 不是底层表达式库版本、自动升级的 latest 标记或浏览器本地语义分支。 |
| BindingPolicy | 由全局 Node 定义者为一个 node kind + property path pattern 声明的可绑定目标类型与属性校验合同。 | 不是 Template 可创建或覆盖的配置、ValueSource 来源白名单或属性赋值模式。 |
| BindingPolicyCatalog | 对全部 Template 全局生效、只追加的可绑定 Node 属性规则集合；已有目标、类型与校验含义不可修改或删除。 | 不保存在 Template 中，不是按 Template 定制的权限表、动态插件注册表或 DesignDSL 版本快照。 |
| Binding | 位于消费 Design Node 上、以 ValueSource 求值结果覆盖一个已存在静态属性叶子的可选动态赋值。 | 不是顶层连线表、静态值的运行时 fallback、可创建属性的 JSON Patch 或必须存在的属性模式。 |
| TargetPropertyRef | Binding 在其宿主 Design Node 内定位一个已存在、全局允许绑定的具体属性出现位置的结构化引用。 | 不包含 nodeId 或 slotId，不是业务 StaticSchema field path、动态数组查询或任意 JSON Pointer。 |
| Expression | RenderWeave Expression Profile 定义的封闭、版本化、显式输入且强类型的动态值语言；只能经 Evaluator 的受控 Evaluation Capabilities 访问获准环境值。 | 不是 JavaScript，也不拥有任意 IO、网络、文件、反射或隐式类型转换能力。 |
| Design Node | DesignDSL 中由全局固定 Node 属性模型定义的元素或容器节点；每个 Template 只能实例化并填写该模型。 | 不是 Template 自定义属性类型、任意插件代码、HTML/CSS/SVG DOM 或运行时自行注册的 kind。 |
| Evaluation Capabilities | Rendering 上下文内 Expression 可经显式输入调用的封闭环境能力集合，首批为 Clock 与 Random。 | 不是 AssetResolver、通用网络、SQL、文件、脚本或凭证访问接口，也不默认保证跨次求值可重放。 |
| Evaluator | Rendering 上下文中，对 TemplateSnapshot、一次渲染输入和获准 Evaluation Capabilities 执行唯一权威语义求值的组件。 | 浏览器即时反馈或 Renderer 不构成第二套动态语义权威。 |
| Evaluation | Rendering 上下文中，Evaluator 把 TemplateSnapshot、RenderInput 和获准 Evaluation Capabilities 降低为 RenderDocument 的一次运行。 | 不是编辑、保存、RenderEngine 布局或最终图片编码。 |
| RenderDSL | Rendering 上下文拥有、面向 RenderEngine 的版本化语言；表达具体文本、静态节点、渲染级布局规则和已解析资源清单。 | 不是 DesignDSL，也不含 Binding、Expression、循环、Template 引用、current 语义或 capability 调用。 |
| RenderDocument | Evaluation 产生的一份请求级 RenderDSL 文档；供 RenderEngine、诊断与一致性测试使用，但没有独立产品生命周期。 | 不叫 MaterializedScene，不是浏览器权威预览、用户管理的 Artifact、最终几何场景或 RenderOutput。 |
| LaidOutScene | RenderEngine 根据 RenderDocument 完成布局、资源准备和文本 shaping 后形成的最终几何绘制场景。 | 不是跨上下文合同、DesignDSL、RenderDocument 或用户产品输出。 |
| Renderer / RenderEngine | Rendering 上下文中把 RenderDocument 经布局、资源准备、文本 shaping、绘制和编码转换为 RenderOutput 的组件。 | 不重新解释 DesignDSL、ValueSource、Expression、循环或 Template 引用。 |
| RenderOutput | RenderEngine 为一次请求产生的一张或一组图片及其媒体描述；正式输出和权威预览都使用这一结果。 | 不是 RenderDocument 或浏览器本地画布；是否持久化为 RenderArtifact 由后续规格决定。 |
| Authoritative Preview | 使用与正式输出相同的 RenderEngine 生成并展示的 RenderOutput。 | 不是浏览器对 DesignDSL 或 RenderDSL 的本地解释；本地画布反馈只能是非权威草稿。 |
| Asset | Asset Management 中以全局唯一不透明 `assetId` 标识的稳定聚合；拥有不可变 ownerScope/kind、可变 metadata、current content 与 `ACTIVE/DELETED` 可恢复生命周期。 | 不是内容版本、Blob、泛化 `Resource`、外部数据源、任意 URL 或可跨 scope 移动的共享对象。 |
| Asset content revision | 以 `{assetId, contentVersion}` 精确标识、经某个 AssetAcceptanceProfile 接纳的不可变原始内容及技术描述；旧内容恢复会复用 Blob 并追加新版本。 | 不叫 `AssetVersion`，不是 metadata 历史、可改写文件、current 指针或独立用户资源。 |
| Asset current | Asset 唯一的当前 Asset content revision；不同 AssetRef 出现位置在各自解析时读取当时的 current。 | 不是一次 Evaluation 内自动冻结或 memoize 的版本，也不是可由 DesignDSL 精确选择的历史版本。 |
| AssetAcceptanceProfile | Asset Management 拥有的版本化格式、特性和单内容上限合同；v1 只接纳静态 PNG/JPEG/WebP 与非集合、非 variable 的 TTF/OTF。 | 不是对在线 Renderer capabilities 的动态代理、任意文件白名单或内容转换配置。 |
| Asset Blob | ownerScope 内按 SHA-256 内容寻址、可被多个 Asset content revision 复用的内部不可变字节对象。 | 不是 Asset 身份、contentVersion、跨 scope 全局去重结果或可写入 DesignDSL 的引用。 |
| AssetRef | DesignDSL 中封闭的 `{assetId}` 逻辑选择器；所在属性给出预期 kind，每个实际执行到的出现位置独立解析 Asset current。 | 不是精确 contentVersion、Asset 聚合、ResolvedAsset、文件名/hash/URL 或一次 Evaluation 内稳定值。 |
| ResolvedAsset | 单个 AssetRef 出现位置解析后形成的不可变清单项，钉死 assetId、contentVersion、kind、受信 fetch URL、SHA-256、mediaType 与 byteLength。 | 不是可变 Asset、已加载字节、公共 URL 或请求内按 assetId 共享的 memoized 选择。 |
| AssetResolver | Asset Management 向 Rendering 提供、按出现位置把同 scope ACTIVE AssetRef 解析为 ResolvedAsset 的内部能力。 | 不使用调用者的 asset.read 权限，不是通用 HTTP/文件读取接口，也不得让 Rendering 访问 Asset 持久化模型。 |
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
