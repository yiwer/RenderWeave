# ADR 索引

按任务只读命中的 ADR，不要通读。全部 58 份均为 `accepted`。新增 ADR 使用 `TEMPLATE.md`，
编号从当前最大号 0058 之后继续，禁止复用编号。

**编号修复记录（2026-09-03）**：0046–0058 原以 0041–0053 落盘，与 Template 波次的 0041–0045 冲突，
已重编号（slug 不变）；历史 handoff（`docs/history/`）与 `docs/history/NOTES-2026-09-03.md` 中引用的
旧号 0041–0053 若属 live-admission 语义，对应新号 = 旧号 +5。Template 波次（0041–0045）编号未动。

## Schema / Inference v1 基线（0001–0007）

| 编号 | 主题 |
|---|---|
| [0001](0001-draft-static-identity-and-publication.md) | Draft/Static 身份与发布不可变性 |
| [0002](0002-closed-dsl-inline-compiler-and-authoritative-validator.md) | 封闭 DSL、inline compiler 与权威 validator |
| [0003](0003-java-modular-monolith-and-postgresql-snapshots.md) | Java 模块化单体与 PostgreSQL 快照 |
| [0004](0004-evidence-candidate-and-no-publish-ai-boundary.md) | Evidence/Candidate 与 AI 不发布边界 |
| [0005](0005-shared-editor-session-form-and-map.md) | 共享 EditorSession 的 Form+Map 编辑器 |
| [0006](0006-contract-first-web-and-single-node-topology.md) | Contract-first Web 与单节点拓扑 |
| [0007](0007-risk-driven-evidence-and-ai-quality-gates.md) | 风险驱动证据与 AI 质量门 |

## 视觉识别与 live 评测波次（0008–0040）

| 编号 | 主题 |
|---|---|
| [0008](0008-dashscope-chat-completions-and-dual-profile-evaluation.md) | DashScope chat-completions 与双 Profile 评测 |
| [0009](0009-profile-certification-matrix-and-protocol-compatibility.md) | Profile 认证矩阵与协议兼容 |
| [0010](0010-evidence-anchored-prompt-v2.md) | Evidence 锚定 Prompt v2 |
| [0011](0011-deterministic-json-grounding-and-visual-overlay.md) | 确定性 JSON grounding 与视觉 overlay |
| [0012](0012-payload-free-attempt-problem-taxonomy.md) | payload-free attempt 问题分类 |
| [0013](0013-image-only-diagnostic-evaluation-plan.md) | IMAGE_ONLY 诊断评测计划 |
| [0014](0014-guided-inference-review-workbench-and-acceptance-gates.md) | 引导式审核工作台与验收门 |
| [0015](0015-product-live-profile-catalog-and-optional-run-cost-limit.md) | 产品 live Profile 目录与可选成本上限 |
| [0016](0016-product-profile-v2-evidence-repair-and-call-ceiling.md) | Profile v2 证据修复与调用上限 |
| [0017](0017-bounded-oversized-image-normalization.md) | 超限图片有界规范化 |
| [0018](0018-image-only-technical-canonicalization-and-payload-free-execution-log.md) | 技术规范化与 payload-free 执行日志 |
| [0019](0019-image-evidence-coordinate-canonicalization-and-review-density.md) | 证据坐标规范化与审核密度 |
| [0020](0020-serial-visual-analysis-and-topology-preservation.md) | 串行视觉分析与拓扑保持 |
| [0021](0021-dashscope-timeout-and-provider-lease-renewal.md) | 超时与 Provider lease 续租 |
| [0022](0022-hybrid-visual-grounding-and-deterministic-candidate-materialization.md) | 混合 grounding 与确定性 Candidate 物化 |
| [0023](0023-bounded-live-visual-evaluation-ledgers.md) | 有界 live 视觉评测账本 |
| [0024](0024-local-visual-plan-materializer-and-model-capability-matrix.md) | 本地视觉计划物化与模型能力矩阵 |
| [0025](0025-multiscale-region-grounding-and-explicit-domain-hints.md) | 多尺度 region grounding 与领域 hint |
| [0026](0026-local-document-vision-capability-and-hybrid-ablation.md) | 本地 Document Vision 与混合消融 |
| [0027](0027-versioned-visual-json-shape-remediation.md) | 版本化视觉 JSON shape 修复 |
| [0028](0028-bounded-visual-semantic-verifier-and-stage-local-repair.md) | 有界语义 verifier 与 stage-local repair。**⚠ 111KB——只按章节读，勿整读** |
| [0029](0029-pinned-flash-snapshot-and-additive-goal-budget.md) | pinned Flash 与附加 Goal 预算。**⚠ 23KB** |
| [0030](0030-v40-engineering-catalog-and-capability-aware-admission.md) | v40 工程目录与 capability-aware 准入 |
| [0031](0031-v41-early-relationship-group-prerequisite.md) | v41 relationship group 前置 |
| [0032](0032-v42-bounded-runtime-envelope.md) | v42 有界 runtime envelope |
| [0033](0033-v43-shallow-grounding-fallback.md) | v43 浅 grounding 回退 |
| [0034](0034-v44-cmyk-document-sequence-coverage.md) | v44 CMYK 文档序列覆盖 |
| [0035](0035-v45-repeated-observation-coalescing.md) | v45 重复 observation 合并 |
| [0036](0036-durable-typed-state-graph-and-document-observation-ir.md) | 持久类型化状态图与 DocumentObservationIR |
| [0037](0037-successor-goal-authority-epoch-after-ledger-loss.md) | 账本丢失后的 Goal authority epoch |
| [0038](0038-separate-n7-live-semantic-scoring-from-layered-shadow-evaluation.md) | N7 live 评分与分层 shadow 评测分离 |
| [0039](0039-code-owned-single-round-bounded-visual-inspection.md) | 代码拥有的单轮 bounded visual inspection |
| [0040](0040-append-only-profile-certification-authority.md) | append-only Profile Certification authority |

## Template v1 波次（0041–0045）

| 编号 | 主题 |
|---|---|
| [0041](0041-template-module-interfaces-and-process-isolation.md) | Template 模块接口与进程隔离。**⚠ 12.6KB** |
| [0042](0042-template-aggregate-and-persistence-seams.md) | Template 聚合与 persistence seams。**⚠ 18KB** |
| [0043](0043-asset-admission-resolution-deep-interface.md) | Asset 接纳/解析 deep interface |
| [0044](0044-evaluator-renderdocument-seam.md) | Evaluator–RenderDocument seam |
| [0045](0045-rust-renderer-process-protocol-and-certification.md) | Rust Renderer 进程协议与认证 |

## Live admission 与隐私安全波次（0046–0058，原 0041–0053）

| 编号 | 主题 |
|---|---|
| [0046](0046-successor-only-mixed-region-rejection-envelope.md) | successor-only mixed-region 拒绝 envelope |
| [0047](0047-goal-standing-approval-materializes-exact-live-authority.md) | Goal standing approval 实例化 exact live authority |
| [0048](0048-v49-diagnostic-j1-binds-fresh-normalization.md) | v49 diagnostic 绑定 fresh normalization |
| [0049](0049-successor-only-lossless-local-id-canonicalization.md) | successor-only 无损 local-ID 规范化 |
| [0050](0050-v50-diagnostic-binds-canonicalizer-implementation.md) | v50 diagnostic 绑定 canonicalizer 实现 |
| [0051](0051-v51-classifies-parent-containment-before-recovery.md) | v51 先分类 parent containment 再恢复 |
| [0052](0052-successor-only-repeated-group-evidence-envelope-normalization.md) | successor-only 重复 group 证据 envelope 规范化 |
| [0053](0053-single-gateway-assertion-authority.md) | 单一 GatewayAssertion authority |
| [0054](0054-bind-normalized-manifest-and-confirmation-in-one-transaction.md) | manifest 与确认同事务绑定 |
| [0055](0055-separate-ciphertext-wrapped-deks-and-kek-custody.md) | ciphertext-wrapped DEK 与 KEK custody 分离 |
| [0056](0056-separate-logical-payload-tombstones-from-physical-erasure.md) | 逻辑 tombstone 与物理擦除分离 |
| [0057](0057-payload-free-audit-chain-and-independent-dual-switches.md) | payload-free 审计链与独立双开关 |
| [0058](0058-run-production-ocr-as-no-ip-unix-socket-sidecar.md) | 生产 OCR 以 no-IP Unix socket sidecar 运行 |
