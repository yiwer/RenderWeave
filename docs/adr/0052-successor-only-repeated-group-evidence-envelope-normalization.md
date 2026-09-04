# ADR-0052：successor-only repeated-group evidence envelope normalization

- 状态：accepted
- 日期：2026-08-18
- 决策来源：v51 immutable classified terminal、ticket 41、ADR-0047 standing approval
- 关联：ADR-0049、ADR-0051、IOPA-P1-R26

## 背景

v51 单案 diagnostic 首次拒绝即得到
`VISUAL_GROUNDING_PARENT_CONTAINMENT_ITEM_ZERO_COMPATIBLE`。classifier 实现限定了该事实：失败 child 是 ITEM；
其 current parent 存在、不是 self、kind 为 REPEATED_GROUP、repeatGroupId 与 ITEM 相等，但 parent evidence 未包含 child；
同时没有另一个既兼容又包含 child 的 parent。因此，重接 parent 没有证据，而 current repeated-group 是唯一已声明关系。

## 决策

1. v52 只为上述 exact link 做 deterministic evidence-envelope normalization：current parent box 更新为自身与全部 direct、
   same-artifact、same-repeatGroup ITEM child boxes 的坐标并集，不加 padding。
2. 不移动或裁剪 ITEM，不改变 parent reference、region/element ID、kind、multiplicity、repeatGroup、readingOrder、ownership、
   semantic value 或 Candidate 内容；不读取 OCR、token 文本或模型 payload。
3. 先在副本中完成所有 bounded parent 更新，再运行完整 `VisualGroundingPlan` 与既有后续 validator。任何 invariant 失败
   全量回滚；不递归扩大 ancestor、不删除 branch、不放宽 containment。
4. 只由 hidden v52 pipeline 4.34 opt in；Prompt 16 与 v51 其他 Profile 字段保持不变。v51 CLOSED 工件与更早行为不变。
5. 实现先经 Provider-zero property/compatibility/PostgreSQL gate；live 只能使用 fresh exact、短时、one-shot J1。

## 后果与验证

- 正向：利用 v51 新增的结构因果事实，修复抽象容器的可机械 envelope，而不猜测替代 parent 或叶子证据。
- 风险：parent 扩张可能越过 ancestor；因此完整 plan validation 与 atomic rollback 是强制边界，不能递归“修到通过”。
- 验证：exact item fixture 成功；wrong kind/group/artifact、ambiguous/orphan、ancestor overflow、数量超限全部保持原始失败；
  v51 differential 不变；telemetry 只输出 fixed count；Testcontainers 证明成功路径不发 correction permit。
- 回退：停止创建 v52 authority；不得关闭 normalization 后复用 v52 identity，也不得回写/重开 v51。
