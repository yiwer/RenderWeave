# 实现 DesignDSL Definition/ValueSource 原子

Type: task
Status: open
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
