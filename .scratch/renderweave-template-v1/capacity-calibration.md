# RenderWeave Template v1 容量校准档案

Status: virtualized-calibration-approved
Target issue: [19-security-capacity-acceptance.md](issues/19-security-capacity-acceptance.md)
Observed at: 2026-08-15

## Purpose

本档案只记录 Ticket19 数值冻结前的环境事实、校准协议与原始证据索引。它不是产品实现、Renderer Profile、READY 证明、运营 SLA 或用历史数值替代测量的许可。

## Current authority boundary

- v1唯一候选 READY target 已选为 `Linux x86_64-v2`、CPU-only、固定软件 raster/codec 与固定 SIMD 路径；任何容量冻结都必须明确区分该 target 的证据与 Windows/浏览器 rehearsal。
- 当前仓库通用 gate 最多产生 A1；视觉评测在严格输入范围有 Java producer + 独立 Python verifier 的 A2 模式，但该结论不能外推到尚不存在的 Template/Layout/Renderer corpus。
- 当前没有 A3 CI/分支保护，也没有 Template spec/conformance 专用 gate。进入实施规划可以建立 A1 规格证据；Renderer READY 仍须未来 A2 独立重放与 A3 强制。

## Environment facts

- 当前主机为 Windows，CPU 是 8C/16T Ryzen 7 9800X3D，约 31 GiB RAM；CPU 指令集满足并超过 x86_64-v2。它可以开发和rehearse校准harness，但不能直接代表Linux READY target。
- Docker Desktop Linux engine已按授权启动，报告Docker Engine `29.6.1`、`linux/x86_64`；宿主默认暴露16 CPU与约16 GiB内存，但校准容器必须显式覆盖，不能把默认值写入reference deployment合同。
- 本机Rust仅安装`x86_64-pc-windows-msvc` target；Java 21、Maven、Node 24可用。不存在可直接执行的Linux reference harness。
- 票据16引用的`E:\rust-app\busbox-render-engine`及其父目录当前不存在；`E:\rust`下也没有可替代的busbox/haibo源码。历史研究只能证明曾观察到预算维度，不能复核旧常量、测试或benchmark。

## Existing evidence shapes that may be reused

- A1 revision/input-hash/log/exit捕获外壳：[`tools/run-gate.ps1`](../../tools/run-gate.ps1)与[`CONSTITUTION.md`](../../CONSTITUTION.md)。现有`capacity` gate是opt-in且不属于`full`，也没有Template子 gate。
- 独立重放形状：Java生成固定projection/golden，再由[`tools/document-vision/verify_v45_projection.py`](../../tools/document-vision/verify_v45_projection.py)或[`tools/verify_layered_evaluation_gate.py`](../../tools/verify_layered_evaluation_gate.py)独立重算并做tamper检查。
- corpus形状：版本化manifest、固定case identity/hash、对抗case与closed fixture，现有实例位于`renderweave-inference/src/main/resources/replay-corpus/v1`及测试resources；它们不包含Template或Renderer语义。
- fault-injection形状：PostgreSQL trigger回滚、竞争winner、lease/checkpoint恢复已有测试先例；尚无closure drift、CapabilityState unknown commit、Resolver retry、Engine queue/cancel/seal、fetch SSRF或shard-loss场景。
- capacity报告形状：`CapacityBaselineTest`记录环境、输入规模、测量与并发且明确不宣称latency SLA；它没有Template/Renderer最坏路径、peak working set或合同阈值。

## Evidence that does not transfer

- 历史`20,000px / 150M pixels / 256MiB`只可成为候选边界输入。当前无法证明其计数对象、检查时点、机器、代码revision或测试结果，证据等级不足以成为v1合同。
- 旧Haibo的strict wire、Rust/Skia/codec/fetch测试思路可以转化为负例或fixture主题；旧DPI必填、并发fetch、环境/built-in字体fallback、缺少safe cancel/registry/descriptor/trace及codec默认均与新合同不兼容。
- 浏览器截图、Windows本机耗时、单侧unit test、同进程重复byte相等或旧视觉raster稳定性不能认证Linux target的layout、exact pixel或encoded byte语义。

## Required calibration record

在冻结数值前，后续记录至少必须包含：

1. pinned Linux image、CPU/RAM/ephemeral quota、machine manifest、toolchain/dependency版本与reference commit；
2. 每个预算轴的计数单位、施加阶段、边界下/边界值/边界上fixture及stable error oracle；
3. 组合最坏路径、warmup、重复次数、噪声控制、peak memory/CPU/wall time原始数据与推导余量；
4. allocation/call前原子预留、deadline/cancel/retry/fault injection及零partial output证据；
5. Windows或虚拟化rehearsal与Linux target证据的显式标签，不混合形成平均值；
6. 固定revision的输入hash、命令、原始输出、verifier结果及A1/A2等级声明。

## Approved virtualized target fingerprint

- Image reference: `docker.io/library/rust@sha256:8fa55b2f3ddf97471ab6a767bfa3f37e6bad0986ba823e75fea57e2a2a5c3073`
- Image ID: `sha256:7ea53b11a01d2d52b4af8fa073cd76ecbaf7dc385b88cc9d0ce8205a7c941ff1`
- Image platform and creation fact: `linux/amd64`, `2026-07-14T04:54:45.933926147Z`
- Toolchain: `rustc 1.97.0 (2d8144b78 2026-07-07)`, host `x86_64-unknown-linux-gnu`, LLVM `22.1.6`
- Compile target: `-C target-cpu=x86-64-v2`; reported required features include `cmpxchg16b/popcnt/sse3/ssse3/sse4.1/sse4.2`。
- Container controls verified by cgroup v2: `cpu.max=400000 100000`（4 CPU）与`memory.max=8589934592`（8 GiB）；network在离线measurement run中使用`none`，`pids-limit=512`。
- `4 GiB ephemeral`仍须由harness工作目录和输出累计breaker证明；Docker Desktop当前没有把该值作为容器root filesystem硬配额的证据，因此不能先宣称已强制。

## Current conclusion

环境与证据形状已经完成盘点；已批准启动Docker Desktop并用pinned Linux x86_64-v2容器在`4 vCPU / 8 GiB RAM / 4 GiB ephemeral` reference deployment边界内建立A1虚拟化校准。输入、作者、Expression、closure/Repeat、RenderDocument、诊断与物理几何的离散结构上限已经由产品边界冻结并记录在[`capacity-budgets-v1.json`](capacity-budgets-v1.json)；它们尚需`limit-1/limit/limit+1` fixture，但不等待性能benchmark重新选择。

一次性harness与原始结果记录在[`capacity-harness`](capacity-harness/README.md)及[`virtualized-results-2026-08-15.json`](capacity-harness/virtualized-results-2026-08-15.json)。三次相同50M-pixel/512MiB-decoded/256MiB-raw/256MiB-encoded候选run的峰值为`1,245,820..1,245,916 KiB`；把encoded buffer提高到512MiB并把粗粒度操作提高到10M layout/8M glyph后，峰值为`1,508,032 KiB`，约占8GiB cgroup的`17.98%`。这支持把2GiB作为下一轮单请求RAM ceiling候选，但不证明真实layout/shaping/codec/fetch/deadline。

对候选`50,000,000` pixels、edge `16,384`作独立静态上界计算：RGBA8 surface为`200,000,000 bytes`；按最坏16,384个scanline filter bytes、65,535-byte stored-deflate blocks、1MiB IDAT chunks及固定PNG结构计，非压缩PNG上界为`200,034,026 bytes`（约`190.767 MiB`），低于候选`512 MiB` encoded cap并留`336,836,886 bytes`余量。该计算只证明候选PNG容量自洽；JPEG仍由capped writer在最终profile算法下执行独立encoded-byte检查，不能从PNG公式推导。

资源、surface、layout operation、deadline与编码上限已结合静态公式、A1 rehearsal及产品边界完成用户决策，全部容量数值现已冻结到`capacity-budgets-v1.json`，其code/stage/zero boundary与comparator分别冻结到`capacity-oracles-v1.json`和`capacity-boundaries-v1.json`。当前175个contract cells生成525个隔离边界case，另有18个跨分组组合case，因此自动验收的contract-boundary严格下限为543；尚未完成的是这些fixture的真实执行、非容量atomic scenario拆分、真实layout/shaping/codec/fetch/cancel测量及A2/A3 READY认证，虚拟化结果不能替代这些后续证据。
