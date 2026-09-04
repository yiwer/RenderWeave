# ADR-0046：以 successor-only typed envelope 表达 mixed-region rejection

- 状态：accepted
- 日期：2026-08-18
- 决策来源：ticket 24 Q1–Q4 所有者批准、v49 approved delta 与 Goal standing recommendation approval
- 关联：IOPA-P1-R09..R15、AC-IOPA-V49-002..011、tickets 24–30

## 背景与约束

v48 non-scoring diagnostic 只留下 payload-free `VISUAL_GROUNDING_REGION_INVALID` terminal。该 code 能证明
generic fallback 被触发，却不能证明具体字段、值、region 数量或模型根因。读取或推测历史模型 payload 会违反
信任边界；把 v48 原地改成可重试又会破坏 immutable Profile、CLOSED J1/ledger 与 negative terminal。

同时，v48 codec 的确定性 escape seams 中既有“两个以上已知 field family 同时失败”，也有 collection、constructor
或未知异常等无法安全分类的状态。两者若继续共用一个 generic code，worker 无法在 payload-free 前提下区分可行动的
bounded correction 与必须立即终止的未知状态。

## 决策

1. 新语义只由 successor Profile opt in；v48 及更早 pipeline 的 bytes、hash、fixed code、retry 与 breaker 行为保持
   不变。v49 相对 v48 只改变 `profileId`、pipeline 4.31 与 element Prompt 15 三个 identity fields。
2. codec 先通过代码拥有的确定性 classifier 生成 typed rejection envelope，不检查异常 message 或任何动态 payload：
   known mixed 使用 primary `VISUAL_GROUNDING_REGION_FIELDS_INVALID` 与七码 closed enum 中 2..7 个去重、稳定排序
   detail；无法安全分类使用 `VISUAL_GROUNDING_REGION_UNCLASSIFIED` 且无 detail。
3. primary 是 terminal `failureCode` 的唯一来源；detail 只以 fixed-code set/cardinality 进入 attempt、PostgreSQL ledger
   与 evidence，不拼接为动态 code、异常文本或日志 payload。
4. 只有 Prompt 15 明确覆盖的完整 mixed detail set 可形成 bounded correction。相同 canonical set 第三次 rejected
   attempt 先落账后熔断；不同 set 分别计数，但 call/token/cost/time hard caps 始终优先。unclassified、未知、未列或
   malformed envelope 第一次落账后 terminal。
5. classifier、envelope propagation、correction policy、Profile identity 与 live diagnostic 分票落地；每一层先在
   Provider-zero scripted/Testcontainers gate 证明，再允许生成下一层 authority。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| 查看 v48 原始 response 决定字段 | 可直接观察单例失败 | 泄漏 payload，且事后解释不可重复 | 违反 payload-free 与不可推测边界 |
| 把字段集合拼进动态 failure code/message | 接线简单 | code 空间无界、日志可能带值、无法稳定聚合 | 不满足 closed taxonomy |
| 所有 generic rejection 都允许重试 | 可能偶然恢复 | 未知状态会重复外传和消费预算 | 无安全 hypothesis，违反 fail-closed |
| 原地 patch v48 | 无需新 Profile | 改写 immutable identity 与历史 terminal 语义 | 破坏认证与审计权威 |
| 只在 Prompt 中要求模型自报字段 | 实现量小 | provenance 由不可信输出声明，不能独立重算 | 不能证明 deterministic coverage |

## 后果与验证

- 正向后果：mixed provenance 可在不查看模型 payload 的情况下稳定重算；worker 后续可携带 bounded detail 而不扩大
  日志或 retry 权限；历史 Profile 与失败证据保持不可变。
- 负向后果/债务：需要 typed envelope、append-only ledger 投影和新 Profile/Prompt identities；真正 unknown 会保守
  terminal，可能牺牲偶发可恢复性。
- 验证：ticket 25 A1 evidence `.sdlc/evidence/20260818-101738-image-only-v49-provenance/`；后续 tickets 26–29
  必须补 scripted-provider、Testcontainers PostgreSQL、exact diff/hash 与 Provider-zero gates，ticket 30 另需 exact J1。
- 回退/替代条件：在 v49 激活前可删除未发布 successor-only 接线并保持 v48 不变；一旦产生 v49 ledger/terminal，
  只能追加 superseding Profile/事件，不回写历史对象。Provider 调用与费用不可由源码回退撤销。
