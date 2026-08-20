# Template v1 Goal 交接文档

> 生成时间：2026-08-19，由 round 14 结束时自动生成
> 目标 ID：`goal-be3d8acc-be2f-4625-b316-77db8ace7c6b`（revision 2, phase paused, disarmed）
> 接手后请用 `update_goal action=resume` 重新激活 goal。

---

## 1. 当前状态快照

| 项目 | 值 |
|------|-----|
| Worktree | `D:\Yiwer\code\RenderWeave-template-v1-implementation` |
| 分支 | `feature/template-v1` |
| HEAD | `fcde358` (T12b) |
| Ahead of origin | **19 commits**（从未 push，需用户授权） |
| Worktree 状态 | **干净**（0 untracked/modified） |
| Goal roundsStarted | 14 / maxGoalRounds 20 |
| Goal activation | disarmed（接手后 resume） |

### 已完成 tickets（automated_verified）
T01–T12a, T12b, T14, T14b, T15, T16, T17, T18, T19, T20

### 剩余 open tickets
| Ticket | 类型 | Blocked by | 状态 |
|--------|------|-----------|------|
| **T13** | task | 05, 07, 08, 11 | **open — 以首个 Rendering 实现票 + T08 为前置，当前无 unblocked frontier** |
| T19 | task | — | open（容量 oracle，独立 track） |
| Editor E1–E9 | task | 各自前置 | 未登记 |

**关键结论：当前 DAG 中没有任何 unblocked frontier ticket。** T13 需要等首个 Rendering 实现票落地（与 T08 Rust Renderer protocol 同批），在那之前 single-writer 无事可做。接手 agent 应先确认是否有新的 Rendering 票被创建或解锁；若无，应等待用户指示而非强行推进。

---

## 2. 最近两轮完成的工作

### Round 13 — T20 Template 依赖投影 (`488c091`)
- `AssetRefAtomExtractor` + `TemplateDependencyEvaluator`：从 admitted canonical DesignDSL 提取 AssetRef atom + TemplateUse occurrence，current-only 投影物化
- `AssetReferenceAuthority`（raw current-only proof）、`TemplateReadinessAuthority.recheck`（系统级 READY/INVALID 重算）
- `TemplateAssetStaleConsumer`：消费 `asset_audit_event` DELETE/RESTORE/CONTENT_REPLACE/CONTENT_RESTORE → STALE → recheck
- V021 migration：`template_asset_reference`, `template_use_reference`, `template_asset_stale_cursor`
- Readiness CHECK 扩为 READY/INVALID/STALE
- Gate: kernel 211/211 v8 + A2 extraction replay + static authorityDiff=0

### Round 14 — T12b 被引用 Asset 删除确认与恢复编排 (`fcde358`)
- `AssetReferencePort`（asset spi outbound）+ `TemplateAssetReferencePortAdapter`（app bridge → T20 authority + redaction）
- 5-min 单次确认 token（V022 `asset_delete_confirmation`），绑定稳定 actorId/ownerScope/assetId/assetRevision/完整 fingerprint
- Delete：FOR UPDATE exclusive reservation + token 全绑定校验 + proof 重算比对 → 漂移零写
- Template read reservation：`PostgresTemplatePersistence.create/append` FOR SHARE on asset rows sorted by assetId
- Restore lifecycle：DELETED→ACTIVE same current content version, no new contentVersion
- HTTP: `POST /{id}/delete-precheck`, `DELETE /{id}` (X-Confirmation-Token), `POST /{id}/restore-lifecycle`
- OpenAPI 0.13.0 + Web SDK regen + contractVersion 0.13.0 + canary migration count 22
- Tests: asset module 86/86, slice 5/5, app suite 302 green
- Gates all passed A1: asset/template/fast/server/web/**full 16/16**

---

## 3. 关键技术约定（接手必读）

### 模块边界与禁区
- **AGENTS.md 全部禁区仍然有效**：不碰 dirty main、不 push（需用户授权）、不跑付费 live AI、不用 H2/SQLite 模拟 PG、不引入 placeholder
- Asset→Template 反向协作只通过 Asset-owned outbound Interface + app Adapter，不形成 compile edge / 共享聚合 / 共享表 / 跨上下文数据库事务
- Template module 自身不读 asset 表；app adapter（PostgresTemplatePersistence）可桥接
- 每个已验证 ticket 独立提交并如实登记 A1/A2/A3 与 J0/J1

### Single-writer 纪律
- 每轮只 claim 一个 unblocked frontier ticket
- 一票 resolved 后才由其 Blocked by 关系产生下一 frontier
- 未知实现切片留在 map 的 Not yet specified，不提前发明接口/migration/Profile identity

### Gate 与证据
- `template` gate = repository-diff + template-kernel-replay (211/211 v8 + A2 extraction) + template-static-replay
- `asset` gate = repository-diff + asset-kernel-replay (41/41)
- App-wiring tickets 的受影响验证 = `asset` + `server` + `web` + `full` 16/16
- Evidence 写入 `.sdlc/evidence/<timestamp>-<gate>/`
- Kernel manifest frozen at `renderweave-template-canonical-kernel-v1/8` = 211 cases; §12 gate text 已含 A2 extraction step

### pwsh / Windows 注意事项
- pwsh 后台 job IGNORE `workdir` param — 必须 prefix `Set-Location 'D:\Yiwer\code\RenderWeave-template-v1-implementation'`
- .NET static calls resolve relative paths against session workspace — 用绝对路径
- `Set-Content -Encoding utf8` writes BOM — avoid; use `[System.IO.File]::WriteAllText(..., UTF8Encoding($false))`
- stderr noise → exit-1 on pwsh wrappers but gate metadata.json is truth
- `Get-Content ... | Select-Object -Skip N` to read surefire reports
- npm warn stderr 不影响实际结果

### Testcontainers / DB
- asset_aggregate ↔ asset_content_revision circular FK: DEFERRABLE INITIALLY DEFERRED; insert aggregate FIRST then revision in one TransactionTemplate
- asset_delete_confirmation FK → asset_aggregate ON DELETE RESTRICT; truncate lists must include it
- PostgresTemplatePersistence create/append takes FOR SHARE on referenced asset_aggregate rows sorted by assetId (T12b read reservation)
- PostgresAssetPersistence.delete recomputes proof via port INSIDE the delete tx (same thread-bound connection)

### Token / Actor Identity
- Confirmation token binds authority-resolved stable actorId (NOT per-request random invocation)
- ConfiguredSingleOwnerAssetScopeAuthority.ExistingGranted carries actorId = ownerScope.value()
- Recheck identities are capability-scoped single-use (mirrors template adapter pattern)

---

## 4. 文件索引

### Plan & Tracker
- `plans/renderweave-template-v1-plan.md` — DAG, status, §12 gate strategy, execution cards
- `.scratch/renderweave-template-v1-implementation/map.md` — ticket registry + Not yet specified
- `.scratch/renderweave-template-v1-implementation/issues/*.md` — per-ticket question + resolution
- `NOTES.md` — per-ticket narrative log (UTF-8 no BOM)
- `CONTEXT.md` — domain vocabulary + module boundaries + lifecycle summary

### Key source files (T12b additions)
- `renderweave-asset/src/main/java/.../spi/AssetReferencePort.java` — NEW
- `renderweave-asset/src/main/java/.../api/AssetApplication.java` — deletePrecheck/delete/restore + outcomes
- `renderweave-asset/src/main/java/.../spi/AssetPersistence.java` — issueDeleteConfirmation/delete/restore SPI
- `renderweave-asset/src/main/java/.../spi/AssetOwnerScopeAuthority.java` — ExistingGranted.actorId + DELETE/RESTORE ops
- `renderweave-asset/src/main/java/.../internal/CanonicalAssetApplication.java` — orchestration
- `renderweave-app/.../app/template/TemplateAssetReferencePortAdapter.java` — NEW bridge
- `renderweave-app/.../app/asset/PostgresAssetPersistence.java` — delete/restore/token impl
- `renderweave-app/.../app/template/PostgresTemplatePersistence.java` — acquireAssetReadReservations (FOR SHARE)
- `renderweave-app/.../resources/db/migration/V022__asset_delete_confirmation.sql` — NEW
- `openapi/renderweave-v1.yaml` — 0.13.0 + 3 new endpoints
- `web/src/api/generated/*` — regenerated SDK

### Tests
- `renderweave-asset/.../AssetApplicationContractTest.java` — 86 tests incl. 11 new delete/restore scenarios
- `renderweave-app/.../template/AssetDeleteRestoreSliceTest.java` — NEW 5-test vertical
- `renderweave-app/.../asset/AssetApiTest.java` — +4 HTTP scenarios
- `renderweave-app/.../EnvironmentCanaryTest.java` — migration count 22, contractVersion 0.13.0

### Evidence dirs (latest)
- `.sdlc/evidence/20260819-224825-full/` — full gate 16/16 passed
- `.sdlc/evidence/20260819-222826-server/` — server-verify passed
- `.sdlc/evidence/20260819-224643-web/` — web-node24 passed
- `.sdlc/evidence/20260819-222711-template/` — template gate passed
- `.sdlc/evidence/20260819-222711-asset/` — asset gate passed

---

## 5. 接手后的第一步建议

1. `get_goal` → 确认 goal state (paused/disarmed)
2. `update_goal action=resume` → 重新激活
3. 检查 plan row 13 的 Blocked by 是否仍包含未满足的前置（T08/Rendering 实现票）
4. 若 T13 仍 blocked：向用户报告"当前无 unblocked frontier，等待 Rendering 票或用户指示"
5. 若有新票解锁：按 single-writer 纪律 claim 并推进
6. **不要 push** — 19 commits ahead，需用户明确授权

---

## 6. 已知 latent issues / 边界备注

- **asset_aggregate physical deletion impossible**: aggregate↔content_revision circular FK with ON DELETE RESTRICT on both sides makes physical row deletion impossible in any order. Soft-delete via lifecycle='DELETED' is the only supported path. T12b documents this boundary; if future work needs physical purge, a migration to NO ACTION DEFERRABLE would be required.
- **Fetch leases don't exist yet** (T13): delete doesn't revoke leases because none are issued. When T13 lands, the delete flow may need a lease-check step.
- **Editor E1–E9 not registered**: prerequisites (stable ownership/error surface from Evaluator seam) not yet met.
- **Ticket 19 (capacity oracle)**: independent track, not part of the Template v1 DAG frontier.
- **OpenAPI 0.13.0 is the current contract version**; next API change bumps to 0.14.0.
