# 定义 Asset 引用、选择与解析合同

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 05, 07, 08, 09

## Question

DesignDSL 如何表达 Asset 的逻辑 current 引用；AssetResolver 如何执行准入、类型匹配、线性化选择、租约、缓存、超时与不可用/删除处理；Rendering 如何把内部 ResolvedAsset 一对一投影为 RenderDocument 的 RenderResource manifest，并与 RenderEngine 的受限 fetch/hash/media/length/descriptor 校验衔接？

## Inherited constraints

- 只有 ACTIVE Template current 的 AssetRef 进入 dependency projection 和反向索引；current 改变时整体替换，历史不参与。
- Asset 引用事实变化先使相关 Template STALE，再重检为 READY/INVALID；保存可对结构合法但 AssetRef 无效的问题二次确认并提交 INVALID。
- TemplateReadiness/当前报告不是 Render 授权事实；每次 Render 必须对最新 current 的 AssetRef 做同请求权威解析。
- 用户特定授权失败是请求级问题，不得把 Template 全局标成 INVALID。
- AssetRef 已冻结为只含 `{assetId}` 的逻辑 current 选择器，预期 IMAGE/FONT 由所在 DesignDSL 属性给出；不能 authored exact contentVersion、hash 或 URL。
- 每个实际执行到的 AssetRef 出现位置必须独立读取当时 current，不按 assetId 做请求内 memoization；同一 Evaluation 允许同一 Asset 混合多个 contentVersion，但每个 ResolvedAsset 自身精确不可变，后续解析失败必须使整个 Evaluation 零输出失败。
- ResolvedAsset 必须钉死assetId/contentVersion/kind/hash/mediaType/byteLength、technical descriptor与短期Renderer-only fetch lease；随后一对一投影为去除Asset业务身份的RenderResource。调用者获准Render Template后使用同scope内部Resolver，不逐个检查asset.read。
- Asset 只接纳静态 PNG/JPEG/WebP 与 TTF/OTF；current content、删除、恢复触发 STALE，metadata 与相同内容 no-op 不触发。
- imageRef/fontRef 是不同 ValueType，但 wire value 都是封闭 `{assetId}`；预期 Asset kind 来自全局 Node property targetType 或 Definition 声明，禁止字符串/URL/hash/contentVersion 充当 asset value。
- 所有 authored imageRef/fontRef literal 即使位于被 node-local Binding 覆盖的 static baseline、未选择 Mapping/Expression branch 或未使用 Definition 中，也属于 Template current 的依赖事实并进入 current-only projection/readiness。
- RenderInput PUBLIC Custom override 产生的 imageRef/fontRef 在 admission 时检查同 ownerScope、ACTIVE、kind 与 caller asset.read；跨 scope/不可见返回 NOT_FOUND。已准入的 child fill 不重复要求 render caller asset.read。
- runtime override/Computed Definition 透传的 AssetRef 不写入 Template dependency projection；只有实际 materialize 的消费位置调用 AssetResolver，Definition memoization 不得把多个消费位置合并成一次 Asset current resolution。
- Binding 失败、动态 asset admission 失败或单个消费位置 resolution 失败都中止整个 Evaluation，不能回退该属性 authored static AssetRef。
- authored `{assetId}` logical selector 是 Canonical DesignDSL/contentHash 的一部分；Asset current contentVersion、metadata、ResolvedAsset 与用户授权不进入 Template revision hash，变化只走 STALE/recheck 或 request-level resolution。
- Template revision export 中的 identity/StaticSchemaRef/contentHash 不构成 Asset authorization 或 provenance；导入后仍只能按目标 ownerScope 与服务端事实解析，文件不能授予 asset.read。
- 票据 09 已冻结所有 FONT/IMAGE authored occurrence 都使用原子 ValueType `fontRef/imageRef` 与闭合 `{assetId}` wire；Run fontRef 必填且 Renderer 无隐式默认字体、环境 fallback 或 family substitution，Image 也不接受 URL/base64/hash/contentVersion。
- NodeContract 只验证 AssetRef shape/identifier；Asset existence/lifecycle/kind 是 dependency/readiness。静态 authored occurrence（包括被 Binding 覆盖的 baseline 与尚未 demand 的 Definition literal/default）进入 current-only reverse projection；runtime input/system/expression 动态产生的 AssetRef 不预索引，只在实际消费解析失败时使该次 Render 零输出，不能据此自动把 Template 标记 INVALID。
- Image 单轴 HUG 需要 ResolvedAsset 提供可信 pixel dimensions/aspect ratio，但不得把资源 DPI 注入 authored physical size；图片技术 metadata 与精确验证边界由本票据冻结。
- `render:false` subtree 在实际 dynamic Asset resolution 前剪枝；`visible:false` 与 `opacity:0` 的实际 FONT/IMAGE occurrence 仍必须解析、验证、解码到足以完成 measure 的信息，任一失败使本次请求零 RenderDocument/RenderOutput。实现不得用 paint optimization 改变该失败合同。
- Template与全部TemplateRef target拥有相同不可变ownerScope；调用者获准根Render后，内部Resolver可解析每个实际child AssetRef occurrence而不逐项要求child/asset.read。跨scope TemplateRef在保存/closure admission即非法，不能把AssetResolver当跨scope桥梁。
- 完整Template closure包含静态不可达child snapshot，但只有实际invoked TemplateUse descendant才运行dynamic Asset resolution。相同child snapshot被多个use/Repeat occurrence调用时，每个实际AssetRef出现位置仍独立读取Asset current，不共享ResolvedAsset选择。
- Resolved IMAGE 必须向 Layout Profile 提供 orientation 后非零 logical pixel dimensions，Resolved FONT 必须提供可重复的 OpenType design metrics/glyph 数据；Asset DPI、系统字体和平台 fallback 不得进入 physical layout。完整 bytes 的交接、去重和延迟 fetch 时点仍由本票据冻结，但必须在 Engine measure 前可用且绑定 exact contentVersion/hash。

## Answer

### 1. AssetRef 与 authored dependency occurrence

- `assetId`由Asset Management服务端生成canonical lowercase UUID v4；客户端只能接收并引用。`imageRef/fontRef`都是不可拆分的原子ValueType，exact wire只允许closed `{"assetId":"..."}`。missing、非v4、非canonical大小写、JSON null或unknown member是不可确认hard error；kind由声明ValueType唯一派生，DesignDSL禁止contentVersion、hash、URL、文件名、Profile或动态selector。
- Template保存/重检必须从权威解析后的DesignDSL AST递归提取每个authored AssetRef atom，包括Node static baseline、Custom default、Definition literal、Mapping case/default以及`list<imageRef/fontRef>`中的每个元素。Binding是否覆盖、definition/branch是否被运行时消费、结构是否静态不可达都不改变依赖事实。
- 每个atom形成独立dependency occurrence，至少保留`assetId/expectedKind/canonical JSON Pointer`以及可用的nodeId、definitionId、bindingId和结构定位；同一Asset重复出现不聚合丢失。ACTIVE current projection是由这些occurrence生成的事务性只读索引，current改变时整体替换，历史revision不参与。权威重检始终重新从DesignDSL提取，projection不能成为第二事实源。
- 删除影响proof可合并后端查询，但必须保留全部occurrence；fingerprint对稳定occurrence tuple排序后计算。projection与报告不得因运行时剪枝或后续resolved current而改写。

### 2. 完整 closure Asset admission

- 每次Render在一致Template closure snapshot冻结后，对每个unique TemplateSnapshot的全部authored AssetRef occurrence重新作actor-independent admission；shared child snapshot检查一次，不按TemplateUse/Repeat实际调用次数展开。检查父子same ownerScope、Asset存在、ACTIVE及immutable kind精确匹配，但不读取用户metadata、不钉死contentVersion、不建立Asset snapshot/锁/长期租约。
- 后端可以按`ownerScope/assetId/expectedKind`合并物理读取，但每个occurrence仍保留诊断身份。问题按root-first closure、authored edge及canonical pointer形成稳定有界顺序；不可向无child读取权限的调用者泄漏child内部Asset身份。
- admission通过后即释放读取边界。随后replace/delete/restore可发生，实际resolve occurrence各自在自己的线性化点观察当时current；TemplateReadiness或异步STALE投影不构成请求授权事实。

### 3. 外部 Asset 值准入与授权

- 根PUBLIC custom override中的每个`imageRef/fontRef` atom（包括list元素）即使最终未使用，也必须在AdmittedRenderInput阶段检查same ownerScope、存在、ACTIVE、kind与调用者`asset.read`。跨scope、missing或不可读统一表现为NOT_FOUND；有效值形成请求级、不可持久化的`AdmittedAssetValue`。
- AdmittedAssetValue可经Definition、Mapping、Binding及TemplateUse fill传递，不在每次传递或child invocation重复检查actor权限；但每个实际Node property消费仍独立解析Asset current。authored baseline/default在调用者获准Render根Template后直接使用内部same-scope Resolver，不逐项要求asset.read。
- 根Template render授权与外部override授权只在请求admission权威检查一次。权限随后变化不取消本次请求，内部Resolver和fetch lease不再读取actor权限；新请求必须重新检查。用户特定失败只影响该请求，不改变TemplateReadiness。

### 4. Resolve occurrence 与稳定执行顺序

- 只有已经通过ValueSource/Definition/Binding求值及Node property validation、最终流入一个concrete materialized Node property的AssetRef才形成runtime resolve occurrence。未消费的Definition/default/Mapping branch不resolve；一个memoized definition值流入三个properties产生三个独立occurrence；Text每个Run的fontRef也分别解析。
- 每个occurrence由完整`OccurrencePath + consumerPropertyRef`定位。`consumerPropertyRef`复用TargetPropertyRef的closed `{rootPropertyId,selectors[]}`形状和最多一次INDEX/一次MEMBER约束，但不要求目标拥有BindingPolicy。例如Image为`{rootPropertyId:"imageRef",selectors:[]}`，首个Run字体为`{rootPropertyId:"runs",selectors:[{kind:"INDEX",index:0},{kind:"MEMBER",memberId:"fontRef"}]}`。
- Evaluator按materialized authored DFS、NodeContract property声明顺序、Text Run authored index顺序串行消费；AssetRef在concrete value与aggregate property校验成功后立即resolve。禁止speculative/unordered resolve，首个demanded失败后停止后续Resolver/capability/Asset工作；RenderResource manifest保持同一encounter order。
- `render:false`、false Conditional、零项/零survivor Repeat及TemplateUse SKIP在resolve前剪枝；`visible:false`与`opacity:0`仍完整resolve、fetch、decode、measure并保留全部失败。

### 5. AssetResolver、线性化与幂等

- AssetResolver是Asset Management向Rendering提供的窄内部接口，不是Expression capability或通用下载器。输入只含`renderRequestId/ownerScope/resourceId/assetId/expectedKind/rendererAudience/renderDeadline`；OccurrencePath、consumerPropertyRef、Template/DesignDSL、actor权限、运行数据和表达式结果不跨入Asset Management。
- 单次resolve在一个线性化事务中检查ownerScope、存在、ACTIVE、immutable kind，读取current contentVersion及不可变content descriptor，并提交`(renderRequestId,resourceId)`短期幂等记录。记录绑定完整请求fingerprint、exact selection、lease identity/claims、issuedAt与expiresAt；只有记录提交后才算选择成功。
- 同key同fingerprint的响应丢失重试只能从记录重建同一contentVersion与同一lease；同key不同输入冲突。事务冲突、连接中断或UNAVAILABLE只可在同key、fingerprint与总deadline内有界重试；提交状态不明必须先查询记录。记录提交前的重试可观察新current，提交后绝不能重读current或把timeout变成重新选择。
- 幂等记录加密、自动过期，至少保留到lease expiry加重试余量；它不是Asset审计、Evaluation历史或长期transcript，不出现在普通查询、日志或备份导出。请求取消不撤销已签发lease，到期后记录与lease共同失效。

### 6. Fetch lease

- 成功选择签发短期Renderer-only HTTPS fetch lease，claims精确绑定renderer audience、renderRequestId、resourceId、assetId、contentVersion、hash、length及expiry。`expiresAt`使用UTC RFC 3339，并必须覆盖render deadline与exact Profile固定安全余量；无法覆盖时resolve失败。
- 已签发lease授权读取exact immutable bytes，不在fetch时重新检查Asset ACTIVE/current或actor权限。后续replace/delete不撤销lease；在expiry前完成下载并校验的bytes不因随后过期而失效。expiry/deadline后不续签、不重新resolve logical current，整次Render失败。
- fetch URL是bearer secret，只存在于请求内交接；不得返回浏览器/外部调用者、写入诊断/日志/trace/cache key或进入Evaluation identity。

### 7. ResolvedAsset、resourceId 与 RenderResource

- 正式三层语言为`AssetRef → ResolvedAsset → RenderResource`，不另造ResolvedAssetOccurrence同义词。ResolvedAsset是Rendering内部closed value，精确包含`resourceId/assetId/contentVersion/kind/sha256/mediaType/byteLength/acceptanceProfileId/technicalDescriptor/lease/occurrencePath/consumerPropertyRef`；后两个locator由Rendering在Resolver响应后附加。
- 每个resolve occurrence一一对应一个ResolvedAsset及一个RenderResource，即使多个选择得到相同assetId/contentVersion/hash也不合并manifest entry。`resourceId = "rwres_" + 64位小写SHA-256 hex`，digest输入为带版本domain separator的canonical `OccurrencePath + consumerPropertyRef + expectedKind`；同一manifest内碰撞或重复是hard error。
- resourceId只在单个RenderDocument内要求唯一。同一canonical locator跨请求可产生相同resourceId；它不是secret、授权、Asset/global identity、cache key或跨请求幂等键，lease及幂等记录另外绑定renderRequestId。
- RenderResource从ResolvedAsset一对一投影，仅向Engine交付closed `resourceId/kind/fetchUrl/expiresAt/sha256/mediaType/byteLength/acceptanceProfileId/technicalDescriptor`，不携带assetId、contentVersion、ownerScope、Template identity或业务输入。Node property只引用resourceId，声明资源集合必须与实际引用集合精确相等；manifest按encounter order，unknown/null/未引用/缺失/重复resource均失败。
- `sha256`是64位lowercase hex；`acceptanceProfileId`精确为`renderweave-asset-acceptance/1.0`；mediaType只允许`image/png | image/jpeg | image/webp | font/ttf | font/otf`并与kind匹配。

### 8. TechnicalDescriptor 与字节权威

- IMAGE descriptor精确为closed `encodedWidthPx/encodedHeightPx/orientation/logicalWidthPx/logicalHeightPx/frameCount/colorEncoding`，尺寸都是正整数且`frameCount`恒为1，`colorEncoding`恒为`SRGB_8BIT`。orientation是EXIF八值语义枚举：`IDENTITY | MIRROR_HORIZONTAL | ROTATE_180 | MIRROR_VERTICAL | TRANSPOSE | ROTATE_90_CW | TRANSVERSE | ROTATE_270_CW`；logical尺寸是应用orientation后的结果。DPI、任意metadata bag、色彩profile原文与文件名不进入descriptor。
- FONT descriptor精确为closed `faceIndex/flavor/unitsPerEm`，`faceIndex`恒为0，`flavor`为`TRUETYPE_GLYF | CFF`，unitsPerEm为合法正整数。字体展示名、family、许可证和系统font identity不进入。
- Asset接纳时保存的descriptor是必须成立的不可变证明，exact bytes是最终渲染事实。Engine在measure/shaping前取得并验证hash的完整bytes，重新解析media/magic/首批PNG/JPEG/WebP特性、唯一orientation、logical dimensions、`SRGB_8BIT`颜色合同或single-face/non-variable/monochrome OpenType结构，并与descriptor精确比较；不一致是内部integrity/Profile错误，零输出且不得静默选一方。图片先应用orientation与确定性sRGB转换为straight RGBA8，再由Renderer Profile执行premultiplication；字体只允许TrueType `glyf`/CFF轮廓及获准layout tables，完整子集以票据05/16为准。
- image layout只用orientation后logical pixel aspect ratio，不读Asset DPI。字体layout/shaping只用exact bytes与exact Renderer/Layout Profile；系统字体、环境family、implicit/built-in fallback不存在。缺glyph依赖实际文本，是runtime失败，不自动使Asset或Template INVALID。
- `displayName/tags/sourceFileName`等用户metadata只由具有asset.read的Asset产品接口提供；Resolver、ResolvedAsset、RenderResource、selection digest及Engine不得读取或携带。metadata更新不改变resolve结果、digest、readiness或cache key。

### 9. Engine fetch、重试与 cache

- Engine只允许canonical HTTPS exact-origin+path-prefix allowlist，按segment boundary匹配并拒绝userinfo、fragment、非canonical host/port、dot-segment和percent绕过；生产空allowlist fail closed。每次connect重新执行DNS/egress policy，禁止redirect、proxy environment、cookie、调用方header、range、transparent compression及URL fallback；只接受`200`、identity body与唯一匹配声明的`Content-Length`，再流式验证总预算、actual length、SHA-256、kind/mediaType/magic及完整decode/parse。
- 只有transport failure与5xx可在原URL、原expiry、原deadline内按Profile有界重试；4xx、lease expiry、hash/length/media/magic/decode/profile错误不重试。Engine绝不能向Resolver申请续签、重新读取Asset current或回退其他URL/字体/内容。
- current selection、Resolver result及logical assetId→current永不共享缓存。选择完成后，持有trusted ownerScope的组件可按`ownerScope + kind + sha256 + byteLength + mediaType`缓存verified raw bytes，decoded IMAGE/FONT cache还必须加入exact Renderer Profile；禁止跨scope共享。没有可信scope partition的Engine只能作本次Render内缓存。
- cache hit不能减少resolve occurrence、预算计数或first-error顺序，并仍须验证本occurrence lease未过期。resourceId、去除签名query的URL或assetId都不是内容cache key；cache corruption立即驱逐并使请求失败，不回退旧内容或重新resolve current。

### 10. 完成边界、失败与诊断

- Resolver选择、descriptor或lease签发失败时不形成完整RenderDocument。RenderDocument封存并交给Engine后，fetch/hash/decode/shaping失败时它可在请求内部短暂存在，但公共Render操作只返回失败，不返回或持久化partial document，也不产生任何RenderOutput。
- Template依赖问题至少使用`TEMPLATE_ASSET_NOT_FOUND/DELETED/KIND_MISMATCH`；Resolver至少使用`ASSET_RESOLVE_NOT_FOUND/DELETED/KIND_MISMATCH/UNAVAILABLE/TIMEOUT`；Engine至少使用`RESOURCE_LEASE_EXPIRED/FETCH_FAILED/LENGTH_MISMATCH/HASH_MISMATCH/MEDIA_MISMATCH/DECODE_FAILED`、`FONT_GLYPH_MISSING`与按需`FONT_DECORATION_METRICS_MISSING`。malformed wire、manifest引用不相等及duplicate/colliding resourceId是不可确认hard error。
- authored跨scope引用对外折叠为`TEMPLATE_ASSET_NOT_FOUND`，可经既有二阶段流程确认保存INVALID但不能Render。运行时failure不自动改TemplateReadiness；integrity/Profile异常产生脱敏运维信号，但不得自动delete/restore Asset、追加contentVersion或创建新生命周期状态。
- Engine错误只返回稳定code、resourceId及安全参数。Rendering使用请求内`resourceId → occurrencePath/consumerPropertyRef`映射补回定位并执行权限脱敏；Engine不接收assetId、Template identity或DesignDSL pointer。
- Template编辑/校验/报告在调用者拥有对应Template read时可显示authored assetId；RenderInput admission可回显调用者自己提交的assetId；runtime authored/child错误默认只显示resourceId与安全locator，只有调用者另有该Asset的asset.read时才附带assetId。任何层都禁止problem/log/trace输出URL/token、hash、contentVersion、blob locator、sourceFileName、bytes、完整metadata或原始网络错误。

### 11. 并发变化、编辑器与 preview

- 异步STALE、Asset replace/delete/restore事件不取消已经开始的Evaluation。已resolve occurrence保持exact；尚未resolve occurrence观察变化后的状态。最后一个资源成功resolve后再发生变化不影响本次输出；下一次Render重新closure admission。
- 权威preview始终走完整Evaluator与RenderEngine。非权威编辑器草稿preview使用独立、要求asset.read的Asset preview接口；浏览器永远拿不到Renderer-only fetch lease。missing/DELETED/kind-mismatch可显示诊断或UI placeholder，但placeholder不写入DesignDSL、RenderDocument或正式输出，也不能自动替换引用。
- 含fetch lease的RenderDocument是请求内、Renderer-only、不可持久化、不可返回调用者且不可跨请求复用的交接值。请求取消/deadline/expiry均零输出，不续签或返回文档。

### 12. Evaluation identity、capacity 与 Profile

- 成功选择按resource encounter order对每项的`resourceId/occurrencePath/consumerPropertyRef/assetId/contentVersion/kind/sha256/mediaType/byteLength/technicalDescriptor/acceptanceProfileId`作domain-separated `assetSelectionDigest`；票据15已将其与closure/input/contracts/capabilityResultDigest组合为成功`evaluationResultDigest`。fetch URL/token/expiry、cache hit和网络时序不进入digest；即使两个contentVersion指向相同bytes，selection identity也不同。
- 完整ResolvedAsset transcript只存在请求内。若后续持久化Evaluation元数据，只可保存selection digest、计数、稳定错误码和耗时；普通Asset审计不为每次resolve追加事件。
- Ticket19必须分别限制authored dependency occurrence、unique logical asset、actual resolve occurrence、RenderResource entry、unique exact content、按occurrence与unique content的声明字节、图片像素、字体字节/表解析、manifest/URL字节、fetch/retry/deadline。cache不能减少occurrence预算；可预判超限在RenderDocument前失败，Engine再对actual bytes/decode独立施加硬限制。
- 部署提供Render前，exact `renderweave-renderer/1.0`必须通过机器manifest与golden corpus证明是每个选择携带的AssetAcceptanceProfile兼容超集；颜色、orientation、decoder、OpenType及raster解释由Renderer Profile固定，shaping/layout由exact Layout Profile固定，升级不得静默改变旧Profile。
- 当前`haibo.render/1.0`六字段manifest缺少expiresAt、acceptance profile和technical descriptor，且现有Layout Profile缺少compositionViewport。票据15已冻结独立`renderweave-render-command/1.0`与`renderweave-render/1.0`；未来实现必须使Engine精确支持并认证该合同与Layout Profile，不得丢字段复用旧version、让Engine猜版本或创建本阶段placeholder wire/adapter/kind。

### 13. 验收锚点与明确排除

- Ticket19 conformance至少覆盖：两个occurrence间replace并混用contentVersion；Resolver响应丢失幂等重试；resolve后delete仍可fetch且后续occurrence失败零输出；render/visible/opacity惰性；dynamic override授权及跨child fill；独立resourceId与exact-byte cache共享；manifest引用集合相等/collision；expiry/redirect/hash/length/media/magic/decode；EXIF orientation；无字体fallback/缺glyph；Repeat/TemplateUse定位、跨scope脱敏及全部容量上限。
- v1明确排除authored exact/latest/dynamic content selector、URL/base64/data URI、public/cross-scope AssetRef、任意HTTP/header、current cache、redirect、原URL fallback、系统/内置字体fallback、自动资源替换/转码/恢复、lease续签、失败资源跳过及partial output。SVG、动画、DOCUMENT及其他媒体继续留在fog。
- 本票据只冻结探索规格与下游约束，不创建Asset/Template/Resolver/RenderDocument/Engine产品代码、API、表、路由、placeholder或正式实现Phase。
