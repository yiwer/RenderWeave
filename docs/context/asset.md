# 领域分片：Asset

> 由 `CONTEXT-MAP.md` 路由加载。触碰 Asset 聚合、接纳（图片/字体）、Blob、AssetRef 解析、
> fetch lease 或删除引用证明时读取本分片。

| 术语 | 精确定义 | 不代表什么 |
|---|---|---|
| Asset | Asset Management 中以服务端生成的全局唯一canonical UUID v4 `assetId`标识的稳定聚合；拥有不可变ownerScope/kind、可变metadata、current content与`ACTIVE/DELETED`可恢复生命周期。 | 不是内容版本、Blob、泛化`Resource`、外部数据源、任意URL或可跨scope移动的共享对象。 |
| Asset content revision | 以 `{assetId, contentVersion}` 精确标识、经某个 AssetAcceptanceProfile 接纳的不可变原始内容及技术描述；旧内容恢复会复用 Blob 并追加新版本。 | 不叫 `AssetVersion`，不是 metadata 历史、可改写文件、current 指针或独立用户资源。 |
| Asset current | Asset 唯一的当前 Asset content revision；每个实际消费位置在自己的线性化点独立读取。 | 不是closure admission冻结值、一次Evaluation内自动memoize的版本，也不是可由DesignDSL精确选择的历史版本。 |
| AssetAcceptanceProfile | Asset Management 拥有的版本化格式、特性和单内容上限合同；首个 exact ID 为 `renderweave-asset-acceptance/1.0`，只接纳静态 PNG/JPEG/WebP 与非集合、非 variable 的 TTF/OTF。 | 不是对在线 Renderer capabilities 的动态代理、任意文件白名单或内容转换配置。 |
| Asset acceptance kernel | `renderweave-asset` 内通过唯一 `AssetAcceptanceAuthority.admit(rawBytes, kind)` 对静态 PNG/JPEG/WebP 与单 face、non-variable、monochrome outline TTF/OTF 执行 strict magic/结构/CRC/完整解码/字体表解析与 64MiB/32MiB/2 万 px/1 亿像素预算，输出 TechnicalDescriptor/sha256/byteLength 的产品原子。 | 不是 Asset create 纵切、动画/变量/彩色字体、Asset 聚合、Blob/S3、Resolver/lease 或 acceptance Profile available 资格。 |
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
