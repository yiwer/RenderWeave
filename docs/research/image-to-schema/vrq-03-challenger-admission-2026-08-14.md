# VRQ-03：离线 OCR / layout challenger 准入核验

> 状态：研究输入，不是依赖批准、许可意见或许可 J1。调研快照：2026-08-14。
> 范围：GitHub [#4 / VRQ-03](https://github.com/yiwer/RenderWeave/issues/4) 与已批准的
> [图片识别质量修复 delta](../../../specs/changes/20260814-visual-recognition-quality-repair-offline-admission.md)。
> 证据口径：仅使用项目 issue/spec、上游官方文档、官方源码/发布页、PyPI 元数据与上游官方模型仓。
> 文中 **[事实]** 表示来源直接支持，**[推断]** 表示把事实映射到 RenderWeave，**[建议]** 表示尚待 ticket 验证的工程选择。
> 安全口径：本次只读取远端元数据；没有下载模型或安装包、没有安装依赖、没有执行 challenger、没有读取密钥、没有调用 Provider。

## 1. 结论

截至 2026-08-14，VRQ-03 的可执行结论应是：

| Challenger | 当前 disposition | 主要原因 | 解除条件 |
| --- | --- | --- | --- |
| PP-StructureV3 | `NOT_ADMITTED` | 尚无项目级许可 J1；默认缺模型目录时会自动下载；完整依赖锁、所有启用权重、资源实测和两次确定性证据均不存在 | 独立许可 J1 + 完整 hash/SBOM + 预置本地模型 + deny-network 负测 + Windows 资源与确定性 A1 |
| Tesseract TSV/hOCR | `NOT_ADMITTED` | 引擎与官方 `tessdata_fast` 均有 Apache-2.0 上游声明，但尚无项目级许可 J1，也没有预置二进制/语言包及资源、确定性 A1 | 独立许可 J1 + 引擎/语言包本地 SHA-256 + deny-network 负测 + Windows 资源与确定性 A1 |
| optional third challenger | `NOT_ADMITTED_BY_DESIGN` | #4 已把首轮第三路冻结为 `NONE` | 不能在本 ticket 中解除；未来必须先有新的窄规格/ticket 与新的冻结 identity |

**[推断]** 这不是能力否定。它是 AC-VRQ-007 的正常 fail-closed 结果：研究发现的 Apache-2.0 声明不等于
RenderWeave 的许可 J1；缺依赖、hash、资源边界或离线保证时不得运行 challenger。

**[建议]** Tesseract 的供应链面更小、当前官方 Windows 资产更容易做内容寻址；PP-StructureV3 的潜在 layout
能力更强，但其实际运行面是 `PaddleOCR + PaddleX + PaddlePaddle + 多个模型仓 + pre/post-process`，必须作为一个
复合 capability 冻结，不能只写 `PP-StructureV3` 名称或只锁一个 Python 包版本。

## 2. 共同准入规则

### 2.1 许可 J1 必须分开

**[事实]** #4 要求分别审查代码许可和模型权重许可，并要求 PP-StructureV3 与 Tesseract 有可区分的
`J1/J0` license disposition。已批准 delta 进一步规定：许可 J1 未成立时以 `NOT_ADMITTED` 正常结束，不等待、
不下载、不运行 challenger。

**[建议]** 至少需要两个独立的人工决定记录，不能用一个“开源 OCR 可用”的总括判断代替：

1. `PP_STRUCTURE_V3_LICENSE_J1`：覆盖 PaddleOCR、PaddleX、PaddlePaddle、实际启用的每个模型仓/权重、再分发与
   NOTICE 义务，以及锁文件中的传递依赖；
2. `TESSERACT_LICENSE_J1`：覆盖 Tesseract 引擎、选择的官方 Windows 发行资产、`tessdata_fast` 的
   `eng`/`chi_sim`/`osd` 权重与其再分发方式。

研究笔记只能把上游许可声明交给审批人，不能产生 J1。当前两项都应记为 `J0_PENDING`。

### 2.2 capability identity 不能只用版本号

**[建议]** 每个 capability identity 至少绑定：

- challenger ID、OS/arch、Python 或 native runtime；
- 每个包/可执行文件的名称、版本、来源 URL、SHA-256 与发布 provenance；
- 每个模型仓的 immutable revision、每个实际加载文件的 SHA-256；
- inference backend、CPU/GPU 模式、线程数与数值精度；
- 完整启用/禁用模块、pre-process、post-process、排序和 IR projection 版本；
- runtime network policy=`DENY`，并把任何下载尝试作为硬失败；
- CPU/GPU/RAM/disk、启动时间、单 case timeout、stdout/stderr 大小等冻结上限；
- code-license disposition、weight-license disposition 与对应 J1 identity；
- 两次运行的 canonical observation digest 和 payload-safe metric digest。

本地绝对路径、wall-clock 时间和 OCR 文本不进入 canonical identity 或常规证据。

## 3. PP-StructureV3

### 3.1 当前可锁的软件版本

| 层 | 2026-08-14 可用版本/identity | 官方证据 | 供应链观察 |
| --- | --- | --- | --- |
| PaddleOCR | `3.7.0`; tag commit `b03f46425e8ff4442b268ce449e3eef758146cd4` | [官方 release](https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.7.0)、[PyPI 3.7.0](https://pypi.org/project/paddleocr/3.7.0/) | wheel SHA-256 `c0f0a81ad4112727f30c6fcf986ac0ef6a120d31ee0991a01fae0357ee32d338`；PyPI 标记为非 Trusted Publishing |
| PaddleX | `3.7.2`; tag commit `ffb64904d23708863ff5b8da312a5cbd52a7f462` | [PyPI 3.7.2](https://pypi.org/project/paddlex/3.7.2/) | wheel SHA-256 `f1678bf650bbaccfd8f0d4e49d0ae631b4685c829fdae6e802ccd90d4fcb9a7f`；PyPI 提供绑定该 commit/tag 的 Trusted Publishing attestation |
| PaddlePaddle CPU | `3.3.1`; tag commit `7688495538f4d6c1893f084dd238a402e8f68ab6` | [PyPI 3.3.1](https://pypi.org/project/paddlepaddle/3.3.1/)、[官方 Windows 安装要求](https://www.paddlepaddle.org.cn/documentation/docs/zh/install/pip/windows-pip_en.html) | CPython 3.12 / Windows x64 wheel SHA-256 `324b5122cf3887dfbd15db17f36e2421ef923fd4569d26111bf1a21fe84d442b`；PyPI 标记为非 Trusted Publishing |

**[事实]** `paddleocr==3.7.0` 的元数据只把 PaddleX 约束为 `>=3.7.0,<3.8.0`，并不会替使用者锁定一个精确
PaddleX patch；PP-StructureV3 所在的 `doc-parser` extra 还会扩大依赖集合。
[PaddleOCR 安装文档](https://github.com/PaddlePaddle/PaddleOCR/blob/v3.7.0/docs/version3.x/installation.en.md)

**[推断]** manifest 应显式锁定 `paddleocr==3.7.0`、`paddlex==3.7.2`、选定平台的
`paddlepaddle==3.3.1`，并用带 hash 的完整 lock/SBOM 冻结所有传递依赖。以上三个 direct package hash 不足以
证明完整环境已锁定。

### 3.2 代码与模型许可是两个事实集合

**[事实]** PaddleOCR v3.7.0 源码仓的 [LICENSE](https://github.com/PaddlePaddle/PaddleOCR/blob/v3.7.0/LICENSE)、
PaddleX 3.7.2 的 [PyPI 元数据](https://pypi.org/project/paddlex/3.7.2/) 以及 PaddlePaddle 3.3.1 的
[PyPI 元数据](https://pypi.org/project/paddlepaddle/3.3.1/) 均声明 Apache-2.0。

首轮最小 `layout + OCR` 配置涉及的三个官方 PaddlePaddle 模型仓也分别声明 Apache-2.0：

| 模型 | 官方 revision | 主要权重 `inference.pdiparams` SHA-256 | 大小 |
| --- | --- | --- | ---: |
| `PP-DocLayout_plus-L` | `aa52b8528c84f9b1a34ac3a88fe0e576edb9d11d` | `24ca3e2e442164505e250deef59f7ee9a54ea12dd32875c9cd6155d959dc97da` | 129,307,978 B |
| `PP-OCRv5_server_det` | `ca867c897ecbca8873081573a802ad70d499cb94` | `183146fe9d9910352f68482f623bcbbb9fa7b9e8fa1463b9ad288cef00524d2d` | 87,932,887 B |
| `PP-OCRv5_server_rec` | `b26c3587fda8da3c8ec0ce357214b4d661ff1558` | `63853f062a5f4089befc16f565a68277618e0da5cb45468b49d11079de0ada77` | 84,390,117 B |

来源：[layout 模型仓](https://huggingface.co/PaddlePaddle/PP-DocLayout_plus-L)、
[det 模型仓](https://huggingface.co/PaddlePaddle/PP-OCRv5_server_det)、
[rec 模型仓](https://huggingface.co/PaddlePaddle/PP-OCRv5_server_rec)。上述 revision、license metadata、文件大小和
LFS SHA-256 可从三个官方 revision API 重算：
[layout](https://huggingface.co/api/models/PaddlePaddle/PP-DocLayout_plus-L/revision/aa52b8528c84f9b1a34ac3a88fe0e576edb9d11d?blobs=true)、
[det](https://huggingface.co/api/models/PaddlePaddle/PP-OCRv5_server_det/revision/ca867c897ecbca8873081573a802ad70d499cb94?blobs=true)、
[rec](https://huggingface.co/api/models/PaddlePaddle/PP-OCRv5_server_rec/revision/b26c3587fda8da3c8ec0ce357214b4d661ff1558?blobs=true)。

**[推断]** 这为单独的模型许可审查提供了较强的一手依据，但仍不是 RenderWeave 的 J1。Apache-2.0 的通知、
归属、修改标记和再分发义务需要由审批人针对实际部署方式确认；完整 lock 中的其他包也需要 license scan。

### 3.3 pipeline、离线行为与下载风险

**[事实]** PP-StructureV3 是组合 pipeline，而不是单一模型。官方用法页列出 layout、OCR、方向、去畸变、
table、seal、formula、chart 等模块；多个 `*_model_dir` 参数未指定时，官方模型会被自动下载。
[PP-StructureV3 用法](https://paddlepaddle.github.io/PaddleOCR/main/en/version3.x/pipeline_usage/PP-StructureV3.html)

**[事实]** 当前文档默认会启用 text-line orientation、seal、table 与 formula 等能力中的若干项；只锁上表三个
模型却沿用默认配置，会产生未声明的额外模型下载或加载面。官方 API 支持通过 `*_model_dir` 提供本地模型目录，
也支持显式开关这些子模块。

**[建议]** 首轮 R2 manifest 应是一个显式、最小、CPU-only 的 layout+OCR 配置：

- backend=`paddle` / CPU；不引入 `paddlepaddle-gpu`、CUDA、TensorRT 或远端 API；
- 显式锁定 layout、text detection、text recognition 的本地目录和完整文件集合；
- 不需要的 orientation、unwarping、seal、table、formula、chart、region 模块全部显式关闭；
- 若 PP-StructureV3 3.7.0 无法在该配置下提供所需 observation/order 信息，则 fail-closed，先扩展 manifest，
  不能让运行时自行补齐模型；
- OS/process 层拒绝网络；任何 DNS/socket/download 尝试均使 capability preflight 失败。

这个建议必须由 adapter 的黑盒测试确认；不能依据文档把“传了本地目录”等同于已证明零网络。

### 3.4 Windows、CPU/GPU 与资源

**[事实]** PaddlePaddle 当前 Windows 指南要求 64-bit Windows 10/11 Pro/Enterprise、x86_64、64-bit
Python 3.9–3.13 与 pip 20.2.2+；CPU 版本可直接使用，GPU 路线要求 NVIDIA GPU，文档列出 CUDA 11.8/12.6/12.9
等对应包，并指出 Windows 不支持 NCCL/distributed。
[Windows 安装指南](https://www.paddlepaddle.org.cn/documentation/docs/zh/install/pip/windows-pip_en.html)

**[事实]** 三个上表核心权重本身合计 301,630,982 bytes；这只是权重传输大小，不包括 Python、PaddlePaddle、
PaddleX、临时张量、图优化、OpenCV、模型配置或运行峰值。官方 PP-StructureV3 页面给出部分模型的测试时间与
storage size，但没有承诺一个适用于 RenderWeave 60-case corpus 的完整 pipeline RAM/disk 上限。

**[推断]** 不能从模型文件大小推导 RAM/VRAM 或 p95 latency。VRQ-03 需要在实际目标 Windows 主机上预声明
上限并测量启动、p95、peak RSS、临时磁盘和失败率；在这份 A1 证据出现前，资源谓词必须是 `MISSING`。

### 3.5 确定性与 identity

**[事实]** 上游文档允许选择 CPU/GPU、线程、precision、MKL-DNN、阈值和多个 pre/post 参数；这些选项会改变
结果或运行行为。上游没有为“PP-StructureV3 3.7.0 + 任意 backend”提供跨运行 byte-determinism 保证。

**[建议]** 首轮先锁 CPU、线程数、`fp32`、所有阈值、NMS/unclip/merge、图像 normalization 与 projection 排序；
identity 同时包含三个模型 revision 和本地全文件 SHA-256。然后按 spec 对同一 DEV 输入实际运行两次，并比较
canonical `DocumentObservationIR/1.0`；不能把包版本相等当成确定性证据。

### 3.6 当前 disposition

`PP_STRUCTURE_V3 = NOT_ADMITTED`，reason codes 建议至少包括：

- `LICENSE_J1_MISSING`
- `TRANSITIVE_LOCK_MISSING`
- `LOCAL_MODEL_SET_NOT_VERIFIED`
- `RUNTIME_NETWORK_DENIAL_NOT_VERIFIED`
- `WINDOWS_RESOURCE_ENVELOPE_MISSING`
- `TWO_RUN_DETERMINISM_MISSING`

在这些条件满足前，不应创建 venv、下载模型或运行 corpus。

## 4. Tesseract TSV/hOCR CPU baseline

### 4.1 当前可锁的软件与 Windows 资产

**[事实]** Tesseract 当前稳定 release 是 `5.5.3`（2026-07-24）。tag 是带有效签名的 annotated tag：
tag object `6951ffe10ce031374bcd04fe400811da1e7e04ad`，指向 commit
`db0ec62f81b0737fbbe184d8fea40af5738f8eef`。
[官方 release](https://github.com/tesseract-ocr/tesseract/releases/tag/5.5.3)

**[事实]** 该 release 提供官方 x64 Windows 安装资产：

```text
tesseract-ocr-w64-setup-5.5.3.20260724.exe
size: 26,573,224 bytes
sha256: bee9e3434bd94fd65387d9be28cd467a41f61b1275383b55b0f59a1331270ae4
```

发布元数据与 digest 可由 [GitHub release API](https://api.github.com/repos/tesseract-ocr/tesseract/releases/tags/5.5.3)
重算。官方安装文档也说明 Windows 可使用 Tesseract 5 installer 或 Visual Studio build artifacts；引擎依赖
Leptonica 读取图片。[安装文档](https://tesseract-ocr.github.io/tessdoc/Installation.html)、
[引擎 README](https://github.com/tesseract-ocr/tesseract/tree/5.5.3)

**[推断]** VRQ-03 可优先锁上述官方 release asset，而不必依赖 UB Mannheim 第三方 installer；但仍需在隔离的
预备步骤中验证 Authenticode/文件 hash、枚举随安装包分发的 DLL/NOTICE，并生成本地 SBOM。研究阶段没有下载或
执行该资产。

### 4.2 代码与 traineddata 许可

**[事实]** Tesseract 5.5.3 引擎的 [LICENSE](https://github.com/tesseract-ocr/tesseract/blob/5.5.3/LICENSE)
是 Apache-2.0。官方 `tessdata_fast` 仓说明仓内数据均为 Apache-2.0，并包含 `eng`、`chi_sim` 与 `osd`；其
[LICENSE](https://github.com/tesseract-ocr/tessdata_fast/blob/87416418657359cb625c412a48b6e1d6d41c29bd/LICENSE)
也是 Apache-2.0。

可冻结的 `tessdata_fast` revision 是 `87416418657359cb625c412a48b6e1d6d41c29bd`：

| 文件 | Git blob SHA | 大小 |
| --- | --- | ---: |
| `eng.traineddata` | `bbef4675053b5b468cdb477053e28b1c698ba08e` | 4,113,088 B |
| `chi_sim.traineddata` | `388bac276d033d06e5ed5ba7a7ad14ae58f97dab` | 2,469,156 B |
| `osd.traineddata` | `527457ca8f8fe1fda7c2f88bce3c0e4be12be9d0` | 10,562,727 B |

来源：[官方 frozen tree](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd)、
[官方语言/模型文档](https://github.com/tesseract-ocr/tesseract/blob/5.5.3/doc/tesseract.1.asc#languages-and-scripts)。

**[事实]** Git blob SHA 是 Git 对象 identity，不是原始文件 SHA-256。

**[建议]** admission manifest 应同时绑定 repository commit/blob identity 和预置本地文件 SHA-256。研究没有下载
这些文件，所以不能在此填造 SHA-256。模型许可仍需独立于引擎代码形成 `TESSERACT_LICENSE_J1`。

### 4.3 离线行为与固定调用合同

**[事实]** Tesseract 把 engine 和 traineddata 作为两个本地安装部分；运行时通过 `--tessdata-dir`（推荐）或
`TESSDATA_PREFIX` 查找本地语言/脚本模型。命令行支持 TSV 与 hOCR；hOCR 包含 bbox/confidence，TSV 是明确的
输出 config。多个语言可用 `-l` 的 `+` 组合。
[man page](https://github.com/tesseract-ocr/tesseract/blob/5.5.3/doc/tesseract.1.asc)、
[命令行文档](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html)

**[推断]** 官方 CLI 没有文档化的运行时自动下载机制；当 binary、config 或 traineddata 缺失时，适配器应直接
失败。即便如此，RenderWeave 仍应在 OS/process 层 deny network，并用负测证明没有隐式联网，而不是仅依靠
“Tesseract 通常离线”的假设。

**[建议]** 首轮 baseline 合同冻结为：

- exact binary=`5.5.3`，CPU-only，不启用 OpenCL；
- traineddata=`tessdata_fast` 上述 revision，语言=`chi_sim+eng`；只有实际用 orientation/script detection 时才加载
  `osd`，否则从 manifest 移除；
- `--oem 1`、固定 `--psm`、固定 DPI/thresholding 参数、固定 TSV 或 hOCR parser/projection identity；
- `--tessdata-dir` 指向已 hash 的只读目录；进程禁止网络；
- `OMP_THREAD_LIMIT=1`。官方 man page 说明该变量可把多线程 build 限制到一个 CPU core；
- stdout/stderr 只进入受限解析器和 payload scan，OCR 文本不进入常规证据。

### 4.4 Windows、资源与确定性

**[事实]** 官方 5.5.3 Windows asset 加上述三个 `tessdata_fast` 文件的传输大小合计 43,718,195 bytes；这不是
安装后磁盘或 peak RAM 承诺。上游没有给出 RenderWeave corpus 上的 RAM、p95 latency 或 byte-determinism 保证。

**[推断]** Tesseract 无需 GPU，适合成为独立 CPU error-source baseline，但“更轻”不是已通过资源门。仍需在
目标 Windows 主机上测启动、p95、peak RSS、disk、timeout、non-zero exit 和两次 canonical IR 等价。

### 4.5 当前 disposition

`TESSERACT = NOT_ADMITTED`，reason codes 建议至少包括：

- `LICENSE_J1_MISSING`
- `WINDOWS_BINARY_NOT_STAGED`
- `TRAINEDDATA_SHA256_MISSING`
- `RUNTIME_NETWORK_DENIAL_NOT_VERIFIED`
- `WINDOWS_RESOURCE_ENVELOPE_MISSING`
- `TWO_RUN_DETERMINISM_MISSING`

它比 PP-StructureV3 更接近“可做本地预置”的供应链形态，但在 J1/A1 之前仍不得运行 corpus。

## 5. Optional third challenger

**[事实]** #4 明确把首轮 optional third challenger 固定为 `NONE`。虽然上位 delta 允许在 DEV 前从 docTR 与
PaddleOCR-VL 中预声明至多一个，但 ticket 的更窄约束已经选择不启用第三路。

**[推断]** 因此本轮不应为 docTR、PaddleOCR-VL、Surya 或其他库创建 package/model manifest、下载缓存或空 adapter。
`NONE` 本身应进入冻结 experiment identity，避免后续看到 DEV 结果后再加入第三模型。

## 6. 可机读 manifest 的最低字段

下面是 VRQ-03 实现可采用的领域字段集合，不绑定具体文件路径：

```text
manifestVersion
challengerId
disposition
reasonCodes[]

codeArtifacts[]:
  name, version, source, immutableRevision, sha256, provenance
modelArtifacts[]:
  modelId, source, immutableRevision, fileSetDigest, licenseExpression

license:
  codeDisposition, weightDisposition, j1Identity

runtime:
  os, arch, runtimeVersion, backend, device, precision, threads
  runtimeNetworkPolicy, runtimeDownloadsAllowed

pipeline:
  enabledModules[], disabledModules[]
  preprocessingIdentity, postprocessingIdentity, projectionIdentity

resources:
  maxStartupMillis, maxCaseMillis, maxPeakRamMiB, maxDiskMiB, maxGpuVramMiB

evidence:
  manifestVerifierIdentity, negativeTestDigest, twoRunDeterminismDigest
  providerAttempts, providerReservations, providerCostMicrosCny
```

Hard invariants：

- `j1Identity` 缺失时只能 `NOT_ADMITTED`；
- 任一启用模块没有 package/model/file hash 时只能 `NOT_ADMITTED`；
- `runtimeDownloadsAllowed` 必须为 `false`，网络探测或下载尝试必须硬失败；
- 资源边界是预声明上限，不得从执行结果倒填；
- `providerAttempts == providerReservations == providerCostMicrosCny == 0`；
- manifest/evidence 不含图片、Prompt、OCR/模型/Candidate 原文、bbox 全量、RootDocument、API key 或本地绝对路径。

## 7. 后续 ticket 的准入顺序

1. 先生成 `NOT_ADMITTED` manifests 与 hash/network/resource negative tests，不创建环境、不下载模型。
2. 由用户分别给出 PP 与 Tesseract 的 exact license J1，或保留 `J0_PENDING` 正常结束。
3. 只有对应 J1 存在，才可在新的、受限的本地准备 ticket 中预置 artifacts；下载/安装动作必须有自己的明确授权与
   可恢复边界，不能由运行时隐式发生。
4. 对预置内容独立重算所有 SHA-256、生成 SBOM/license inventory，并在断网下做 missing/hash-drift/download-attempt
   负测。
5. 在目标 Windows 主机测资源边界与两次 deterministic smoke；只有全部通过，manifest 才可变为 `ADMITTED`。
6. `ADMITTED` 只允许进入冻结 DEV shadow，不允许读 HOLDOUT、写 Candidate、改默认 AcquisitionPolicy 或请求 live J1。

## 8. 研究边界

- 没有对任何许可证作法律意见；Apache-2.0 上游声明只作为审批输入。
- 没有验证下载后的实际包/模型内容、代码签名、SBOM、CVEs、Windows 启动、资源或输出确定性。
- 没有把 corpus v2 提升为 AC-021 权威 corpus。
- 没有修改 Prompt 12/7/4、Candidate Prompt 5、Profile、validator、pipeline、IR/1.0 或 PostgreSQL 状态机。
- 没有创建 live authorization；N7-04/N7-05 状态和 identity 未被复用。
