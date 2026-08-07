# NOTES.md

## 当前目标与进度
- Goal 已于 2026-08-08 激活：连续实现并验证 P1–P4；P5 live AI、真实数据和付费调用不在当前自动执行边界内。
- 需求访谈已收束，v1 产品语义以 `specs/renderweave-v1.md` 为准。
- 生命周期状态：P0 `accepted`；P1–P3、P4/T4-1 `automated_verified`；P4/T4-2 已进入实现。

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
- [ ] T4-2：`replay-v1` Profile、deterministic profiler、Candidate contracts、60-case synthetic corpus 与零网络 workflow。
- [ ] live AI Phase 前补齐 independent verify（A2）与当次付费/数据授权。

## 重要发现或局部阻塞
- 本机全局 Node 为 20.20.2；正式 gate 已使用 checksum 固定的仓库局部 Node 24.19.0，不依赖或修改系统 Node。
- 已建立真实 Git 节点边界：`main@93cdefb` 固化 P0–P3，P4 在 `phase/p4-replay-inference`；仍无 CI、issue tracker 或独立 verifier，因此整体计划不宣称 A2/A3。
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
- 当前恢复点：P4 / T4-2；从 versioned replay Profile、deterministic profiler 与 Candidate contract 的 failure-first tests 开始，所有执行固定为零网络 synthetic replay。
