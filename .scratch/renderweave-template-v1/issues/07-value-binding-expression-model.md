# 选择 Value Source、Binding、Mapping 与 Expression 模型

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 02, 03, 06

## Question

DesignDSL 应如何表示 literal、字段路径、参数、循环 scope、命名 definition、mapping 与 expression；Binding 如何定位有类型的节点属性；静态值与动态值如何互斥；缺失、null、类型错误和 capability 调用如何传播？

## Inherited constraints

- Evaluator 只能读取已准入的 closed typed context 与有效 Custom map；RootDocument 未声明字段和原始 JSON 永远不进入 ValueSource/Expression。
- 合法 StaticSchema path 遇到可选缺失产生 typed ABSENT，null 已在 RootDocument/Custom 准入时拒绝；不存在的 Schema path 是 Template 依赖 ERROR，不是 missing 值。
- CustomDefinition 固定在 invocation domain，具有必填 typed literal 默认值与 PUBLIC/PRIVATE exposure；根 override 只作用于 PUBLIC，child 只经显式 fill，Custom 永远有具体值而没有 ABSENT。
- 每个 persisted context source 和非 Custom Computed Definition 必须显式选择 invocation 或稳定 loopId；不能引入消费位置相关的 `$current/$parent/$root`、动态作用域或跨 Template definition 读取。
- definitions 数组顺序没有求值含义；definition 引用按 Template 内唯一 definitionId 建图，词法越界与 dependency cycle 是不可确认 hard error。
- Clock、Random、AssetResolver 都不是可浏览 system map；Expression 只能经版本化 Evaluation Capabilities 显式输入使用 Clock/Random，AssetResolver 则只按实际 asset occurrence 的独立内部合同运行。

## Answer

### 1. 类型系统

- `BaseValueType` 精确为 `text | decimal | boolean | date | time | color | imageRef | fontRef`；`enum<catalogId>` 与 `list<T>` 是派生 `ValueType`，不是基础类型。
- StaticSchema 不随 Template 扩展：其事实类型仍是五种 scalar、reference 与 array；不向 StaticSchema 增加 color/image/font，也不新增对应 `system-basic-*` Schema。
- `list<T>` v1 只允许五种 StaticSchema scalar item，保持顺序、重复与空数组；禁止 null、异构、嵌套 list。reference array 是 loop-only 的精确 Schema collection，不伪装成通用 `list<object>`。
- 类型兼容严格且不做隐式转换：enum 必须同 catalog，list invariant，imageRef/fontRef 不互换，StaticSchema constraint 也不形成隐式 subtype。
- decimal 使用任意精度 BigDecimal 数值语义，`1.0 == 1.00`、`-0 == 0`，scale 与尾零不可观察；date/time 分别使用 `YYYY-MM-DD`/`HH:mm:ss`，color 规范为大写 `#RRGGBBAA`，imageRef/fontRef 值均为封闭 `{assetId}` 且由类型区分。
- enum token 大小写敏感；enum catalog 由全局 Node 属性模型不可变拥有，v1 不允许 Template 自定义 catalog。Expression 1.0 对 color、asset ref、enum、list 只能显式输入后选择或透传，不能自行构造。

### 2. Definition 与 ValueSource

- DesignDSL 顶层 `definitions[]` 是精确封闭联合 `custom | mapping | expression`；`Computed Definition` 只是 MappingDefinition 与 ExpressionDefinition 的统称，不是 wire kind，也没有 inline expression/mapping。
- 所有 Definition 具有 Template 内唯一 opaque `definitionId` 与必填、非唯一 `displayName`。CustomDefinition 沿用 invocation domain、必填 typed literal default 与 PUBLIC/PRIVATE；MappingDefinition/ExpressionDefinition 显式声明 invocation 或稳定 loopId domain 及 output type。
- ValueSource 是精确封闭联合：
  - `{kind:"literal", valueType, value}`；
  - `{kind:"context", domain, pointer}`；
  - `{kind:"loopIndex", loopId}`；
  - `{kind:"definition", definitionId}`；
  - `{kind:"capability", ...closedPayload}`。
- literal、Custom default、Mapping operand 共用一个权威类型 decoder；null、quoted decimal、嵌套/异构 list、错误 asset shape 都是 hard error。
- context domain 只能是 invocation 或 `{kind:"loop", loopId}`；pointer 是非空、正确 RFC 6901 escaping 的 StaticSchema field path，不能取得 whole object、用数字/wildcard 穿越 array 或读取原始 JSON/unknown field。
- `loopIndex` 返回零基、非负、整数值 decimal；item 值通过该 loop domain 的 typed context 读取，v1 没有 loop key ValueSource。
- Capability ValueSource 只可用作 Expression 显式输入，不能直接用作 Binding、Mapping input/result、Custom default、child fill 或静态属性。普通属性 Binding 只允许 context、loopIndex 或 definition source；literal 应写成 DesignDSL 静态值。
- Context source 可以是 `MAY_BE_ABSENT`；Computed Definition output 与普通 Binding source 必须在静态分析后为 `CONCRETE`。合法 optional missing 是 typed ABSENT，必须通过 `exists`、`coalesce`、Mapping otherwise 或后续结构节点的显式 missing policy totalize；ERROR 不是 ABSENT。

### 3. Definition graph 与词法规则

- Definition 可以前向引用，但 graph 必须无环、引用存在且 domain 合法；Definitions、Expression inputs 与 node-local bindings 的数组顺序都没有求值含义，只有 Mapping cases 等显式序列有顺序语义。
- 非 Custom Definition 只能读取自身或词法祖先 domain，并只能由自身或后代 domain 消费；不能把 loop 值带到 parent、sibling、child Template，移动消费节点可使原 Binding 词法越界。
- 所有 Definition 即使未被消费也在保存/重检时完成语法、类型、domain、cycle 与依赖检查；未使用 Definition 只产生非阻塞 editor hint。
- Mapping/Expression Definition 在声明 frame 惰性求值并 memoize：invocation-domain 每 invocation 最多一次，loop-domain 每实际 loop frame 最多一次。Binding 只在其 consumer materialize 时求值。

### 4. MappingDefinition

- wire 固定为 `kind/definitionId/displayName/domain/output/input/cases/otherwise`；input 是单个 ValueSource，cases 至少一个、没有 caseId、顺序即 first-match 语义，otherwise 必填且全部结果 source 与 output 精确兼容。
- case 使用封闭 operator 与可选 typed literal operand；v1 operator 为：`IS_ABSENT`、`IS_PRESENT`、`EQ`、`NOT_EQ`，decimal/date/time 的 `GT/GTE/LT/LTE`，text 的 `CONTAINS/STARTS_WITH/ENDS_WITH/PATTERN_MATCH/IS_BLANK/IS_NOT_BLANK`，以及 scalar list 的 `CONTAINS`。
- 输入 ABSENT 时只匹配 `IS_ABSENT`；包括 `NOT_EQ`、`IS_NOT_BLANK` 在内的所有 value operator 都不匹配。ERROR 直接传播。
- `PATTERN_MATCH` 只接受 literal pattern，复用 StaticSchema 的 Java/ECMAScript 安全交集、substring 语义与 1024 Unicode code-point 上限；Expression 1.0 仍不提供 regex。
- blank 使用冻结的 Unicode White_Space 集合：U+0009–000D、0020、0085、00A0、1680、2000–200A、2028、2029、202F、205F、3000。其余 text operator 精确比较 Unicode sequence，不做 normalization、case folding 或 locale 处理。
- case 重叠合法且只给 editor hint；复杂条件应改用 ExpressionDefinition。

### 5. Expression Profile 1.0

- wire 语言是 RenderWeave 自有 `renderweave-expression/1.0` 文本语言。RenderWeave 自己定义 grammar、类型、BigDecimal、ABSENT、标准函数、求值顺序与预算；Java Evaluator 是唯一语义权威，CEL 只能作为通过 conformance corpus 的可替换内部实现候选。
- 每份 DesignDSL 始终携带一个顶层 `expressionProfile`，即使没有 Expression；ExpressionDefinition 只保存 definitionId/displayName/domain/output/inputs/source，不重复 profile。一个 revision 不能混用 profile，未来升级必须显式迁移并追加新 Template revision，禁止自动升级或 fallback。
- source 是 exact UTF-8 JSON string，可多行、无注释、大小写敏感；whitespace/newline 不归一化并参与 DesignDSL content hash。AST/IR 是派生产物，不进入历史事实源。
- 每个 Expression input 是 `{alias, source}`；alias 为最多 64 字符的 ASCII 标识符、必须唯一且全部被使用，顺序无语义。source code 只能通过 `input.alias` 读取，不能直接写 path、definitionId、loopId 或 capability。
- grammar precedence 从低到高为 `||`、`&&`、equality、relational、`+ -`、`*`、unary `! -`、primary。支持 text/decimal/boolean literal、括号、decimal `+ - *`、同类型 equality、decimal/date/time relational、lazy `&& || if`、`exists/coalesce`、`concat/length`、`divide/round/formatDecimal`、`formatDate/formatTime`。
- text literal 使用单引号及冻结的受限 escape（含 `\\`、`\'`、换行类与 `\u{scalar}`）；decimal literal 使用 JSON number/exponent 词法。text 不支持 `+`，decimal 不提供 `/`、`%`，必须调用显式函数。
- v1 排除 member/index access、object/list construction、filter/map/reduce、comprehension、lambda、recursion、user function、eval、regex、date arithmetic、color operation 与任意脚本。未支持复杂功能在本 profile 中非法，不存在 parsed-but-unimplemented 状态。
- 普通参数从左到右求值；`&&`、`||`、`if`、`coalesce` 惰性，Expression input 也惰性并在单次 Expression evaluation 内 memoize。未选择分支不调用 capability，但所有分支仍静态验证。
- `exists(x)` 对 ABSENT 为 false、PRESENT 为 true、ERROR 仍 ERROR；`coalesce` 只处理 ABSENT，fallback 与第一参数同 base type；`if` 条件必须 concrete boolean 且两分支同型；`concat` 只收 concrete text；`length` 按 Unicode code point 或 list item 计数并返回整数 decimal。
- presence refinement 只支持 `exists(input.x)` 及其直接 `!`，并在 `exists(x) && RHS`、`!exists(x) || RHS` 中收窄 RHS；不做通用 flow analysis。
- `+/-/*` exact；`divide/round` 的 scale 与 rounding mode 必须是 compile-time literal，scale 非负，mode 只允许 `HALF_UP/HALF_EVEN/DOWN/UP`，除零、非法 scale 与预算越界均 ERROR。
- `formatDecimal(value,min,max,mode)` 先按 max 精确舍入，再移除超过 min 的尾零并保留 min 位；ASCII、固定小数点、无 grouping/exponent，max=0 时无小数点。`formatDate`/`formatTime` 只输出上述固定 canonical 格式；locale、timezone 与自定义 pattern 留待未来 profile。
- equality 仅用于同型 concrete scalar；text 精确 Unicode sequence，imageRef/fontRef 按 assetId，enum 按同 catalog token。list 不提供 whole equality/order。

### 6. 全局 BindingPolicyCatalog

- Design Node 属性对所有 Template 全局固定；Template 不定义、复制或覆盖可绑定属性规则。平台 Node 定义者维护同一份全局 `BindingPolicyCatalog`，前端/客户端据此呈现和创作，服务端保存与 Evaluation 时权威执行。
- 单条 BindingPolicy 由 `nodeKind + propertyPathPattern` 唯一定位，并给出 `targetType` 与目标属性原有 `propertyValidation`。不存在匹配 Policy 的属性不可绑定；Catalog 不使用 `STATIC_ONLY/STATIC_OR_BINDING/BINDING_REQUIRED` 等赋值模式。
- Catalog 是单调追加而非随 DesignDSL version 冻结：已有 Policy 的 path、type、validation 不可修改或删除；新增 Policy 不得与已有 target set 重叠，只会使此前不可绑定的固定属性从此允许可选 Binding，不能使任何既有合法 Template 失效。
- Template 不保存 policyId 或 Catalog revision；Binding 通过 node kind 与 targetPropertyRef 解析当前追加式 Catalog。新增 Policy 无需迁移旧 DesignDSL，也不改变旧 revision 已有求值语义。
- Property target 检查的是 source 求值后的 ValueType，不按 context/loopIndex/definition 来源分别列白名单；普通 Binding source kinds 使用上面的统一规则，输出必须与 Policy targetType 精确一致，并在 overlay 后重新通过目标 propertyValidation。

### 7. Node-local Binding 与 targetPropertyRef

- Binding 存在于实际消费属性的 Design Node 的 `bindings[]` 中；host node 隐式确定，因此 wire 不保存 nodeId、slotId，也没有顶层 Binding 表。单条 wire 为 `{bindingId, targetPropertyRef, source}`，bindingId 在 Template 内唯一。
- targetPropertyRef 使用 `{rootPropertyId, selectors[]}`；支持精确五种形状：`property`、`property.member`、`property[index]`、`property[index].member`、`property.member[index]`。
- selectors 最多两个且至多一个 `member` 与一个 `index`；member 是全局 Node 属性模型中的 propertyId，不是任意 JSON key；index 是 literal、零基、非负整数，不允许动态、负数、wildcard 或 slice。
- target 的所有对象/数组容器与最终静态叶子必须已存在且自身合法；index 必须在 authored array 范围内。Binding 不创建缺失 member、不扩展 array。对象/复杂数组只是属性路径容器，不能作为任意 JSON 值整体绑定；scalar list 整体只有全局属性类型明确为 `list<T>` 时才可绑定。
- 数组 index 是 revision 内的位置而非 item identity；缩短导致越界是不可确认 hard error，重排默认继续指向相同下标。编辑器若要保持原 item，必须在一次作者操作中同步重写 target index。
- 同节点 bindings 数组顺序无语义；重复 target 以及 ancestor/descendant overlap 都是 hard error，因此 overlay 顺序不影响结果。全局 BindingPolicy path pattern 也不得产生一个 target 多义匹配。

### 8. 统一静态 baseline overlay

- 所有可绑定属性始终保存合法 DesignDSL 静态值，不存在 Binding-required 属性：没有对应 Binding 时使用静态值；存在 Binding 且成功产生 concrete 合法值时覆盖目标叶子；删除 Binding 后静态值重新生效。
- 静态值只是 authoring baseline，不是 runtime fallback。Binding 存在但产生 ABSENT、ERROR、错误类型或 propertyValidation failure 时，整次 Evaluation 立即失败，不得回退 baseline 或产生部分 RenderDocument。
- Evaluator 先形成合法静态 property tree，再应用互不重叠的叶子 overlay。普通 visual property 必须是 concrete ValueType；Loop 等结构目标若后续也采用同一模型，必须由全局 Node 属性模型声明其封闭类型并在 DesignDSL 保留合法静态 baseline，具体 collection 语义由票据 11 决定。
- 所有 authored imageRef/fontRef literal 即使处在被 Binding 覆盖或 lazy 未选择的 baseline 中，仍是 DesignDSL 当前依赖事实，必须进入 AssetRef dependency projection/readiness 检查；runtime override 中的 AssetRef 不成为 Template 持久依赖。

### 9. Validation、Evaluation 与问题传播

- save/recheck 对所有 Definition、Expression branch/input、Mapping branch、Binding target/source、ID、type、domain、cycle 与 authored literal 做有界、确定性的完整问题收集；可证明的 literal/runtime fault 即使位于不可达分支也属于 hard error。常量折叠可选但不得改变语义或 capability call count。
- 自包含的 grammar、unknown member、非法 domain/path syntax、empty pointer、duplicate ID/alias/target、cycle、declared type/slot mismatch、错误函数与 baseline/target 结构问题均是不可确认 hard error。
- 按精确 StaticSchema 事实发现 path 不存在或 type/presence proof 漂移，以及无效 AssetRef/TemplateRef，是可经既有二阶段流程确认保存 INVALID 的 dependency ERROR；INVALID/STALE 不能 Render。
- runtime demand-driven：只 materialize 实际消费 node/binding、selected Mapping result 与 selected Expression branch。第一个实际 demanded ERROR fail-fast，之后不再调用 capability 或解析 Asset，整次 Evaluation 零 RenderDocument。
- diagnostics 使用稳定 code、DesignDSL JSON Pointer/target、definitionId/bindingId、零基 UTF-16 line/character source span 与有界 consumption chain；不回显实际业务值。Web linter 只作反馈，服务端保存/重检与 Java Evaluator 是 authority。

### 10. Asset 与 capability 边界

- authored imageRef/fontRef 由全局 target type 给出期望 kind；动态根 PUBLIC asset override 在 RenderInput admission 时必须验证同 ownerScope、ACTIVE、kind 与 caller asset.read，不可见或跨 scope 返回 NOT_FOUND。child 已准入 fill 不重复要求 render caller asset.read。
- 每个实际消费 property 的 AssetRef 仍按出现位置独立解析当时 Asset current；Definition memoization 逻辑值不能合并不同消费位置的 Asset resolution。
- Capability 只作为 Expression 显式 input，并以 definitionId + alias + domain frame 形成稳定 call position；Clock/Random 的 payload、request consistency 与总预算由票据 14 冻结。AssetResolver 不是普通 Expression capability。
