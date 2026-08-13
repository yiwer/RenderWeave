# 定义 Evaluation 编译流水线与 RenderDocument 合同

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 08, 10, 11, 12, 13, 14

## Question

输入验证、definition/binding、条件、循环、嵌套、Asset 解析与 RenderDSL lowering 的权威顺序是什么；各阶段如何限界和报错；RenderDocument 的版本、静态 kind、渲染级布局、具体载荷、资源清单、大小限制和请求生命周期如何与现有 `busbox-render-engine` 合同对齐？

## Inherited constraints

- Render 不能信任最终一致的 TemplateReadiness 或 current report；每个请求必须重新权威检查最新 root current、AssetRef 和全部嵌套 current。
- 根与全部authored可达TemplateRef必须冻结为一致Template closure snapshot；snapshot按templateId去重但保留每条use edge，静态不可达branch也参加same-scope/readiness/DAG/Profile检查。任一current漂移时有界重试或失败，结构剪枝只跳过实际invocation。
- INVALID/STALE 或任何闭包依赖 ERROR 都不能产生成功 RenderDocument/RenderOutput；问题必须结构化返回。
- Asset是刻意例外：Template闭包仍一致冻结，但每个实际materialized Node property消费位置用OccurrencePath+ConsumerPropertyRef独立解析当时Asset current；同一assetId可在一个RenderDocument中出现多个contentVersion，每个occurrence一对一产生ResolvedAsset/RenderResource与独立resourceId，不能按assetId/exact bytes合并manifest entry。
- closure冻结后必须先从每个unique TemplateSnapshot重新提取并admit全部authored AssetRef atom（含静态不可达branch、baseline/default/Mapping及asset-ref list），只检查same-scope/existence/ACTIVE/kind而不钉死current。根PUBLIC override中的每个asset atom即使未使用，也必须先形成AdmittedAssetValue；后续actual consumption才resolve current。
- 任一Resolver选择/descriptor/lease失败中止Evaluation且不形成完整RenderDocument；已经成功的ResolvedAsset保持exact bytes可读。RenderDocument封存后Engine fetch/hash/decode/shaping失败允许请求内部文档短暂存在，但公共操作不得返回或持久化document且零RenderOutput。
- 根入口必须先封闭解析 RenderInput、用 TemplateSnapshot 永久 StaticSchemaRef 每次权威验证 RootDocument，并按 last-wins 规则消解根 PUBLIC Custom override；任一准入错误都不得创建 frame 或执行 capability。
- 根/closure/Profile、RenderInput及全部authored/dynamic Asset admission成功后，Evaluator必须在root frame前静态收集完整closure中声明的exact capability contracts，并按票据14线性化建立所需CapabilityState组件；即使声明位于静态不可达branch也初始化，未声明的Clock/Random组件不初始化，完全无capability source时没有state记录。
- 内部renderRequestId由Rendering创建。CapabilityState以pre-execution evaluationFingerprint短期加密幂等：同key同fingerprint重放snapshot/nonce，不同fingerprint冲突，提交状态不明先查记录，fixed expiry后不续期；caller不能提交time/seed/nonce/raw state key。
- Evaluator 只消费不可变 AdmittedRenderInput，不得重新解析原始 JSON或观察 StaticSchema 未声明字段；合法 optional missing 使用 typed ABSENT，null 已在准入边界拒绝。
- child context使用exact StaticSchemaRef typed-view proof；scalar Repeat由Evaluator构造精确符合正式`system-basic-*@v1` StaticSchema、含`index/value`的context，empty selector构造`system-empty@v1` context。它们都是一等StaticSchema而非私有类型，仍禁止结构相似性转换；每个child invocation与Loop frame遵守隔离词法边界。
- 本票据必须冻结pre-execution evaluationFingerprint与success-only evaluationResultDigest，但不能使ignored unknown fields、duplicate loser或unknown/PRIVATE Custom override重新成为表达式可观察数据；票据19只补容量数值，票据16只决定如何再组合Renderer/Output Profile形成可选输出cache key。
- Java Evaluator 必须实现 `renderweave-expression/1.0` 的自有权威语义；Web/CEL/其他 library 只能通过同一 conformance corpus 提供非权威反馈，底层 library version 不能进入 DesignDSL 语义身份。
- save/recheck 已对全部 Definition、Expression/Mapping branch、Binding target/source、domain、cycle 与 literal 做静态完整检查；runtime 则 demand-driven，只求值实际 materialize node/binding、selected case/branch 与其显式 inputs。
- Definition graph 允许 forward reference 但必须无环；Mapping/Expression 在声明 invocation/loop frame 惰性 memoize，ordinary call left-to-right，`&&/||/if/coalesce` lazy。未选择分支不得调用 capability。
- Capability input首次实际读取才按declaration frame的CapabilityCallPosition产生逻辑demand；同alias重读/Definition memo hit不重复，不同alias分别计数。Clock从单一UTC整秒snapshot投影，Random按exact HMAC/rejection合同从server-only nonce派生；demand按现有consumer order串行提交并与所有runtime ERROR共同first-fail。
- 每个 Design Node 先形成合法 authored static property tree，再按 node-local、互不重叠 targetPropertyRef 应用 Binding overlay；结果必须 concrete、精确 target type 并通过全局 property validation，ABSENT/ERROR/失败都中止而不回退 baseline。
- BindingPolicyCatalog 只决定目标是否合法，Template 不携带 policy 版本；Evaluator 必须从同一全局追加式 Catalog 解析 nodeKind + targetPropertyRef，不能信任 Web 预检或 Template 自报 target type。
- runtime第一个实际demanded ERROR fail-fast，停止后续capability与Asset resolution；Asset按materialized authored DFS、NodeContract property声明及Text Run index顺序，在concrete property/aggregate validation后立即串行resolve。只有全部求值、closure及实际Asset occurrence成功后才能原子形成无Binding/Expression/ABSENT/logical AssetRef的RenderDocument。
- 建立 TemplateSnapshot 前必须从 persisted DesignDSL 重新执行对应 dslVersion 的 canonicalization 并核对 domain-separated contentHash；mismatch 是内部 integrity failure，不能降级为 INVALID、重新 hash 或把内容交给 Evaluator。
- contentHash只绑定authored DesignDSL，不含StaticSchemaRef、resolved TemplateRef current、Asset current、RenderInput或capability result，因此不能单独充当Evaluation/Render cache key。pre-execution fingerprint至少绑定scope/authorization、closure、AdmittedRenderInput、exact contracts及budget profile；成功完整identity必须包含票据14按demand order形成的capabilityResultDigest与票据13按resource encounter order形成的assetSelectionDigest，但不得包含Clock未投影部分、Random nonce、fetch URL/token/expiry、cache hit或网络时序。
- 根与每个child revision都按各自exact、永久支持的dslVersion/expressionProfile pair解析；root DesignDSL Profile经永久compatibility table唯一确定目标Layout Profile，全部child必须有无损lowering edge。Evaluation不做read-time migration、caller negotiation/latest/fallback或subtree Profile混用。
- Evaluator 消费的是服务端权威解析后的 Canonical DesignDSL semantic value，而非上传 bytes、数据库 serializer 文本、raw repair buffer 或客户端 AST。
- 票据 09 已冻结 NodeContract validation 顺序：closed structure/type → property/composite → ContentModel/placement → whole-tree aggregate → Binding target/Policy → external readiness。Evaluator 必须使用同一 Catalog 形成 static property tree、按 Policy overlay 后重新执行 exact leaf 与 aggregate validation；不能复制 Node switch 或信任 RenderDocument consumer 补验 authored 语义。
- lowering输入是唯一Canvas-rooted ordered Node tree；`render:false` subtree在layout/resource/output前移除，`visible:false`保留layout且仍准备资源但不绘制。RenderDocument不携带node-local Binding、BindingPolicy、default omission、AssetRef logical selector或动态结构判别；每个IMAGE/FONT property只引用请求级resourceId。
- 本票据必须决定哪些 final-geometry aggregate constraints 在 Evaluation/lowering 与 Engine 间归属并避免双重权威，尤其是 QR square、Text/Vector final bounds 与 Image aspect-derived HUG；无论归属何处都不得改变已冻结 authored property identity/default/unit。
- RenderDocument 必须显式携带由 compatibility table 选择的 exact `renderweave-layout/1.0`，展开全部 Node default，把所有 decimal 统一降低为六位 `HALF_EVEN` pt/unitless/degree，并按实际 materialized occurrence order保存静态 tree；它保留 Stack/Grid/Placement 规则而不携带派生 coordinate、line break、glyph、LayoutBox 或 LaidOutScene。
- lowering 必须在资源/layout 前移除 `render:false`、false Conditional 与零项 Repeat，保留 `visible:false`/`opacity:0` occurrence；每个最终 occurrence 在 RenderDocument 中只携带本票据定义的请求级 opaque occurrenceId，完整 OccurrencePath 与可选 source-node diagnostic 留在请求级 sidecar。RenderDocument 不得包含逻辑 AssetRef、default omission、BindingPolicy 或任何让 Engine 回读 DesignDSL 的句柄。
- CapabilityState、contract、CallPosition、result digest、Clock/Random原值和调用句柄同样必须在RenderDocument前完全消失；Engine只能看到能力影响后的concrete property value，不能判断其原始ValueSource。只有形成完整Evaluation identity后才能考虑exact downstream cache，任何cache都不能绕过准入或惰性Evaluation。
- save/Evaluation 负责以同一 NodeContract/Profile 做静态及 post-binding aggregate preflight；Engine 只做同 Profile 的防御性检查并权威计算 final geometry。dependency cycle、无 definite FILL、Grid/HUG/QR/Text 组合等 hard failure 必须在最早可判定边界失败，但任何边界都不得形成另一套布局含义或返回部分 RenderDocument。
- Repeat 的固定 consumer order 为 `render → items → placement → visible → opacity → transform → itemLayout → instanceLayout → children DFS`；Conditional 为 `render → condition → placement → visible → opacity → transform → children DFS`。composite leaf 使用 NodeContract declaration order，不能按 bindingId 或 JSON member order改变首次 demanded error。
- `render:false`、零项/零 surviving Repeat 与 false Conditional 必须在后续 common Binding、packing、descendant、TemplateUse、Asset/capability 前惰性剪枝；accepted `EMPTY/FALSE` absent policy 静默成功。任何 demanded item、frame、operation或 materialized budget超限都使整次 Evaluation失败，禁止跳过失败项、截断或部分 RenderDocument。
- true Conditional 降低为无外观 Frame；每个 surviving Repeat item 降低为 HUG Stack/Grid，Repeat 外层降低为继承其 common fields/placement 的 Stack/Grid。PACK 映射为生成父的静态 placement，Repeat GRID 生成 explicit AUTO tracks 与零基 cell；Evaluator不得计算 final coordinate、track size、line break或glyph。
- 合成repeat-container/repeat-item/conditional-frame不生成UUID；请求级诊断 sidecar 对每个 opaque occurrenceId 保留完整 OccurrencePath 与可选 sourceNodeId，路径由Template invocation、每层`{loopId,inputIndex}`、role/source node组成且不含item值。`system-basic-*@v1`是正式一等StaticSchema，scalar frame的`/index`与loopIndex相同。
- TemplateUse固定consumer order为`render → contextSelector/absentPolicy → placement → visible → opacity → transform → fills(targetDefinitionId order) → child frame → child DesignRoot DFS`。render:false与SKIP不建立invocation；visible:false/opacity:0仍完整执行。fills的upload order无语义，first demanded error/capability order必须按canonical targetDefinitionId稳定。
- TemplateUse/child Canvas完全降低为静态compositionViewport：携带source trim、concrete background、完整静态child subtree与固定CONTAIN/CENTER/source+host clip规则，不携带TemplateRef、useId、selector、fill、frame或callback。Engine在source trim约束内layout child后按host LayoutBox计算scale/offset；Evaluator不能预布局，exact RenderDocument wire由本票据冻结。
- 内部OccurrencePath必须递归包含root templateId/resolvedRevision、外层Repeat loopId/inputIndex、TemplateUse useId/childTemplateId/resolvedRevision、child内Repeat及最终node/role；`template-use-viewport`与`canvas-background`等synthetic role不生成UUID。skipped use无path，路径不含业务值且不进入RenderDocument、revision/hash/cache identity。
- RenderResource manifest必须与tree中resourceId引用集合精确相等，按resolve encounter order保存且每个occurrence一对一。entry精确携带`resourceId/kind/fetchUrl/expiresAt/sha256/mediaType/byteLength/acceptanceProfileId/technicalDescriptor`，不含assetId/contentVersion/ownerScope；duplicate/collision、unused/missing entry、unknown/null或kind mismatch均为hard failure。
- `resourceId`按票据13从versioned canonical `OccurrencePath + ConsumerPropertyRef + expectedKind`产生，跨请求可重复且只在本document唯一；Rendering在请求级诊断sidecar保留resourceId到安全occurrence locator的错误回接映射，不把该映射、Template身份或Asset业务身份交给Engine。
- 含fetch lease的RenderDocument是Renderer-only请求内交接值，不可持久化、公开、返回调用者或跨请求复用；expiry/deadline后不能续签或重新解析Asset current。当前`haibo.render/1.0`资源manifest与Layout Profile不足，exact新RenderDSL/Layout Profile必须保留票据13全部字段并加入compositionViewport，不能静默降级。

## Answer

### 1. 权威边界与生命周期

- Rendering拥有Evaluation、RenderDSL、RenderDocument与Renderer Command。只有服务端权威Evaluator与sealer能产生可交给Engine的RenderDocument；Web/客户端只能维护明确非权威的画布反馈，不能提交自制RenderDocument、声明权威digest、绕过准入或直连Engine。
- DesignDSL与RenderDocument之间不定义正式MaterializedScene、可序列化MaterializedTree或第二套静态语言。实现可以使用请求级typed builder，但它是私有、不可持久化、不可缓存、不可对外、无独立identity的实现细节；全部成功后只封存一个不可变RenderDocument。
- RenderDocument是Renderer-only请求级交接值，不是Artifact、Workspace内容、审计对象或调试下载。公共Render/Authoritative Preview只返回RenderOutput描述或结构化problem；未来若需导出静态文档，必须另设删除lease与内部身份的新合同。
- v1不跨请求缓存或复用RenderDocument；票据16同样明确排除v1跨请求RenderOutput cache，每个成功Evaluation都必须进入Engine。未来若另行引入cache，也不得跳过当前请求的权威准入、Capability demand或Asset current选择。

### 2. 唯一 Evaluation 流水线

一次Evaluation严格按以下阶段全序执行，任一阶段失败都不进入后续阶段：

1. 认证、ownerScope、严格请求envelope与最外层容量准入；
2. 解析root current；每份候选revision必须先通过exact parse/canonical/contentHash integrity gate，之后才能解释其authored TemplateRef并递归冻结完整Template closure；
3. 对每个unique TemplateSnapshot执行完整静态语义、same-scope、DAG、外部依赖、Profile及无损lowering-edge权威重检；
4. 验证RootDocument、形成closed typed context并消解全部根Custom；
5. 按canonical occurrence order先准入全部authored AssetRef，再准入有效外部PUBLIC Custom override中的AssetRef；
6. 收集完整closure声明的capability contracts并线性化建立所需CapabilityState；
7. 建立root frame，按固定consumer order惰性物化、应用Binding、展开结构，并在实际property消费点串行resolve Asset；
8. seal静态tree与manifest，形成capability/asset/result/document digests；
9. 构造Renderer Command并调用RenderEngine。

- closure冻结期间任一root/child current漂移都丢弃整轮读取并从root有界重试；不得只补读某个child或拼接不同时刻。次数由票据19冻结，耗尽返回`TEMPLATE_CLOSURE_UNSTABLE`。冻结成功后不再读取Template current；Asset current继续按每个实际occurrence的独立线性化规则处理。
- 每次Render不信任TemplateReadiness或当前report。每个unique snapshot都重新按其永久exact Profile完成上述检查；若一个已接纳Profile如今不能按原语义解析/验证，属于内部兼容性违约，不自动把Template改成INVALID。
- persisted DesignDSL先按对应Profile重新canonicalize并核对contentHash；mismatch立即失败，不建立TemplateSnapshot、不重算/回写历史，也不把payload交给Evaluator。

### 3. 问题收集、串行语义与失败原子性

- closure、静态依赖、RenderInput与Asset admission按各自固定canonical顺序有界收集problem；到统一上限追加`PROBLEM_LIMIT_REACHED`并停止当前阶段，绝不进入下一阶段。malformed strict JSON、contentHash mismatch、unsupported exact Profile等无法安全继续的问题单问题立即停止。
- CapabilityState建立后进入严格runtime first-demanded-error：第一个ValueSource、Definition、Binding、Capability、property、Asset或容量ERROR终止Evaluation；不继续调用Capability/Resolver、不继续lowering，也不形成partial RenderDocument。
- 产品语义是一条确定的串行trace。实现只能并行执行不可观察的纯读取/准备，最终demand、预算、first error、Asset选择、manifest及tree顺序必须与规定串行执行完全一致；线程完成顺序不构成语义。
- Definition在其exact lexical frame内惰性memoize成功或失败；同一逻辑位置不重复求值。运行时失败只影响当前请求，不自动改变TemplateReadiness、revision或依赖projection。

### 4. Node 消费、Binding overlay 与结构展开

- 普通non-structural Node的固定consumer order为`render → placement → visible → opacity → transform → kind-specific properties → children DFS`；Canvas为`root geometry/bleed → backgroundColor → children DFS`。composite leaf、Text Run、Point、PathCommand与Track按RenderNodeContractCatalog声明顺序消费；JSON member、bindings数组与bindingId顺序不影响runtime order。
- Repeat、Conditional与TemplateUse继续使用票据11/12冻结的专用consumer order。`render:false`、false Conditional、零项/零survivor Repeat及SKIP在后续common Binding、descendant、Capability与Asset前惰性剪枝；`visible:false`和`opacity:0`不剪枝，仍完整求值、解析资源、layout并可能失败。
- Evaluator先依据NodeContract形成完整typed static property tree并展开语义default，同时保留每个叶子是否由作者实际materialize的证明。只有作者已存在且Policy允许的target才应用Binding overlay；default展开不能创建bindable target。overlay后重新执行leaf、composite、placement及whole-node aggregate validation，ABSENT/ERROR/类型或validation失败绝不回退baseline。
- 一个materialized Node先求完全部concrete property并通过post-binding validation，再按NodeContract property及Text Run index顺序串行解析其IMAGE/FONT occurrence，之后才进入children DFS。节点自身验证失败时不签发任何Asset lease。
- true Conditional降低为无外观普通`frame`；每个surviving Repeat item与Repeat外层分别降低为普通`stack/grid`，PACK转换为生成父对应的STACK/GRID placement。合成来源角色只保留在诊断sidecar，RenderDocument不携带synthetic marker。

### 5. 数值降低与最终几何权威

- post-binding语义先在DesignDSL原单位与任意精度decimal上验证。lowering随后对物理量精确计算`pt = mm × 360 / 127`，对pt/unitless/degree统一量化到最多六位小数、`HALF_EVEN`，规范`-0`为`0`；再以实际RenderDSL值重检范围、正值、min/max、有限性及此时可判定的aggregate约束。
- 量化不能修复作者错误；量化导致0、溢出或合同失效使本次Evaluation失败。Engine不再次执行产品语义rounding，也不能从原始mm重新计算。
- Evaluator只提前拒绝无需布局即可确定的矛盾；RenderEngine按exact Layout Profile唯一计算final geometry并判定依赖measure/arrange的QR square、Text/Vector final bounds、Image aspect-derived HUG等规则。Java不得预布局、输出final coordinate、line break、glyph、LayoutBox或LaidOutScene，也不得运行第二套布局算法。
- Engine返回的合法final-layout约束失败是请求级layout错误且不改变readiness；若Engine发现malformed sealed document、动态残留或manifest不变量破坏，则是内部RenderDocument合同违约。

### 6. RenderDSL 与 RenderDocument closed wire

- 首个exact合同分别为Renderer Command `renderweave-render-command/1.0`、RenderDSL/RenderDocument `renderweave-render/1.0`与Layout Profile `renderweave-layout/1.0`。它们是独立兼容维度；新合同不宣称兼容或扩展`haibo.render/1.0`/`haibo.dsl/1.0`，也不允许静默降级。
- RenderDocument使用UTF-8 strict JSON，拒绝duplicate key、unknown member、unknown kind、null及非canonical scalar。顶层closed envelope精确为：

```json
{
  "dslVersion": "renderweave-render/1.0",
  "layoutProfile": "renderweave-layout/1.0",
  "canvas": {},
  "resources": []
}
```

- 顶层禁止requestId、deadline、DPI、output、Template/Evaluation identity、capability/asset digest和metadata；这些若属于执行合同，只能在Renderer Command或Rendering内部存在。
- static node kind精确为`canvas | group | frame | stack | grid | text | image | rect | ellipse | line | polygon | polyline | path | qrCode | barcode | compositionViewport`。Canvas只能是根；compositionViewport只能由lowering产生；`repeat/conditional/templateUse`在RenderDSL中非法。
- 每个kind是lowerCamelCase `kind`判别的closed per-kind union，不使用通用properties bag。版本化RenderNodeContractCatalog由获准的DesignDSL→RenderDSL lowering edge显式定义；同义属性保留名称，只允许`*Mm → *Pt`、`imageRef/fontRef → *ResourceId`、删除authored-only字段、展开结构与default等已冻结转换。Java sealer与Rust parser/validator必须消费同一机器可读合同和conformance语料，不得各写一套漂移switch。
- root Canvas精确携带`occurrenceId/kind/widthPt/heightPt/backgroundColor/bleed/children`。其他Node携带`occurrenceId/kind/placement/visible/opacity/transform`及per-kind payload/允许的children；所有default显式展开，删除nodeId、displayName、bindings与render。
- RenderDSL Placement只含closed `ABSOLUTE | STACK | GRID` union，保留FIXED/HUG_CONTENT/FILL、min/max、margin、track/cell等布局规则，全部物理字段为`*Pt`。PACK必须在lowering中消失。
- `compositionViewport`的kind与Layout Profile永久隐含CONTAIN、CENTER、source clip与host clip，不保存冗余可配置字段。其closed `sourceCanvas`为`{occurrenceId,widthPt,heightPt,backgroundColor,children}`且不含bleed；viewport occurrence对应TemplateUse host，sourceCanvas occurrence对应child Canvas/background。

### 7. occurrenceId、诊断 sidecar 与资源引用

- seal阶段按最终静态tree先序为root Canvas、所有真实/合成Node及compositionViewport sourceCanvas分配连续unsigned 64-bit ordinal；wire为`rwocc_`加16位零填充lowercase hex。顺序是root后依children；viewport之后先分配sourceCanvas再进入其children。剪枝内容不占号，`visible:false`、`opacity:0`与普通空Node仍占号。
- Engine只看到opaque occurrenceId。完整OccurrencePath、sourceNodeId、definitionId/bindingId回接及`resourceId → occurrence/property`映射只存在于容量受限的请求级诊断sidecar；操作结束后销毁，不进入文档、identity、cache、普通日志或长期历史。
- Image必填`imageResourceId`，Text每个Run必填`fontResourceId`；不内联URL、descriptor或资源对象。每个实际property occurrence使用自己的唯一resourceId，即使选择相同Asset、content或bytes也不共享。
- `resources[]`按runtime resolve encounter order保存。每项继续精确为票据13的closed`resourceId/kind/fetchUrl/expiresAt/sha256/mediaType/byteLength/acceptanceProfileId/technicalDescriptor`；不含assetId、contentVersion、ownerScope或Template identity。
- tree资源引用与manifest entry必须严格一对一双射：每个引用及entry各出现一次。missing、unused、duplicate、collision、kind mismatch或集合/次数不一致均使seal失败，禁止合并或保留预取资源。

### 8. Seal、空画布与失败清理

- 全部求值与Asset解析成功后执行一次原子seal：验证唯一Canvas root、closed kind/ContentModel、default展开、量化值、occurrenceId连续唯一、tree/manifest双射、RenderResource/lease、动态与业务身份零残留，以及全部容量限制；最后才产生不可变canonical bytes与digest。
- 在seal发现本应由Evaluator排除的结构残留或不变量破坏属于内部lowering合同违约；普通运行时数值或容量错误必须在进入seal前使用所属领域code失败。seal前任何builder内容都不能发送Engine。
- 空Canvas合法：当children原本为空或全部被合法剪枝时，文档仍包含root Canvas、显式background/bleed、空children与空resources，Engine输出对应背景或透明图片；不插入placeholder。
- seal前失败时丢弃builder、未封存bytes与完整sidecar，绝不调用Engine。已线性化CapabilityState和AssetResolver记录保留到固定expiry以支持unknown-commit安全性，不重采样、不删除、不续签，也不形成partial digest/document/history；只保留允许的聚合计数、稳定code与耗时。

### 9. Canonical RenderDocument 与传输摘要

- exact canonical profile为`renderweave-render-c14n/1.0`：object member按member name UTF-8字节词典序；string复用Design canonical escaping/Unicode规则；integer最短ASCII十进制；decimal为最多六位小数的plain canonical JSON number，禁止exponent、leading plus、无意义尾零、`-0`与非有限值。
- `children/runs/points/commands/tracks/resources`及所有语义数组保持既定顺序，不做set sorting。canonical writer不修复、补猜或删除非法值。
- `renderDocumentDigest = "sha256:" + lowercaseHex(SHA-256(UTF8("renderweave-render-document/1\0") || canonicalRenderDocumentBytes))`。它覆盖包括fetchUrl/expiresAt在内的完整交接字节，由Renderer Command携带并由Engine核对。
- renderDocumentDigest只保护本次Java→Engine传输完整性，不进入Evaluation identity、cache、日志、公共响应或持久化。同一活跃renderRequestId使用相同closure/input/CapabilityState及已线性化ResolvedAsset/lease重建时，必须产生byte-identical文档与digest；新请求可因新lease产生不同bytes/digest。

### 10. closure、输入、恢复与成功身份

- 本节identity object复用`renderweave-design-c14n/1.0`的UTF-8 member ordering、string/Unicode、integer与任意精度decimal primitive规则，但不执行DesignDSL metadata normalization或set推断；只有下述明确指定的数组排序，其他数组保持语义顺序。所有object均closed且拒绝unknown/null。
- `closureDigest = "sha256:" + lowercaseHex(SHA-256(UTF8("renderweave-template-closure/1\0") || canonicalClosureManifest))`。manifest顶层绑定ownerScope与root`{templateId,revision}`；`snapshots[]`每个unique snapshot携带`templateId/revision/staticSchemaRef/contentHash/dslVersion/expressionProfile`并按templateId UTF-8排序；`edges[]`每条authored use携带`parentTemplateId/parentRevision/useId/childTemplateId/childRevision`并按parentTemplateId、parentRevision、useId排序。静态不可达edge仍进入，diamond snapshot只列一次；readiness、display name及数据库row version排除。
- `admittedInputDigest = "sha256:" + lowercaseHex(SHA-256(UTF8("renderweave-admitted-input/1\0") || canonicalTypedInput))`。canonicalTypedInput精确为closed`{staticSchemaRef,rootDocument,customValues}`：rootDocument只含Schema声明且PRESENT的typed semantic字段；customValues每项为`{definitionId,value}`并按definitionId排序；普通数组保持输入顺序。ValueType由绑定的StaticSchema/Template contracts唯一确定，decimal/date/time/color/AssetRef使用各自canonical value wire而不重复自报type。optional ABSENT省略；未知字段、duplicate override loser、unknown/PRIVATE override原值、原数字拼写/member顺序及admission proof排除。
- pre-execution `evaluationFingerprint`精确为对closed`{ownerScope,authorizationContextDigest,closureDigest,admittedInputDigest,renderDslVersion,layoutProfile,capabilityContracts,assetAcceptanceProfile,effectiveBudgetVector}`作`renderweave-evaluation-fingerprint/1`domain-separated SHA-256。authorizationContextDigest绑定authenticated subject与相关授权决定而不含raw token；contract列表canonical排序，budget写实际完整数值向量。
- renderRequestId是Capability/Resolver短期记录的外层key，不进入fingerprint。Clock/nonce/result、Asset current/lease、DPI/output、deadline绝对时刻及网络时序同样排除。fingerprint只用于同请求恢复与冲突检查，不公开或记录；本节全部SHA-256 wire统一为`sha256:`加64位lowercase hex。
- 只有RenderDocument成功seal后才形成`evaluationResultDigest`：对closed`{ownerScope,closureDigest,admittedInputDigest,renderDslVersion,layoutProfile,assetAcceptanceProfile,capabilityResultDigest,assetSelectionDigest}`作`renderweave-evaluation-result/1`domain-separated SHA-256。它排除subject、requestId、budget、lease、renderDocumentDigest及Renderer output参数。
- `renderRequestId`、`evaluationFingerprint`、`evaluationResultDigest`、`renderDocumentDigest`与Template contentHash是五个不可互换的概念。未来Output cache只能在完整Evaluation后以result digest再组合exact Renderer/Output Profile，并独立执行授权与scope partition。

### 11. Renderer Command 与 exact 重发

- 票据16已将有效Renderer Command冻结为closed `contractVersion/requestId/rendererProfile/deadlineAt/renderDocumentDigest/document/output/diagnostics`；首版每条Command恰好输出根Canvas的一张完整PNG或JPEG。PNG output精确为`{profile,dpi}`，JPEG再含`quality`；省略DPI/quality在构造前分别展开为96/90。Command、RenderDocument与Layout/Renderer/Output Profile各自exact，禁止Template/Evaluation/Capability/Asset业务身份进入。
- `renderweave-render-command-c14n/1.0`的canonical effective Command及其domain-separated `rendererCommandDigest`唯一决定同一Engine requestId的join/replay/conflict/cancel。Engine transport或结果状态不明时，只能在原absolute deadline和全部lease有效条件下重发同requestId、同canonical Command；不得重新seal、续签lease、重新resolve current、重建CapabilityState或延长deadline。registry state丢失必须失败，新请求重新Evaluation。
- Engine先验证内部认证、Command/profile、request registry、strict document与renderDocumentDigest，再依票据16固定顺序准备资源和layout；不能读取调用方原始Render请求、Template current、DesignDSL或诊断sidecar。cancel、active registry、atomic output seal与请求瞬态结果不改变本票据的Evaluation identity。

### 12. 容量与重试计数维度

- Ticket15冻结容量轴，票据19填写exact数值：closure的unique snapshot/edge/depth/retry/canonical bytes；admission的typed values/Asset atoms/dependency calls/problem bytes；runtime的operations/Definition/frame/item/invocation/occurrence/depth/synthetic node/generated track-cell；resource的actual resolve/manifest entry/unique exact content、按occurrence与unique content的声明bytes及manifest/URL bytes；document的node/children/run/text/point/command/track/string/depth/canonical bytes；diagnostic sidecar/problem/trace条目与字节。
- 动态计数必须在创建frame/node/resource、分配大对象或外部调用前原子预留；超限立即失败，不截断、不跳项。相同逻辑位置在同一Evaluation只计一次语义单位；内部重试另行消耗attempt、physical-operation与deadline预算且不得重置累计值。新renderRequestId才有新预算。
- 静态可证明超限在CapabilityState前失败；动态超限返回所属稳定budget code并保证零RenderDocument。内存耗尽、线程调度或下游默认限制不能替代合同预算。

### 13. 错误、诊断与权限

- 保留最具体的所属领域code并增加closed stage：`REQUEST_ADMISSION | TEMPLATE_CLOSURE | INPUT_ADMISSION | ASSET_ADMISSION | CAPABILITY_STATE | MATERIALIZATION | ASSET_RESOLUTION | DOCUMENT_SEAL | ENGINE`。Template dependency、Expression、Capability、AssetResolver、layout/resource Engine错误不统一改写为含糊`EVALUATION_FAILED`。contentHash/Profile兼容回归、malformed sealed document或Engine再次发现manifest不变量等内部违约，对外折叠为`RENDER_INTERNAL_ERROR`并产生脱敏运维告警。
- 公共problem基础形态为`{code,stage,safeLocation,parameters}`。Admission可在权限范围内定位Template/revision/canonical DesignDSL pointer；runtime/Engine首先只返回opaque occurrenceId/resourceId与安全property identity。
- Rendering只为拥有相应Template read权限的诊断调用者把sidecar投影为definitionId、bindingId、sourceNodeId与逐段授权Invocation/OccurrencePath；无权child segment必须redact。Asset identity继续依票据13的asset.read规则附加。
- problem、log、trace和metrics不得回显业务值、完整文本、原始输入、Expression/Capability结果、Asset URL/token/hash/bytes、完整child path、stack或provider/raw cause。metrics只使用stage/code/profile/status等有界label。

### 14. Conformance 与明确排除

- 实施前必须让Java Evaluator/sealer与Rust parser/validator独立重放同一corpus：覆盖全部static kind/default/lowering edge、canonical scalar与document digest vectors、结构展开与occurrenceId、剪枝/空树/visible/opacity、Binding/lazy/first-error、量化、manifest/descriptor/碰撞、动态及业务身份零残留、malformed/profile mismatch，以及合法final-geometry失败与内部文档违约的分类。
- 同一机器可读RenderNodeContract和字节向量是跨语言authority；同名Java/Rust类、浏览器截图、旧MaterializedScene或单边单元测试都不能替代。
- v1明确排除正式MaterializedScene、持久/序列化中间IR、Java预布局/final geometry；partial/streaming/带错误RenderDocument、断点续跑、跨请求文档复用；public RenderDSL上传下载、Workspace/Artifact保存、长期sidecar；宽松JSON、unknown/null、properties/metadata bag、插件kind；caller协商`latest`、旧Haibo静默降级；Engine回读Template/Resolver/Capability或动态callback；失败截断、资源跳过、placeholder、默认字体及partial output。
- 本票据只冻结探索规格、术语及下游实施约束，不创建Evaluator、builder/sealer、RenderDSL model、Renderer client、API、表、路由、缓存、生产Engine适配或占位实现。
