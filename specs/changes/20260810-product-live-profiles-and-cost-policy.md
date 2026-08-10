# Spec Delta：产品 DashScope 运行目录与可选任务成本上限

- 状态：merged
- 后续：产品可见 Profile revision 与单次上界已由
  `20260810-product-profile-v2-evidence-repair.md` 增量替代；本文件继续记录 product-v1 的历史决策。
- 触发任务：P6/T6-3a
- 触发证据：用户于 2026-08-10 要求开放“排队识别并进入审核”，将重复模型替换为四个指定模型，并允许设置或不设置成本限额
- 影响 AC/规则：AC-015、AC-019、AC-020；R-INF-001、R-INF-007、R-OPS-002
- 再锚定关系：本 delta 一经批准，即成为 `RULE-ANCHOR-001` 的对照基准之一；批准前的需求演化不视为漂移。

## 冲突或新事实

基线把产品入口绑定到已关闭的 P5 synthetic canary 账本，导致部署即使已配置 Key 与 live gates，
启动按钮仍会因历史 remaining budget 为零而禁用。入口还暴露多个同模型的评测 Profile，不符合用户
对模型选择的心智模型。一次性评测账本不能承担长期产品运行配额。

## 变更

### ADDED

- 新增四个独立产品 Profile：`qwen3.7-flash`、`qwen3.7-plus`、`qwen3.8-max`、
  `qwen3.7-max-2026-06-08`，均为 `EXPERIMENTAL`。
- 新增逐 run 可选 `costLimitMicrosCny`；设置时覆盖首次识别与 repair 的累计预留/实际费用，留空时
  仍保留 Profile 单次预留上界、最大输出 token 与最多三次调用。
- 新增 `USER_PROVIDED` 请求分类与逐任务外发确认；产品 reservation 使用独立追加式审计命名空间。

### MODIFIED

- `live-availability` 只返回四个产品 Profile，并以 worker/upload/credential 判定运行可用性，不再读取
  P5 canary remaining budget。
- live Compose overlay 明确同时打开 worker/upload；基础 Compose 继续关闭。
- retry 创建新 run 时继承原任务成本上限。

### REMOVED

- 从产品选择器移除 `qwen3.7-plus-2026-05-26` 等历史评测 Profile；资源和历史 evidence 不删除。
- 移除“只有 SYNTHETIC_ONLY 才能从产品入口创建 live run”的限制。

## 影响面

- 用户价值/范围：配置好 DashScope 后可以真正启动识别；模型列表无重复；成本上限可选。
- 实现与数据：OpenAPI/Web/Controller/Profile registry/worker/budget store 变化；V013 为 run 增加 nullable
  成本列并增加 product reservation namespace，既有 run 自动兼容为 null。
- 验证与发布：实现测试必须清空 Key/live gates、零 Provider；真实调用只由用户显式点击触发。
- DAG/预算：纳入 T6-3a；不重开或修改任何 CLOSED authorization ledger。
- 恢复影响（源码/数据/外部副作用，RULE-REC-001）：源码可 revert；V013 数据保持 forward-only；关闭
  live overlay 可立即停止新外部调用，已发生费用不可撤销。

## 决策

- 批准人：yiwer
- 日期：2026-08-10
- 结论与理由：批准。产品运行与评测授权分离，保留显式外发确认和调用前费用预留，同时满足可选
  累计成本上限与稳定模型目录。
