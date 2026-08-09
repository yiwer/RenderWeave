# NOTES.md

## 当前目标与进度
- 2026-08-08 授权的 P1–P4 Goal 实施范围已完成并通过本地 A1 自动验证。
- 2026-08-08 的 P5 canary J1 已关闭：双 Profile 各 1 次，共 2 attempts / ¥0.054017；只证明通路，不构成质量认证。
- 2026-08-09 Flash 60-case live 已完成并经独立 A2：112 attempts / ¥0.122980，2/60 exact pass，policy=`EXPERIMENTAL`，authorization 已 CLOSED。
- 2026-08-09 pinned Plus 60-case live 已完成并经独立 A2：75 attempts / ¥0.825948，18/60 exact pass，policy=`EXPERIMENTAL`，authorization 已 CLOSED。
- 2026-08-09 Prompt v2 60-case live 已完成并经独立 A2：70 attempts / 256,153 tokens / ¥0.868772，47/60 exact pass，policy=`EXPERIMENTAL`，authorization 已 CLOSED。
- 2026-08-09 Grounded Pipeline v2 60-case live 已完成并经独立 A2：80 attempts / 278,740 tokens / ¥0.908984；JSON_ONLY 20/20 零调用、COMBINED 20/20 单次调用，IMAGE_ONLY 0/20，policy=`EXPERIMENTAL`，authorization 已 CLOSED。
- 2026-08-09 T5-9 payload-free attempt taxonomy 已完成 clean server A1 与独立 A2（0 Blocker / 0 High / 0 Medium）；未调用 Provider，不构成新的 Profile 认证。
- 2026-08-09 T5-10 已建立复用同一 Grounded Profile 的 20-case IMAGE_ONLY 诊断方案；identity、clean A1、独立 A2 与 PROPOSED 负探针均通过，ledger 仍为 `PROPOSED`，0 Provider 调用。
- 用户已将候选模型扩大到 Qwen3.7 dated/alias 与 Qwen3.8 Max，其他模型费用硬上限 ¥10；按协议能力优先评测 pinned `qwen3.7-plus-2026-05-26`。
- 需求访谈已收束，v1 产品语义以 `specs/renderweave-v1.md` 为准。
- 生命周期状态：P0 `accepted`；P1–P4 `automated_verified`；P5 Flash / Plus / Prompt v2 / Grounded v2 均为 `live_independently_reviewed`，T5-9 为 `independently_reviewed`，T5-10 为 `prelive_independently_reviewed`。所有 DashScope Profile 仍为 `EXPERIMENTAL`、默认关闭；新诊断账本为 PROPOSED，其余已用授权账本均为 CLOSED。

## 下一步
- [x] Java / React / PostgreSQL / OpenAPI 最小 canary 与 A1 full gate 通过。
- [x] 用户接受“A 默认表单 + B Map + 吸收 C 的 preview/密度”的编辑器方向（J1，2026-08-08）。
- [x] 创建 P1–P4 implementation Goal。
- [x] T1-1：strict DSL envelope、key 与无 `fieldId` 契约（8 tests；A1 server gate）。
- [x] T1-2：Draft create/save/revision PostgreSQL 纵切（真实 PG 并发与零覆盖；A1）。
- [x] T1-3：REST/OpenAPI/generated SDK 与最小生产 A+B 编辑器旅程；真实 PG/browser create/save/reload 已绿。
- [x] T2-1：完整七类型、类型专属约束、数组非嵌套与 regex safety（16 schema tests；A1）。
- [x] T2-2：事务引用图、DAG/depth 与 Draft delete/restore/copy/history（真实 PG 并发；A1）。
- [x] T2-3：不可变 Static 发布、系统预置与自底向上 inline compiler；原子发布、精确 artifact 与系统预置已通过 server/web gates。
- [x] T2-4：权威 RootDocument batch validator（strict JSON、frozen target、稳定 100-problem 诊断与 Draft/Static 批量 API）。
- [x] T3-1：共享 EditorSession semantic action/reducer（七类型、100-step undo/redo、typing coalescing、save/reload/restore 边界）。
- [x] T3-2：完整 Form/Map/Inspector、reference/publish-prep、无损 decimal 边界、dirty/history、256-field 与高密度可读性生产交互。
- [x] T3-3：Draft/Static 生命周期页面、冲突 diff 与 RootDocument sample validator；真实 PG 浏览器旅程覆盖 restore/delete/publish/copy/validate。
- [x] T4-1：零网络 replay inference 输入归一化、BlobStore、durable run 与 lease/checkpoint/cancel/retry；Java 79 tests 与 V005 fresh migration 全绿。
- [x] T4-2：`replay-v1` Profile、deterministic profiler、Candidate contracts、60-case synthetic corpus 与零网络 durable workflow；Java 99 tests 与 V006 fresh migration 全绿。
- [x] T4-3：Candidate 查询/逐项 revision autosave API、Form/Map review editor、image/JSON evidence overlay 与真实 PG/browser 闭环（无 confirm-all）。
- [x] T4-4：deterministic create-only materializer、原子 bundle apply、SSE 与 crash/replay recovery；冲突/故障/并发零部分写，真实 PG/browser 证明 StaticSchema 数量不变。
- [x] P5 当次 provider/cost/data J1：DashScope、synthetic-only、≤6 attempts、≤¥1。
- [x] T5-1：DashScope provider-neutral contract、双模型 versioned Profile、Prompt/费用快照、环境变量/Compose secret 与零网络 adapter contract tests（A1 server gate）。
- [x] T5-2–T5-5：live upload/worker/UI、限定 canary、安全 A2 与“不认证”决定；旧授权 CLOSED。
- [x] T5-6 pre-live：60-case v2 corpus、完整图指标、fail-closed policy、每批 5 case 的 journal harness、12×5 重载闭环、evaluation identity 与独立 A2 PASS。
- [x] 将最终 tracked tree digest 写入新 `PROPOSED` 账本并创建 pre-live 节点提交。
- [x] 获得 Flash 60-case / ≤180 attempts / ≤¥3.60 / synthetic-only J1，逐批完成后立即 CLOSED。
- [x] Flash live 结果经独立 A2 重建：60 unique cases、112 settled attempts、¥0.122980、policy=`EXPERIMENTAL`。
- [x] 完成 pinned Plus Profile、独立 ledger selector、≤180 attempts / ≤¥10 / 4h J1 的 pre-live 门禁与 A2。
- [x] 逐批运行 Plus 60-case，立即 CLOSED 并独立复核；结论为 `EXPERIMENTAL`，没有自动晋级。
- [x] 记录 Max / dated 模型协议矩阵：强制思考或无 JSON mode 保证的模型先补协议与 credit/CNY 双预算，不用当前 harness 盲调。
- [x] 以 Plus 的 field/type/edge 与 critical hallucination 缺口驱动不可变 Prompt/Profile v2，并新增 payload-free failure taxonomy。
- [x] 为 Prompt v2 冻结 synthetic-only `PROPOSED` ledger；任何复验使用新 Profile、identity 与新的精确 J1。
- [x] 在用户 12h / 每模型 1M-token J1 内，将本轮执行收窄为单一 pinned Plus Prompt v2 Profile、4h、¥2、≤180 attempts、≤5 case/批；完成 60/60 后立即 CLOSED。
- [x] 独立 A2 重建 Prompt v2 的 60 case、70 settled attempts、256,153 tokens、¥0.868772、全部 slice metrics 与泄露扫描；无 Blocker / High / Medium。
- [x] 以 Prompt v2 失败归因构建 Grounded Pipeline v2；JSON_ONLY 确定性零调用、COMBINED 受限视觉 overlay、Prompt/Profile v3、OpenAPI/Web 与 adversarial trust-boundary tests 完成，pre-live A2 PASS。
- [x] 将 Grounded 最终 staged tree digest 写入单一 Profile、synthetic-only、≤120 attempts / ≤¥2 / ≤5 case 每批的 PROPOSED ledger。
- [x] 在用户 12h / 每模型 1M-token J1 内，以独立 OPEN 提交执行 Grounded 60-case；60/60 完成后立即 CLOSED，CLOSED 负探针零写入。
- [x] 完成 Grounded live journal、预算、指标、policy 与泄露面的最终独立 A2：PASS，0 Blocker / 0 High / 0 Medium。
- [x] 完成 T5-9 payload-free IMAGE_ONLY attempt taxonomy 的 clean server A1 与独立 A2；所有 live gate 保持关闭。
- [x] 冻结 T5-10 IMAGE_ONLY 诊断 ledger identity，完成 clean pre-live A1/A2 与 PROPOSED 负探针；全程零调用。
- [ ] 取得 T5-10 exact J1 后，才把选定 ledger 短时转为 OPEN 并按每批最多 5 case 执行；完成或停止后立即 CLOSED。

## 重要发现或局部阻塞
- 本机全局 Node 为 20.20.2；正式 gate 已使用 checksum 固定的仓库局部 Node 24.19.0，不依赖或修改系统 Node。
- 已建立真实 Git 节点边界；当前工作分支为 `phase/p5-image-only-attribution-v1`。T5-6/T5-7 live、T5-8 与 T5-10 pre-live 均有独立只读 A2，但仍无外部 CI/branch protection 的 A3。
- T4-4 首次 server gate 由于外层命令时限过短中断，其不完整 evidence 不作为结论；随后的完整 server/web/e2e 与 real inference journey 均为绿色。
- UI 设计数据库把本项目误路由到 hero-centric/mobile/dark SaaS；已在 page override 中拒绝，采用已确认的 dense warm editorial workbench。
- Docker registry 代理不可用；Compose config 与等价 API/PG runtime canary 已绿，`docker compose up --build` 仍 pending。

## 最近 checkpoint
- `plans/logs/ENV-001.md`；A1 full evidence：`.sdlc/evidence/20260807-231218-full/metadata.json`。
- `plans/logs/P1-T1-1.md`；A1 server evidence：`.sdlc/evidence/20260808-002421-server/metadata.json`。
- `plans/logs/P1-T1-2.md`；A1 server evidence：`.sdlc/evidence/20260808-003059-server/metadata.json`。
- `plans/logs/P1-T1-3.md`；G-P1 full：`.sdlc/evidence/20260808-005748-full/metadata.json`；final affected：`.sdlc/evidence/20260808-010032-draft-e2e/metadata.json`。
- `plans/logs/P2-T2-1.md`；A1 server evidence：`.sdlc/evidence/20260808-011728-server/metadata.json`。
- `plans/logs/P2-T2-2.md`；A1 server/web evidence：`.sdlc/evidence/20260808-013450-server/metadata.json`、`.sdlc/evidence/20260808-013512-web/metadata.json`。
- `plans/logs/P2-T2-3.md`；A1 server/web evidence：`.sdlc/evidence/20260808-015525-server/metadata.json`、`.sdlc/evidence/20260808-015701-web/metadata.json`。
- `plans/logs/P2-T2-4.md`；A1 server/web evidence：`.sdlc/evidence/20260808-022017-server/metadata.json`、`.sdlc/evidence/20260808-022100-web/metadata.json`。
- `plans/logs/P3-T3-1.md`；A1 web evidence：`.sdlc/evidence/20260808-023127-web/metadata.json`。
- `plans/logs/P3-T3-2.md`；A1 web/e2e/real-browser evidence：`.sdlc/evidence/20260808-030732-web/metadata.json`、`.sdlc/evidence/20260808-030709-e2e/metadata.json`、`.sdlc/evidence/20260808-030319-draft-e2e/metadata.json`。
- `plans/logs/P3-T3-3.md`；G-P3 evidence：`.sdlc/evidence/20260808-033646-draft-e2e/metadata.json`，浏览器/axe：`.sdlc/evidence/20260808-033128-e2e/metadata.json`。
- `plans/logs/P4-T4-1.md`；A1 server evidence：`.sdlc/evidence/20260808-041051-server/metadata.json`。
- `plans/logs/P4-T4-2.md`；A1 server evidence：`.sdlc/evidence/20260808-044819-server/metadata.json`。
- `plans/logs/P4-T4-3.md`；A1 server/web/real-browser evidence：`.sdlc/evidence/20260808-052825-server/metadata.json`、`.sdlc/evidence/20260808-052917-web/metadata.json`、`.sdlc/evidence/inference-e2e-9372/metadata.json`。
- `plans/logs/P4-T4-4.md`；G-P4 A1 server/web/mocked-browser/real-browser evidence：`.sdlc/evidence/20260808-060743-server/metadata.json`、`.sdlc/evidence/20260808-061010-web/metadata.json`、`.sdlc/evidence/20260808-061225-e2e/metadata.json`、`.sdlc/evidence/inference-e2e-36108/metadata.json`。
- `plans/logs/P5-T5-5.md`：旧 canary 与 safety A2 已收束，两个 Profile 保持 `EXPERIMENTAL`。
- `plans/logs/P5-T5-6.md`：Flash / pinned Plus 的 60-case live 均经独立 A2，决定均为 `EXPERIMENTAL`，authorization 均 CLOSED。
- `plans/logs/P5-T5-7.md`：Prompt/Profile v2 pre-live A1/A2 与 60-case live A2 PASS；live evidence：`.sdlc/evidence/p5-certification-20260809-plus-prompt-v2/summary.json`；决定为 `EXPERIMENTAL`。
- `plans/logs/P5-T5-8.md`：Grounded Pipeline v2 clean A1 + pre-live/live A2 PASS；60-case live evidence 已 CLOSED，decision=`EXPERIMENTAL`。
- `plans/logs/P5-T5-9.md`：payload-free attempt taxonomy 已在 `ec53b3d` 完成 clean server A1 与独立 A2；0 Blocker / 0 High / 0 Medium。
- `plans/logs/P5-T5-10.md`：同一 Grounded Profile 的 20-case IMAGE_ONLY 诊断 pre-live 已完成 clean A1、独立 A2 与 PROPOSED 负探针；0 Blocker / 0 High / 0 Medium，0 Provider attempt。
- 当前恢复点：`phase/p5-image-only-attribution-v1` 的 T5-10 `prelive_independently_reviewed` 节点；真实 IMAGE_ONLY 归因必须使用该精确 ledger 并取得新 J1。
