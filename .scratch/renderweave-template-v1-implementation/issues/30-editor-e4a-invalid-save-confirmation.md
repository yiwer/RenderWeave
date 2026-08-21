# 实现 Editor E4a 依赖问题确认与 hard-error 零写

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 04, 09, 20, 29（均已 resolved：aggregate/confirmation 合同、Editor J1、current dependency projection、E3 save）

## Question

如何把当前 T20 已真实物化的 AssetRef/TemplateRef 依赖检查从“save 直接追加 INVALID”收紧为 T09 E4 的首个真实
二阶段确认纵切：结构/hard admission 与 TemplateRef cycle 永远零写；依赖 ERROR 首次返回完整有界、绑定 proposed
canonical content 的问题集与短期 opaque token；作者只能以具名动作重交 exact body、expectedRevision 与 token，服务端
重新校验 authority、问题集和依赖快照，任何 content/current/dependency/expiry 漂移都零写并要求重新确认；Web 既不能
把确认折叠成 generic force，也不能破坏 E3 的 conflict/unknown 保守纪律？

## Answer（本票冻结的实施决定）

1. **E4a 的真实依赖闭包**：本票覆盖 T20 已物化的全部 authored AssetRef atom 与 TemplateUse logical occurrence：
   Asset 的 same-scope/ACTIVE/kind/current-content fact，Template target 的存在/lifecycle/same-scope/current revision/
   readiness/content identity，以及 proposed root + current child-use graph 的 DAG/closure limit。问题按 canonical pointer、
   code 的 UTF-8 顺序稳定输出；同一 dependency 的多个 authored occurrence 不聚合丢失。
2. **诚实的 validator 上限**：StaticSchema field path、TemplateUse child PUBLIC fill/type/StaticSchema compatibility 等更深
   dependency semantic validation 尚未在 T20 kernel 物化，不在本票伪造。E4a 完成只证明上述 dependency surface 的
   confirmation；后续必须另票补齐这些语义后，才能声称完整 E4/Template validator。
3. **hard 与 dependency 分流**：DesignDSL admission rejection 继续使用现有 hard failure；cross-scope dependency、
   TemplateRef cycle、closure capacity/integrity 与 `PROBLEM_LIMIT_REACHED` 均不可确认且零 Template 写。只有完整、
   未截断的 missing/deleted/kind-mismatch/not-ready dependency ERROR 集合可签发 invalid-save confirmation。
4. **共享 problem budget**：最多 200 项（截断时 199 ordinary + 一个 `PROBLEM_LIMIT_REACHED` marker），每项 canonical
   bytes ≤4096、总 canonical bytes ≤262144，并始终预留 1024-byte marker。marker 出现即没有 token；问题项只含稳定
   code/category/severity、canonical pointer 与有界安全 message args，不回显 Asset/child 原始内容或未授权路径。
5. **确认 token**：新增 Template-owned `InvalidCommitConfirmationAuthority` real SPI/production PostgreSQL Adapter，
   5 分钟 opaque 64-hex token 绑定 `SAVE`、可信 actor/ownerScope、target Template、permanent StaticSchema、
   expectedRevision、proposed contentHash、完整 problem fingerprint、exact dependency snapshot fingerprint 与 expiry；
   token 不是 `force=true`，不进入 DesignDSL，也不授权其他操作。
6. **fresh revalidation + transaction fence**：confirmed request 必须重交完整 canonical body、同一 expectedRevision 与
   header token；服务端重新执行 admission、Schema、current、dependency、problem budget 与 Host authority recheck。
   persistence commit 携带 exact dependency facts，并在 PostgreSQL SERIALIZABLE transaction 内锁定/比较所有现存事实；
   missing-row predicate 或任一事实漂移均零写。正常 READY save/create/recheck 同样复用 snapshot fence，关闭 T20
   precheck→commit 竞态。
7. **closed outcomes**：首次完整 dependency ERROR 返回 422 `TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED`；token
   invalid/expired/stale 分别返回稳定 code，stale 只在 fresh problem set 仍完整可确认时签新 token并要求再次确认；
   hard/truncated 返回不可确认 422；dependency/confirmation persistence unavailable 返回 503；revision conflict 仍由
   E3 的 409 overwrite 流程处理。任何可能已发出 PUT 后的 transport/5xx/malformed success 仍进入 outcome-unknown。
8. **Web session 绑定**：confirmation offer 绑定 `{token, expectedRevision, draftCanonical, previewGeneration,
   problems, expiresAt}`；任何 edit/undo/redo 或 conflict current 变化使旧 offer 失效。确认期间继续使用同一 mutation
   单飞锁；成功必须通过 E3 的 exact 200 verification，采用 `INVALID` baseline 并清 history。取消只清 offer，不改 draft。
9. **可访问最小面**：Canvas Focus 显示问题总数、稳定 code、canonical pointer 与截断状态；具名按钮明确“仍保存为
   INVALID”，另有取消，不设快捷键或 generic force。E9 的统一问题面板、实体/属性/span 定位、授权 locator、焦点导航、
   200% zoom/high-contrast 全验收不提前伪造。
10. **发布边界**：允许修改 Template api/spi/internal、PostgreSQL Adapter/V025、Controller/OpenAPI/generated SDK、
    Web save coordinator/shell/tests/CSS 与 tracker/plan/log/NOTES。禁止产品 route、E5 reconciliation、E6 preview、
    E7 recovery、E8 import、E9 完整问题面板、copy/restore confirmation、Renderer/Profile/formal record/READY、
    provider/真实数据/API Key。

## 验证与完成信号

- TDD：先 Template pure contract RED（strict create、problem ordering/budgets、cycle hard、offer claims、invalid/expired/
  stale、confirmed INVALID、dependency drift zero-write），再 PostgreSQL Testcontainers RED（V025、multi-connection snapshot
  drift/rollback），再 API/OpenAPI 与 Web coordinator/DOM RED。
- 局部：Template/App focused Maven + focused Vitest；受影响：`template`、`server`、Node 24 `web`、`fast`，最终 `full`。
- generated SDK 必须与 OpenAPI 0.15.0 一致；runtime canary migration count 25；默认测试/provider attempts/API Key reads=0。
- 完成后改为 `resolved / automated_verified`，形成一个 verified local commit 且 worktree clean；不 push/tag/PR，
  不声明完整 E4、Editor、Renderer 或 Template v1 READY。

## 完成结果

- Template core 已把 dependency outcome 收敛为稳定、有界的结构化问题集；cycle/cross-scope/integrity/closure-limit/
  truncation 均为不可确认 hard rejection，只有完整 dependency ERROR 可签发五分钟、actor/scope/body/revision/problem/
  snapshot 全绑定的 opaque confirmation token。
- READY 与 confirmed INVALID 的 create/save/recheck 都携带 exact dependency snapshot 进入持久化 fence；PostgreSQL
  使用 SERIALIZABLE transaction 与现存 Asset/Template 事实锁定比较。正常 READY 路径会对短暂 drift 重新求值，confirmed
  INVALID 遇到 drift 永远零写，并只在 fresh problems 仍可确认时签发替代 token。
- V025、production PostgreSQL confirmation authority、Controller/OpenAPI 0.15.0/generated SDK 已对齐；缺少 Host authority
  的隔离 Spring composition 保持 fail-closed，正式应用 composition 使用 PostgreSQL authority。
- Web save coordinator 严格验证 offer expiry/problem bounds/category 与 INVALID success；Canvas Focus 提供具名“仍保存为
  INVALID”与取消动作，offer 对 edit/undo/redo/conflict drift 失效，并复用 E3 mutation/outcome-unknown 锁。
- A1 证据：`template` `.sdlc/evidence/20260821-115240-template/`、Node 24 `web`
  `.sdlc/evidence/20260821-115304-web/`、`fast` `.sdlc/evidence/20260821-120546-fast/`、`server`
  `.sdlc/evidence/20260821-120603-server/`；最终 exact-manifest `full` 的目录只记入提交交接，不反写本票。
- 本票仍只证明 T20 已物化 dependency surface；StaticSchema field-path、child PUBLIC fill/type/Schema compatibility
  仍是下一纵切。未开放产品 route，未运行 provider、真实数据、API Key 或付费外部调用，未 push/tag/PR，未升级 READY。
