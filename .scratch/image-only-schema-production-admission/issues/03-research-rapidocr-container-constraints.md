# 核实 RapidOCR 生产容器的许可与运行约束

Type: research
Status: resolved
Claimed by: research/image-only-production-rapidocr-container
Blocked by: none

## Question

仅依据 RapidOCR、OpenVINO、PaddleOCR/PP-OCR 与相关模型发布方的一手仓库、许可文本、发行元数据和官方运行文档，冻结当前 exact capability `rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1` 进入 Linux production container 所需的事实：Python/OS/CPU/架构与 native runtime 兼容性，glibc/Alpine 约束，依赖与模型文件的再分发许可及 notice/attribution，模型下载或 vendoring 边界，离线构建与启动探测，可复现版本/hash 锁，以及同镜像 subprocess seam 与 sidecar 之间由事实造成的硬约束。无法由一手资料证明的内容必须显式列为未知，不得在本 ticket 选择最终部署架构。

## Answer

Disposition：`RESOLVED_CONDITIONALLY_FEASIBLE`；production admission 仍为 `BLOCKED_PENDING_ADMISSION_EVIDENCE`。

- exact RapidOCR/OpenVINO/model capability 已在 Linux/amd64、CPython 3.12、glibc 2.36、AVX2、CPU-only 下，以断网、只读、non-root、drop-all-caps 和资源上限完成 capability 与合成图 OCR A1 探针。首版可准入平台应收窄为 `linux/amd64 + CPython 3.12 + glibc >=2.28 + AVX2 + CPU`；ARM 保持 `UNKNOWN`，Alpine/musl 不准入。
- 当前两行 requirements 不是 production lock：binary-only resolver 会选到可安装但不可运行的 `omegaconf==2.0.0`。成功组合需要 `omegaconf==2.3.0` 以及从已验 sdist 在固定 builder 中提升的内部 `antlr4-python3-runtime==4.9.3` wheel，并锁定完整传递依赖、hash、OCI/OS 制品与 provenance。
- 三份 exact ONNX 已包含在 RapidOCR wheel；生产应在构建阶段提取、校验并只读预置，启动时禁止下载。最终镜像还需全离线 build、SBOM/CVE/malware、签名/attestation、目标节点资源/SLO/OOM/长稳和独立 replay 证据。
- RapidOCR、OpenVINO 与模型宿主的许可主线为 Apache-2.0，但 RapidOCR wheel 缺 LICENSE/NOTICE，exact 转换模型、传递包和系统库仍需最终 notice/provenance bundle 与人工法律审批；`LICENSE_DISPOSITION=J0_PENDING`。
- 上游不强制 sidecar。若接受 OCR 与 API 共用 network namespace/cgroup，可继续现有 stdin/stdout subprocess；若要求 OCR 独立断网或独立 CPU/RAM/OOM 熔断，sidecar 是硬要求。研究本身不选择 topology。

研究资产：分支 `research/image-only-production-rapidocr-container`，提交 `d4769826fa335cb2b7f3866710745cb956f29873`，[报告](E:/java_project/RenderWeave-research-rapidocr-container-constraints/docs/research/image-only-production-admission/rapidocr-container-constraints.md)。未访问 Provider、live、Key 或真实数据。
