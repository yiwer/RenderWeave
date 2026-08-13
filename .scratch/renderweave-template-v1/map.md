# Wayfinder Map：RenderWeave Template v1

Label: wayfinder:map

## Destination

形成一份可直接交给后续实施规划的、决策完备的 `RenderWeave Template v1` 产品与软件规格：覆盖在线 Template 与 Asset Management、JSON DesignDSL、数据/表达式、元素与布局、循环与嵌套、受控求值、RenderDSL/RenderDocument 以及有限二维图片输出；本地图不实施产品代码。

## Notes

- 当前 Schema v1 与已批准 delta 仍是权威；Template 创建时永久绑定精确 `{schemaKey, versionTag}`，不得改变既有 StaticSchema 内容或生命周期。
- Template 没有发布状态；metadata 位于 DesignDSL，每次 accepted save 追加不可变 revision；Template 拥有不可变 ownerScope，TemplateRef 只允许 same-scope 并跟随 current，DELETED 是不可恢复终态。
- TemplateReadiness/当前报告只是最终一致投影；INVALID/STALE 可编辑但不能 Render，编辑器打开和每次 Render 都权威重检最新 current 与一致闭包。
- DesignDSL JSON 是唯一作者内容事实源；编辑器是无损投影，Evaluator 是动态语义的唯一权威，RenderEngine 只消费符合 RenderDSL 的 RenderDocument。
- Evaluation严格经过closure/input/Asset admission、CapabilityState、串行惰性物化与原子seal；不定义正式MaterializedScene。RenderDocument使用独立`renderweave-render/1.0`、opaque occurrenceId与一对一RenderResource，只保留静态布局规则并由Engine形成final LaidOutScene。
- renderRequestId、evaluationFingerprint、evaluationResultDigest、renderDocumentDigest与Design contentHash严格分离；完整OccurrencePath/sourceNodeId只在请求级诊断sidecar，不进入RenderDocument或Engine。
- 外部 Connector 在 Template 边界外归一化业务数据；DesignDSL 不获得任意 SQL、HTTP、文件、脚本或凭证能力。
- Expression只可经显式input使用exact Profile拥有的封闭Clock/Random Evaluation Capabilities；同一renderRequestId/fingerprint内部恢复重放CapabilityState，新Evaluation重新取UTC snapshot/server-only nonce且不承诺跨请求重放。AssetResolver仍是独立的实际asset occurrence解析合同。
- Asset Management 首版只接纳 IMAGE/FONT，以稳定 Asset、不可变内容版本、逻辑 current、scope 内 Blob 去重、标签目录和可恢复软删除运行；被引用 Asset 仍可经精确影响确认删除。
- 资源交接固定为 `AssetRef → ResolvedAsset → RenderResource`：authored 引用只选 logical current，Rendering 按实际消费位置线性化钉死 exact 内容，RenderEngine 只接收去除 Asset 业务身份的一对一请求级 manifest 与 Renderer-only fetch lease。
- 首个语义目标是每条Renderer Command把一个有限二维根画板原子生成一张完整PNG/JPEG；多Template、输入、变体、DPI或格式由Engine外编排为独立请求。主要作者是理解 StaticSchema、字段路径和表达式的技术型/低代码模板设计者。
- `D:\Yiwer\code\hbads-design-v2` 仅作证据与反例来源，不承诺旧 DSL、API、存储或行为兼容。
- 允许一次性 UI/逻辑原型与 JSON fixtures 作为决策证据；不得进入产品路由、API、数据库或生产模块。
- 处理 grilling 票据时始终使用 `grilling` 与 `domain-modeling`；research/prototype 票据分别使用对应技能。

## Decisions so far

<!-- 仅在子票据解决后追加一行上下文指针。 -->

- [从历史系统提取 DesignDSL 语义证据](issues/01-historical-design-dsl-evidence.md) — 历史系统证明精确 Schema 上下文、集中动态语义与静态 Scene seam可行，也暴露了属性权威分散及无通用datasource/图片Renderer；原取证commit的ArrayLoop未接线已被后续HEAD修正，但最新系统仍以Java预布局、可持久化旧MaterializedScene运行，不能作为新RenderDocument复用。
- [评估受控能力下的表达式语言基础](issues/02-expression-language-foundations.md) — CEL 是首个原型候选，但类型、missing/decimal、受控能力、预算与 Java/Web 权威边界必须由 RenderWeave 自定义；JSONata/JMESPath/ANTLR 保留为对照。
- [划定 Template、Render 与 Asset 的限界上下文](issues/03-bounded-contexts-and-language.md) — 三个上下文以 Template closure snapshot、`AssetRef → ResolvedAsset → RenderResource` 和 RenderDocument 单向交接；Evaluation 消除动态组合并产生Engine-facing RenderDSL，Engine 内 LaidOutScene 再产生图片与权威预览。
- [定义可变 Template 生命周期与永久 Schema 合同](issues/04-mutable-template-lifecycle.md) — Template 以永久 Schema、线性 revision、current-only Asset/Template 依赖和终态软删除运行；依赖错误可确认保存 INVALID，但 Render 必须重检一致 current 闭包。
- [定义 Asset Management 产品边界与生命周期](issues/05-asset-management-domain.md) — Asset 以 scope 内内容去重、不可变 contentVersion、逻辑 current 与 ACTIVE/DELETED 恢复运行；AssetRef 按执行出现位置独立解析，被引用删除经 proof/token 协调后使 Template 重检。
- [定义渲染输入与词法作用域模型](issues/06-render-input-and-scope.md) — 根 Evaluation 以单一 RootDocument 和可选根级 Custom overrides 形成 AdmittedRenderInput；显式 invocation/loop domain、typed ABSENT、隔离子调用与 exact-Schema context proof 构成封闭词法作用域。
- [选择 Value Source、Binding、Mapping 与 Expression 模型](issues/07-value-binding-expression-model.md) — ValueSource/Definition 与自有 Expression 1.0 形成严格类型、显式输入、惰性求值模型；Binding 以 node-local targetPropertyRef 覆盖既有静态叶子，全局只追加 BindingPolicyCatalog 决定可绑定目标且失败绝不回退 baseline。
- [冻结 DesignDSL envelope、身份与演进规则](issues/08-designdsl-envelope-and-evolution.md) — DesignDSL 以 exact Profile、唯一 DesignRoot、client-owned UUID v4、strict JSON 与自有 canonical/hash 成为语义无损作者事实；Template revision export、raw import、显式 migration 和永久版本兼容均失败封闭，外部依赖漂移不改变 contentHash。
- [定义封闭的节点、属性与 BindingPolicy 模型](issues/09-node-property-slot-model.md) — 唯一 NodeContractCatalog 冻结 Canvas 根、15 种首批视觉 Node、mm/pt 单位、Placement/容器/Text/Image/Vector/码制合同与 ContentModel；独立只追加 BindingPolicyCatalog 只授权永久 Property Identity 的已有叶子，不引入 Slot、属性包、隐式字体或 Binding fallback。
- [定义几何与布局容器语义](issues/10-geometry-and-layout.md) — `renderweave-layout/1.0` 冻结固定物理 Canvas 内的约束自适应 measure/arrange、box/transform/clip/paint、Stack fillWeight、Grid track、Text shaping/overflow 与 Image/Vector bounds；RenderDocument 保留静态布局规则，Engine 内 LaidOutScene 不持久化，任何失败零输出。
- [定义循环与条件结构语义](issues/11-repeat-and-conditional-structure.md) — authored Repeat/Conditional 以 typed items/condition、显式 absent policy、Loop lexical frame 与确定性剪枝运行；item subtree 可显式放置 TemplateUse，itemLayout/instanceLayout 与 PACK 完成两级排布，动态结构在 RenderDocument 前完全降低为普通静态 Frame/Stack/Grid。
- [定义嵌套 Template 组合语义](issues/12-nested-template-composition.md) — structural TemplateUse以same-scope logical current、exact ContextSelector和PUBLIC Custom fills建立隔离child invocation；完整closure先冻结，实际调用按结构惰性执行，child artboard最终降低为RenderDocument静态compositionViewport并以CONTAIN/CENTER/双层clip嵌入parent host。
- [定义 Asset 引用、选择与解析合同](issues/13-asset-reference-and-resolution.md) — AssetRef 是服务端 UUID v4 的封闭 logical-current selector；完整 closure 先检查全部 authored occurrence，实际 Node property 再按稳定顺序逐 occurrence 线性化选择并形成 `ResolvedAsset → RenderResource`，exact lease、descriptor、Engine fetch、cache、错误脱敏及 selection digest 均失败封闭且零 partial output。
- [定义 Evaluation Capabilities 与请求内一致性](issues/14-evaluation-capabilities.md) — exact Expression Profile只开放显式input中的UTC_DATE/UTC_TIME与位置派生`[0,1)`Random；完整准入后建立短期幂等CapabilityState，同请求恢复重放而新请求换state，逻辑demand预算、HMAC向量、result digest、脱敏与Engine零残留均失败封闭。
- [定义 Evaluation 编译流水线与 RenderDocument 合同](issues/15-evaluator-and-render-document.md) — 权威Evaluator按固定阶段与consumer order把closure/input/capability/Asset物化并原子seal为`renderweave-render/1.0` strict静态文档；opaque occurrenceId、请求sidecar、一对一manifest、跨语言canonical/digest、四类运行身份及Engine final-geometry边界均失败封闭，文档不可公开、持久化或跨请求复用。
- [定义 RenderEngine 与图片输出合同](issues/16-renderer-and-image-output.md) — 每条closed Command为根Canvas原子生成一张PNG/JPEG；独立Layout/Renderer/Output Profile冻结deadline/cancel/registry、surface像素、资源与字体、sRGB raster、QR/Barcode、byte-exact编码、trace/transport/error及READY证据，旧busbox只能作为重建基线而不兼容。

## Not yet specified

- Tickets17/18仍须以已冻结的单图片、权威preview、cancel与trace合同收口原型和编辑体验。
- Ticket19须填写全部已命名容量数值，并冻结跨语言、跨平台conformance规模与最终证据等级；未来若引入batch/variant，应作为Engine外独立编排领域另行设计。

## Out of scope

- 本地图不创建或修改 Template/Asset 产品代码、API、数据库表、路由、生产配置或实施 Phase DAG。
- 不修改 Schema v1、StaticSchema 不变量、Schema DSL、validator 或 compiled JSON Schema 语义。
- 不导入或兼容 `hbads-design-v2` 的历史 DesignDSL；只提取可验证的语义证据与失败教训。
- 不实现 CSV、Excel、数据库、HTTP 等具体 Connector，也不允许 DesignDSL 直接持有查询、凭证或任意外部访问能力。
- Workspace、实时协作及组织级权限模型不在本规格；Asset Management 所需的最小所有权边界由其领域票据决定。
- 不开放任意插件节点、运行时代码注册、HTML/CSS DOM 或通用脚本能力。
- HTML、PDF、视频、3D 等通用文档/媒体目标，以及生产 render farm、调度和部署，不在首版目的地内。
- Asset 的 SVG/动画/DOCUMENT、URL/ZIP 导入、文件夹/分享、内容变换、标准化 quarantine、硬清除/GC/保留期、scope 计费配额、全局去重与原子批处理留在 fog。
