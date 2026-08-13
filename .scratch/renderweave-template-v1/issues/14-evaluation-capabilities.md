# 定义 Evaluation Capabilities 与请求内一致性

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 02, 07, 13

## Question

Clock与Random的封闭能力接口、调用位置、请求内稳定性、预算、超时、审计和失败语义是什么；Expression能否直接调用能力，还是只能读取Evaluator注入的值；它们如何与非Expression能力AssetResolver保持边界；未来新增能力如何版本化而不退化为任意工具执行？

## Inherited constraints

- RenderInput/AdmittedRenderInput 不包含可浏览 system map；tenant、actor、requestId、凭证及 Connector 状态不得进入 expression-visible context。
- Capability 不是 StaticSchema field、CustomDefinition、AssetRef 或隐式 lexical variable；任何可见值/调用都必须使用显式版本化 capability 合同。
- child invocation 的数据和 definition frame 隔离已冻结；本票据只能定义 capability 是否及如何沿 Template closure 传播，不能借 capability 暴露父 frame 或任意环境访问。
- 票据12已冻结传播边界：同一Render请求获准的capability集合可供每个实际child invocation使用，但TemplateUse不能新增、替换、屏蔽或保存per-use allowlist；child Expression仍必须显式声明capability input。静态closure membership本身不触发call，实际调用拥有独立InvocationPath身份并共享请求级总次数/费用/时限预算。
- Capability ValueSource 只允许出现在 ExpressionDefinition 的显式 `{alias, source}` inputs；Expression source 只能读取 `input.alias`，不能直接调用 capability，Binding/Mapping/Custom/default/child fill 也不能直接持有 capability source。
- capability call position 由 definitionId + input alias + 显式 invocation/loop frame 稳定标识；input 在单次 Expression evaluation 内惰性 memoize，未 materialize Definition 或未选择 expression branch 不得调用能力。
- nested call position必须在上述identity前加入root/TemplateUse invocation与Repeat segments；相同child snapshot的不同use或loop item不能共享capability result，除非本票据明确冻结某个capability自身的请求级稳定值语义。
- Expression 1.0 的 grammar、类型与 lazy order 已冻结；本票据只能定义 Clock/Random closed payload、请求内稳定性、传播和预算，不能增加 eval、IO、任意函数或隐式环境读取。
- capability ERROR 不是 ABSENT，`coalesce` 不得吞掉；第一个实际 demanded capability ERROR 终止 Evaluation，之后不再调用其他能力或解析 Asset。
- AssetResolver不是普通Expression capability；它只在concrete imageRef/fontRef实际消费位置按票据13的固定DFS/property encounter order运行。Capability不得调用、包装、影响其current选择、lease、resourceId或cache；Asset预算与错误身份也不并入Clock/Random call-site模型。
- 票据13已冻结AssetResolver只接收最小内部请求、以`renderRequestId/resourceId`短期幂等地线性化Asset current并签发Renderer-only lease；本票据不得重新打开per-use Asset授权、表达式可见Asset metadata、logical current memoization或lease续签。
- DesignDSL 与 Expression Profile 按 exact、只追加 compatibility pair 校验；新增 capability-visible expression syntax/type/function 必须形成新 Expression Profile 并经显式 migration/save，不能修改既有 pair。
- Expression source 是 Canonical DesignDSL 中的 exact string，source whitespace 参与 contentHash；capability runtime result 不进入 revision hash，票据15已冻结pre-execution evaluationFingerprint与成功evaluationResultDigest，票据19只补数值容量。
- 一旦 Expression Profile 被 revision 接纳，其 capability call-site/type/order 语义是永久兼容义务；底层 evaluator/library 更新不得改变旧 profile。

## Answer

### 1. 所有权、Profile 与 closed wire

- Evaluation Capability由Rendering拥有，并由exact Expression Profile永久映射到exact capability contract。`renderweave-expression/1.0`精确映射`CLOCK → renderweave-capability-clock/1.0`与`RANDOM → renderweave-capability-random/1.0`；Template、TemplateUse、Render Request和部署配置都不能添加、替换、屏蔽或协商这组合同。
- v1 Capability ValueSource只有三个closed wire：`{kind:"capability",capability:"CLOCK",operation:"UTC_DATE"}`、`{kind:"capability",capability:"CLOCK",operation:"UTC_TIME"}`与`{kind:"capability",capability:"RANDOM",operation:"UNIFORM_DECIMAL_0_1"}`。前两者输出分别为既有`date/time`，后者输出`decimal`；禁止args、seed、timezone、contractVersion、`latest`、null和unknown member。
- Capability ValueSource只允许作为ExpressionDefinition的显式`{alias,source}` input；Expression source仍只能读取`input.alias`。Capability不是Expression函数、StaticSchema field、system map或普通ValueSource，不能直接用于Binding、Mapping、Custom default、child fill、结构source或Node property。
- save/recheck对完整authored closure中的全部capability source做closed shape、exact Profile、operation、输出类型与位置合法性检查，包括未使用Definition、未选择branch和静态不可达child。非法合同是不可确认hard error；已经接纳的Profile是永久兼容义务，不建立Asset式反向索引或STALE传播。

### 2. CapabilityState 建立与作用范围

- Rendering先完成根及完整Template closure冻结、exact Profile兼容检查、RenderInput/Custom/动态Asset准入以及全部authored dependency admission；全部成功后、创建root invocation frame前，才为一个逻辑Evaluation建立CapabilityState。任一更早失败都不读取Expression Clock或entropy。
- 建立前静态扫描完整closure实际声明过的contract：即使source只在不可达branch或未调用child中，声明CLOCK仍创建单一Clock snapshot，声明RANDOM仍创建单一Random nonce；完全未声明某类能力时不创建其组件，完全没有capability source时不创建CapabilityState记录。部署仍必须完整支持其声称支持的整个Expression Profile。
- 一个根Evaluation只有一个CapabilityState，传播到全部实际child invocation。由同一RenderDocument产生的多种图片编码/尺寸共享该状态；多个RootDocument、根Template或未来batch record分别建立Evaluation，不得为共享Clock/Random而合并。
- 内部`renderRequestId`由Rendering服务创建，调用者不能把任意字符串直接作为state key。未来如开放公共重试，只能由授权的服务端幂等映射或恢复句柄继续同一fingerprint；它不能选择nonce、覆盖Clock或用于另一请求。

### 3. Clock 1.0

- Clock provider在CapabilityState线性化创建时读取一次UTC instant，向过去截断到整秒，形成全Evaluation共享的Evaluation Clock snapshot。使用proleptic Gregorian`0001-01-01…9999-12-31`及`00:00:00…23:59:59`；不表示leap second，provider错误或范围外结果失败。
- `UTC_DATE`与`UTC_TIME`只从同一snapshot分别投影为现有canonical `YYYY-MM-DD`和`HH:mm:ss` typed value；不会跨午夜混读。不同alias/frame仍是不同CapabilityCallPosition、分别计预算并进入结果摘要，即使投影值相同。
- v1固定UTC，不读取服务器、浏览器、tenant或actor时区。业务本地日期/时间必须通过RootDocument或CustomDefinition显式输入；Clock不输出datetime、instant、epoch、offset、timezone或text。
- Expression可见Clock与deadline/timeout/latency/record expiry使用的受信操作或单调时钟严格分离；snapshot后的wall-clock跳变不改变已建立值，操作计时及剩余deadline永不进入Expression。

### 4. Random 1.0 与 CapabilityCallPosition

- Random component使用受信OS CSPRNG生成exact 256-bit server-only nonce。它只用于请求内确定性派生，不进入DesignDSL、RenderInput、公共API、日志、problem、RenderDocument、cache key或持久Evaluation历史；HMAC的使用不使输出成为密码、token、identity或其他安全随机能力。
- CapabilityCallPosition绑定ExpressionDefinition的declaration frame而不是consumer Node：按运行嵌套顺序包含root exact Template revision、每个TemplateUse的useId/child template/revision、截至declaration domain的每个Repeat`loopId + 原输入零基index`，再包含definitionId、input alias、exact capability contract与operation。
- invocation-domain Definition不包含其consumer下游loop segment，因此在同invocation的多个loop consumer间memoize同一结果；loop-domain Definition逐原输入item独立。duplicate item仍独立，重排collection会改变随机值与item的对应；不按item内容、业务key、最终Node occurrence或“第N次调用”派生。
- `renderweave-capability-call-position/1.0`的closed semantic object精确为`{positionVersion,path,definitionId,inputAlias,capabilityContractId,operation}`，其中`positionVersion`的唯一合法值是`"renderweave-capability-call-position/1.0"`。`path[0]`只能是`{kind:"ROOT",templateId,revision}`；后续依实际嵌套只允许`{kind:"TEMPLATE_USE",useId,templateId,revision}`或`{kind:"REPEAT",loopId,inputIndex}`。ROOT唯一且最先，path终止于Definition declaration domain；所有ID/string必填，revision/inputIndex为非负整数。
- `positionBytes`使用该Profile的canonical JSON encoding：object member按member name UTF-8字节词典序、string复用`renderweave-design-c14n/1.0`的escaping/Unicode规则、整数使用最短ASCII十进制，path数组保持语义顺序；无metadata normalization、set sorting、unknown/optional/null。exact结构和字节向量是永久conformance义务。
- 对counter `0…127`依次计算`HMAC-SHA-256(key=nonce, data=UTF8("renderweave-capability-random-uniform-decimal/1\0") || uint64be(positionBytes.length) || positionBytes || uint32be(counter))`，把digest解释为unsigned big-endian 256-bit整数`x`。令`M=10^18`、`limit=floor(2^256/M)×M`；`x < limit`时取`k=x mod M`，否则继续counter。128次均拒绝返回`CAPABILITY_RESULT_INVALID`。
- `UNIFORM_DECIMAL_0_1`在exact集合`{k/10^18 | 0 ≤ k < 10^18}`上均匀分布；允许0，最大值为`0.999999999999999999`。它是精确BigDecimal数值，继续遵守尾零不可观察语义；canonical typed encoding不依赖Java double、JavaScript number、locale或对象`toString()`。

### 5. Demand、memoization 与执行顺序

- Capability source仍遵守Expression惰性：只有source branch实际读取某个input alias时才发生Capability demand。未materialize Definition或未选择branch为零demand；所有branch仍在保存时静态验证。
- 首次demand一个CapabilityCallPosition时求其唯一结果；同一Expression evaluation内重复读取alias以及同declaration frame的Definition memoized重用不再调用或计数。两个alias即使引用同一operation也是两个position：Clock返回同一snapshot投影，Random独立派生，二者分别计数。
- Capability demand严格服从既有普通参数左到右、lazy branch、固定Node/Repeat/Conditional/TemplateUse consumer order及Definition frame规则。Evaluator串行提交逻辑demand并在首个错误处停止；实现可并行做不可观察的内部准备，但不得改变结果、计数、first-error或随后AssetResolver顺序。
- capability ERROR不是ABSENT，`exists/coalesce`都不能吞掉；第一项demanded capability ERROR使整次Evaluation失败并停止后续Capability、Asset、lowering与Engine工作，绝不回退static baseline或输出部分结果。

### 6. 预算、deadline 与本地执行

- 每次首次demand在产生结果前原子预留`totalCapabilityDemands`及对应`clockDemands`或`randomDemands`各一单位；超出任一上限则该position不产生结果并返回`CAPABILITY_BUDGET_EXCEEDED`。alias重读、Definition memo hit与CapabilityState初始化不消耗demand预算，物理Clock/HMAC复用不能降低逻辑计数。
- Ticket19统一冻结三个exact次数上限、静态capability-input数量、CapabilityState/position/摘要字节、初始化重试、TTL余量及总Evaluation operation/deadline的数值；Profile/部署只能在认证范围内fail closed，不能按caller/Template临时放宽或形成未版本化语义差异。
- v1 Clock/Random provider必须在受信Evaluator进程内执行，不访问网络、文件、插件或RenderEngine，也不产生按次外部费用。内部幂等记录存储只是Rendering基础设施，不成为capability可见IO。
- Evaluation取消或总deadline到期使本次请求零RenderDocument/Output；不能延长deadline、续期state、把操作时钟作为输入或用旧state启动新请求。

### 7. 线性化、幂等恢复与 expiry

- CapabilityState通过单一线性化创建操作读取所需Clock/CSPRNG并提交短期加密记录，key为内部`renderRequestId`，记录绑定`evaluationFingerprint`、声明contract集合、snapshot和/或nonce、issuedAt与固定expiresAt。只有提交成功后才允许建立root frame。
- pre-execution `evaluationFingerprint`由票据15精确冻结：绑定ownerScope、authorizationContextDigest、closureDigest、admittedInputDigest、exact RenderDSL/Layout/Capability/Asset contracts与effective budget vector；排除renderRequestId、Clock/nonce/结果、后续Asset选择、URL、output与网络时序。fingerprint与记录不返回调用者。
- 同renderRequestId、同fingerprint只重放已提交state；不同fingerprint返回`CAPABILITY_STATE_CONFLICT`。提交前瞬时失败可在总deadline内有界重试；提交状态不明必须先查询记录；提交后禁止重新取样。不同renderRequestId即使fingerprint相同也创建新state。
- expiresAt在创建时固定，至少覆盖Render deadline和Ticket19固定重试余量；不因重试、取消或下游失败续期。失败/取消后的记录可留至expiry后自动清理，但不是审计、Evaluation history、备份导出或公共查询对象。

### 8. 错误、状态与诊断

- 稳定请求级错误至少为`CAPABILITY_PROFILE_UNAVAILABLE`、`CAPABILITY_STATE_UNAVAILABLE`、`CAPABILITY_STATE_CONFLICT`、`CAPABILITY_CLOCK_UNAVAILABLE`、`CAPABILITY_ENTROPY_UNAVAILABLE`、`CAPABILITY_BUDGET_EXCEEDED`、`CAPABILITY_DEADLINE_EXCEEDED`、`CAPABILITY_CANCELLED`及`CAPABILITY_RESULT_INVALID`。provider原始异常只能映射为这些有界code与安全参数。
- unknown capability/operation/member、args/null、自报版本、错误输出类型或在非Expression-input位置使用capability是不可确认DesignDSL hard error。已合法Profile的Clock/entropy/state store瞬时不可用只使请求失败，不改变TemplateReadiness、不创建STALE/INVALID或新revision。
- 公共Render错误默认只返回稳定code与安全请求定位。具备相应Template读取权限的编辑/诊断界面可显示definitionId、input alias及有界且逐段授权的InvocationPath；无child权限的segment必须redact。任何层不得输出Clock/Random结果、nonce、fingerprint、完整child path、原始输入、provider详情或stack/raw error。

### 9. Evaluation identity、持久化与 cache

- 只有成功完成Evaluation才按实际demand encounter order，对每项closed`{capabilityContractId,operation,callPosition,outputType,result}`使用上述primitive canonical JSON规则编码；date/time result是canonical string，decimal result是canonical JSON number，每项以前置`uint64be(entryBytes.length)`分帧。`capabilityResultDigest = "sha256:" + lowercaseHex(SHA-256(UTF8("renderweave-capability-results/1\0") || framedEntries))`。未demand的state组件、nonce、完整Clock instant中未投影部分、物理执行、cache与时序不进入。
- capabilityResultDigest与Ticket13的assetSelectionDigest共同成为Ticket15完整Evaluation identity的运行成分；contentHash或“声明了能力”不能代替实际结果。包含capability source的请求仍必须完成准入和惰性Evaluation，只有完整identity形成后才可参与后续exact Render cache。
- 成功Evaluation元数据最多持久化capabilityResultDigest、实际使用的contract IDs、各类聚合demand计数、稳定结果码与耗时；失败不形成partial digest或调用transcript，只可保留已消耗聚合计数、耗时和错误码。不得长期保存call position、snapshot、nonce或typed结果。
- 同请求恢复不能仅凭CapabilityState跳过未证明完成的阶段；state、digest、恢复句柄或cache不能转换为新renderRequestId的Clock/nonce或跨请求replay承诺。

### 10. RenderDocument、预览与创作体验

- Evaluator只把能力影响后的concrete typed值写入普通Node properties。RenderDocument/RenderResource/Engine不得携带CapabilityState、contract、call position、digest、Clock/Random原值、调用句柄或来源标签；Engine不能分辨concrete值原来是literal、context还是capability。
- 每次新的Authoritative Preview都是新的Evaluation并创建新状态，只有同一逻辑操作的内部恢复才重放。v1不提供EditorSession时间/seed锁定；浏览器草稿可使用明确标注的模拟值或placeholder，但不得声称权威、写入DesignDSL或保留旧权威结果冒充当前结果。
- 编辑器只在Expression input source picker中按exact Profile展示三个typed operation，不称为“系统数据源”，不显示seed/timezone/contract版本/自由参数；必须提示新权威预览可能变化且Random不可用于安全用途。
- conformance harness可以从内部provider seam注入fixed UTC instant与fixed nonce；该入口不得出现在生产HTTP/header、DesignDSL、RenderInput、Template metadata、Workspace fixture或普通运行配置覆盖中。Web仅权威校验closed authored shape的反馈等价性，任何runtime模拟必须标记非权威。

### 11. 可观测性与安全边界

- metrics只使用contract、operation、status等有界label，并可记录聚合demand数、state初始化/求值耗时与失败计数。普通log/trace禁止Template高基数path、definitionId/alias、结果、snapshot、nonce、fingerprint、输入或provider原始错误；授权定位只走有界结构化problem。
- Capability不读取tenant、actor、requestId、权限、凭证、Connector状态、Asset metadata或任意环境map；这些事实可以参与内部授权/fingerprint但永远不可被Expression观察。
- HMAC派生只提供稳定分布，不承诺不可预测性或安全强度。Expression 1.0也不能把Random转换为UUID、token或任意字节；任何安全身份必须由所属产品域服务端生成。

### 12. 演进规则

- 新Evaluation Capability必须是显式Expression input、closed typed、只读、请求级、无业务副作用、无凭证且无任意目标选择；合同必须冻结operation、payload、output、CallPosition、稳定性、结果、错误、预算、诊断与conformance，并通过新的exact Expression Profile及显式migration/save引入。
- closure可包含不同永久支持的Expression Profile；每个TemplateSnapshot使用自己的exact capability contract，所有实际invocation共享同一基础Clock snapshot/Random nonce，但CallPosition与HMAC domain包含各自contract ID。根Profile不覆盖child，child也不能协商或屏蔽能力。
- 需要任意网络、付费调用、业务数据读取、mutation、credentials或动态工具选择的功能必须留在Connector或新的独立领域/授权设计，不能包装成`TOOL(name,args)`、Template插件或Evaluation Capability。

### 13. 验收锚点与明确排除

- Ticket19 conformance必须覆盖UTC午夜/截秒/年份边界及date-time同snapshot，fixed nonce的HMAC/rejection字节向量，零值/最大值/rejection counter，同alias/不同alias、invocation/loop domain、duplicate/reordered item、nested TemplateUse、多contract domain separation、lazy零demand、静态不可达声明仍初始化、state恢复/冲突、预算边界、deadline/cancel、digest order及RenderDocument零capability残留。
- 负向语料必须证明unknown capability/operation/member、null、args、自报版本及非法位置hard fail；caller不能注入time/nonce/seed/raw state key；未声明Random时entropy故障不影响请求；无前端、serializer、migration或Engine路径可放宽closed合同；problem/log/trace/公共响应不泄漏结果或内部state。
- v1明确排除datetime/instant/epoch、offset/IANA timezone、locale、亚秒、leap second、日期时间运算；caller seed/time、preview pin、跨请求replay/state共享；random integer/UUID/text/shuffle/sample/weighted random及安全用途；system map、Expression直接capability函数、per-Template/request allowlist、通用tool/plugin、任意HTTP/SQL/file/credential、付费/有副作用能力、Engine调用、长期transcript、partial/fallback、unknown忽略、Profile热升级、`latest`和实现自选算法。
- 本票据只冻结探索规格及下游约束，不创建Evaluator/CapabilityState/API/表/路由、RenderDocument字段、生产provider或其他产品实现。
