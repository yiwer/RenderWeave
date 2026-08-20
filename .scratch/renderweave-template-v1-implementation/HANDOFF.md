# Template v1 Goal 交接文档

> 状态日期：2026-08-20
> Goal：持续推进 Template v1 实现直到完成
> Goal tracker：active；single-writer 纪律不变

## 1. 当前快照

| 项目 | 值 |
|---|---|
| Worktree | `D:\\Yiwer\\code\\RenderWeave-template-v1-implementation` |
| 分支 | `feature/template-v1` |
| 验证锚点 | `c91a128`（T21）+ TV1-T13 closeout diff |
| Upstream | 当前已提交历史 ahead 9；从未 push，本轮也未 push/tag/PR |
| Goal | active，无 token budget |
| 当前 ticket | TV1-T13 resolved / `automated_verified`；最终 full exact-manifest gate 后形成单一 verified commit |

T01–T21 的实施 tracker ticket（含 T13，编号并非连续的执行顺序）现已全部 resolved。旧冻结规格中的
Security/Capacity Ticket 19 仍 open，它不是已经完成的 implementation TV1-T19（TemplateUse）同一票。

当前没有已登记且 unblocked 的下一 ticket。按 single-writer 规则，T13 提交后必须从 map 的
`Not yet specified` 中只登记/claim 一张新票；不得把 Rust Engine、公开 render route、Editor 或求值硬化
顺带塞进 T13。依赖顺序上，最自然的下一候选是 ADR-0045 已冻结的首个 Rust daemon/process Adapter +
`render` gate 实现纵切，因为公开权威 preview 与 Editor 后续切片依赖真实 Engine；登记前仍要核对 frozen
spec/ADR 与当前源代码，不预造 Profile 或 placeholder。

## 2. 最近完成的两张票

### TV1-T21：首个 Rendering 应用纵切（commit `c91a128`）

- 新 `renderweave-rendering` artifact；TemplateClosureAuthority、Evaluator stage 1–8、Materializer、Sealer、
  RenderNodeContract catalog/vector、CapabilityState 加密落盘与 RenderEngine scripted port。
- app Testcontainers PostgreSQL assembly 证明 Template create → closure/admission/materialize/seal。
- 无公开 render/preview route、无 Rust Engine；AssetResolutionPort 当时 production bridge 缺省 fail-closed。
- 最终 full exact input manifest 已核验；本地提交完成，未 push。

### TV1-T13：AssetResolver / Renderer-only lease（本次 closeout）

- Asset-owned `AssetResolver` 与 `AssetFetchEndpoint`；precheck metadata-free，resolve closed input 只含
  request/scope/resource/asset/kind/audience/deadline。
- V024 `asset_render_selection`：`(renderRequestId, resourceId)` 单事务线性化，同 fingerprint exact replay，
  异 fingerprint conflict；selection 随机 nonce AES-GCM 加密，AAD 绑定 key/fingerprint，lease 与 expiry
  为 opaque plaintext control。
- HMAC signed canonical HTTPS app-origin bearer URL；内部 GET 在任何 body byte 前验证 token/expiry/record 和
  S3 exact blob length+sha256，拒绝 cookie/range/compression，固定 1 MiB chunks，404/500/503 closed status。
- Rendering app bridge 穷尽映射 NOT_FOUND/DELETED/KIND_MISMATCH/CONFLICT/TIMEOUT/UNAVAILABLE；Evaluator
  每次请求从 UTC Clock 冻结 60 秒 deadline；nested child failure 保留原始 Asset problem。
- 纵切覆盖 exact replay、replace 后混合版本、8 线程线性化、delete 后旧 lease fetch、ciphertext、token
  tamper、Range、blob corruption 与 Evaluator→Resolver→PG/S3。

## 3. 当前证据

| Gate | 结果 | Evidence |
|---|---|---|
| asset | Java 90/90；Python independent 41/41 | `.sdlc/evidence/20260820-173235-asset/` |
| server | 全 Reactor success；Rendering 104、Asset 90、app 319，0 failure/error | `.sdlc/evidence/20260820-173254-server/` |
| fast | repository diff、八模块 package、Web typecheck 通过 | `.sdlc/evidence/20260820-174441-fast/` |
| full | 用 T13 最终 docs + product diff 的 exact manifest 执行；目录在最终 handoff 消息报告，不反写此文件 | `.sdlc/evidence/<final-passing-full>/` |

Gate wrapper 显式清空全部付费/live AI selector；provider attempts、费用与 API-key reads 必须保持 0。自动 gate
最高只支撑 `automated_verified`；没有新的 J1/A3，也没有 Template/Editor/Renderer READY 声明。

## 4. 关键边界

- 不碰另一个 dirty main/worktree；只在本实施 worktree 写入。
- 不 push/tag/PR；需要用户另行明确授权。
- PostgreSQL 语义只用 Testcontainers PostgreSQL，Blob 只走同一 S3 Adapter/Testcontainers MinIO；不用
  H2/SQLite、文件系统替代或 test-only bypass。
- fetch URL 是请求级 bearer secret：进入完整 RenderDocument bytes 以做交接完整性保护，但不进入
  assetSelectionDigest/evaluationResultDigest、普通日志、审计或 selection record 明文。
- replace/delete 不撤销已签发 lease；endpoint 按 selection exact hash 读取 immutable blob，不重查 current、
  lifecycle 或 actor。新 resolve occurrence 独立观察后续状态。
- 无 Rust daemon/process Adapter、Engine network allowlist/retry/cache、公共 render/preview、Editor 产品代码、
  图片渲染、Profile registration 或 physical Linux certification。
- `asset_aggregate` 与 `asset_content_revision` 的 circular deferred FK 仍使物理删除不属于现有语义；v1 只用
  lifecycle soft delete。若未来需要 purge，必须另票设计 forward migration。
- app 全量测试关闭多个 Testcontainers context 时，既有 scheduled consumer 可能在 shutdown 期间记录旧连接
  refused 噪声；Maven/gate 的 test summary 与 metadata exit code 才是结果权威。不要把日志噪声伪报为失败，
  也不要在真实 test failure 时忽略 exit code。

## 5. 接手后的顺序

1. `get_goal` 确认同一 goal 仍 active。
2. 核对 T13 最终 full evidence、verified commit 与 clean worktree；若未完成，先收口 T13，不另 claim。
3. 从 `map.md`、ADR-0045、冻结 Renderer tickets/requirements 与当前 Rendering seam 重算 DAG。
4. 只登记并 claim 一个最小但完整的 unblocked implementation ticket；优先评估首个 Rust Engine/process
   vertical，不以 placeholder、scripted adapter 或 Windows/WSL 结果冒充生产 Renderer。
5. 按 TDD 与局部→受影响→Phase→Goal gate 推进；继续禁止付费/live provider、真实数据与任何 secret 输出。

## 6. 权威索引

- `CONSTITUTION.md` / `CONTEXT.md`：治理、领域语言与模块边界
- `plans/renderweave-template-v1-plan.md`：当前 DAG/status
- `.scratch/renderweave-template-v1-implementation/map.md`：tracker 与未登记切片
- `.scratch/renderweave-template-v1-implementation/issues/13-resolver-and-renderer-lease.md`：T13 contract/resolution
- `plans/logs/TV1-T13.md`：T13 TDD、产品增量、证据与边界
- `docs/adr/0043-asset-admission-resolution-deep-interface.md` / `0044-evaluator-renderdocument-seam.md` /
  `0045-rust-renderer-process-protocol-and-certification.md`：冻结实现方向
- `.scratch/renderweave-template-v1/issues/13-asset-reference-and-resolution.md` 与 requirements/13.tsv：冻结
  Asset resolution 语义；后续 Engine 约束另见 tickets 16/19 与相应 requirements
