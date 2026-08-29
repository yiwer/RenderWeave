# T171 — Capability position bytes per-demand production guard 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T131, T170 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S8-028` 与 cap-041：`capabilityRuntime.positionCanonicalBytesPerDemand` 使用
MAX_INCLUSIVE `2048`，observed `2047/2048/2049`，contract stage `CAPABILITY_FIRST_DEMAND`、public stage
`MATERIALIZATION`、code `CAPABILITY_BUDGET_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。T131 已实现真实
first-demand position canonical-byte admission，但冻结 probe 要求 exact production guard 且禁止 duplicate guard；
本票将 per-demand 轴合流到唯一 `RenderingPipelineCapacityGuard`。

## seam 与兼容边界

- production guard catalog 成为 per-demand position frozen id/maximum/problem/public-stage 唯一权威；
  `CapabilityBudget` 删除 `MAX_POSITION_BYTES_PER_DEMAND` 与本地直接比较，frozen/effective vector 均从 catalog
  取得上限，认证部署只能在 `0..2048` 内收紧。
- `reserveDemand` 保持 total→kind→position-per-demand→position-total 顺序；以实际 canonical byte length 调用
  per-demand guard，全部 admission 成功后才提交 total/kind/position counters。per-demand 超限不提交任何 counter、
  不调用 provider、不产生 result。
- alias 重读、Definition memo hit、未选择 branch 与未物化 Definition 仍零 demand。runtime failure 继续通过既有
  closed Expression/Materializer 边界投影 exact `CAPABILITY_BUDGET_EXCEEDED` / `MATERIALIZATION` / limitId；
  CapabilityCallPosition canonicalization、CapabilityState、result digest 与 fingerprint 行为不变。

## TDD、验证与边界

- guard test 先引用缺失的 `CAPABILITY_RUNTIME_POSITION_CANONICAL_BYTES_PER_DEMAND` 捕获 compile RED，再重放
  `2047/2048/2049`、frozen taxonomy、effective tightening 与不可放宽。
- production tracker test 先以 `2049` bytes 拒绝，再执行 4096 CLOCK + 4096 RANDOM、每次 `2048` bytes，精确填满
  kind/total/position-total 三轴；全部成功证明失败 demand 未部分提交任一 counter，下一 demand 再由 total 先失败。
  公开 Evaluator 既有 per-demand effective max `1` 回归继续证明 provider 调用为零。
- focused guard/CapabilityValues/Evaluator/architecture、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/
  Web/migration/Profile，不重复 server/full。
- 本票不迁移 cap-042+ position-total/state/result/retry/rejection 轴，不改变 effective vector wire、Capability SPI/
  state store/provider。provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高
  `automated_verified`、J0。

## Resolution

- TDD 首轮得到唯一预期 compile RED：guard test 引用了尚不存在的
  `CAPABILITY_RUNTIME_POSITION_CANONICAL_BYTES_PER_DEMAND`。补入 catalog 并迁移 production tracker 后，guard +
  CapabilityValues focused 47/47 转绿；未伪造新的行为 RED，因为 T131 已有正确 first-demand 产品语义，本票只做
  冻结 guard 合流。
- `RenderingPipelineCapacityGuard` 现在独占 per-demand position frozen id、MAX_INCLUSIVE `2048`、problem 与
  public stage；effective maximum 只能在 `0..2048` 内收紧，尝试放宽到 `2049` 会失败。`CapabilityBudget` 已删除
  重复 `MAX_POSITION_BYTES_PER_DEMAND` 与本地直接比较，frozen/effective vector 均从 guard catalog 取得上限。
- request tracker 保持 total→kind→per-demand→position-total 顺序，全部 admission 成功后才提交
  total/kind/position counters。首个 2049-byte CLOCK demand 返回 exact per-demand limitId；随后 4096 CLOCK + 4096
  RANDOM、每个 2048 bytes 全部成功，精确填满 total/kind/position-total 三轴，证明拒绝没有部分提交任一 counter；
  下一 RANDOM 再由 total guard 先失败。公开 Evaluator 既有 effective per-demand maximum `1` 回归继续证明 provider
  调用为零。
- 最终 expanded focused 125/125；受影响 reactor 为 Schema 20/20、Validation 13/13、Template 84/84、Asset 92/92、
  Rendering 265/265。`render` A1 `.sdlc/evidence/20260829-091126-render/`（2/2）与 `fast` A1
  `.sdlc/evidence/20260829-091213-fast/`（3/3）metadata 均 passed；`git diff --check` 通过。
- cap-041 fixture 只要求 exact production guard，本票没有行为路径专属 A2/A3；render gate 中既有独立 replay 保持
  绿色但不冒充 T171 证据。J0 pending、J1 未批准；按非 app-wiring 边界未重复 server/full。cap-042+ 仍 deferred，
  provider attempts、API Key reads、真实数据、费用、Profile registration、push/tag/PR 均为 0。
- 状态回填后的 `fast` A1 `.sdlc/evidence/20260829-091316-fast/` metadata 为 passed，3/3 steps 全绿；npm 配置
  warning 仅写入 stderr，没有改变 step exit code 或 metadata truth。
