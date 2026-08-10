# Spec Delta：Product v3 串行视觉层级识别

- 状态：approved
- 触发任务：P6/T6-3a.8
- 触发证据：运行 `d1a1ffcb-f78a-4201-aed2-f051721b614f` 将停靠站点降级为 `Array<TEXT>`，且漏识别站点中英文名与“温馨提示”子 Schema
- 影响 AC/规则：AC-015、AC-019、AC-020、AC-021；ADR-0016、ADR-0020
- 再锚定关系：本 delta 经用户在 2026-08-10 明确要求分步骤优化，成为后续实现与验收基准。

## 冲突或新事实

当前规格把 OBSERVE 描述为流程阶段，但 product-v2 实现中它只是零调用 checkpoint。一次 STRUCTURE
调用生成的合同可以形式正确而语义降维，因此现有 validator/repair 不足以保证复杂图片的实体召回。

## 变更

### ADDED

- IMAGE_ONLY product-v3 必须依次产生元素盘点、层级计划、元素归属和 Candidate 四个产物。
- 元素盘点区分数据槽与重复/嵌套组，每项携带直接 IMAGE evidence，但不得保存观察到的原始值。
- 元素盘点证据在进入层级步骤前应用与最终 Candidate 相同的保守像素坐标族换算，四步始终共享
  规范化产物的 0..10000 坐标空间。
- 层级计划必须是最多 16 层的可达单根树；关系基数只能为 ONE 或 MANY。
- 元素归属必须覆盖每个数据槽一次；层级中的每个组元素必须被实体或关系使用。
- Candidate 必须保持计划中的实体、关系、基数、字段和直接 evidence；计划外 Schema/字段不得静默加入。
- 站牌合成验收夹具至少要求：
  - 根“站牌”包含站点名称、站点英文名；
  - 根以 ONE reference 指向“温馨提示”，提示含生效日期/内容等可见数据槽；
  - 根以 MANY reference 指向“线路”，线路以 MANY reference 指向“停靠站点”；
  - 停靠站点可包含名称、顺序、当前站/换乘等有直接证据的字段。

### MODIFIED

- 产品目录从四个 product-v2 Profile 切换到同模型的四个 product-v3 Profile。
- IMAGE_ONLY 最大调用数从 3 调整为 5，其中四次为串行基线，剩余一次用于中间合同重试或 Candidate repair。
- product-v3 单步最大输出从 4096 调整为 8192 tokens，避免多实体 Candidate 在旧上限附近被截断；
  262144-byte 输出合同与 ¥2 单次保守预留硬门不变。
- 阶段集合新增 HIERARCHY、ELEMENT_BINDING；审核执行日志必须用人类可读名称展示。
- 单次 `maximumEstimatedCostMicrosCny=2,000,000` 不变；任务累计成本限额仍可填写或留空。

### REMOVED

- 不再把 product-v3 IMAGE_ONLY 的 OBSERVE 当作零调用占位阶段。

## 影响面

- 用户价值/范围：提升复杂信息板、表格/列表嵌套和重复实体的可解释识别；不扩展到 Template/渲染。
- 实现与数据：新增中间合同、checkpoint v2、阶段迁移、Prompt/Profile v3 与最终拓扑校验。
- 验证与发布：先以合成图/桩响应离线验证；真实模型质量验证需要新的精确 J1，旧 live evidence 不继承。
- DAG/预算：作为 T6-3a.8 插入，完成后再继续 T6-3b；最大五次但每次继续独立预留。
- 恢复影响：旧 checkpoint v1 只读迁移为内存 v2；崩溃后从最后一个已持久化中间产物恢复，不重复已成功阶段。

## 决策

- 批准人：yiwer
- 日期：2026-08-10
- 结论与理由：采用分步骤专注流程，并将站牌自身字段、“温馨提示”、线路和停靠站点作为最低回归目标。
