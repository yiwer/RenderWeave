# 原型验证在线 Template 与 Asset 创作工作流

Type: prototype
Status: resolved
Claimed by: Codex /root
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

## Answer

### 1. 原型结论与适用范围

- Ticket 17 选择 `B · Canvas Focus` 作为 Template v1 在线创作工作台的信息架构基线。方案 A 与 C 只保留为本次决策的比较原型，不构成三套产品模式，也不要求后续实现长期维护三套页面。
- 方案 B 的首要对象是固定物理尺寸画布：画布持续占据视觉中心；结构、节点库、Asset、Definition 与导入导出共用可收起的左侧导航面板；属性使用可收起的右侧检视器；数据、权威预览、问题与保存由紧凑 dock 调用。选中节点可出现轻量情境工具条，但它只能触发同一 DesignDSL 编辑命令，不能形成第二套属性事实源。
- 顶栏持续显示 Template 身份、永久 exact StaticSchema 绑定、当前 readiness/revision 与保存状态。场景切换器仅是 throwaway 原型的受控 fixture 驱动器，用于验证 INVALID、Asset 漂移、Binding ABSENT/ERROR、保存冲突与预览失败；它不是正式作者工作台的永久业务导航。
- 画布只提供非权威的编辑反馈。`权威预览` 必须作为独立动作经过完整 Evaluator 与 RenderEngine，并与正式图片输出复用同一条 Profile 路径；浏览器草稿图、Asset placeholder 与 LayoutTrace 均不得被表现为最终 RenderOutput。
- Ticket 17 只冻结工作流、信息架构和交互投影，不新增 Template/Asset API、数据库、产品路由或运行时语义。后续产品视觉 token 可以演进，但不得破坏本票据冻结的权威边界与信息层级。

### 2. 主工作区与作者路径

- 左侧导航以 `结构 / 节点 / 资产 / 定义 / 交换` 五个中文入口组织作者任务。结构树、创建面板与右侧属性必须来自同一 NodeContractCatalog 投影；Asset、Definition 与交换面板仍各自遵守其领域合同，不能把引用、输入样例或导入来源混入 DesignDSL。
- 中央画布始终明确显示 authored mm 几何与非权威状态；Text 的字体大小以 pt 呈现，DPI 只在权威预览请求中出现。Stack/Grid 的比例与约束反馈属于固定 Canvas 内的响应式布局，不得使用网页 breakpoint 或 CSS responsive 语言解释。
- 右侧属性检视器是节点编辑的主要入口；绑定编辑器、资源选择器、TemplateUse、Repeat、问题定位和预览详情按需打开，不通过常驻的大型表单挤压画布。面板收起只改变作者视图，不改变任何 authored value 或 Binding。
- 底部 dock 至少能显式切换导航、属性、数据、问题，发起权威预览和保存。INVALID 可以继续编辑并经二次确认保存，但必须禁止权威预览/Render；修复后仍须经过服务端重检才能恢复可用状态。
- 小于桌面编辑阈值的窗口显示明确的“不支持当前宽度”状态，不把面板压缩成不可操作的移动端编辑器。v1 原型验证的是桌面在线设计工作台；这里的 viewport 适配不改变 DesignDSL 的物理布局语义。

### 3. 中文属性投影与类型化控件

- 右侧属性栏不直接展示 `widthMm`、`writingMode`、`fontRef` 等 wire/property ID。每个 exact Property Identity 由客户端展示元数据投影为专用中文名称；首版只有中文，不承诺多语言。该名称只用于呈现，不持久化进 DesignDSL、不参与 Binding 寻址、不能成为 UI-only semantic alias，也不能扩张 NodeContract。
- 属性固定按 `内容 / 文字 / 布局 / 外观 / 数据 / 行为 / 子模板 / 高级` 分组；只显示当前 NodeContract 实际拥有的组和属性。未知或未配置展示元数据的 property 必须进入受控的“扩展属性”诊断投影，不能退回把 wire ID 当普通作者标签。
- 控件按 property 类型与约束选择：短文本、长文本、带单位数值、开关、中文枚举、颜色色块/选择器、FONT/IMAGE Asset picker、ValueSource/Definition picker、Template picker 与只读结果。数值控件必须显示 `mm` 或 `pt` 等真实单位以及合法步进/范围；枚举显示中文标签但提交 exact wire value。
- 每行使用稳定的三段结构：左侧中文属性名，中间自适应的属性控件，右侧固定宽度的 Binding 动作。长说明、校验问题与已绑定 source 摘要放在该行下方，不把操作按钮挤到下一行，也不让同组属性因控件类型不同而失去纵向对齐。
- static baseline 控件始终存在且可编辑；Binding 只决定求值时是否覆盖 baseline。界面不提供“属性类型=静态/绑定”的额外模式字段，也不把已绑定状态解释为删除或替换 authored baseline。

### 4. Binding 投影与反馈

- 是否显示右侧 Binding 动作，唯一由全局追加式 BindingPolicyCatalog 针对 exact Node kind、Property Identity 与 source/target type 决定。中文展示元数据不能授予 bindability，Template 也不能局部开启 Binding。
- 未绑定使用描边按钮 `绑定`；有效 Binding 使用高对比填充按钮 `已绑定` 和反色文字；Binding 存在但求值为 ABSENT/ERROR 时使用填充错误态 `异常`。已绑定时才在行下显示脱敏 source 摘要与“覆盖基础值”说明；异常时明确告知权威预览失败，绝不暗示回退 static baseline。
- 点击同一行按钮打开既有 Binding 编辑器。普通属性列表只使用中文展示名；编辑器内部必须显示 exact node-local `targetPropertyRef`、target type 与允许的 selector 形状，因为它们是作者修复嵌套 member/fixed-index Binding 所需的精确语义，而不是属性栏标签。
- Binding 模式只筛选全局策略允许绑定的当前属性，并继续使用相同中文分组、控件行与状态反馈。不能维护独立的可绑定属性表，也不能引入 Slot、nodeId 连线或隐藏的 fallback 值。

### 5. 原型证据与后继票据

- 三种比较方案与共享属性检视器已落在 commit `27e7420aea231ee57a1fe78db98a022de2f43048`；方案 B 覆盖桌面主工作区、面板显隐、场景 fixture、保存/预览入口和本票据所述属性/Binding 反馈。
- Web gate 已通过：14 个测试文件、76 个测试与 production build；A/B/C、1024px 桌面宽度及 320/375/414/768px unsupported-width 状态完成浏览器检查，检查时无浏览器 console error 与 axe violation。A1 本机证据目录为 `.sdlc/evidence/20260814-180312-web`；临时截图与检查脚本不是规格事实源，不进入交接提交。
- 下一票据为 [定义编辑器预览、保存冲突与恢复体验](18-editor-preview-and-recovery.md)。Ticket 18 必须复用方案 B 和本票据的属性投影，不重复打开已冻结的信息架构选择，并继续收口权威预览、cancel、失败撤图、canonical save 重同步与冲突恢复。
