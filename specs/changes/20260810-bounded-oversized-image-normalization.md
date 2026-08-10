# Spec Delta：超大设计图由服务端有界规范化

- 状态：merged
- 触发任务：P6/T6-3a.3 产品真实上传可用性
- 触发证据：用户请求排队识别的 multipart 已到达 Java，但以 `INFERENCE_IMAGE_DIMENSIONS_INVALID` 返回 422
- 影响 AC/规则：AC-015、AC-019、AC-020；RULE-VAL-001、RULE-EVD-001
- 再锚定关系：本 delta 合并后成为 `RULE-ANCHOR-001` 的对照基准之一。

## 冲突或新事实

旧规格把“源图最长边”和“Provider/持久化规范图最长边”都固定为 4096。实际设计截图常超过该边界，
虽然文件仅约 1.25 MiB、格式合法，仍在任务创建前被拒绝；这与用户直接提交设计图的核心旅程冲突。

## 变更

### MODIFIED

- 源图硬门改为最长边 65535、总像素 268435456；单图/批次 bytes 与格式限制不变。
- 超过规范化目标时先进行有界解码 subsampling，再高质量缩放至最长边 ≤4096。
- artifact、Provider 输入、费用估值与 evidence 坐标继续绑定规范化产物。

### ADDED

- 解码工作集像素门，避免高压缩图片导致无界内存放大。
- API + PostgreSQL 回归验证 4097 像素输入创建任务后持久化为 4096 像素 artifact。

### REMOVED

- 移除“任何源图最长边 >4096 必须由用户手工处理”的产品要求。

## 影响面

- 用户价值/范围：高分辨率设计图可直接进入 Schema 识别。
- 实现与数据：只修改 normalization；无迁移，历史 artifact 不重写。
- 验证与发布：InputNormalizer unit、真实 PostgreSQL multipart API、server gate 与零 Provider 部署探针。
- DAG/预算：不增加调用次数；Provider 仍只接收最长边 ≤4096，保守预留公式不变。
- 恢复影响：源码可回退；无数据或外部副作用需要补偿。

## 决策

- 批准人：yiwer（以“排队识别并进入审核”的实际产品指令触发）
- 日期：2026-08-10
- 结论与理由：服务端自动规范化更符合核心旅程，同时保留源图硬门和解码工作集边界。
