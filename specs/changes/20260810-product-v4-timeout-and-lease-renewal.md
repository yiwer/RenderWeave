# Product v4 请求时限与 lease 续租

## 触发信号

真实用户运行 `ea18b4f8-7910-405d-aadd-a7120e836902` 的前三个串行阶段成功，
`STRUCTURE` 两次调用分别在 90,018 ms 与 90,014 ms 终止，且零返回 Token。
这不是图片上传、DNS 或 DashScope 整体不可达，而是 product-v3 的固定 90 秒
HTTP request deadline；原 adapter 又将 `HttpTimeoutException` 归入了通用网络错误。

## 决策

- product-v1/v2/v3 Profile 和已保存 run snapshot 保持不变。
- 新增同模型、同 pipeline 3、同 Prompt 5 的四个 product-v4 Profile。
- v4 每个 Provider stage 的 request timeout 固定为 240 秒；其余上限不变：
  单次保守预留 ¥2、最多 5 calls、最多 1 repair、8192 output tokens。
- worker 在每次费用预留与 Provider 调用前续租当前 run；续租失败则零调用终止。
- JDK HTTP deadline 异常以 `DASHSCOPE_TIMEOUT` 记录，保留可重试属性；其他 I/O
  失败仍为 `DASHSCOPE_NETWORK_ERROR`。
- Web 显示有限、可读的超时处置信息，不显示 Provider 响应或异常原文。

## 恢复边界

人工 retry 复用原 run 的不可变 Profile snapshot，因此 v3 失败任务不能通过
“重新运行”获得 v4 时限。部署 v4 后必须从新增识别页创建新任务。
本变更不自动发起任何真实 Provider 调用，也不声称 240 秒必然足以完成所有图片。
