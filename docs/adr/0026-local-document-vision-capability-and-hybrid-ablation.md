# ADR-0026：本地 Document Vision 能力与 Hybrid 消融门

- 状态：Accepted
- 日期：2026-08-10
- 关联：AC-VR-005、AC-VR-010、P6/T6-5 N5、ADR-0023、ADR-0025

## 背景与约束

多尺度视图能让视觉模型看到小字，但仍要求同一个生成模型同时完成字符辨认、版面定位、层级推理和
Schema 抽象。密集站牌、菜单、时刻表等场景中，字符辨认错误会在第一阶段直接变成字段遗漏；后续
hierarchy、binding 和本地 Candidate materializer 只能忠于这个不完整计划，不能恢复从未观察到的元素。

本地 OCR/layout 可以提供小字位置和文本候选，但 OCR 文本同样是不可信输入：它可能错误、缺失，也可能
包含 prompt injection。它不能变成第二事实源、不能自动创造字段或关系，也不能进入 checkpoint、常规日志
或评测 evidence。历史 Profile 必须保持字节稳定；缺少本地能力时不得静默退回另一个未标识流程。

[RapidOCR 官方项目](https://github.com/RapidAI/RapidOCR)支持离线部署及 OpenVINO，工程代码为
Apache-2.0，但明确区分 OCR 模型版权；[官方模型矩阵](https://rapidai.github.io/RapidOCRDocs/latest/model_list/)
说明 PP-OCRv6 small 从 RapidOCR 3.9.0 起支持 OpenVINO；
[推理引擎文档](https://rapidai.github.io/RapidOCRDocs/main/install_usage/rapidocr/how_to_use_infer_engine/)
给出 Det/Cls/Rec 全部绑定 OpenVINO 的配置。RapidOCR 官方优先推荐 ONNX Runtime CPU，但本机受控探针中
ONNX Runtime 1.22.1 与 1.28.0 均发生 Windows native DLL 加载失败，OpenVINO 2026.0.0 能稳定运行，因此
本节点按可复现证据选择后者，而不是宣称它在所有部署环境都更优。

## 决策

1. **新增窄域 `DocumentVisionPreprocessor` port。** 输入仅为已经由 RenderWeave 规范化、带内容哈希和
   已验证尺寸的 PNG/JPEG 内存字节；输出仅包含 artifact identity、有序文本行、原图 0..10000 bbox、
   有限置信度桶和有界文字。port 不提供任意文件、HTTP、模型下载或业务写能力。
2. **参考 adapter 固定为 RapidOCR 3.9.2 + OpenVINO 2026.0.0 + PP-OCRv6 small。** Det/Cls/Rec 三个
   ONNX 文件按逐文件 SHA-256 和规范 manifest digest 绑定；能力 ID 同时包含 engine/runtime/model
   identity。模型文件不进入仓库和普通构建，部署者必须单独取得并完成版权/许可证审查。
3. **Java 通过无 shell 的固定子进程协议调用 Python。** 可执行文件、脚本和 model root 启动时解析为
   绝对真实路径；stdin/stdout 是唯一图片/文字通道；stderr 丢弃；继承环境被清空，只保留进程运行所需的
   OS/临时目录变量并显式移除 Key/proxy 面。Python 在加载 OCR runtime 前禁用 Python socket 网络。
4. **协议双侧 strict 且有硬边界。** 请求/响应拒绝 duplicate、unknown、trailing、null primitive 和
   scalar coercion；最多 10 张、单图 10 MiB、总请求 42 MiB、最长边 4096、总像素 16M；最多 512 行、
   单行 256 UTF-8 bytes、总文字 32 KiB、响应 512 KiB、执行 1..60 秒。任一越界或 engine 异常只返回
   稳定、无载荷 diagnostic code。
5. **新增不可变 pipeline 4.2 / v7 Hybrid Profile。** Flash、Plus、Max 各有一份隐藏 GENERIC Profile，
   精确绑定 capability ID、OCR policy prompt 和既有 multiscale identity；仍只有 OBSERVE/HIERARCHY/
   ELEMENT_BINDING 三次 Provider 调用，STRUCTURE 继续本地零调用。v7 暂不进入产品目录。
6. **OCR 是每次执行的一份 ephemeral secondary observation。** worker 在首次视觉阶段前处理一次，并将
   同一 observation 传给三个 stage；OCR 文字可以帮助模型核对小标签、双语标签和重复行，但必须与像素、
   view/region 空间证据相互印证。OCR 不得单独产生 element/entity/edge/type/constraint/required。
7. **持久化边界是强不变量。** task v5 可以在调用瞬间携带 observation；checkpoint 仍为 3.0，只保存
   已验证的原图 region/evidence；Candidate、problem、attempt、journal、日志和报告均不得包含 OCR text、
   line ID 或 OCR-specific payload。Profile snapshot 可以且必须保存 capability identity。
8. **取消优先于本地预处理。** 已标记取消的 run 在读取 blob、启动 OCR 或预留 Provider 费用前完成确认；
   OCR timeout/缺 binary/缺模型/identity drift 使精确 v7 run 可读失败，不自动执行 v6/v4。
9. **是否成为默认由同 corpus 消融决定。** `pure/full-image` 使用 N2 的 product-v4 已有结果；N5 对同一
   dense/small-text cases 比较 v6 GENERIC multiscale 与 v7 GENERIC hybrid。只有 field recall 绝对提升
   至少 0.05 且 critical hallucination 不增加，且 token/费用/延迟完整时，Hybrid 才有资格进入 N7
   默认候选；否则保持可插拔、`EXPERIMENTAL` 和产品隐藏。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| 只依赖多模态 LLM | 部署最少 | 字符识别、层级推理竞争同一视觉上下文；小字遗漏不可恢复 | N2 已显示 dense/小字召回不足，N4 只能改善可见度 |
| 浏览器/客户端 OCR | 服务端无 Python | Web/Desktop 能力漂移、输入重复处理、结果身份难冻结 | v1 需要服务端可恢复且 Profile-bound 的一致执行 |
| OCR 微服务 | 可独立扩缩容 | 新网络面、鉴权、数据外传和运维复杂度 | 单节点 v1 的 bounded local process 已足够验证价值 |
| ONNX Runtime CPU | 官方首选、依赖较小 | 当前 Windows 环境两版 native DLL 均加载失败 | 保留未来 capability 变体，不在同 ID 下静默替换 |
| PaddleOCR 完整产线/版面模型 | 表格/文档能力更强 | 依赖、模型、GPU/CPU成本和版权面更大 | 先验证最小 OCR grounding 是否带来可测增益 |
| 保存 OCR 结果用于恢复 | 重启无需重算 | 增加敏感 payload 与数据保留面 | OCR 可确定性重算，恢复收益不足以抵消隐私风险 |
| OCR 直接生成字段 | 可能提高表面召回 | 值/标签混淆、注入和 hallucination 直接污染 Schema | 违反“像素+region+服务端 validator”事实边界 |

## 后果与验证

- 正向：小字号和重复行获得独立的字符/位置观测；能力与模型资产可精确复现；adapter 缺失或漂移时
  可读 fail-closed；旧 pipeline/Profile 不变。
- 代价与残余：部署新增 Python/OpenVINO 与约 32 MiB 模型；Python socket denial 不是 OS 级网络 sandbox，
  高保证部署仍应使用容器/防火墙 egress deny；OCR bbox/文字正确性只能通过 corpus 评测，不能由合同证明；
  OCR 模型资产不可在完成上游许可审查前随产品再分发。
- 验证：领域 contract tests；strict process/failure/secret-env tests；真实 PostgreSQL 三 stage 一次 OCR、
  取消先行、三次 reservation、零 OCR 持久化；显式本地 runtime canary；同 case v4/v6/v7 live 消融与
  Goal aggregate guard。普通 gate 默认 adapter off、Provider calls=0。
- 回退：从 registry 隐藏/移除 v7 新建入口即可停止新运行；历史 v7 snapshot 保留 reader 和精确失败语义。
  adapter 不产生 migration 或仓库写入，源码可按 N5 commit revert；已经发生的 Provider 费用只能关闭 ledger，
  不能回滚。
