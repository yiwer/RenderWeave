# 领域分片：Rendering（Evaluation、RenderDSL、Renderer、布局与预览）

> 由 `CONTEXT-MAP.md` 路由加载。触碰 RenderInput 准入、词法 frame、Evaluation/Capability、
> RenderDocument、Renderer Command、布局几何或 Authoritative Preview 时读取本分片。作者合同的
> 完整词法 domain 见 `template-editor.md`。

| 术语 | 精确定义 | 不代表什么 |
|---|---|---|
| OccurrencePath | 一次Evaluation内按root/TemplateUse invocation、Repeat的loopId与原输入index、合成role及source node组成的请求级出现位置，只保留在Rendering诊断边界。 | 不是authored identity、业务key、revision事实、item/context内容、跨请求稳定地址或Engine输入，也不写入DesignDSL/RenderDocument。 |
| Render occurrence ID | RenderDocument seal时按最终静态树先序分配、以`rwocc_`加16位小写十六进制序号编码的请求内opaque occurrenceId。 | 不是OccurrencePath、nodeId、业务身份、跨请求地址、随机UUID或Template信息的hash。 |
| Connector | 在 Template 边界之外获取 CSV、Excel、数据库或 HTTP 等外部数据并归一化为渲染输入的集成组件。 | 不叫 `DataSource`，不是 DesignDSL 节点，也不能把凭证、SQL、文件或网络能力带入表达式求值。 |
| RenderInput | 一次根 Evaluation 接收的封闭 strict-JSON envelope，由必填单一 `rootDocument` 与可省略的根级 `customValues[]` 组成；Schema 目标只来自 TemplateSnapshot。 | 不叫 `DataSource`，不是 Connector、ValueSource、多个命名数据根或可由调用方选择 Schema 的载体，也不保存在 DesignDSL/Template revision 中。 |
| AdmittedRenderInput | RenderInput 经 envelope 检查、精确 StaticSchema 权威验证和 Custom override 消解后形成的请求级不可变语义值；包含 closed typed context、PRESENT/ABSENT 状态、有效 Custom map 与类型证明。 | 不是原始 JSON、持久化输入记录、Workspace fixture、通用对象 map 或允许部分 Evaluation 的容器；Evaluator 不得越过它重读 RootDocument。 |
| AdmittedAssetValue | 外部PUBLIC Custom override中的imageRef/fontRef经same-scope、ACTIVE、kind及调用者asset.read检查后形成的请求级授权值。 | 不是持久化AssetRef、精确contentVersion、child fill权限检查或可免除实际消费位置current解析的ResolvedAsset。 |
| ABSENT | 一个 StaticSchema 已声明可选值在具体 typed context 中未出现时的内部有类型状态；合法路径遇到缺失可选祖先也传播该状态。 | 不是 JSON null、空字符串、默认值、Schema 外字段、无效 field path 或可由调用方直接提交的 literal。 |
| Invocation frame | 一次具体 Template 调用创建的请求级不可变词法帧，持有该 Template 的精确 typed context、有效 CustomDefinition 值与自身 definitions。 | 不是父 Template/RootDocument 的共享 map、持久化会话或允许子调用回读调用者状态的动态作用域。 |
| Loop frame | Repeat 的某个实际输入项在所属 Template invocation 内创建、由稳定 loopId 定位的请求级不可变词法帧；持有 typed item、原集合零基 index，并只链接同 Template 的词法祖先。 | 不是 authored NodeKind、`$parent/$root` 漫游句柄、子 Template 继承环境、可变迭代器或任意 JSON object 上下文。 |
| Scalar item context | Evaluator为scalar Repeat item构造、精确符合对应`system-basic-*@v1` StaticSchema的不可变typed context；暴露必填`/index`与`/value`，可成为匹配TemplateUse的完整child context。 | 不是私有Context Contract、通用system map、任意object包装、shape推断或向reference业务Schema注入index。 |
| Evaluation Capabilities | exact Expression Profile拥有、Rendering向Expression显式input提供的封闭环境能力集合；首批仅为Clock与Random，部署必须完整支持该Profile。 | 不是AssetResolver、Template/request allowlist、调用者授权、通用网络、SQL、文件、脚本或凭证接口，也不默认保证新Evaluation重放旧结果。 |
| Closed capability surface | 由Host capability、exact Expression Profile拥有的Clock/Random、内部AssetResolver和Renderer-only fetch lease组成的v1完整能力边界；未列能力一律不存在。 | 不是通用tool/plugin协议、管理员后门、任意IO/脚本/模型调用、调用方可扩展allowlist或可从DesignDSL声明的新能力。 |
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
| Render registry reservation | RenderEngine为一个内部requestId、canonical Command identity与deadline建立的短期冲突及重放边界。 | 不是queue position、accepted execution、资源承诺、成功结果或可延长deadline的幂等键。 |
| Accepted render execution | 已原子取得RenderEngine FIFO queue position、因而必须在exact容量和deadline合同内完成或返回合同终态的一条Renderer Command。 | 不是只有registry reservation的BUSY请求、公共renderOperationId、后台持久job或可接纳后降档的best effort工作。 |
| Active render request | RenderEngine以内部requestId与canonical Command identity线性化的一次有deadline执行；同内容重发join/replay，取消与atomic output seal在同一生命周期竞争。 | 不是公共renderOperationId、跨请求cache、持久job、Artifact、可续期lease或允许换参数重跑的幂等键。 |
| Sealed render replay state | Render终态后短期保留的最小registry与已授权完整sealed response或安全terminal problem，用于同Command exact replay。 | 不是未seal RenderDocument、lease、raw sidecar/trace、Template或Asset快照、可重新投影诊断的恢复点或长期历史。 |
| compositionViewport | RenderDocument中把一个已完全展开的静态child artboard以固定CONTAIN/CENTER和双层clip规则映射进parent host LayoutBox的布局原语。 | 不是DesignDSL TemplateUse、nested Canvas、child revision句柄、栅格快照、跨Template callback或final-coordinate scene。 |
| Layout Profile | 由 exact 标识冻结 RenderEngine 的 measure/arrange/shaping、box、transform、clip、paint order 与确定性数值语义的兼容合同；首个为 `renderweave-layout/1.0`。 | 不是调用方可选的质量档位、DPI、DesignDSL Profile、浏览器 CSS 版本或可原地热修复的 latest 算法。 |
| Renderer Profile | 由 exact 标识冻结Engine对资源、orientation、颜色、字体解析、raster、sampling、blend及QR/Barcode像素算法的兼容合同；首个为`renderweave-renderer/1.0`。 | 不是Layout Profile、图片格式/quality、engine build version、GPU/CPU开关、调用方质量档位或环境默认集合。 |
| Output Profile | 对已经确定的像素冻结某种图片格式的encoder、bitstream和metadata合同；首批为`renderweave-output-png/1.0`与`renderweave-output-jpeg/1.0`。 | 不是layout/raster算法、任意codec option、文件名、持久化策略、caller可协商的latest或多格式集合。 |
| v1 capacity contract | 分别由相关exact wire/Profile拥有、调用方不可协商的一组per-request语义上限；READY部署一旦接纳请求就必须完整兑现这些上限。 | 不是容量档位、运营SLO、`latest`配置、deployment queue/slot大小或可在执行中降低的资源配额。 |
| Capacity oracle | 把v1容量矩阵中的closed limitId唯一映射到失败类别、稳定code、合同阶段、预留点与零partial-result边界的规划及验收事实。 | 不是调用方传入的预算、运行时计数转储、自由文本错误、Profile选择器或实现默认值。 |
| Renderer spike candidate | 为验证依赖、工具链、patch与确定性路径可行性而冻结的不可变非认证Renderer选择；失败后只能由新candidate identity替代。 | 不是Certified Renderer manifest、READY target、Profile authority、可原地换版本的依赖范围或权威Render路径。 |
| Source-integrity rehearsal | 在显式时间、临时存储与固定开源source边界内核对候选commit/tree/archive、工具分发identity、patch/header bytes及静态适用性的A1取证。 | 不是compile/link、ELF closure、字体执行、pixel golden、物理Linux replay、Certified Renderer或READY证据；临时vendor source必须清理。 |
| Hermetic Renderer build prerequisite | 在Renderer build rehearsal发生前必须封闭满足的source、toolchain、environment、fixture、audit与授权条件集合。 | 不是build manifest、compile/link结果、证据等级、Certified Renderer或执行构建的授权本身。 |
| Portable tricky-font fixture | 以确定性recipe、license、exact bytes与SHA-256绑定、能被FreeType识别为`FT_IS_TRICKY`并区分hinting flag行为的synthetic或open conformance font。 | 不是proprietary real font、local-only补充诊断、未经hash的系统字体或只在一台主机出现的认证authority。 |
| Certified Renderer manifest | 把一个READY Renderer target的精确依赖源码、工具链、构建参数、单一执行路径与golden身份固定为可核验事实的认证清单。 | 不是包名或版本范围、系统库发现结果、开发机锁文件、运行时feature探测或只读文字说明。 |
| READY Renderer target | 绑定exact Profile、target machine manifest、reference commit与完整conformance corpus，并已证明可产生权威RenderOutput的平台组合。 | 不是开发机、浏览器反馈、仅能启动的Engine、未经独立重放的OS/CPU/SIMD组合或多个target结果的投票。 |
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
| Authoritative Preview | 针对已保存且当次权威重检通过的 Template current，使用与正式输出相同的 Evaluator、RenderEngine 与 exact Profile 路径生成并展示的 RenderOutput。 | 不是未保存 EditorSession、浏览器对 DesignDSL/RenderDSL 的本地解释或旧图片回退；本地画布反馈只能是非权威草稿。 |
| Authoritative Preview basis | 一张当前展示的 Authoritative Preview 所精确对应的已保存 Template current、无本地内容分歧状态、EditorSession 输入样例、公共输出参数与诊断选择。 | 不是 Evaluation fingerprint、cache identity、持久历史或允许旧结果覆盖新编辑状态的宽松标签。 |
