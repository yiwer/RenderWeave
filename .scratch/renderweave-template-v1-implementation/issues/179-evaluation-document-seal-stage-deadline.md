# T179 — Evaluation + RenderDocument seal 15 秒 stage deadline 合流

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T178 (resolved)

## 目标

物化 Ticket 19 `RW-T19-S8-054`、`RW-T19-S9-015/018/019` 与 cap-049：
`deadlineAndRetention.evaluationAndDocumentSealMillis` 使用 EXACT `15000`，observed
`14999/15000/15001`，contract stage `EVALUATION_AND_SEAL_DEADLINE`、public stage
`DOCUMENT_SEAL`、code `RENDER_DEADLINE_EXCEEDED`、zero boundary `ALGORITHM_INVARIANT`。

该阶段窗口在 T178 closure 成功冻结且完成最后一次 closure-deadline 检查后固定一次；不得从公共 admission
预留固定 `+20 s` 槽位，否则 closure 提前完成会让 Evaluation 借用前一阶段剩余时间。阶段 deadline 使用同一
request-local monotonic clock，不被 Input/Capability/Asset/materialization/seal 内部 retry 或 wall drift 重置，
并继续同时受 T177 不可延长的 60 秒 total deadline。

## seam 与 first-fail 边界

- `RenderingPipelineCapacityGuard` 独占 exact duration、limitId、comparison 与 public taxonomy；其他类不复制
  `15000`、不手写 limitId，也不把 deployment/Profile invariant 冒充请求超时。
- `CanonicalEvaluator` 在唯一 `ClosureFrozen` transition 建立 package-private cooperative evaluation control；
  `EvaluationCommand`、HTTP/OpenAPI、Renderer Command、digest、日志和持久化均不新增 stage deadline 字段。
- 同一 control 贯穿 closure 后的 declaration scan、Input/Asset admission、CapabilityState、materialization/
  Asset resolution 与 atomic document seal。每个昂贵/外部边界前后检查，循环在现有安全 reservation/递归边界
  cooperative 检查；不创建线程、Future timeout 或到期后继续运行的 orphan task。
- 已先观察到的 closed 领域失败继续按 first-fail 与 `RW-T19-S9-015` 返回；只有工作边界成功后发现阶段耗尽，
  才返回唯一 cap-049 problem。seal 期间或 seal 成功返回后到期必须丢弃 builder/canonical bytes/digests，零 Engine。
- closure 失败仍属于 T178 前一阶段；closure 成功后 admission+closure cap 停止生效。cap-050+、Engine queue/
  resource/layout deadline、retention/cancel/checkpoint interval 继续 deferred。
- 不新增 route/OpenAPI/Web/migration/Profile registration/READY certification，不运行 provider、读取 API Key、
  处理真实数据、push、tag 或 PR。

## TDD 与验证

- guard 先以缺失 enum 形成 `14999/15000/15001` compile RED。
- 通过 `Evaluator.evaluate` 证明窗口只在 closure 成功后启动、Input/Capability/Asset/materialization 到期均停止
  后续工作并返回 exact DOCUMENT_SEAL problem；证明未到期工作正常 seal，closure 阶段 taxonomy 不回归。
- 对 seal module interface 增加 atomic discard 行为覆盖：deadline 在 canonical commit 前或成功返回边界耗尽时
  不产生 `SealedDocument`；不为测试新增 production adapter seam。
- focused 后运行受影响 Rendering reactor、`render` 与 `fast`；无 app/public wiring、HTTP、migration 或 Web
  变化，不重复 `server/full`。cap-049 isolated guard 不冒充正式 Ticket 19 record/product executor 或 A2/A3。
- 最高状态 `automated_verified`；claim 时 A0、J0 pending，J1 未批准。

## Resolution

- `RenderingPipelineCapacityGuard` 现独占 cap-049 的 exact `15000`、limitId、comparison 与 public taxonomy；
  `CanonicalEvaluator` 在唯一 accepted `ClosureFrozen` transition 及最后一次 closure-deadline 检查后，从当时的
  request-local monotonic instant 建立一次 stage control，不借用 admission/closure 的剩余时间。
- 同一 control 已贯穿 declaration scan、Input/Asset admission、CapabilityState establish/load/save/restore、
  capability demand、materialization、Asset resolve 与 seal；外部调用及循环边界前后 cooperative 检查，retry、
  wall-clock drift 与 unknown-commit recovery 均不重置窗口。
- closed 领域失败继续按 first-fail 胜出；只有成功工作边界耗尽才映射为 cap-049 的
  `DOCUMENT_SEAL / RENDER_DEADLINE_EXCEEDED`。seal 期间到期会丢弃 builder、canonical bytes 与 digests，零
  `SealedDocument`，因此不会进入 Engine。
- TDD RED/GREEN 覆盖 `14999/15000/15001` profile、closure-relative 起点、declaration/Input/Asset/
  Capability/resolve 截止、外部调用后截止、first-fail 胜出与 seal atomic discard。最终受影响 reactor 为
  Schema 20 / Validation 13 / Template 86 / Asset 92 / Rendering 295，全部 0 failure / 0 error。
- A1：render `.sdlc/evidence/20260829-122619-render/`（2/2）与 fast
  `.sdlc/evidence/20260829-122749-fast/`（3/3）metadata 均为 passed；`git diff --check` 通过。T179 无专属
  A2/A3，J0 pending、J1 未批准，状态为 `automated_verified`。
- cap-050+ 继续 deferred；未新增 API/OpenAPI/Web/migration/Profile registration，也未运行 provider、读取
  API Key、处理真实数据、产生费用、push、tag 或 PR。OpenAPI 保持 0.13.0。
