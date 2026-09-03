# 领域分片：Conformance 与容量证据语料

> 由 `CONTEXT-MAP.md` 路由加载。只在处理 conformance registry、容量边界（limitId）断言、
> execution class、Probe Profile 或 Renderer 认证语料时读取。
>
> 退役说明（2026-08-31 起）：A0–A3/J0–J1 证据分级与生命周期标签不再驱动新工作
> （见 `CONSTITUTION.md`）；本分片中的词汇保留，是因为 conformance 机制本身仍服务于
> Renderer 认证与容量验收。文中作为"不代表什么"出现的 J1/A1/A2 只描述该历史分级，
> 不是任何新票的完成条件。

| 术语 | 精确定义 | 不代表什么 |
|---|---|---|
| Normative requirement | 由稳定requirementId标识、从历史 Tickets 04–19 权威Answer/Decisions拆出的一个不可再独立失败或观察的规范性原子断言。 | 不是source line、Question、复述型Inherited constraint、Conformance case、测试方法或可换义复用的编号。 |
| Conformance case | 由稳定caseId标识的一组输入、故障时序与预期终态，独立于由哪种语言、target或revision执行。 | 不是一次测试运行、测试方法名、requirement条目、截图或把多个容量轴混在一起的宽松示例。 |
| Conformance oracle | 由稳定oracleId标识、供一个或多个Conformance case核验的code、stage、digest/bytes、副作用与状态断言集合。 | 不是自然语言期望、实现内部日志、运行结果本身或可随executor变化的容差解释。 |
| Conformance assertion | Conformance oracle内由局部assertionId标识、以封闭probe和operator比较一个字面期望或哈希绑定artifact的最小可核验断言。 | 不是自然语言谓词、参数模板、通配规则、任意脚本、默认fallback或整个oracle的别名。 |
| Coverage edge | Conformance case中把一个Normative requirement精确连接到一个Conformance oracle内若干assertionId的权威追踪边。 | 不是平行ID列表、按摘要模糊匹配、派生反向报表、J1替代证明或case标题暗示的覆盖。 |
| Active conformance record | 在完整append-only registry中未被任何有效新记录通过supersedes取代的case或oracle记录。 | 不是记录内可修改status、按文件最后一行猜测的latest、旧记录回填supersededBy或允许悬空覆盖的临时状态。 |
| Conformance canonical profile | 冻结case、oracle、input与fault identity所用strict JSON primitive、object/member顺序、array排序、domain-separated digest projection及signature重复规则的exact字节合同。 | 不是DesignDSL canonical profile、通用JCS、transport serializer默认、binary-float近似或允许同signature unrelated records并存的去重提示。 |
| Probe Profile | 以exact identity封闭列出每个Conformance probe的ValueType、observation boundary、operator许可、sensitivity与execution-class membership的观察合同；全局Profile发行只由完整inventory与probe/operator/type assertion vectors控制，各execution class另过自己的record gate。 | 不是generic JSONPath/state snapshot、任意脚本、参数模板、wildcard、natural-language predicate、executor内部日志schema，也不因某个class通过就证明其他class executable。 |
| Probe Profile candidate | 在不改变current Profile的前提下，完成全部probe inventory、operator vectors与class Adapter并经独立静态重放的未发行Profile候选；它可供planning candidate精确绑定，但`recordMayReference=false`，直至获得显式发行与supersession authority。 | 不是current Profile、Oracle可引用identity、浏览器观察、产品实现、J1、A2产品重放或READY证据；不得借测试便利暴露generic Editor state、raw DesignDSL或raw recovery draft。 |
| Execution class | Conformance case声明的唯一most-downstream稳定执行边界；精确catalog区分spec registry、domain service、Design/Input/Expression、Rendering pipeline、Renderer exact output与Editor automation。 | 不是具体executor、target、implementation revision、证据等级、runId或J1人工结论，也不因同case跨target重放而变化。 |
| Safe baseline | 绑定一个Execution class、由不可变片段组成的最小合法Conformance输入根；每个case只在其上施加显式变化并绑定完整内容身份。 | 不是ambient默认、跨class fallback、未校验fixture、latest样例或可由executor补全的输入。 |
| Conformance generator | 由exact Profile标识、把完整显式参数、Safe baseline与哈希绑定artifact确定性转换为Conformance fixture的纯生成合同。 | 不是测试框架helper、隐藏default、ambient环境读取、网络下载、未声明entropy或运行时解释模板。 |
| Capacity coverage mapping | 在容量record发行前，把每个closed limitId及三个边界变体精确连接到权威requirement、执行边界、预期终态和assertion计划的生成事实。 | 不是已发行case/oracle、runtime fallback、模糊摘要匹配、默认值索引或完整非容量覆盖表。 |
| Capacity boundary shape candidate | 从已冻结mapping静态展开、通过schema/canonical/identity/coverage/probe-type双重重放的未发行Case或Oracle候选形状；它用于在真实class fixture与executor存在前发现映射及字节合同缺陷。 | 不是Safe baseline fixture、generator golden、preissuance最终bytes、正式append-only record、容量终态执行、execution-class A2、Renderer认证或READY证据。 |
| Isolated capacity guard fixture | 在某一Execution class的Safe baseline上，只把一个closed limitId的精确observedValue字符串、valueEncoding、comparator、stage、reservation point与zero boundary交给同一limit-specific guard seam的静态夹具；用于避免以巨大或跨轴非法payload伪造边界隔离。 | 不是权威parser/canonicalizer/counter/evaluator已运行的证明，不证明observedValue来自真实输入，也不能替代产品target对每轴值来源、guard接线、terminal与零副作用边界的独立回放。 |
| Execution-class fixture bootstrap | 为一个Execution class冻结Safe baseline bytes、closed observation adapter、纯generator target与golden fixture，并由不同runtime独立重放其精确字节的静态前置。 | 不是产品target、required executor role、数据库或Renderer执行、容量terminal证明、正式record发行、class executable或历史Ticket19关闭。 |
| Editor automation admission | 位于`EXEC::EDITOR_AUTOMATED::1.0`执行seam上的全成或全不成接口；只有supported-target matrix、immutable product build、exact browser/OS target、environment profiles、runner、active corpus、observation adapter与独立产品回放全部digest-bound后才返回ADMITTED。 | 不是Playwright dependency、device alias、channel/project名、reuseExistingServer、prototype A1、routing inventory、fixture A2或J1替代证据。 |
| Editor atomic scenario candidate | 以`EDC::Jnn::nnn` planning-only identity把一个Editor journey拆成唯一的abstract input plan、fault schedule、terminal vector、assertion plan与requirement edge；任何fixture或fault artifact、terminal literal、issued probe、target、runner或独立产品回放未绑定时保持`PREISSUANCE_BLOCKED`。 | 不是`CONF::EDITOR_AUTOMATED` Case、`ORC::EDITOR_AUTOMATED` Oracle、active corpus、浏览器执行、产品行为证据或J1；candidate mapping只表示预期证明路径，不构成requirement coverage。 |
| Editor terminal adjudication | 把一个planning candidate的唯一expected terminal收口为exact outcome，并把code与stage分别裁决为字面量或`NOT_REQUIRED`的封闭目录；新Editor字面量只在该验收接口内取得身份。 | 不是产品已实现的错误码、浏览器观察、正式Oracle、允许自然语言解析或generic fallback的运行时策略；正式发行前仍须产品重放证明相同terminal。 |
| Editor fault schedule artifact | 对一个非`NONE` planning fault schedule保存exact candidate、ordered exact-once named-seam events与expected terminal的不可变SHA-256绑定artifact；future runner只能通过closed catalog和窄adapter消费。 | 不是任意脚本、wildcard/regex trigger、DOM/transport/persistence内部寻址、ambient时间/熵/网络、产品已存在injection seam或已执行fault的证据。 |
| Editor semantic input fixture | 对一个planning candidate冻结exact baselineId、closed parameters与ordered named action script，并以不可变artifact及formal input identity绑定的窄输入接口。 | 不是DOM selector、框架state、数据库行、隐藏setup、产品fixture adapter、浏览器已加载baseline或action已执行的证据。 |
| Editor semantic projection | 位于semantic fixture与target binding之间的窄封闭接口；只把settled candidate语义投影为closed command-result code、显式起点加eligibility transition得到的preview generation、一组exact内容前置依赖，或在Raw Repair/Compatibility Read-only中证明可信canonical DesignDSL working copy不存在。 | 不是DOM/framework counter、产品实现、原始DesignDSL、猜测digest、placeholder、浏览器观察或正式Oracle；不得把malformed raw bytes或unsupported original bytes冒充working copy，内容前置未齐和UI观察未发生时必须继续pending。 |
| Editor content source | 位于semantic projection的内容前置图与target binding之间的窄封闭接口；每个第一层内容决策只绑定一个source slot，且仅允许immutable spec fixture或admitted product capture提供exact canonical DesignDSL bytes以及所需revision/contentHash/baseline projection facts。Editor自有immutable spec fixture还必须携带closed state proof，target binding只能从其exact result机械重放digest。 | 不是baselineId、revisionDelta、动作名、semantic fixture、跨execution-class baseline、placeholder DesignDSL、猜测hash或产品观察的替代品；slot为`UNBOUND`时不得生成workingCopyDigest、canonical baseline bytes或exact target，已绑定spec fixture也只证明规划bytes/state rule，不证明产品执行或浏览器行为。 |
| Editor target binding | 只在既有冻结语义能机械推出唯一expected literal或immutable artifact时，把pending assertion收口为exact target；无唯一值时必须保留pending并记录原因。 | 不是产品观察、占位值、相似UI推断、generic default、正式Oracle或requirement coverage；exact planning target仍须独立产品重放。 |
| Conformance bootstrap order | 规定各Execution class建立executor、target、adapter与独立重放证据的前置依赖顺序。 | 不是run顺序、并发调度、后序证据对前序缺口的替代或J1人工验收流程。 |
| SPEC_REGISTRY bootstrap | 以`RW-T19-S00-001 + 全部RW-T19-S13-*`为assigned requirement集合，对requirement/case/oracle/schema/profile/manifest事实源执行class-local no-orphan、canonical、identity、reference-closure及双执行器重放的首个execution-class闭环。 | 不是全部历史Tickets需求已经自动覆盖、525条容量record已经签发或可执行、其他execution class可执行、Renderer认证或历史Ticket19关闭。 |
| Conformance run | 把一个Conformance case绑定到精确executor、target与implementation revision后产生的一份不可变证据记录。 | 不是新的case语义、跨target投票、可覆盖旧失败的mutable状态或人工J1结论。 |
| Conformance registry | 分别保存Conformance case与oracle不可变记录的append-only事实源；acceptance manifest只索引这些registry并声明完整性状态。 | 不是反向覆盖报表、测试框架发现结果、一次run记录、可重编号清单或把requirement与case强制一对一的表。 |
| Editor J1 acceptance | 具名人员在固定build、浏览器与OS上完成全部冻结Editor任务后形成的人工体验及可访问性结论。 | 不是自动gate、无障碍扫描、截图、部分任务通过或未记录环境的口头确认。 |
