# 原型验证在线 Template 与 Asset 创作工作流

Type: prototype
Status: open
Blocked by: 05, 07, 09, 10, 11, 12, 13, 14, 15, 16

## Question

一个不进入产品代码的 throwaway 原型应如何呈现 Template/Asset 管理、画板、节点树、属性与 Binding、布局、循环、嵌套、权威预览和错误，使技术型/低代码作者能够验证核心工作流与信息架构？

## Inherited constraints

- Asset 目录默认 ACTIVE，支持 kind、tagsAll/tagsAny、名称搜索、稳定游标和 DELETED 视图；首版无文件夹、Tag 聚合、分享或内容变换。
- Asset picker 只显示 ACTIVE，但编辑器必须能展示导入/历史内容中的 missing、DELETED 与 kind mismatch AssetRef，并允许沿用二阶段确认保存 INVALID。
- 删除流程必须展示完整影响数量、可见 Template 明细与 redactedCount，并能表达确认 token 因引用漂移失效；恢复后相关 Template 会重新检查。
- 多文件上传是独立逐文件结果，不是原子批次；内容历史可查看、精确下载并通过追加新 contentVersion 恢复。
- 原型必须把 RootDocument、根 customValues 与 DesignDSL 明确分开；输入样例只属于本地 EditorSession，不能表现为 Template revision 内容。
- definitions UI 使用 CustomDefinition/Computed Definition 语言，展示稳定 definitionId、PUBLIC/PRIVATE、默认值与显式 invocation/loopId domain，不能称为通用 DataSource 或暗示 `$parent/$root` 动态作用域。
- Binding/Repeat/TemplateUse 检视器必须可见 exact Schema context、typed ABSENT、Loop frame 的零基 index 与显式 child fill；子调用不能暗示自动继承父数据或同名参数。
- 外部 unknown/PRIVATE custom override 的静默忽略与 authored child fill 失效导致父 INVALID 必须在原型中呈现为不同场景。
- Binding UI 必须以当前 Node kind 与全局追加式 BindingPolicyCatalog 生成可绑定 property picker；Template 不能自行开启 bindability，Catalog 未列出的属性不出现 Binding 操作。
- Binding 编辑器必须呈现 node-local `targetPropertyRef`，覆盖 property/member/fixed index 及最多一次 member+index 的组合；host node 隐式，不显示 nodeId/slotId 连线模型。
- 每个 bindable property 始终保留可编辑 static baseline；UI 要区分“没有 Binding，使用 baseline”“Binding 成功覆盖”“Binding 存在但 ABSENT/ERROR，权威预览失败”，不能暗示 runtime fallback。
- 数组重排若作者意图保持原 item，原型必须演示同步重写 numeric target index；越界、duplicate target 与 ancestor/descendant overlap 应定位为不可保存 hard error。
- definitions UI 必须分别支持 Custom、ordered Mapping 与显式-input Expression source；复杂未支持 expression syntax 直接报 profile error，不展示“已解析但稍后才实现”的功能。
- 浏览器 lint/画布只是非权威反馈；保存/预览问题必须能展示服务端 stable code、JSON Pointer、definitionId/bindingId 与 UTF-16 source span，同时不回显实际输入值。
- 原型创建 Node/Definition/Binding/Repeat/TemplateUse 时由前端生成相应 canonical lowercase UUID v4；服务端不会补 nodeId/definitionId/bindingId/loopId/useId。复制 subtree/definition 必须演示成组 remap IDs/refs，whole Template copy/restore 则保留 local IDs。
- Import 必须覆盖 bare DesignDSL 与 exact revision export：strict/unsupported version 进入 raw repair，结构可识别的无效内容进入 best-effort canvas，identity/Schema 只展示来源且不能静默覆盖目标 Template。
- Save 后 UI 必须以服务端 canonical DesignDSL/revision/contentHash 重新同步，能观察 metadata trim、set-like definitions/bindings/inputs 排序和等价 decimal token canonicalization，而不丢 exact Expression source。
- 原型必须提供 exact dslVersion/expressionProfile 不受支持时的只读/export/migration 状态，禁止旧客户端对 partial model 保存；显式 migration 先预览 canonical output/changes/problems，再走普通 save。
- Export UI 区分 bare DesignDSL 与 exact Template revision envelope，并明确 contentHash 不是签名、文件 identity 不能授予 Schema/Template/Asset 权限。
- Node tree、属性面板与可绑定 target picker 必须全部从同一 NodeContractCatalog + BindingPolicyCatalog 投影；原型不能引入 Slot/SlotRegistry、通用 properties bag、Template-local bindability、zIndex 或 UI-only property alias。
- 新 Node 由前端生成 UUID v4，并按父 ContentModel 写入正确 ABSOLUTE/STACK/GRID placement 与合法 static baseline；reparent 必须显式转换 placement。省略 default 的 property 若要 Binding，UI 要先 materialize exact baseline，不能让 Binding 创建 member。
- 纯文本创建为一个完整 Run；编辑器字体 preset 必须在创建时写入 explicit fontRef。原型应覆盖 horizontal/vertical Text、双轴 distributed alignment、padding/stroke/shrink-to-fit，以及无有效 FONT Asset 时 INVALID/不能权威预览的体验。
- 原型必须把固定物理 Canvas 内的“约束自适应布局”与网页 responsive 清楚区分：演示 Stack main-axis `fillWeight` 比例/min-max water filling、Grid FRACTION/AUTO、HUG/FILL cycle 和 signed margin；不提供 breakpoint、viewport、percent、CSS flex/grid alias 或 zIndex。
- 浏览器草稿画布不得持久化派生 x/y/LayoutBox，也不能冒充 `renderweave-layout/1.0` 权威结果；应显式展示 mm geometry、pt typography 与 request DPI，并在权威预览返回时可用有界 LayoutTrace/稳定问题定位 occurrence、overflow、clip 与 paint order。
- 原型必须呈现 `render:false` occurrence 不占布局/资源、`visible:false` 与 `opacity:0` 仍占布局且资源错误仍失败，以及 `VISIBLE + maxLines`、非法 fillWeight/Grid/HUG cycle 是不可确认的 hard error，而非浏览器自动修复。
- Repeat inspector 必须把 `items` 的静态 `list<T>`/exact reference-array 类型证明、原输入零基 `loopIndex`、item subtree、`itemLayout` 与 `instanceLayout` 分开展示；不得使用已弃用的 `templateLayout` 名称，也不得把 packing 面板伪装成 Template picker。
- 循环子 Template 选择必须通过 item subtree 中显式 TemplateUse 完成，允许同一 item 放置多个不同 TemplateUse；Schema 只用于验证 context selector，UI 不得提供 Schema→Template 自动匹配、Repeat-level templateId 或 runtime shape inference。
- 原型必须演示 PACK direct-child placement、item 被结构剪枝后 instance packing 紧凑但 loopIndex 保持原输入 index、两级 STACK/GRID 的顺序，以及零 surviving item 使整个 Repeat移除。首版不提供 filter/sort/key/pagination/masonry/per-item packing。
- 复制 Repeat subtree 时必须成组生成 nodeId/bindingId/loopId并原子重写内部 domain refs；move/reparent 导致 loopId 词法不可达或 PACK/父 ContentModel 不兼容时，客户端必须要求显式修复，不能让服务端猜测 scope或placement。
- TemplateUse inspector必须把三个动作分开：从same-ownerScope目录选择logical child Template current；选择exact StaticSchema ContextSelector及ERROR/SKIP；按child PUBLIC definitionId编辑typed fills。不得称为自动继承“自定义数据源”，也不得提供exact revision/latest、跨scope、fallback、Slot或动态templateId。
- 系统Schema picker必须把`system-empty@v1`与五种含`index/value`的`system-basic-*@v1`作为真实一等StaticSchema展示。scalar Repeat中选择匹配system-basic child时可预览整个item context，清楚显示`/index`等于loopIndex；reference业务Schema绝不显示伪注入index。
- TemplateUse Node没有children/appearance/fit面板，只显示common与父ContentModel决定的ABSOLUTE/STACK/GRID/PACK placement。画布反馈应演示HUG使用child trim、FIXED/FILL使用CONTAIN/CENTER、透明letterbox、source/host clip、child bleed忽略且host尺寸不使child reflow。
- render:false/SKIP必须在草稿树中显示为无实际invocation，但完整authored closure的missing/INVALID/cycle/Profile问题仍阻止权威预览；visible:false/opacity:0仍显示child fill/Asset/capability错误。UI不能把“未调用”误称为“未依赖”。
- copy/move TemplateUse必须重建nodeId/useId/bindingId、验证context/fill lexical refs并显式转换placement；target templateId与child targetDefinitionId保持。whole Template copy仅同ownerScope且保留local IDs，v1不提供cross-scope deep copy。
- 权威LayoutTrace应经服务端请求级sidecar把opaque occurrenceId投影为获准的root/use/Repeat递归OccurrencePath与`template-use-viewport`/`canvas-background` role；浏览器不得接收完整sidecar、持久化compositionViewport或把它冒充DesignDSL Node。旧客户端不理解templateUse exact wire时必须只读/export/migration。
- Asset创建的`assetId`由服务端生成canonical lowercase UUID v4，必须与前端生成Node/Definition/Binding/Repeat/TemplateUse本地identity的职责明确区分。Asset picker、metadata、版本历史和草稿缩略图只走要求`asset.read`的Asset产品接口，不得暴露Renderer fetch URL/lease。
- Asset依赖面板必须按canonical authored occurrence展示Node baseline、Custom default、Definition/Mapping及`list<imageRef/fontRef>`元素；即使Binding覆盖、branch未选择或结构不可达也属于保存/重检事实。重复assetId不能折叠掉定位，UI可聚合显示但必须保留每个pointer和expected kind。
- 根PUBLIC custom override中的每个Asset atom必须先显示same-scope/ACTIVE/kind/`asset.read`准入结果；已准入值经Definition/fill传递不代表固定contentVersion。实际权威预览中，每个materialized Node property消费仍独立选择current；原型可用两个消费位置之间replace的受控场景演示同一assetId观察到不同内容，但不得向UI披露contentVersion/hash/lease。
- 权威预览只能展示完整Evaluator+RenderEngine结果；浏览器不能提交、下载或持久化Renderer-only RenderDocument。草稿可经独立Asset preview接口显示资源或诊断placeholder，但placeholder不能进入DesignDSL/RenderDocument/输出，也不能把失败资源静默替换为旧图或默认字体。Engine问题经请求级sidecar把resourceId回接安全OccurrencePath/ConsumerPropertyRef，不向UI泄漏URL/token/hash/version/raw network error。
- Expression input source picker必须按exact Profile只展示`UTC_DATE`、`UTC_TIME`与`UNIFORM_DECIMAL_0_1`三个有类型Capability操作；不能称为系统数据源、提供自由args/timezone/seed/version，或在Binding/Mapping/Custom/fill/Node属性中直接出现。
- 原型必须解释declaration-frame语义：invocation-domain Definition跨多个loop consumer共享memoized Random，loop-domain逐原输入index独立；duplicate item独立、reorder改变随机结果与item的对应。UI不展示nonce、CapabilityCallPosition canonical bytes或实际result digest。
- 每次新的权威预览都建立新CapabilityState并可能改变Clock/Random结果，不提供EditorSession pin或生产seed/time覆盖。浏览器草稿模拟必须显著标为非权威；失败时撤下旧权威结果，不能用模拟值或旧preview伪装成功。
- Capability问题只在调用者具有相应Template read时显示definitionId/input alias与逐段授权的InvocationPath；始终隐藏结果、snapshot、nonce、fingerprint、child私有segment和provider错误。Random旁必须明确标记不可用于UUID、token或其他安全用途。
- 权威预览面板只提交公共`PNG | JPEG`、正整数DPI及JPEG quality；省略值应明确显示为Engine有效值96/90。一次preview operation只展示根Canvas的一张完整图片，不能提供node export、crop、target size、background override、child viewport output或batch选项。
- 预览成功必须先核验完整length/digest/result再替换旧结果；网络截断、cancel、deadline、trace超限及任一Engine问题都撤下旧权威图片，不显示partial bytes、warning image或旧结果。Engine `requestId`、Command/document digest、Profile内部选择和exact replay control不得进入普通作者UI。
- 获准LayoutTrace时，原型只展示服务端经sidecar投影后的occurrence box/clip/paint/overflow；trace是成功结果的有界诊断附件，不是浏览器Scene、下载Artifact或失败时仍可查看的partial layout。相同有效Profile/DPI/quality的正式输出与权威预览不得使用不同渲染路径。
