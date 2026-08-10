# ADR-0020：IMAGE_ONLY 使用串行视觉分析与拓扑保持

- 状态：Accepted
- 日期：2026-08-10
- 决策来源：真实运行 `d1a1ffcb-f78a-4201-aed2-f051721b614f` 的审核结果与用户确认的分步识别目标
- 关联：ADR-0016、ADR-0018、AC-015、AC-019、AC-020、AC-021、P6/T6-3a.8

## 背景与约束

现有 product-v2 在 IMAGE_ONLY 中由一次 STRUCTURE 调用同时完成视觉观察、实体划分、字段归属和
Candidate 编码。真实站牌运行成功生成了合同合法的 Candidate，却把“停靠站点”压成
`stops: Array<TEXT>`，并漏掉站点中英文名和“温馨提示”子结构。由于该 Candidate 没有确定性
blocker，CRITIQUE/REPAIR 不会启动；增加 repair 次数不能修复这种语义降维。

Profile、Prompt 和 pipeline 都是不可变快照。新行为不能原地改变 product-v2，也不能通过保存原始
模型输出、chain-of-thought 或图片载荷来换取可恢复性。JSON_ONLY 的零 Provider 不变量和 COMBINED
的 JSON truth grounding 不能退化。

## 决策

1. 新增不可变 `renderweave-inference-pipeline/3.0` 与 product-v3 Profile。product-v1/v2 资源继续
   可重放但不再作为新建任务的产品目录项。
2. pipeline 3 对 IMAGE_ONLY 串行执行四个专注步骤：
   `OBSERVE（元素盘点）→ HIERARCHY（层级建模）→ ELEMENT_BINDING（元素归属）→ STRUCTURE（数据定义）`。
3. 前三个步骤分别输出严格、版本化且有界的中间合同：
   - 元素盘点只保存数据槽、重复组、类型/基数提示和规范化 IMAGE evidence，不保存可见原始值；
   - 层级计划只保存根实体、实体、单父级关系、基数和支持元素；
   - 元素归属要求每个数据槽恰好绑定到一个实体。
4. 中间合同必须通过 unknown/duplicate/trailing/coercion fail-closed 解码、数量/长度边界、artifact/bbox
   校验和 rooted-tree 校验后才能进入下一步。元素证据若确定性满足既有“artifact 像素坐标族”规则，
   在持久化和后续步骤前换算到 0..10000。只持久化解析后的合同和有限问题码，不持久化原始响应。
5. STRUCTURE 输出除 Candidate 原有校验外，还必须保持计划中的根、实体、引用/数组关系、字段归属和
   直接 evidence；不得把计划的子实体降级成标量数组。偏差作为 repairable deterministic blocker。
6. product-v3 最多五次 Provider attempt：四次基线调用加至多一次中间重试或 Candidate repair；每次仍受
   Profile ¥2 保守预留上界和用户可选任务累计成本限额约束。为容纳多实体 Candidate，product-v3 的
   单步输出上限为 8192 tokens；调用前预留、账本与 lease 语义不变。
7. pipeline 3 的 JSON_ONLY 继续零调用，COMBINED 继续使用 deterministic JSON grounding；串行视觉合同
   首先只用于 IMAGE_ONLY，避免改变已经验证的 JSON truth 边界。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| 只强化单个 Prompt | 改动最小、调用便宜 | 所有认知任务仍互相竞争；合法但降维的 Candidate 无确定性反馈 | 真实运行已经证明该边界 |
| 先生成一个 Candidate 草稿再让第二次调用润色 | 可复用 Candidate codec | 观察和正式数据定义混在同一合同；第二步仍可静默删除实体 | 不能表达并强制元素与层级关系 |
| 持久化模型原始响应供后续调用使用 | 信息最多 | 违反 payload-free/最小持久化边界，恢复与泄露面扩大 | 不接受 |
| 四步各自使用自由文本 | 易于提示 | 无法确定性恢复、验证或保持拓扑 | 不接受 |

## 后果与验证

- 正向后果：复杂版面被拆成可观测、可恢复、可逐步纠错的结构任务；“站牌→温馨提示”和
  “站牌→线路[]→停靠站点[]”可以成为确定性回归目标。
- 负向后果/债务：IMAGE_ONLY 基线调用从一次增至四次，延迟和费用上升；product-v3 在完成新的
  synthetic/holdout 评测前保持 `EXPERIMENTAL`。
- 验证或观测方式：严格 codec/图不变量单测、真实 PostgreSQL stub-provider 恢复测试、旧 pipeline
  回归、Web 阶段日志与 clean server/web/e2e A1；任何真实 DashScope 质量验证另需绑定精确 Profile、
  evaluation identity、数据、次数、费用和时限的 J1。
- 回退条件：产品目录可回指 product-v2；已保存的 v3 Profile snapshot 与 checkpoint 继续按版本恢复，
  不通过 down migration 或修改历史 Profile 回退。
