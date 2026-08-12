# ADR-0035：v45 重复实例字段观测聚合

- 状态：Accepted
- 日期：2026-08-12
- 决策来源：用户要求继续诊断并修复指定 live run 中嵌套数组结构整体丢失的问题
- 关联：ADR-0028、ADR-0033、ADR-0034、P6/T6-5/N7

## 问题

product-v44 的首个真实修复验证已经在 OBSERVE/HIERARCHY 恢复出重复组、两个实体和一条 MANY 关系，
证明原先的 ROOT-only 遗漏已被阻断；run 随后却在 ELEMENT_BINDING 以
`VISUAL_BINDINGS_V2_COVERAGE_INVALID` 用尽调用次数。payload-free 回放显示 14 个重复实例 SLOT 具有完全
相同的字段语义并归属同一 child entity。旧合同同时要求“每个 SLOT 恰好绑定一次”和“同一实体的字段 key
不可重复”，因此不存在可被接受的 binding response，重复模型重试也无法改变结果。

## 决策

1. 新增 immutable product-v45 Profile 与 visual binding Prompt 4；v44 及更早 snapshot 不修改。v45 继续使用
   pipeline 4.28、element Prompt 12、hierarchy Prompt 7、相同模型、价格、Document Vision capability、
   7-call、360 秒和输出边界。
2. binding policy 仅在 Prompt 4 精确启用。每个 SLOT 仍必须出现且恰好绑定一次；只有同一实体内
   `proposedKey`、`displayName`、`multiplicity`、`valueHint` 全部相同，且分别属于同一 repeat group 的
   互异 ITEM region 的观测允许共享字段身份。
3. field/relationship key 冲突、不同显示名、不同基数或不同值类型仍然拒绝。Prompt 要求重试时枚举所有
   SLOT，不允许先在 Provider 输出中去重或遗漏重复实例。
4. 本地 materializer 按实体和字段 key 确定性聚合已验证观测，生成一个 Candidate field，字段 ID 与类型不受
   Provider 局部 elementId 影响；evidence 去重、排序并继续受现有最多 8 项边界约束。
5. Candidate validator 以相同 policy 校验计划与物化结果；旧 Profile 默认使用严格唯一字段策略，保证历史
   replay 和 snapshot 语义不漂移。

## 权衡与回退

聚合只适用于同语义、同一重复组且 ITEM region 互异的实例观测，不进行相似名称、shape 或业务词推断。这会把同一列表中多个实例的字段
观察解释为可复用 child Schema 字段，符合 Schema DSL 的结构语义，同时保留根上的 MANY reference。若发现
回归，停止新建 live 并把产品目录切回 v44；已创建的 v45 run 仍按其不可变 snapshot 恢复。

## 验证状态

- 单元合同覆盖旧策略拒绝、v45 策略接受、单字段物化与两份 evidence 合并。
- Testcontainers PostgreSQL 工作流覆盖 OBSERVE → HIERARCHY → ELEMENT_BINDING → LOCAL_MATERIALIZE，断言
  生成 root + child 两个 CandidateSchema、根上 MANY array reference 和 child 上单一聚合字段。
- 精确 clean revision 门控、真实 Provider 复验与最终费用/Token 证据在 `plans/logs/P6-T6-5-N7.md` 追加；
  v45 保持 `EXPERIMENTAL`，单图成功不替代 final 60、全局 N7 或生产可靠性验收。
