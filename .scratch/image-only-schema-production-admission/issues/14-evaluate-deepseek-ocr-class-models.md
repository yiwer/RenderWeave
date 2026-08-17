# 评估 DeepSeek-OCR 类强 OCR/版面模型强化本地确定性层

Type: research
Status: resolved
Claimed by: research/image-only-deepseek-ocr-eval
Blocked by: none

## Question

仅依据一手资料（模型发布方仓库、许可文本、官方文档、权威模型托管目录）评估 DeepSeek-OCR 及同类强 OCR/版面模型是否适合强化 RenderWeave 本地确定性层（现 RapidOCR 槽位，ticket 08 已冻结 sidecar 拓扑）。需查明的事实：exact 模型 identity 与许可；自托管的硬件/运行时要求（GPU 是否硬约束、CPU-only 可行性、linux/amd64+CPython 3.12+glibc 兼容性）；输出形态（是否产出 bounding box/版面结构/阅读顺序，还是仅 markdown 文本流）；与 RenderWeave OBSERVE 合同的互补性——能否把版面分析/阅读顺序/表格结构从 VLM 手里下沉，让 LLM 阶段少做像素级 grounding（2026-08-17 试用证据：OBSERVE 拒绝率是难图失败的主要放大器）；若走第三方托管 API（如 vanchin 等），其数据外发含义（将触发新 Provider 审查与新 scoped J1，本票只列事实不做决定）。供应链与许可证据按 ticket 03 同等标准：一手来源、exact 版本/hash、缺失项显式列 UNKNOWN。本票只出事实与适配性评估，不选择是否引入——引入决策留待后续 grilling 票。

## Answer

总 disposition：DeepSeek-OCR（v1/v2）自托管 = `FEASIBLE_WITH_GPU` / 当前 CPU-only 拓扑 = `NOT_FEASIBLE_CPU_ONLY`；CPU-only 路线下可行的是 MinerU pipeline 后端（`FEASIBLE`）与 PaddleOCR 系。

1. **exact identity 与许可**：DeepSeek-OCR 由 DeepSeek 发布（GitHub `deepseek-ai/DeepSeek-OCR`，2025-10-20；HF sha `9f30c71f`），架构 = DeepEncoder（SAM-B+CLIP-L，约 380M）+ DeepSeek3B-MoE-A570M 解码器（config.json 证实 12 层/64 experts/top-6），bf16 权重 6.67GB；许可 **MIT**（GitHub LICENSE 原文 + HF cardData 一致）。后续版本 DeepSeek-OCR-2（2026-01-27，arXiv:2601.20552，**Apache-2.0**，权重 6.78GB）的 DeepEncoder V2 会按语义重排视觉 token，与"阅读顺序下沉"目标直接同构。
2. **自托管要求**：官方环境 cuda11.8+torch2.6.0+CPython 3.12.9+flash-attn 2.7.3+vllm 0.8.5 cu118 manylinux x86_64 wheel；模型卡明示 "on NVIDIA GPUs"；自定义 modeling 代码 `infer()` 内硬编码 `.cuda()`，flash-attn 无 CPU 构建 → **GPU 是事实硬约束，CPU-only 官方不支持（UNKNOWN 偏不可行）**；linux/amd64+CPython 3.12+glibc 在装 GPU 前提下兼容；加载需 `trust_remote_code=True`（供应链注意点）。
3. **输出形态**：markdown 文本流 + 内联 `<|ref|>label<|/ref|><|det|>[[x1,y1,x2,y2]]<|/det|>` 版面标签，坐标 **0–999 归一化**；表格输出为 HTML；阅读顺序隐含在生成流序中，**无独立契约保证**。
4. **互补性**：能力上成立——版面类别+bbox+流序阅读顺序+表格结构可一次前向产出，正是要从 VLM 下沉的三件事；但与 ticket 08 冻结的 CPU-only OpenVINO sidecar 拓扑直接冲突，引入即拓扑变更。第三方 benchmark 汇总显示其版面/阅读顺序指标（OmniDocBench TextEdit 0.073/ReadOrder 0.086）落后 PaddleOCR-VL-0.9B（0.035/0.043）与 MinerU2.5-1.2B（0.047/0.044）。
5. **同类简表**：PaddleOCR-VL-1.6（0.9B，Apache-2.0，官方 GPU/vLLM 路线，transformers 示例有 CPU 回退但非官方口径）；MinerU 3.4（pipeline 后端官方支持纯 CPU，Python 3.10–3.13，16GB RAM；许可为 Apache-2.0+附加条款：MAU>1亿或月营收>$20M 需商业许可、在线服务须标识）；dots.ocr/dots.mocr（MIT，vLLM≥0.11.0 官方集成，CPU 仅 issue 评论级方案 UNKNOWN）。
6. **vanchin 托管事实**：`vanchin/deepseek-ocr` 是阿里云百炼上第三方托管模型，推理提供方为**快手万擎**（非 DeepSeek 官方）；付费 API（北京地域目录价 0.216 元/1M tokens 输入/输出同价）；上下文 8192；RPM 500/TPM 1M；调用即图片数据出域至阿里云→快手万擎，引入需新 Provider 审查 + scoped J1。DeepSeek 官方自营 OCR API 是否存在：UNKNOWN。

**给后续 grilling 票的铺垫**：决策实质是「为本地确定性层引入 GPU 节点（DeepSeek-OCR/dots.ocr 类 VLM）」vs「留在 CPU-only 拓扑内选 MinerU pipeline 或 PP-StructureV3/PP-OCRv6 类级联方案」；DeepSeek-OCR 在 CPU-only 约束下不可行这一事实已将选择空间收敛。

研究资产：分支 `research/image-only-deepseek-ocr-eval`，commit `449d8637138a5f1affe29c86d47720e22255323e`，报告 `E:/java_project/RenderWeave-research-deepseek-ocr/docs/research/image-only-production-admission/deepseek-ocr-class-models-eval.md`。全程仅公开一手资料，无付费调用、无 Key、无真实数据。
