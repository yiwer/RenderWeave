# DeepSeek-OCR 经 DashScope API 的引入决策与集成形态

Type: grilling
Status: resolved
Claimed by: Kimi（2026-08-17 会话）
Blocked by: 05 — 冻结新的 IMAGE_ONLY Profile Certification authority

## Question

所有者 2026-08-17 决定：DeepSeek-OCR 不做本地部署，经 DashScope（百炼）模型 API 调用。需冻结：它在流水线中的 exact 角色（Max 的版面/阅读顺序提示预处理器？与本地 RapidOCR sidecar 的分工？）；集成后的新 immutable Profile 形态（v47：双外部模型调用链的 stage 顺序、预算、失败语义——OCR-API 失败时降级为纯 RapidOCR 路径还是 fail-closed）；数据出域链路变化（阿里云→快手万擎第三方推理）在 ticket 06 风险接受与 ExternalTransferNotice 文案中的表达；调用它的 scoped J1 范围（模型、次数、费用、时限）；以及它产出的版面/bbox/流序如何作为 hint 进入现有 prompt 合同而不破坏 v46 的可比证据。前置事实（exact 模型 ID、endpoint 兼容性、Key 作用域、价格、限流、条款）由 research 子任务补齐中，结果贴在本票 Comments。

## Comments

- 2026-08-17（所有者提供线索，Kimi 会话抓取）：[qianwenai.com 模型页](https://www.qianwenai.com/models/vanchin%2Fdeepseek-ocr)显示 `vanchin/deepseek-ocr` 经 OpenAI 兼容模式 `https://dashscope.aliyuncs.com/compatible-mode/v1` + `DASHSCOPE_API_KEY` 调用（与本项目现有路由/凭据同名）；上下文 8K in/out、TPM 1M、RPM 500；示例以远程 image URL 传图（本项目 ticket 04-17 冻结的是 Base64 Data URL，base64 是否受支持待官方文档核实）。**该站为第三方目录，非官方域名，证据级别=线索**；官方口径核实由 research 子任务进行中，结果另行贴入。

### Research：DeepSeek-OCR 经 DashScope API 接入事实（2026-08-17，commit `2b737f3`，分支 `research/image-only-deepseek-ocr-eval`）

来源全部为阿里云百炼/DashScope 官方文档与条款原文（报告 `docs/research/image-only-production-admission/deepseek-ocr-dashscope-api-facts.md`，逐条带 URL）；无实测（未获 scoped J1）。

1. **exact ID 与调用路径【文档级】**：`vanchin/deepseek-ocr`，走 OpenAI 兼容 `POST {base}/chat/completions`，图片用 `image_url`（公网 URL 或 Base64 data-URI），`detail` auto/high/low。**endpoint 差异**：vanchin 文档只给北京 workspace 专属域名 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` 且"仅华北 2（北京）"；项目现用通用域名 `dashscope.aliyuncs.com` 能否路由该模型 = **UNKNOWN**。**最大未知点**：托管 API 是否支持 grounding 提示词契约、输出 `<|ref|>`/`<|det|>` 版面标签及 999 归一化坐标，官方文档完全未记载——"版面 hint 预处理器"角色的可行性无法从文档确认，只能实测。结构化输出支持；Function Calling/缓存/Batch/微调不支持；图像大小官方称"无硬性限制"。
2. **Key 与开通【文档级】**：同一 `DASHSCOPE_API_KEY` 体系，但需在控制台对模型卡片单独"立即开通+授权确认"，未开通返回 400；**Key 是地域级，须为北京地域获取的 Key**；可用 RAM 子账号 + IP 白名单/模型范围收敛专用 Key。控制台实际开通状态 = UNKNOWN。
3. **计费与限流【文档级】**：输入/输出各 0.216 元/1M tokens；**无免费额度**、无 Batch 半价、排除在上下文缓存折扣外；RPM 500 / TPM 1M。开票方为万擎（北京溪流湖科技有限公司），非阿里云（百炼协议 5.3.2）。
4. **条款事实【条款原文级，不做法律判断】**：百炼协议 6.2.9——三方模型 API 的数据处理（存储、删除、内容安全过滤）与部署模式**以用户和三方服务商的约定为准**；阿里云 6.2.5 的"不留存、未授权不训练"承诺**不覆盖**三方模型；5.3.1.2 阿里云可能与三方服务商分享使用信息。万擎 EULA 三.7："不会存储或使用您的数据用来训练及优化模型"（例外：书面授权及法定内容审查）；三.6 保留审查/中止/报告权。链路：输入图片先经阿里云绿网前置检测，再转发万擎推理。
5. **证据级别**：以上全部文档级/条款原文级，零实测。UNKNOWN 清单：控制台开通状态/粒度、通用域名可用性、原生 API 支持、grounding 标签输出、现有 Key 归属地域、促销额度、万擎推理地部署细节。

## Answer

2026-08-17 经两轮 grilling + 一次 scoped J1 spike 冻结（全部按所有者确认的推荐）：

1. **处置：首个生产版本不引入 DeepSeek-OCR**。spike（授权 `plans/live-canary-authorizations/20260817-deepseek-ocr-spike.json`，CLOSED，3 次调用/¥0.0008）证实：接入零障碍（现有 `DASHSCOPE_API_KEY` + 通用域名直接路由，endpoint/Key UNKNOWN 解除）；grounding 版面标签真实存在（`label[[x1,y1,x2,y2]]` + 约 0-999 归一化 bbox，语法与开源版不同）；**但密版面全图（m3-full）严重漏识别且幻觉出图中不存在的外站 URL**——hint 预处理器在形态上可行，在最需要帮助的难图上恰恰不可信，注入 Max prompt 有投毒风险。审核辅助角色（b）同样收益未证明且需 UI/管线改动，一并放弃。
2. **重评估路径**：未来托管模型质量升级或新证据出现时，凭新 scoped J1 重开评估；重评估引用本票与 spike 授权记录，不复用旧 J1。
3. **已固定的周边事实**（供未来重评估或他票引用）：调用走 OpenAI 兼容 `POST /chat/completions`、base64 data-URI 传图可行；单价 ¥0.216/M tokens 无免费额度；链路为 阿里云绿网前置检测→万擎推理，百炼 6.2.9 三方模型条款 + 万擎 EULA；若未来引入，`ExternalTransferNotice` 须列明三方推理方身份（快手万擎）与条款版本，Profile 的 Provider contract identity 绑万擎 EULA + 百炼 6.2.9；生产形态建议 RAM 子账号专用 Key + 模型范围/IP 白名单收敛。
4. **信任边界维持不变**：ticket 06 的风险接受覆盖该链路；本地 RapidOCR 无网 sidecar（ticket 08）与 v46 Max 管线（ticket 07）为唯一生产路径。
