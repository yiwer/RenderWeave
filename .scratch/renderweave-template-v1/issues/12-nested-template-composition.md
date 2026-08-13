# 定义嵌套 Template 组合语义

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 04, 06, 07, 09, 10, 11

## Question

父 Template 默认引用子 Template current revision 时，引用身份、DAG/循环防护、参数填充、数据/系统作用域继承、布局/裁剪、Asset 与 capability 传播、更新可见性和求值失败应如何定义？

## Inherited constraints

- “默认 current”已收紧为唯一 authored 模式：TemplateRef 只保存 templateId，不允许 exact revision selector。
- TemplateRef 图必须是 DAG；cycle 是不可确认 hard error。ACTIVE current projection 用于 incoming delete blocker 和子变更向父递归重检。
- 子 missing、DELETED 或 INVALID 是可确认保存父 INVALID 的依赖 ERROR，但不能成功 Render。
- Evaluation 必须生成一致 Template closure snapshot；闭包收集期间 current 漂移只能重试或失败，不能混合多个时刻。
- 每个 TemplateUse 创建隔离 child invocation frame；child 不继承父 RootDocument handle、Custom map、definitions、loop frame、siblings 或通用 system map。
- child context 必须由显式 selector 取得 exact-StaticSchema typed context：相同 invocation context、reference-typed subtree/loop item、scalar loop item 对应的 `system-basic-*@v1`，或仅对 `system-empty@v1` 的显式 empty context；禁止 shape-based object 注入与重新 JSON 绑定。
- selector 指向可选且运行时 ABSENT 的 context 时，本票据必须在 `ERROR/SKIP` 中定义显式策略；不得隐式选择。
- child Custom 输入只能通过按其当前 PUBLIC definitionId 的显式 fill；fill 在父 TemplateUse 所在词法域、每个 loop item 独立求值并复制 typed value，省略或 ABSENT 使用 child default。
- duplicate fill target 是不可确认的结构 hard error；child definition missing/PRIVATE 或类型不兼容是父 Template 依赖 ERROR，child current 的相关变化必须使父递归重检。外部根 customValues 对 unknown/PRIVATE 的静默忽略不能套用于 authored fill。
- child PRIVATE CustomDefinition 始终使用自身默认值。PUBLIC/PRIVATE 只约束 invocation 边界，不能建立自动同 ID/同名继承。
- child fill 使用父 invocation 中合法的 typed ValueSource，但不能直接使用 literal/capability；literal 应成为 child default 或 authored static value，capability 只能作为父 ExpressionDefinition 的显式 input。
- fill source 与 child PUBLIC CustomDefinition 必须精确同型；省略 fill 或合法 ABSENT 使用 child default，ERROR、错误类型与无效动态 AssetRef 不得回退 default。
- 父/子各自拥有隔离 definitions 与 node-local bindings；Expression input alias、definitionId、bindingId、targetPropertyRef 均不能跨 Template invocation 引用或合并。
- 父侧任何 node property Binding 仍只覆盖该父 Node 已存在静态 baseline；TemplateUse 的哪些属性可绑定由全局追加式 BindingPolicyCatalog 决定，而不是由 child Template 声明。
- TemplateUse 使用独立 client-generated canonical UUID v4 useId；父 Template local IDs 不与 child revision 的同名 namespaces 合并，runtime occurrence identity 必须由 closure/invocation path 另行形成。
- TemplateUse fills 是无语义集合并在 Canonical DesignDSL 中按 child definitionId 排序；任何具有 authored composition order 的 use/host/child arrays 必须由本票据明确保序 authority。
- authored TemplateRef `{templateId}` 进入父 Design content hash，但解析到的 child current revision/hash 不进入；child 漂移只影响 dependency/readiness/closure snapshot，不修改父 revision/contentHash。
- 每个 child revision 按自身 exact dslVersion/expressionProfile 永久解释；closure 不自动 migration，也不能因父版本较新而重写 child DesignDSL。
- 票据 09 的首批 visual kind 不含 TemplateUse；本票据必须为 TemplateUse 定义独立 exact structural NodeKind、ContentModel、placement/children boundary 与永久 Property Identity，并在最终 DesignDSL 1.0 合同冻结前并入同一个 NodeContractCatalog，不能伪装成 Frame、Slot 或可绑定 TemplateRef property bag。
- 父侧 TemplateUse 的 BindingPolicy 仍只能追加授权其已存在的 scalar/atomic property leaf；templateId、fills、child topology、placement discriminator 与 array structure 不得通过普通 Binding 动态改写。
- TemplateUse 必须在进入 `renderweave-layout/1.0` 前完全展开为单一有序 static Node occurrence tree；RenderDocument 不保留 TemplateUse 或跨 Template layout callback。父子边界如何贡献一个 placement box、如何处理 child Canvas/bleed 及 authored composition order 必须在本票据显式冻结，不能让 Engine 猜测或把 child 当独立网页 viewport。
- 本票据必须定义由 closure invocation path、useId、loop path 与 source nodeId 组成的请求级 occurrence identity，并保证同一 materialized order 同时驱动 layout/paint/error/trace。不同 child dslVersion 必须经永久 compatibility table 降低到 RenderDocument 的一个 exact Layout Profile，不兼容时整次 Evaluation 失败且不得 fallback。
- Repeat 永远不保存 `templateId`、`itemTemplate` 或按 item StaticSchema 自动推断 Template；其 `children[]` 就是 item subtree。需要循环嵌套子 Template 时，作者在该 subtree 中显式放置一个或多个 TemplateUse，各自独立选择目标 templateId。
- Repeat descendant 的 TemplateUse 可显式选择当前 Loop frame 中 exact reference-typed item 作为 child context；scalar item形成精确 `system-basic-*@v1` StaticSchema context，可传给永久绑定同一系统 StaticSchema 的child，也可通过`/index`、`/value`驱动父侧property/fill。绝不能按业务StaticSchema shape或runtime value猜测兼容。
- TemplateUse 作为 Repeat direct child 时必须使用票据 11 的 PACK placement；其 invocation occurrence path 必须在全部外层 `{loopId,inputIndex}` segments 后追加 useId，再进入 child source-node path。子调用仍是隔离 invocation frame，不继承任何父 Loop frame。
- Repeat/Conditional 与 TemplateUse 的展开必须保持 authored child order、原输入 index 与 invocation order；完整 authored Template closure 已在求值前冻结，票据 11 的结构剪枝只决定是否实际invoke TemplateUse。未surviving item或false branch不建立child frame、不求fills，也不解析其运行时Asset或capability。

## Answer

### 1. TemplateUse 是 exact structural Node

- authored kind 精确为 `templateUse`。它是同一全局 NodeContractCatalog 中的结构 Design Node，不是Frame、Slot、sidecar composition表或Renderer callback；只能位于Canvas/Frame/Group/Stack/Grid/Repeat/Conditional等合法父ContentModel的children中，不能作为DesignRoot。
- exact wire必填`kind/nodeId/useId/templateRef/contextSelector/fills/placement/bindings`，可选既有`displayName/render/visible/opacity/transform`。`templateRef`精确为`{"templateId":"..."}`并只跟随目标current；`nodeId/useId`分别在所属Template namespace内唯一，均由authoring client生成canonical lowercase UUID v4。
- TemplateUse禁止`children`、fill/stroke/corner/padding/clip、fit/alignment及任何child override字段；全部object/union closed，unknown member/kind与JSON null失败。`bindings`与`fills`即使为空也必须存在。`displayName`只用于作者识别并进入content hash，不求值且不可Binding。
- 每个实际TemplateUse occurrence精确创建一个隔离child invocation。相同target snapshot可以共享闭包事实，但frame、fill求值、capability/Asset occurrence、OccurrencePath与materialized subtree永不共享或memoize。

### 2. 系统 StaticSchema 与 scalar Repeat context

- `system-empty@v1`以及`system-basic-text/decimal/date/time/boolean@v1`是正式Schema规格已定义的一等只读StaticSchema，使用普通`{schemaKey,versionTag}`身份、可展示/选择/引用且没有Draft；不是Evaluator私有Context Contract，也不改变StaticSchema定义或不可变规则。
- 五种basic Schema均拥有必填`index: decimal(min=0,multipleOf=1)`与对应类型的必填`value`。Evaluator为每个scalar Repeat input item构造符合exact系统StaticSchema的不可变typed context；`/index`与`loopIndex` ValueSource都表示该item在原输入collection中的零基index，剪枝与packing不会重编号。
- 绑定对应system-basic StaticSchema的普通Template可由TemplateUse直接接收整个scalar item context。reference item继续只携带其业务StaticSchema，绝不动态注入index；若child需要index，父TemplateUse通过PUBLIC CustomDefinition fill显式传入。

### 3. ContextSelector 与 ABSENT policy

- `contextSelector`是closed union。普通context精确为`{kind:"context",domain:{kind:"invocation"},pointer}`或`{kind:"context",domain:{kind:"loop",loopId},pointer}`；显式empty精确为`{kind:"empty"}`且只可用于永久绑定`system-empty@v1`的child。
- context selector允许空pointer选择所选domain的整个typed context；非空pointer必须是正确RFC 6901 escaping、静态解析到reference-typed object context的路径。禁止scalar leaf、array、数字下标、wildcard、任意JSON object、shape inference或重新JSON验证。结果exact StaticSchemaRef必须等于child永久StaticSchemaRef。
- 普通context selector必填`contextAbsentPolicy: "ERROR" | "SKIP"`；empty selector禁止该member。ERROR使合法optional context的runtime typed ABSENT终止本次Evaluation；SKIP完全移除该TemplateUse occurrence，不占父layout/gap，也不求placement/common、fills、child、Asset或capability。显式empty永远产生具体`system-empty@v1` context。
- null、ValueSource ERROR、非法pointer、Schema中不存在的path、错误类型或StaticSchema不匹配都不是ABSENT，不能由SKIP掩盖。保存时仍完整验证所有selector，即使render或ancestor结构静态剪枝。

### 4. Custom fill 调用合同

- `fills[]`每项精确为`{targetDefinitionId,source}`；target在单个TemplateUse内唯一，数组顺序无语义并按targetDefinitionId canonical排序。target必须是child current中的PUBLIC CustomDefinition且source静态类型精确相同。
- source在父TemplateUse所在lexical domain求值，只允许普通fill已冻结的context、loopIndex或definition source；不能直接使用literal或capability。父侧Definition可以把capability作为显式Expression input后产生fill值，但TemplateUse本身没有能力入口。
- 省略某PUBLIC target的fill或合法fill source得到ABSENT时使用child声明的literal default；ERROR、错误类型、无效动态AssetRef或budget failure绝不回退。PRIVATE definition始终只使用child自己的default。
- target消失、转PRIVATE或类型变化使父形成dependency ERROR并经反向索引递归重检；duplicate target与非法source wire是hard error。child frame在所有PUBLIC fill/default及PRIVATE default冻结后才允许求child definitions/bindings；child永远不能回读父frame。

### 5. 完整 closure 与稳定求值顺序

- 每次Render先从全部authored可达TemplateRef冻结一致Template closure snapshot；它包含静态不可达分支的ref。snapshot按templateId去重并钉死每个current的exact revision/contentHash，每条authored TemplateUse仍是独立closure edge。收集期间任一current漂移只能有界重试或失败，禁止混合时刻。
- 完整closure负责same-scope、存在/ACTIVE/readiness、DAG、Profile compatibility与integrity；之后结构惰性只决定是否实际建立invocation。`render:false`、false Conditional、零项/零surviving Repeat或SKIP都不能隐藏无效closure、解除incoming delete blocker或绕过静态验证。
- 每个实际TemplateUse occurrence的固定consumer order为：`render → contextSelector/contextAbsentPolicy → placement → visible → opacity → transform → fills(targetDefinitionId order) → child frame → child DesignRoot DFS`。composite leaf继续按NodeContract声明顺序，不按JSON member或bindingId决定first error。
- render:false立即剪枝且不求selector、placement/common、fills或child；SKIP在selector后剪枝且不求其后项目。visible:false/opacity:0不是剪枝，仍完整求fill/child、解析实际Asset并保留全部失败。first demanded runtime ERROR fail-fast，整次Evaluation零RenderDocument。

### 6. ownerScope、授权、Asset 与 capability

- Template聚合拥有创建后不可变的ownerScope；TemplateRef只允许目标Template与父Template同scope。v1 Template copy只可在原ownerScope创建新Template；跨scope迁移必须是未来显式export/import加TemplateRef/AssetRef映射，不自动深复制closure，也不允许确认保存跨scope引用。
- 调用者获准Render根Template后，Rendering使用内部能力读取完整same-scope closure与child authored Asset，不逐个要求调用者拥有child Template/Asset read权限；目录、编辑、草稿preview与下载仍遵守各自read授权。每个实际child Node property Asset消费位置继续生成独立resourceId并解析当时Asset current，不因共享snapshot、相同assetId/exact bytes或Definition memoization合并；只有exact bytes cache可在票据13的scope/Profile边界复用。
- 同一请求由各Template exact Expression Profile确定的Evaluation Capability合同传播到全部实际child invocation，但child Expression仍必须显式声明capability input。TemplateUse不能新增、替换、屏蔽或保存allowlist；全部invocation共享CapabilityState中实际建立的UTC Clock snapshot和/或server-only Random nonce及请求级预算，而root/use/loop declaration frame使每个CapabilityCallPosition独立。能力在RenderDocument前完全消除。

### 7. Parent ContentModel 与 host LayoutBox

- TemplateUse由父ContentModel选择ABSOLUTE/STACK/GRID或仅Repeat direct child使用的PACK placement。ABSOLUTE/STACK/GRID允许FIXED/HUG_CONTENT/FILL，PACK只允许FIXED/HUG_CONTENT；既有non-Group min/max、margin、cell/alignment与definite-FILL/cycle规则照常适用。
- HUG自然尺寸是child Canvas trim的原始`widthMm × heightMm`，随后受既有min/max clamp；FIXED/FILL由父布局决定host LayoutBox。任何host尺寸都不反馈改变child Canvas、Stack/Grid/Text的约束或reflow。
- child subtree先在原始trim mm约束内完成layout/shaping/内层composition，再强制裁到source trim；随后整个source artboard（background、geometry、font、stroke与nested viewport）等比CONTAIN并双轴居中映射到host，最后再次强制裁到host。
- child Canvas concrete background只填充source trim并先于其children绘制；bleed在嵌套时不参与size、paint或clip。未被CONTAIN内容覆盖的letterbox区域透明，TemplateUse没有self paint。visible/opacity/transform只在host层作用一次；整个child是parent authored child order中的连续subtree，不能与parent siblings交错paint。

### 8. 静态 compositionViewport lowering

- TemplateUse与child Canvas必须在Evaluator中完全消除。Evaluator把已经展开、无ValueSource/TemplateRef/fill/invocation callback的child subtree内联进RenderDocument的静态`compositionViewport`布局原语，并携带source trim size、concrete background与固定`CONTAIN + CENTER + source/host clip`规则。
- compositionViewport类似Stack/Grid，是RenderDocument/Layout Profile的静态布局能力：RenderEngine在知道host LayoutBox后，先按source trim约束递归layout child，再计算contain scale/offset；Evaluator不预布局、不写final coordinate。nested TemplateUse递归产生nested static viewport，不触发运行时Template读取。
- 现有busbox RenderDSL尚没有该原语，未来实现必须扩展并通过Layout Profile conformance；不得退化为保留TemplateUse、Evaluator预布局、child栅格图片或Engine callback。本阶段只冻结规格，不实现占位kind/API。

### 9. Profile compatibility

- 每个TemplateSnapshot始终按自身exact dslVersion/expressionProfile解析和求值。根Template的exact DesignDSL Profile经永久只追加compatibility table确定唯一exact目标Layout Profile；调用方不能选择、协商、升级或要求latest/fallback。
- closure内每个child Profile必须拥有到根所选Layout Profile的永久、无损lowering edge，且该Profile必须定义compositionViewport。任一缺失在Evaluation前失败，不迁移revision、不修改child、不混用subtree Layout Profile，也不产生partial RenderDocument。

### 10. InvocationPath 与 OccurrencePath

- 请求级路径按执行嵌套顺序由closed segments组成：root invocation含templateId/resolvedRevision；每层Repeat追加loopId/inputIndex；实际TemplateUse追加useId/childTemplateId/resolvedRevision；最终node segment携带local nodeId与role。外层Repeat必定位于use segment前，child内Repeat位于其后。
- compositionViewport关联源TemplateUse nodeId并使用`template-use-viewport` role；child Canvas background关联child Canvas nodeId与`canvas-background` role；repeat-container/repeat-item/conditional-frame继续使用票据11角色。合成节点不生成UUID。
- skipped TemplateUse没有invocation/viewport/descendant occurrence。同一use在不同inputIndex下是不同occurrence；shared child也由use/path区分。RenderDocument只携带opaque occurrenceId；Rendering请求级sidecar以同一OccurrencePath及可选局部sourceNodeId回接problem、paint与LayoutTrace，Engine不接收Template身份。
- resolved revision只用于本次请求准确定位；路径不含context/fill/item值，不是authored identity、业务key、revision事实或跨请求稳定地址，也不进入DesignDSL/contentHash或单独充当cache identity。

### 11. Validation、copy/reparent 与 lifecycle

- unknown/null/非法wire、duplicate nodeId/useId/fill target、非法children/placement/Binding target、selector语法/词法越界与TemplateRef cycle是不可确认hard error。well-formed child missing/DELETED/INVALID、PUBLIC target消失/PRIVATE/type漂移、合法context path不存在、StaticSchema不匹配或closure无共同Layout Profile是dependency ERROR，可经二阶段确认保存INVALID；INVALID/STALE不能Render。
- 合法selector运行时ABSENT且ERROR、fill/child runtime ERROR、动态Asset失败及capability/容量超限只使当前请求失败，不自动改变readiness。problem分类不因静态不可达分支而放宽。
- 同Template复制TemplateUse必须为副本nodeId/useId及bindingId生成新UUID并原子改写内部引用；templateRef与child targetDefinitionId保持。父context/fill source仅在definition/loopId仍词法可达时保留，否则client必须复制/重定向或拒绝。reparent必须显式转换ABSOLUTE/STACK/GRID/PACK；server不猜scope/placement/domain或修ID。whole Template copy/restore继续保留local IDs。
- child current save与DAG检查原子执行；会形成cycle则child save零写。合法current变化使全部incoming ACTIVE parents先STALE再递归重检，最终READY/INVALID；父revision/contentHash不变。引用即使在render:false/不可达branch中仍传播失效并阻止child删除。

### 12. Canonical、dependency projection、capacity 与 diagnostics

- TemplateUse全部authored字段、common、所在parent children位置与logical TemplateRef进入Canonical DesignDSL/contentHash；fills按targetDefinitionId、bindings按bindingId排序，parent children保持layout/paint顺序。resolved child current/closure order、runtime context/fill、OccurrencePath、compositionViewport、contain结果与展开subtree不进入父hash。
- ACTIVE current dependency projection为每个authoredTemplateUse occurrence保留logical templateId与稳定父侧定位；查询可聚合target，但不能丢失具体use或把projection变成第二事实源。全部authored edges参加DAG/readiness/delete blocker。
- Ticket19必须分别限制unique closure snapshot、authored edge、closure DAG depth、实际invocation occurrence/depth、compositionViewport、expanded materialized node/paint item。SKIP/结构剪枝仍消耗closure edge/snapshot/depth，但不消耗invocation/viewport/materialized/capability/Asset执行预算；shared snapshot计一次，实际调用逐次计数。
- 父依赖problem定位nodeId/useId/canonical pointer/templateId及必要targetDefinitionId/context path；child runtime problem使用有界Invocation/OccurrencePath和child local node/definition/binding/property identity。不得回显context值、fill结果、完整文本、child DesignDSL或Asset bytes；跨权限细节可redact但保留父use定位，达到上限明确`PROBLEM_LIMIT_REACHED`。

### 13. v1 明确排除项

- v1不支持authored exact/latest/dynamic Template selector、Schema-to-Template推断、cross-scope ref/deep copy、children/fallback Template/fallback subtree、Slot/content projection、caller child-style override、自动同名Custom继承、父frame回读、双向值/事件/callback、per-use capability allowlist、可配置CONTAIN/COVER/STRETCH/alignment/clip、child bleed、host-driven child reflow、invocation result memoization、child独立输出或多Canvas合并。
- 未来若需要上述能力，必须以新DesignDSL/Layout Profile和明确migration引入；v1不保存nullable placeholder或parsed-but-unimplemented字段。
