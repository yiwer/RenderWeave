# 02 — 让 RapidOCR/OpenVINO 通过主 seam 产出 source-pixel IR

**Parent:** N8 / R0

**Anchor:** approved successor spec、ADR-0036、commit c12f23d76a6fc76a6a38042ff89bbd166e6012b5

**What to build:** 把现有本地 RapidOCR/OpenVINO 进程收进 VisualEvidenceAcquisition Implementation。调用者不再依赖 Python process DTO，真实本地 adapter 可以从规范化图片生成 DocumentObservationIR/1.0。

**Blocked by:** 01 — 冻结 VisualEvidenceAcquisition 与 DocumentObservationIR/1.0 合同。

**Status:** planned

## Acceptance criteria

- [ ] PNG、JPEG、CMYK JPEG 显式 BGR 解码和多图片输入均能产生 exact artifact-bound observations。
- [ ] RapidOCR box 在 IR 中保持 source-pixel 坐标，不提前投影到 0..10000。
- [ ] IR 保留 adapter-native confidence、scale identity、engine/model manifest、预处理、后处理和顺序推导 identity。
- [ ] 文字继续执行 v45 的 NFC、空白折叠、控制字符、单行和总字节上限。
- [ ] missing executable/model、manifest drift、timeout、malformed output、stderr noise、越界 box 和超限输出均返回稳定 code。
- [ ] capability 或输出失败全部发生在 Provider reservation 前。
- [ ] adapter 使用最小环境、默认零网络，不引入 PP-StructureV3、Tesseract、docTR 或其他 challenger。
- [ ] 图片、Base64、OCR text、路径和 Python stderr 不进入常规 evidence。

## Required gate/evidence

- [ ] adapter 黑盒 contract tests 通过。
- [ ] Python adapter 回归和真实 document-vision runtime canary 通过。
- [ ] CMYK、PNG/JPEG、多图、空结果、超限和 timeout fixtures 通过。
- [ ] payload scan 通过并形成 A1。

## Guardrails

- Provider attempts、reservations、cost 必须为 0；不读取 API Key。
- RapidOCR 是 R0 唯一生产 baseline adapter；不提前抽象或接入 challenger。
- OCR text 只在受控内存中存在，不进入 checkpoint、DB、普通日志、evidence、trace 或 report。
- 不修改 product-v45 Profile、Prompt、pipeline 或 capability snapshot。
