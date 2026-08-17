# 冻结 RapidOCR 生产拓扑与 capability admission

Type: grilling
Status: resolved
Claimed by: Kimi（2026-08-17 会话）
Blocked by: none

## Question

在生产信任谓词确定后，首个 exact RapidOCR capability 应采用同 API 镜像的 stdin/stdout subprocess，还是采用无 IP Unix-domain socket sidecar；如何冻结 `linux/amd64 + CPython 3.12 + glibc >=2.28 + AVX2 + CPU-only` 平台、完整 package/model/OCI/OS lock、只读模型、零下载/零 OCR egress、CPU/RAM/PID/timeout/OOM 隔离、telemetry opt-out、capability/synthetic/目标节点探针、SBOM/CVE/license/NOTICE J1、image/attestation digest、Java↔OCR protocol 和 rollback pair？任一供应链、许可、平台、资源或 readiness 证据缺失时，IMAGE_ONLY feature 应以什么稳定状态降级，而不影响确定性 Schema 功能？

## Comments

- 2026-08-17（Kimi 会话，live 证据输入）：试用矩阵显示 OBSERVE 拒绝率与 `maximumTotalCalls=7` 预算零冗余是难图上的主要失败放大器（Plus 难图 OBSERVE 5 次尝试后 `LIVE_CALL_BUDGET_EXHAUSTED`；Flash 两图 OBSERVE 合同违规 0/2，明细见 ticket 07 Comments）。这强化了"把版面/阅读顺序等更多确定性工作下沉到本地 OCR 层、让 VLM 少做像素级 grounding"的方向；所有者已提出 DeepSeek-OCR 类强 OCR/版面模型选项（map fog 已记录）：自托管触发本票全套供应链/平台准入，第三方托管则触发新 Provider 审查与新 scoped J1。本票冻结拓扑时应为该选项留出决策位。

## Answer

2026-08-17 经两轮 grilling 冻结（全部按所有者确认的推荐）：

### 拓扑（继承 ticket 04，不再重议）

生产 OCR = 无 IP 网络、仅 UDS、独立 cgroup/resource limit 的 sidecar（ticket 04 第 4/20 条）；同镜像 stdio subprocess 只保留 dev/offline 路径。API 镜像保持纯 JRE 不动（现 `docker/api.Dockerfile` 为 Alpine/musl，本就不准入 OCR 运行时——glibc≥2.28 硬约束）。

### 精确身份冻结

1. **镜像基座**：`python:3.12-slim-bookworm`（glibc 2.36），按 digest pin；平台 `linux/amd64 + CPython 3.12 + glibc≥2.28 + AVX2 + CPU-only`（ticket 03 证据）；ARM 不准入、Alpine/musl 不准入。
2. **依赖锁**：`pip --require-hashes` 全量传递依赖锁；`omegaconf==2.3.0` + 内部 `antlr4-python3-runtime==4.9.3` wheel 直接入库（仓库治理取向为全离线构建）；三份 exact ONNX 模型构建期从 RapidOCR wheel 提取、SHA-256 校验、只读预置、启动零下载。
3. **协议**：HTTP/1.1 over UDS，server 用 **stdlib**（`http.server`+`UnixStreamServer`，零新增依赖）；JSON envelope 沿用现 stdio 协议形状，Java 侧 DTO/合同测试复用；`/health` 与 capability 端点天然落在 HTTP 上。代码归属：新 `docker/ocr-sidecar/` 构建上下文，复用 `tools/document-vision/` 现有 OCR 调用逻辑。
4. **资源上限**（现在就冻结保守值，探针门禁兜底）：2 CPU / 2GB RAM / PID 64 / 60s 超时 / OOM kill 作用域仅限 sidecar 容器；read-only rootfs、non-root、drop-all-caps、无 IP 网络。
5. **capability identity 不变**：沿用 `rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1`（同模型同运行时），v46 Profile 最小 diff 不受影响。
6. **探针集分两层**：启动阻塞层 = capability 探针（报告 exact id，不符即 fail-closed）+ 合成图 OCR 探针（固定输入固定输出断言）；长稳/资源证据层推迟给 ticket 11（运维遥测），不做启动阻塞。
7. **license 出路**：参照 ticket 06 模式——构建期产出完整 notice/provenance bundle（SBOM 附 license 字段，补齐 RapidOCR wheel 缺失的 LICENSE/NOTICE），所有者自审 J1 接受 Apache-2.0 主线；不阻塞首个生产版本等正式法务。
8. **rollback pair**：首个生产版本无上一份认证 capability，rollback = `DOCUMENT_VISION_UNAVAILABLE` fail-closed（04 第 5 条双轴 readiness，确定性 Schema 站点不受影响）；未来第二份认证 capability 出现时才有 pair 切换语义。
9. **构建门禁**（执行层要求，继承 ticket 03）：全离线 build、SBOM/CVE/malware 扫描、签名/attestation digest；任一缺失则 capability 不准入、feature fail-closed。

### 处置与下游依赖

- **DeepSeek-OCR 类强 OCR/版面模型**：本票不评估；已毕业为独立后续票「评估 DeepSeek-OCR 类强 OCR/版面模型强化本地确定性层」（research）。更换 capability = 新 capability id + 新 Profile + 全套供应链/许可证据重来。
- ticket 05：本票是其最后一个阻塞项，现已解除。
- ticket 11：长稳/资源探针与 OCR sidecar 遥测归其冻结。
