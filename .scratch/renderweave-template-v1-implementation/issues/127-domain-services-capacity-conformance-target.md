# 物化 Domain Services 容量一致性产品执行目标

Type: task
Status: resolved / automated_verified
Claimed by: —
Blocked by: 01, 10, 10b, 11, 12a, 12b, 13, 126（均已 resolved）

## Question

T126 已激活 Template 最终产品页面，129 张既有实施票全部 resolved；冻结 conformance bootstrap 的下一步仍是
`EXEC::DOMAIN_SERVICES::1.0`。该 class 已有 4 个 Asset 容量轴、12 个 below/at/above fixture、closed observation
adapter 与静态 A2 generator evidence，但仍没有绑定当前产品实现的 executor/target，也不能发行 formal records。
如何让这些 fixture 真正经过生产 Asset capacity guard，并形成独立可重放的 A1/A2 证据，同时不分配 64 MiB/32 MiB
边界 payload、不复制容量常量、不扩大 public API，且不把这一步冒充完整上传、PostgreSQL 或 Renderer 认证？

## Answer（本票冻结的实施决定）

1. **唯一生产 guard**：在 `renderweave-asset.internal` 收敛 package-private `AssetContentCapacityGuard`；它唯一拥有
   IMAGE/FONT raw bytes、IMAGE edge pixels 与 total pixels 四个冻结上限和 MAX_INCLUSIVE 判定。现有
   `CanonicalAssetAcceptanceAuthority`、PNG/JPEG/WebP descriptor admission 全部消费同一 guard；删除各 parser 的
   重复常量，既有 public `AssetAcceptanceAuthority` surface、错误码、stage、pointer 与 first-error 顺序不变。
2. **无大 payload 的 closed executor**：test-scope Java executor 只读取冻结的 12 个 fixture，拒绝 expected/oracle/
   requirement/script 等成员，通过 exact internal guard 观察 accepted、terminal、limit、reservation 与 zero-output
   字段并输出确定性 report。它不读取 scenario catalog 内的 planned assertions，也不创建产品 route 或测试旁路。
3. **生产调用链证明**：独立 integration tests 继续经真实 `AssetAcceptanceAuthority.admit(rawBytes, kind)` 证明 raw
   byte 与 PNG/JPEG/WebP descriptor 均在 decode/外部存储前消费同一 guard；正常 admission 与 above-limit stable
   failure 同时覆盖。fixture scalar executor 不声称自己证明真实 media bytes、S3 或数据库事务。
4. **独立重放与 exact target**：Python stdlib verifier 独立读取 frozen capacity mapping、fixture bytes、Java report 与
   source/fixture target manifest，重建 12 个 MAX_INCLUSIVE 结果和 closed observation；不得导入 Java helper、读取
   expected 值或使用 fallback。实现提交后再生成绑定 exact source hash 与 implementation revision 的 target manifest。
5. **gate 与生命周期**：先取得 missing guard/executor RED，再 minimal GREEN；focused Asset → independent replay →
   `asset` → `fast` → sequential `server` → Goal `full` → resolution `fast`。本票完成只使 Domain Services product
   execution target 可重放；12 个 Case/Oracle 的 append-only formal issuance 留给下一独立票，Ticket 19 仍 open。
6. **诚实边界**：不改 API/OpenAPI/migration/Web/Renderer；不注册 Profile、不运行 native build、provider、API Key、
   真实数据、生产、J1/A3/READY。主工作区既有 Image-Only/Schema/Inference dirty work保持原样，精确 staging。

## Results

- 产品执行目标已物化：实现 revision `96dcf3fdb847a59ec70265bb8f60f686b342ff82` 引入唯一的 package-private
  `AssetContentCapacityGuard`，并让 `CanonicalAssetAcceptanceAuthority`、PNG、JPEG 与 WebP admission 精确消费它；
  public API、错误合同、parser/decode first-error 与 persistence seam 均未改变。门控 revision
  `d73f5650365d9da64c117d42cb3b7cac6a228281` 绑定 15 个 exact Git-blob artifact、12 个 frozen fixture 与直接
  consumer/behavioral-test 清单；target generator byte-identical replay SHA-256 为
  `11ea54c739e65482fdae7c7da9500dcb7f8a899624d9f98ac96c34265ed7ad6d`。
- TDD RED 如实捕获 missing guard/executor；minimal GREEN 后 focused Asset/report 为 44/44，重构后的相关集为
  24/24，完整 Asset module 为 97/97。标量 executor 不分配 64 MiB/32 MiB payload；真实 raw-byte、PNG、JPEG 与
  WebP public admission path 继续独立证明相同 guard 在 decode/存储前生效。
- Java primary 与 Python stdlib independent replay 均为 12/12，formal Case/Oracle records 保持 0；直接 runner 证据为
  `.sdlc/evidence/20260826-103326-t127-asset-runner/`，正式 `asset` 为
  `.sdlc/evidence/20260826-103609-asset/`。Python 不导入 Java helper、不读取 expected/oracle/planned assertion，也
  不使用 fallback。
- clean detached exact revision 上，Node 24 依赖就绪后的 `fast`
  `.sdlc/evidence/20260826-103756-fast/`、顺序 `server`
  `.sdlc/evidence/20260826-103818-server/` 与发布级 `full`
  `.sdlc/evidence/20260826-105143-full/` 全部通过；首次 `fast`
  `.sdlc/evidence/20260826-103703-fast/` 只因新 worktree 尚无 `node_modules`/`tsc` 失败，安装 lockfile 精确依赖后由
  后续绿色证据取代，不计为产品失败。
- `full` 覆盖 Template kernel 211/211、Spec Registry 双执行器、App 367、Inference 361、Template 81、Asset 97、
  Rendering 121、Node 24 Web 250、runtime/R0/R1/P0、25 passed + 1 controlled skip Playwright journeys；T126 正式
  Template 产品 journey 2/2 亦通过。provider attempts、API Key reads、provider reservations/cost 均为 0。
- 本票没有发行 formal records，没有完整 upload/PostgreSQL transaction 或 Renderer exact-output 证明，也没有独立
  native deployment/rehearsal、Profile registration/certification、真实数据、生产、J1/A3/READY。`full` 内既有 Rust
  repository checks 与临时 runtime canary 通过不改变 `BUILD_NOT_AUTHORIZED`、`NOT_REGISTERED`、`NOT_CERTIFIED` 边界；
  Ticket 19 与整体 Template v1 lifecycle 继续 `in_progress`。
