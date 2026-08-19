# 实现 DesignDSL Definition/ValueSource 原子

Type: task
Status: resolved
Claimed by: Codex `/root`
Blocked by: 03, 14

## Question

如何在 DesignDSL admission/canonicalization 中实现 `definitions[]` 全切片（旧 map ticket 08 §1 与 ticket
07 的冻结规格）：closed union `custom | mapping | expression`、definitionId 在单 Template 全部 definitions
中唯一、CustomDefinition 的 PUBLIC/PRIVATE exposure 与必填非 null typed literal defaultValue、ValueSource
closed 描述与显式 invocation/loopId lexical domain、MappingDefinition ordered first-match cases 与
required otherwise、ExpressionDefinition 的 exact UTF-8 source 无损保留（whitespace 参与 hash）、
definition 允许 forward reference 但必须无环、定义在 declaration frame 内惰性 memoize 的静态可判定部分、
canonical set sorting（按 definitionId）与 exact vectors，而不实现 Expression 求值器（runtime 求值属
Evaluator 侧）？Binding 原子、capability source 与 Evaluator materialization 不进入本票。

权威输入：旧 map tickets 07（value-binding-expression-model）与 08（envelope/definitions/identity/
canonical）冻结规格；kernel 现状。

## 实现记录（2026-08-19）

- 新增 internal `DefinitionContractCatalog`（唯一机器权威）：DEFINITION_KINDS closed union、common/
  custom/mapping/expression member 集、EXPOSURE_TOKENS、BASE_VALUE_TYPES（8 种 base）、LIST_ITEM_TYPES
  （五种 StaticSchema scalar + imageRef/fontRef，无 color/嵌套）、VALUE_SOURCE_KINDS 五种 closed
  variant 与各自 member 集、MAPPING_OPERATORS（14 个 operator）、NO_OPERAND_OPERATORS（IS_ABSENT/
  IS_PRESENT）、CAPABILITY_OPERATIONS（CLOCK×2 + RANDOM×1 恰三对）、ALIAS/DATE/TIME/COLOR pattern、
  context pointer 段数/字节预算。
- `CanonicalDesignDslAuthority.validateDefinitions`（替代非空 definitions 的 KERNEL_SCOPE_UNSUPPORTED）：
  - 每 definition：kind closed union → per-kind rejectUnknown → definitionId（UUID v4 + Template 内
    唯一）→ displayName 必填 trim；custom = exposure + valueType + typed defaultValue；mapping =
    domain + output + 单 input ValueSource + cases（≥1、ordered、operator/operand 规则、then 与
    otherwise 为 ValueSource 且 literal 结果 valueType 必须精确等于 output）；expression = domain +
    output + inputs（alias ASCII 标识符 ≤64、唯一）+ exact source 无损保留。
  - ValueSource：literal（typed literal decoder 复用 Custom default/Mapping operand）、context
    （domain + RFC 6901 pointer：非空、首 /、合法 ~0/~1、≤32 段、解码 ≤1024 UTF-8 字节）、loopIndex
    （loopId 解析）、definition（记 edge）、capability（仅 Expression input，恰三对 closed）。
  - domain：`"invocation"` 或 `{"kind":"loop","loopId"}`；Repeat 未实现前 loopIds 恒空 → loop
    domain/loopIndex 作为 dangling 引用 fail closed（VALUE_INVALID）。
  - Expression alias usage：有界词法扫描（跳过单引号字符串与转义），`input.<alias>` 标识符路径外
    出现的 alias 必须全部被声明使用；完整 grammar/usage proof 属 Evaluator 侧。
  - definition graph：forward reference 允许、必须无环且引用存在；迭代 DFS（authored order），
    cycle/dangling 指向闭合该环/悬空的 source definitionId pointer。
  - canonical set sorting：definitions 按 definitionId、Expression inputs 按 alias 排序；Mapping
    cases 保持 authored first-match 顺序；Expression source 原样参与 content hash。
- 冻结向量：manifest `renderweave-template-canonical-kernel-v1/3`，94 cases（57 原样 + 1 重写
  `reject-nonempty-set-array-until-definition-contract-is-implemented` →
  `reject-empty-object-definition`（[{}] → /definitions/0/kind）+ 37 新：5 admit 冻结 exact canonical
  bytes/hash（sorted definitions、全 base 类型 literal、ordered mapping cases、inputs sorted by
  alias、forward reference）+ 32 reject 冻结精确 code/stage/pointer）。
- 验证：Template module 27 tests 全绿；`template` gate 绿（Java=94/94 Python=94/94、vector
  sha256 5e394276…、static authorityDiff=0）；Python independent 镜像 Java 检查顺序（含 alias 词法
  扫描与 graph DFS）。Profile 保持 NOT_REGISTERED；plan §12 已更新为 94/94。
- 边界（诚实）：Expression 语法/求值、context pointer 的 Schema path/type/presence 解析、operator
  operand 与 input 的类型域匹配、definition domain 祖先词法规则、enum catalog 注册均属 Evaluator/
  后续票；v1 kernel 未注册任何 enum catalog（enum ValueType fail closed）。loop domain/loopIndex 随
  Repeat 票（T17）接入真实 loopIds 后自然解锁。
- 收口：删除临时 ManifestBuilder15/组装脚本 → `template`/`fast` gates → NOTES/map/plan 同步 →
  Ticket 15 resolved/automated_verified → 单一 verified commit（不 push，待用户授权）。

## Resolution（2026-08-19）

- 实现并验证完成：`template` gate 绿（Java=94/94 Python=94/94，evidence
  `.sdlc/evidence/20260819-194737-template/`）、`fast` 绿（`.sdlc/evidence/20260819-194818-fast/`）、
  Profile 保持 NOT_REGISTERED；临时文件已删除；NOTES/map/plan 已同步；单一 verified commit 形成，
  worktree clean，未 push（待用户另行授权）。T17/T18 以本票为前置，解锁为 unblocked frontier
  （T14b/T16/T17/T18），single-writer 下一轮只 claim 其一。
