# 评估受控能力下的表达式语言基础

Type: research
Status: resolved
Claimed by: research/template-v1-expression-foundations
Blocked by: none

## Question

基于官方规范、文档和一手实现，哪些现有表达式语言或解析基础能够支持封闭版本、显式输入、强类型、缺失值语义、可审计的 Clock/Random/AssetResolver 能力调用与 Java/Web 边界；哪些要求必然需要 RenderWeave 自有语义层？

## Answer

研究把候选收敛为 CEL、JSONata、JMESPath 与 ANTLR 自有语法基线。CEL 最值得优先做 Java 原型验证，但 `BigDecimal`、StaticSchema 类型与任意 Unicode fieldKey、missing/null、受控 Clock/Random/AssetResolver、总资源预算及 Java/Web 权威边界都必须由 RenderWeave 自有语义层定义。JSONata 是 JSON 表达力和编辑体验的强对照，但动态类型、sequence flattening、图灵完备、`$eval`/时间/随机和多运行时一致性使受限 Profile 成本很高；JMESPath 只适合窄选择器；ANTLR 是完全自建的控制力与成本上界。

Context pointer：分支 `research/template-v1-expression-foundations`，commit `bdb943e8d64c67bb97080f56a429641eb25fa993`，文件 `docs/research/template-v1/expression-language-foundations.md`。
