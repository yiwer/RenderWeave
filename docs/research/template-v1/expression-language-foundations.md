# Template v1：受控能力下的表达式语言基础调研

> 状态：研究输入，不是产品决策。调研快照：2026-08-11。

## 1. 问题与既有约束

本票据只回答“哪些现有语言/解析基础值得进入验证”，不选择最终方案。比较对象是 CEL、JSONata、JMESPath，以及用 ANTLR 定义 RenderWeave 自有语法的基线。

RenderWeave 的已知前提是：服务端 Java Evaluator 是唯一权威执行者；表达式只读取显式输入和受控的 `Clock`、`Random`、`AssetResolver`，不能获得任意文件、HTTP 或其他 I/O；Template 绑定不可变的 StaticSchema。现有数据语义还要求区分缺失字段与 `null`，decimal 按原始 JSON number token 和 `BigDecimal` 语义处理，字段名允许除控制字符外的任意 Unicode。[项目 v1 规格 §3.3、§3.8](../../../specs/renderweave-v1.md)

因此，“能解析 JSON”或“有 Java 包”都不等于可直接采用。候选必须拆成两层评估：

1. **可直接采用**：语法、解析器、类型检查器、运行时、浏览器工具链中可以复用的部分。
2. **必须自定义**：RenderWeave 的版本 Profile、StaticSchema 类型映射、missing/null/decimal、能力调用、资源预算、诊断和 Java/Web 权威边界。

## 2. 比较摘要

| 基础 | 可直接采用的主要部分 | 与目标最明显的缺口 | 当前研究定位 |
| --- | --- | --- | --- |
| CEL | 非图灵完备、无变更的表达式模型；编译/类型检查；官方 Java 运行时；宿主声明变量与函数 | 无任意精度 decimal；JSON 常落入动态类型；missing 规则需收口；有状态 `Random` 与 CEL 的无副作用/未指定求值顺序冲突；官方栈没有浏览器 JS/TS 运行时 | **最值得做 Java 原型验证的直接候选**，但不能不加语义外壳就采用 |
| JSONata | 面向 JSON 的路径、过滤、聚合、构造语法；官方 JS 可在 Node/浏览器运行 | 动态类型、sequence flattening、图灵完备、递归和 `$eval`；内置时间/随机语义不受 RenderWeave 控制；Java 端是独立移植实现；数字是 JS/Java 浮点模型 | **表达能力与编辑体验参考**；作为运行时候选必须先证明受限 Profile 和跨引擎一致性 |
| JMESPath | 小而稳定的 JSON 查询语法、规范和共享合规用例 | missing 折叠成 `null`；没有通用算术/日期/格式化表达能力；Java 实现维护状态不理想 | **窄查询/选择器候选**，不能单独覆盖完整模板表达式 |
| ANTLR 自有语法 | 同一 grammar 可生成 Java、JavaScript/TypeScript 解析器；语法和版本完全可控 | 只解决解析；类型检查、Evaluator、安全、预算、标准库和跨端一致性全部自建 | **成本上界与控制力基线**，用于判断现成语言的适配成本是否已经超过自建 |

表中的“定位”是基于下文事实作出的研究推断，不是选型结论。

## 3. CEL

### 3.1 可直接采用

CEL 的语言目标和本项目很接近：规范将其描述为线性时间、无变更、非图灵完备的嵌入式表达式语言；应用声明表达式可见的数据和函数，解析/检查结果还有规范化 protobuf 表示。[CEL specification](https://github.com/cel-expr/cel-spec)

官方 Java 实现提供“解析/检查后再执行”的 API，允许声明变量、类型和自定义函数，并明确把扩展能力留给宿主。当前 README 示例的 Maven 坐标为 `dev.cel:cel:0.13.1`；真正落地时必须锁定精确 artifact，而不能依赖“当前版本”。[cel-java README](https://github.com/cel-expr/cel-java)

CEL 有静态类型检查和渐进类型：已知类型可以在执行前报错，无法静态确定的值可用 `dyn` 延迟到运行时。[CEL language definition — Type System](https://github.com/cel-expr/cel-spec/blob/master/doc/langdef.md#type-system)

### 3.2 不能直接继承的语义

- **decimal**：CEL 的数值基础是 64 位有符号整数、64 位无符号整数和 IEEE 754 `double`，没有与 RenderWeave `BigDecimal` 等价的内建数值类型。[CEL language definition — Values](https://github.com/cel-expr/cel-spec/blob/master/doc/langdef.md#values) 若选 CEL，必须验证自定义 decimal 类型/运算符、函数式 decimal API，或编译改写层中的至少一种；不能先转成 `double`。
- **StaticSchema 类型**：把普通 JSON 对象作为 `map<string, dyn>` 输入会削弱静态检查。RenderWeave 还允许任意 Unicode `fieldKey`，不应假设字段都能映射成 protobuf/Java 标识符。需要自定义 StaticSchema→表达式类型环境，并定义任意 key 的安全访问形式；这是 RenderWeave 语义，不是 CEL 自动提供的能力。
- **missing/null**：CEL 的 `null` 有独立 `null_type`；访问不存在的 map/message 字段会产生错误，`has(...)` 用于 presence 检查，map 的任意 key 也可用 `in` 检查。[CEL language definition — Null](https://github.com/cel-expr/cel-spec/blob/master/doc/langdef.md#nulls)、[Field Selection](https://github.com/cel-expr/cel-spec/blob/master/doc/langdef.md#field-selection) RenderWeave 仍须定义 optional 字段缺失时是值、错误还是仅能经显式检查访问，以及错误、默认值和短路的传播规则。
- **受控非确定能力**：CEL 假定表达式无副作用，并且不规定子表达式的求值顺序。[CEL language definition — Evaluation](https://github.com/cel-expr/cel-spec/blob/master/doc/langdef.md#evaluation) 因而把状态推进式 `random()` 或每次读取真实时钟的 `now()` 直接注册为普通函数，会使结果依赖运行时求值细节。这不是简单的函数绑定问题，必须由 RenderWeave 定义调用语义。

### 3.3 Clock、Random、AssetResolver 的适配边界

CEL 本身不需要获得 I/O。宿主只注册白名单函数/值即可；Java 运行时的 function binding 是可复用机制。[CelRuntimeBuilder source](https://github.com/cel-expr/cel-java/blob/main/runtime/src/main/java/dev/cel/runtime/CelRuntimeBuilder.java)

但下列合同必须自定义，候选实现可在原型中比较，本文不替产品作决定：

- `Clock`：一次 evaluation 固定一个 instant，或每次调用读取新 instant；两者对条件分支和多属性求值含义不同。
- `Random`：按调用顺序推进、按显式 call-site key 派生，或预先注入随机值。若要避免 CEL 未指定求值顺序影响结果，后两类更容易定义稳定语义；即使默认 render 不要求可重放，也仍需定义一次 evaluation 内的一致性。
- `AssetResolver`：可以先解析为显式输入，也可以作为受控异步函数。CEL Java 官方 API 目录提供异步执行能力，但调用次数、超时、缓存、失败分类和允许返回的数据仍由 RenderWeave 负责。[CEL API reference](https://cel.dev/reference/api-reference)

### 3.4 资源限制与 Java/Web 边界

CEL Java 提供源代码点数、解析递归、AST 节点、comprehension 迭代和正则程序大小等配置项；这些选项的默认值并不等于 RenderWeave 的安全预算。[CelOptions source](https://github.com/cel-expr/cel-java/blob/main/common/src/main/java/dev/cel/common/CelOptions.java) CEL 规范也明确指出扩展函数的计算复杂度由嵌入应用负责。[CEL language definition — Performance](https://github.com/cel-expr/cel-spec/blob/master/doc/langdef.md#performance)

因此还要在 Evaluator 外层增加总 deadline/取消、输入与输出大小/深度、能力调用次数和返回大小等配额。预算超限必须成为稳定的领域错误，而不是泄漏运行时异常。

官方 API 目录列出 Java 实现，但截至本调研快照未列出官方 JavaScript/TypeScript 运行时。[CEL API reference](https://cel.dev/reference/api-reference) 这不妨碍 Java 服务端权威执行；它意味着 Web 端若需要即时检查，可以采用服务端编译/预览，或使用非权威本地 parser/linter，但不能未经合规语料验证就声称与服务端完全一致。

### 3.5 CEL 的封闭 Profile 至少应锁定

精确 cel-java 版本、语言/Profile 标识、启用的运算符和宏、标准函数/扩展白名单、自定义类型与函数签名、所有 `CelOptions` 限制，以及 StaticSchema 类型适配器版本都应成为编译身份的一部分。CEL checked AST 可以是派生产物，不能仅靠它替代上述运行语义身份。[CEL canonical representation](https://github.com/cel-expr/cel-spec)

## 4. JSONata

### 4.1 可直接采用

JSONata 原生面向 JSON 的导航、过滤、聚合和结果构造，路径结果可以是零、一或多个值；官方 JavaScript 实现支持 Node 和浏览器。[JSONata processing model](https://docs.jsonata.org/processing)、[jsonata-js repository](https://github.com/jsonata-js/jsonata) 对模板作者而言，这比通用表达式语言更贴近“从数据得到展示值/结构”。

其嵌入 API 接收一个显式 input，并允许通过 bindings 或 `registerFunction` 注入宿主值/函数。[JSONata embedding API](https://docs.jsonata.org/embedding-extending) 这提供了能力白名单的挂载点，但白名单策略本身仍须自定义。

### 4.2 不能直接继承的语义

- **动态类型**：JSONata 类型是 JSON 类型加一等函数。函数 signature 只在运行时检查参数，官方文档明确说明返回类型尚不检查。[JSONata type system](https://docs.jsonata.org/processing#the-jsonata-type-system)、[registerFunction](https://docs.jsonata.org/embedding-extending#expressionregisterfunctionname-implementation-signature) 它不能直接给出基于 StaticSchema 的属性期望类型检查。
- **missing 与 cardinality**：空 sequence 表示“nothing/no match”，对象属性对应空 sequence 时会从输出中消失；singleton 自动解包，多值 sequence 变为数组，嵌套 sequence 还会 flatten。[JSONata sequences](https://docs.jsonata.org/processing#sequences) 这对查询很方便，却会让模板属性的“缺失/单值/数组”形状随数据改变，必须加 RenderWeave 的期望类型、cardinality 和 missing/null 合同。
- **可计算性**：官方文档明确把函数、递归等构造称为使 JSONata 成为图灵完备语言；字符串函数库还提供 `$eval` 来动态解析并执行 JSONata 字符串。[JSONata programming constructs](https://docs.jsonata.org/programming)、[JSONata `$eval`](https://docs.jsonata.org/string-functions#eval) 因此完整语言不满足“封闭且可静态定界”，至少要禁用/拒绝 `$eval`，并对白名单语法和递归/迭代设置硬限制。
- **时间与随机**：`$now()`/`$millis()` 在一次 evaluation 内返回同一时刻，而 `$random()` 返回 0 到 1 的伪随机数。[JSONata date/time functions](https://docs.jsonata.org/date-time-functions#now)、[JSONata numeric functions](https://docs.jsonata.org/numeric-functions#random) 这些内建名称若直接可用，会绕过 RenderWeave 的受控 `Clock`/`Random`；受限 Profile 必须排除或以明确合同替代。
- **decimal**：参考 JS 实现的数值运算依赖 JavaScript number/`Math`，包括 `$random`，不是 RenderWeave 的原始 token + `BigDecimal` 模型。[JSONata 2.2.0 function source](https://github.com/jsonata-js/jsonata/blob/v2.2.0/src/functions.js) Java 移植也不能据此自然获得跨端 decimal 等价性。

### 4.3 Java/Web 与安全边界

官方语言网站把 Java 实现列为其他实现，而参考实现是 JavaScript。[JSONata overview](https://docs.jsonata.org/overview) `dashjoin/jsonata-java` 自称对参考实现的 1:1 port，并提供超时/最大递归深度和自定义函数；其 README 同时暴露 Java 数字表示与 `null`/undefined 适配细节。[dashjoin/jsonata-java](https://github.com/dashjoin/jsonata-java) IBM 的另一移植明确以较旧的 JS 1.8.4 为目标，并列出语义差异。[IBM JSONata4Java](https://github.com/IBM/JSONata4Java)

据此不能把“浏览器与 Java 都有库”推断为同一运行时。若 JSONata 进入候选，必须用包含 missing/null、sequence、decimal、Unicode key、错误、时间/随机和限制触发的共享语料做 Java/Web 差分测试；Web 结果只能是预览，服务端仍是权威。

JSONata 对 JSON 输入本身没有任意文件/网络能力，但 bindings 和扩展函数可以执行宿主代码。[JSONata embedding API](https://docs.jsonata.org/embedding-extending) 安全边界必须是不可被模板扩张的注册表，且不把通用脚本对象、HTTP client、文件句柄或反射对象放进 input/bindings。

## 5. JMESPath：窄查询对照组

JMESPath 有完整语法和内建函数规范，表达式接收 JSON 并产生 JSON；官方项目还维护跨实现合规用例。[JMESPath specification](https://jmespath.org/specification.html)、[compliance tests](https://github.com/jmespath/jmespath.test) 这使它适合作为只读数据选择器的低复杂度对照组。

但规范规定访问不存在的 identifier 返回 `null`，因此不能天然保留 RenderWeave 的 missing/null 区别；其 grammar 和函数集也没有通用算术表达式，无法单独覆盖尺寸计算、日期格式化等模板需求。[JMESPath identifiers](https://jmespath.org/specification.html#identifiers)、[JMESPath grammar](https://jmespath.org/specification.html#grammar)

官方 libraries 页面列出 Java 和 JavaScript 实现及合规等级，但所列 `jmespath-java` 仓库已于 2024-10 归档。[JMESPath libraries](https://jmespath.org/libraries.html)、[jmespath-java repository](https://github.com/burtcorp/jmespath-java) 因而它更适合作为“把选择器与计算语言分开是否值得”的验证项，而不是完整表达式答案；拆成两种语言会增加作者学习与诊断成本，这是后续产品权衡。

## 6. ANTLR：自有语义基线

ANTLR 是 grammar→parser 的生成工具，官方支持 Java、JavaScript 和 TypeScript 等 targets，并生成 parse tree/listener/visitor。[ANTLR repository](https://github.com/antlr/antlr4) 它允许 RenderWeave 精确定义语法版本、StaticSchema 类型、`BigDecimal`、missing/null、能力调用形式和稳定诊断位置；Java 执行、Web 仅解析/编辑提示也能共用 grammar。

ANTLR 不提供表达式类型检查器、Evaluator、安全沙箱、标准库、资源预算或跨 target 的业务语义。这些全部由 RenderWeave 实现和维护。因此它不是“更安全的现成运行时”，而是控制力最高、实现与验证成本也最高的基线。

## 7. 无论选谁都必须由 RenderWeave 定义的语义层

1. **Profile 身份**：语言/Profile 版本、精确依赖版本、启用语法、运算符、宏、函数、类型适配器和预算配置；Template revision 引用该身份。
2. **显式环境**：输入根、循环 item/index、模板参数、StaticSchema 类型环境，以及 `Clock`/`Random`/`AssetResolver` 的不可扩张注册表。表达式不得发现宿主全局对象。
3. **属性类型合同**：每个 DesignDSL 属性声明期望结果类型和是否允许 missing；编译时能证伪则拒绝，运行时仍检查返回值。
4. **值语义**：decimal 精度/舍入/溢出，date/time/timezone，missing、`null`、错误、默认值、短路、比较、排序和集合 cardinality。
5. **能力合同**：Clock 的 evaluation 一致性；Random 的调用身份、分布、种子/审计边界；AssetResolver 的允许参数、返回类型、调用次数、deadline、缓存和错误分类。AssetResolver 只能解析受管资源身份，不能演化成任意 URL/I/O。
6. **资源预算**：源码长度、token/AST 节点和深度、递归/迭代/集合项、正则复杂度、输入大小、总 wall-clock deadline/取消、结果大小/深度、能力调用次数与返回大小。
7. **诊断合同**：稳定错误码、表达式 source span、DesignDSL JSON Pointer、属性期望类型；常规日志不记录完整输入、能力返回内容或敏感资源元数据。
8. **权威边界**：Java 服务端决定 compile/evaluate 结果。Web 本地能力若存在，只用于编辑反馈，并由同一黄金语料证明其已知一致范围。

## 8. 建议的下一轮验证，不是选型

为了让产品决策有证据，建议只做四组可丢弃原型/语料，不进入正式实现：

1. **StaticSchema→CEL 类型环境**：覆盖 optional、数组/嵌套对象、引用，以及包含空格、`.`、`/`、`~`、emoji 的 fieldKey；确认编译期能捕获哪些属性类型错误。
2. **数值黄金语料**：用项目 decimal 边界值比较 CEL 自定义 decimal、JSONata JS、候选 Java port；任何经 `double` 后才比较的方案直接标红。
3. **能力语义语料**：同一表达式包含短路、重复子表达式、循环和错误分支，验证 Clock 一致性、Random 调用身份、AssetResolver 次数/取消/超时；不要把运行时当前求值顺序当合同。
4. **对抗与跨端语料**：深 AST、大集合、递归/comprehension、恶意正则、巨大结果，以及 missing/null/sequence/Unicode/error 差分；Java 是 oracle，Web 只报告与 oracle 的偏差。

当前证据支持把 **CEL 作为优先验证的直接候选**，把 **JSONata 作为 JSON 表达力和编辑体验的强对照**，把 **JMESPath 作为窄选择器对照**，把 **ANTLR 作为完全自建的成本/控制力基线**。是否采用任何一个，仍取决于上述四组验证，尤其是 decimal、StaticSchema 类型映射、受控非确定能力和 Java/Web 边界。
