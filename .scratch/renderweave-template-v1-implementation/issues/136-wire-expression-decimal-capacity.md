# 接线 Expression decimal 七轴产品容量

Type: task
Status: resolved / automated_verified
Claimed by: —（2026-08-29 final gate closeout 完成后释放）
Blocked by: 135（已 resolved）

## Question

T135 已把唯一 Expression grammar parser、immutable AST 与 AST 两轴 reservation 收进 Template-owned
`DesignSemanticAuthority`，但 decimal 的七项冻结容量仍只有 scalar comparator；Rendering 还保留三处硬编码阈值，
且 admitted literal、显式 rounding scale、normalized intermediate 与最终可观察 decimal 尚未全部经过同一 authority。
如何在所有 authored Expression 的 admission 和真实 Evaluation 路径中完成七轴接线，同时保持 exact BigDecimal、lazy
branch、零写 first-fail 与单一 comparator？

## Answer（本票冻结的实施决定）

1. **完整七轴纵切**：本票一次接线
   `expression.admittedDecimalPrecisionDigits`、`expression.admittedDecimalScaleMin`、
   `expression.admittedDecimalScaleMax`、`expression.intermediateDecimalPrecisionDigits`、
   `expression.intermediateDecimalScaleMin`、`expression.intermediateDecimalScaleMax` 与
   `expression.explicitRoundingScaleMax`。成功 target 应为 wired 55/65、remaining 10（geometry）。
2. **沿用两个既有 seam**：静态行为通过 `DesignDslAuthority.admit(rawUtf8)` 观察；动态行为通过既有 Rendering
   evaluation/Expression consumption seam 观察。不新增第二条 admission Interface、HTTP/OpenAPI、migration 或配置面。
3. **normalized admitted decimal**：Template-owned parser 在构造每个 `DecimalLiteral` 前按
   `stripTrailingZeros`（所有 zero 固定 scale 0）形成不可观察规范值，依次观察 precision、scale-min、scale-max；所有
   authored、未使用和静态不可达 Expression 均计入。reject/invalid/throw 在 exact
   `/definitions/<index>/source` 以既有 `DESIGN_DSL_LIMIT_EXCEEDED / DESIGN_SEMANTIC_VALIDATION` 零写退出。
4. **显式 scale**：parser 在接纳 `divide`、`round`、`formatDecimal` call 前，对合法 non-negative integer scale
   literal 调用唯一 capacity authority；`formatDecimal` 的 min 与 max 均观察。literal/non-negative/rounding-mode 仍由
   Analyzer 做结构与类型判断，但删除其硬编码 64 comparator，避免第二套 threshold。
5. **中间值与可观察结果**：Rendering 把已注入 `DesignInputExpressionCapacityAuthority` 沿
   `CanonicalEvaluator → Materializer → DefinitionEngine → ExpressionEvaluator` 传递。每次 exact BigDecimal 运算后、
   结果进入 `EvalValue` 前，按 normalized precision/scale 观察三项 intermediate 轴；`formatDecimal` 的 rounding
   intermediate 同样检查。Expression 根返回 decimal 时再观察三项 admitted 轴，保证可观察值回到 128 / −64..64。
6. **失败封闭与 identity**：capacity reject、invalid 或 throw 都形成 `DECIMAL_LIMIT_EXCEEDED` runtime failure，保留
   exact limitId；scale-min 与 scale-max 不再折叠。authority 是唯一 limit/comparator/terminal 实现，product path 不复制
   128/64/256/128 常量。
7. **TDD 与门控**：按真实 admission recording/rejecting RED → admitted/scale GREEN → runtime recording/rejecting RED →
   intermediate/observable GREEN 的纵切推进；随后回归 Expression engine、Template、Rendering 与 canonical 211 vectors。
   实现提交后保留 v1–v6 不可变并冻结 v7；依次执行 focused → component → `template` → `fast` → sequential
   `server` → Goal `full` → resolution `fast`。

## Boundary

- geometry 十轴、两个 required executor manifests、独立产品 replay 与 195 formal records 继续 pending；本票不发行
  records、不升级 execution-class lifecycle。
- 不运行独立 native Renderer build、provider、API Key、真实数据或生产；不取得 J1/A3/READY，
  `BUILD_NOT_AUTHORIZED` 保持。
- 用户 360 项 dirty work、`tools/run-gate.ps1`、两项 rendering resource dirt 与备份 stash 均不在本票写入/暂存范围。

## Progress（2026-08-27）

- 产品实现 revision `6aba539dcf25f2fbc66f7854d42d62487326a8a3` 已完成七轴纵切：Template 在
  `DecimalLiteral` / explicit scale AST allocation 前使用同一 authority，Rendering 则把 injected authority 沿
  `CanonicalEvaluator → Materializer → DefinitionEngine → ExpressionEvaluator` 传到底，并在每次 decimal operation
  后观察 intermediate 三轴、在根 observable decimal 上观察 admitted 三轴。所有 reject/invalid/throw 均保持 exact
  limitId 并映射为 `DECIMAL_LIMIT_EXCEEDED`；未引入截断、饱和或 binary floating point。
- focused 与 affected 回归已绿：Template decimal admission 19/19、Rendering runtime capacity 35/35；完整 Template
  133/133、Rendering 167/167。zero normalization、合法上下界、全部 decimal-producing operations、静态不可达 admission、
  lazy runtime branch、`formatDecimal` trailing zero 与 18 个 runtime authority fail-closed case 均有产品 seam 证明。
- target revision `9851de8f7cecd5953f8334d49d66b532e26af644` 冻结不可变 v7；v6 predecessor bytes/hash/length
  精确绑定，v1–v6 未改。component evidence
  `.sdlc/evidence/20260827-003602-design-input-expression-capacity-v7/` 的 Java primary 与独立 TypeScript replay 均为
  195/195、failed 0，wired 55/65、remaining 10（仅 geometry）。
- 分级门控 `template` `.sdlc/evidence/20260827-003659-template/` 与 `fast`
  `.sdlc/evidence/20260827-003746-fast/` 为 A1 PASS；canonical Java/Python 211/211、Template 133/133 与 package/typecheck
  均零失败。
- 首次顺序 `server` `.sdlc/evidence/20260827-003833-server/` 未通过，因此本票仍为 `in_progress`，未冒充
  `automated_verified`。唯一失败是用户 360 项基线中的未提交
  `UnixDomainSocketDocumentVisionRunnerTest.typedSidecarErrorsPropagateAsDocumentVisionCodes`：预期 sidecar 422 code，
  偶发在 10 秒后得到 `DOCUMENT_VISION_TIMEOUT`；Application 为 447 tests / 1 failure / 21 skipped，其余 reactor
  模块均通过。单方法循环已复现，线程 dump 证明客户端阻塞在 `readResponse/SocketChannel.read`，而 test sidecar
  acceptor 已返回下一次 `accept`、handler 已完成并空闲；实现当前忽略 HTTP `Content-Length`、只等 EOF，Windows
  AF_UNIX 偶发不交付 close wakeup。该 runner/test 均属用户未提交基线，本票未覆盖或暂存。
- `server` 重验、Goal `full` 与 resolution `fast` 仍 pending；smoke 产品服务正在使用已重新打包的稳定 JAR，验证期间
  暂不执行会 clean 该 JAR 的 gate。产品实现与 v7 target 已完成且当前没有继续写入面，因此本票释放 single-writer，
  由 smoke 暴露的独立 Editor 纠偏票 TV1-T126a 接续；smoke 结束后必须恢复本票最终 gate，未通过前不得改报
  `automated_verified`。provider attempts、API Key reads、reservations/cost 仍为 0；无真实数据、生产、J1/A3/READY 或
  独立 native Renderer build。

## Resolution（2026-08-29）

- 产品 smoke 与 T126a/T126b 自动门控结束后，本票重新独占 claim 并审计 main integration 的最终顺序证据：
  `server` `.sdlc/evidence/20260829-175320-server/`、Goal `full`
  `.sdlc/evidence/20260829-180317-full/`、resolution `fast`
  `.sdlc/evidence/20260829-181957-fast/` 均绑定 merge revision
  `ed152c75dc2579fc9dc1dceb168b91bd324f0656` 且 metadata 为 A1 `passed`；执行时间严格相邻递增，分别为
  1/1、17/17、3/3 steps，零失败。server 为 8-module BUILD SUCCESS，App 381 tests / 0 failures / 0 errors /
  15 controlled skips。
- 实现 revision `6aba539dcf25f2fbc66f7854d42d62487326a8a3` 与 target revision
  `9851de8f7cecd5953f8334d49d66b532e26af644` 均为当前 HEAD 祖先；从该 merge revision 到 T126b verified
  commit 之间没有 Template/Rendering/Schema/Validation 文件变化，因此按 RULE-VAL-001 复用上述最终证据。
  当前 v7 target SHA-256 仍为
  `dc2c9714fe2efbfe552238e67aed09d72ce2cfdbca98e6c573a678d0e25e6363`，与 server input manifest 精确一致。
- component evidence `.sdlc/evidence/20260827-003602-design-input-expression-capacity-v7/` 保持 Java primary
  195/195（A1）与独立 TypeScript replay 195/195、2690 checks（A2，明确仅 component scalar replay / partial
  product wiring）；wired 55/65、remaining geometry 10。formal registry 当前仍为 cases 58 / oracles 58，v7
  `formalRecordsIssued=0`，不满足 issuance、class executable 或 READY。
- 本票 lifecycle 如实升级为 `resolved / automated_verified` 并释放 claim。A3 无，J0 pending、J1 未批准；未运行
  provider、未读取 API Key、未发送真实数据、未注册 Profile、未执行生产或独立 native Renderer build，未
  push/tag/PR；geometry 十轴、两个 required executor manifests、独立产品 replay 与 195 formal issuance 继续
  作为后继工作，不由本票冒充完成。
