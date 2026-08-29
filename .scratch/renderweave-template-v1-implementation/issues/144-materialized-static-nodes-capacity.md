# T144 — 强制 materialized static Nodes 容量

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T143 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-083` 与机器 `cap-013` 已冻结的
`closureAndExpansion.materializedStaticNodes` 容量轴。合同为 MAX_INCLUSIVE `20000`，observed
`19999/20000/20001`，contract stage `SERIAL_MATERIALIZATION`、公开 stage `MATERIALIZATION`、code
`EVALUATION_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。第 20001 个 static Node 必须在创建前以
完整 limitId fail closed，不得返回 partial tree、RenderDocument、Engine Command 或 RenderOutput。

当前 T23 已让真实 Node、Repeat outer/item、true Conditional frame 与 compositionViewport 等最终真实/合成 static
Node 经 `Materializer.reserveMaterializedNode()` 预留；但上限仍由 `MAX_MATERIALIZED_NODES` 与手写比较拥有，并
错误返回 `RENDER_DOCUMENT_LIMIT_EXCEEDED`。这混淆了 MATERIALIZATION-owned
`closureAndExpansion.materializedStaticNodes` 与后续 DOCUMENT_SEAL-owned `renderDocument.staticNodes`；两轴即使
数值同为 20000，stage/code/reservation 也不可互换。

## Boundary 与 seam

- 扩展唯一 package-internal `renderweave-rendering-pipeline-capacity-guard/1.0`，加入 closed
  `MATERIALIZED_STATIC_NODES=20000`；删除 Materializer 重复常量与手写 problem，request-local `nodes` counter
  只经 guard 投影 exact oracle。
- 产品 fixture 使用 10 个合法 Repeat。总 9994 items 产生 `1 Canvas + 10 outer + 9994 item containers + 9994
  Rects = 19999` Nodes；追加一个普通 Rect 为 20000；把总 items 改为 9995 则第 20001 个在 final Rect 创建前
  失败。每 occurrence ≤1000、总 Loop frames <10000、Render occurrences <25000、literal-list total <16384，
  保证本轴 first-fail。
- cap-013 target 虽明确 machine probe 不执行 Evaluator/Sealer，本票仍在真实 `Materializer.materialize` 产品 seam
  重放边界，并另让 exact production guard 重放 19999/20000/20001；不把 candidate 冒充正式 product executor
  或 A2。

## TDD 与验证

- 先把现有 20001-node production test 的 expected code 校正为 `EVALUATION_BUDGET_EXCEEDED`；未改生产时应得到
  actual `RENDER_DOCUMENT_LIMIT_EXCEEDED`，形成 behavioral RED。
- 最小接入唯一 guard，再补 19999/20000 successful tree count 与 isolated guard exact boundary；保持 T140–T143
  的 per-occurrence collection、loop-frame、render-occurrence 和 first-fail 语义。
- 运行 focused Materializer/guard、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/Web/migration/
  Profile 变化，不重复发布级 `full`，server 留到周期性容量批次。
- generated track/cell、logical operations、RenderDocument-owned staticNodes/canonical bytes 等后续轴、正式 Ticket 19
  records/product executor、provider/API Key/真实数据/Profile registration/push/tag/PR 均不在本票。最高
  `automated_verified`；claim 时 A0、J0。

## Resolution

- TDD behavioral RED 已真实捕获：仅把既有 20001-node production test 的 expected code 校正为
  `EVALUATION_BUDGET_EXCEEDED` 后，`MaterializerTest` 14 tests 中恰好 1 个失败，actual 为旧
  `RENDER_DOCUMENT_LIMIT_EXCEEDED`，证明缺口不是只缺 isolated catalog case。
- 唯一 package-internal `RenderingPipelineCapacityGuard` 已增加 `MATERIALIZED_STATIC_NODES=20000`；
  `Materializer.MAX_MATERIALIZED_NODES` 与手写 wrong-code 分支已删除。request-local `nodes` counter 在每个最终
  static Node 创建前先经唯一 guard 预留，成功后才预留 render occurrence，保持独立轴的 first-fail 顺序。
- 真实 `Materializer.materialize` seam 已精确证明 19999 与 20000 Nodes 成功并返回完整 tree，第 20001 个返回
  MATERIALIZATION / EVALUATION_BUDGET_EXCEEDED / `closureAndExpansion.materializedStaticNodes`；isolated production
  guard 同步重放 19999/20000/20001。focused Materializer 16 + guard 6 = 22/22。
- 受影响 reactor Schema 20、Validation 13、Template 84、Asset 92、Rendering 201 全绿；`git diff --check` 通过，
  production source scan 不再存在重复 `MAX_MATERIALIZED_NODES` authority。A1 `render`
  `.sdlc/evidence/20260829-042440-render/`（2/2）与 `fast` `.sdlc/evidence/20260829-042528-fast/`（3/3）metadata
  均 `passed`。
- cap-013 candidate 明确不执行 Evaluator/Sealer，且尚无正式 product target/executor，故无 T144-specific A2；
  A3 无，J0 pending、J1 未批准。未重复 server/full；provider attempts/API Key reads/真实数据/Profile
  registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-042717-fast/` metadata 为 `passed`，3/3 steps 全绿。
