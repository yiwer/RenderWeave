# 定义 Asset 引用、选择与解析合同

Type: grilling
Status: open
Blocked by: 05, 07, 08, 09

## Question

DesignDSL 如何表达精确 Asset 引用与逻辑 current/selector；AssetResolver 如何执行授权、类型匹配、元数据读取、缓存、超时、不可用/删除处理和请求内一致性；ResolvedAsset 如何形成 RenderDocument 的受信资源清单，并与 RenderEngine 的受限 fetch/hash/media/length 校验衔接？

## Inherited constraints

- 只有 ACTIVE Template current 的 AssetRef 进入 dependency projection 和反向索引；current 改变时整体替换，历史不参与。
- Asset 引用事实变化先使相关 Template STALE，再重检为 READY/INVALID；保存可对结构合法但 AssetRef 无效的问题二次确认并提交 INVALID。
- TemplateReadiness/当前报告不是 Render 授权事实；每次 Render 必须对最新 current 的 AssetRef 做同请求权威解析。
- 用户特定授权失败是请求级问题，不得把 Template 全局标成 INVALID。
- AssetRef 已冻结为只含 `{assetId}` 的逻辑 current 选择器，预期 IMAGE/FONT 由所在 DesignDSL 属性给出；不能 authored exact contentVersion、hash 或 URL。
- 每个实际执行到的 AssetRef 出现位置必须独立读取当时 current，不按 assetId 做请求内 memoization；同一 Evaluation 允许同一 Asset 混合多个 contentVersion，但每个 ResolvedAsset 自身精确不可变，后续解析失败必须使整个 Evaluation 零输出失败。
- ResolvedAsset 必须钉死 assetId/contentVersion/kind/hash/mediaType/byteLength 与短期 Renderer-only 受信 URL；调用者获准 Render Template 后使用同 scope 内部 Resolver，不逐个检查 asset.read。
- Asset 只接纳静态 PNG/JPEG/WebP 与 TTF/OTF；current content、删除、恢复触发 STALE，metadata 与相同内容 no-op 不触发。
- imageRef/fontRef 是不同 ValueType，但 wire value 都是封闭 `{assetId}`；预期 Asset kind 来自全局 Node property targetType 或 Definition 声明，禁止字符串/URL/hash/contentVersion 充当 asset value。
- 所有 authored imageRef/fontRef literal 即使位于被 node-local Binding 覆盖的 static baseline、未选择 Mapping/Expression branch 或未使用 Definition 中，也属于 Template current 的依赖事实并进入 current-only projection/readiness。
- RenderInput PUBLIC Custom override 产生的 imageRef/fontRef 在 admission 时检查同 ownerScope、ACTIVE、kind 与 caller asset.read；跨 scope/不可见返回 NOT_FOUND。已准入的 child fill 不重复要求 render caller asset.read。
- runtime override/Computed Definition 透传的 AssetRef 不写入 Template dependency projection；只有实际 materialize 的消费位置调用 AssetResolver，Definition memoization 不得把多个消费位置合并成一次 Asset current resolution。
- Binding 失败、动态 asset admission 失败或单个消费位置 resolution 失败都中止整个 Evaluation，不能回退该属性 authored static AssetRef。
- authored `{assetId}` logical selector 是 Canonical DesignDSL/contentHash 的一部分；Asset current contentVersion、metadata、ResolvedAsset 与用户授权不进入 Template revision hash，变化只走 STALE/recheck 或 request-level resolution。
- Template revision export 中的 identity/StaticSchemaRef/contentHash 不构成 Asset authorization 或 provenance；导入后仍只能按目标 ownerScope 与服务端事实解析，文件不能授予 asset.read。
