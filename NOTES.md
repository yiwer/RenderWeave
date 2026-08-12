# NOTES.md

## 当前目标与进度
- 2026-08-11 pipeline 4.23/product-v36 已形成零 Provider 的离线生产候选。`fdf7d44` 只在 typed
  region shape 唯一决定类型时归一化：`MANY + repeatGroupId`→`REPEATED_GROUP`、
  `ONE + repeatGroupId`→`ITEM`、无 parent/无 repeat/单个 full-artifact evidence 的 `ONE`→`ROOT`；
  SECTION/GROUP 歧义、缺失 repeat 事实与完整 forest 校验失败继续 fail-closed。`86b6074` 发布三份
  immutable v36 Profile，`2076684` 用 real-PG lease-expiry 证明 accepted OBSERVE 不重放、恢复后完成
  HIERARCHY/BINDING 到 `REVIEW_REQUIRED`，OCR sentinel 零持久化；`f395f90` 补齐 monitor/review 中文说明、
  组件测试与 1024px E2E。inference 190/190、snapshot verifier 1/1、real-PG 1/1、Web 73/73、
  typecheck/lint 与 Playwright 1/1 PASS；本机 Web 仅为 Node 20 兼容证据，正式 Node 24 gate 尚待 exact-clean
  执行。Provider attempts=0，Goal 仍为 395 reservations，三 ledger CLOSED；v36=`EXPERIMENTAL`、
  N6=`automated_verified`、N7/Goal=`in_progress`。
- 2026-08-11 pipeline 4.22/product-v35 已形成零 Provider 的离线生产候选。`614359f` 只在 relationship
  support 为空、其已知关系区域是后代 ITEM、且唯一严格祖先容器由一个基数兼容并连接父子实体的既有 GROUP
  拥有时补全 support，并同步归一化关系区域；歧义、断连、非祖先与 unknown support 继续旧 fixed code
  fail-closed。`708522b` 发布三份 immutable v35 Profile，`a2b8181` 证明 real-PG lease-expiry 从已持久化
  OBSERVE 继续 HIERARCHY/BINDING 到 `REVIEW_REQUIRED`，`5c59ce3` 完成 monitor/review 与 1024px E2E。
  contract 31/31、inference 189/189、独立 snapshot verifier、real-PG 定向、Web 73/73 与 Playwright 1/1
  均 PASS；后续 Flash v35 live 5 次均停在 OBSERVE，仍 `EXPERIMENTAL`，N6=`automated_verified`、
  N7/Goal=`in_progress`。
- v34 Flash/Plus live 已按各自 PROPOSED→NOT_OPEN→OPEN→唯一 wrapper→CLOSED 闭环，独立 verifier 与
  payload scan 均 PASS、0 abandoned。Flash 5 次均在 OBSERVE fail-closed；Plus 首次 OBSERVE accepted，
  随后三次 HIERARCHY 为 empty-support×1、support-not-group×2，第五次在 Provider 前被 authorization cost
  reservation 阻断。当前 390 reservations、0 BREACHED：Flash 129/896,093/¥0.435196，Plus
  179/1,087,500/¥4.159620，Max 82/491,919/¥10.289316；三 ledger CLOSED。Max/final 20/60 的同版本
  三阶段与质量门仍失败；该 payload-free 信号已由 v35/v36 bounded 节点承接。Plus 仅剩 1 个 Goal
  attempt，不足以验证三阶段。
- 2026-08-11 v33 cost-restored bounded live 已闭环。`15b5d00` clean full 9/9、Document Vision 19 lines、
  双实现 identity 与三份 snapshot 通过，guard 在首个 reservation 内原子迁移 v4。Flash
  `f12e5af`→`69e8455`→`f50f591` 为 4 attempts / 37,181 tokens / ¥0.019870，均停在 OBSERVE；Plus
  `b0bceab`→`36c13db`→`f7a87b9` 为 5 attempts / 35,407 tokens / ¥0.159584，第五次 OBSERVE accepted
  后由 call cap 在 HIERARCHY 前停止。两份独立 verifier/payload scan PASS、0 abandoned，三 ledger CLOSED、
  无进程/lease 残留。Goal 为 381 reservations（376 SETTLED、5 历史 Plus RESERVED）：Flash/Plus/Max
  124/175/82 attempts、852,697/1,056,615/491,919 tokens。Max/final 20/60 的同版本三阶段门失败；下一安全
  节点仅实现 unique-existing-parent 的 bounded OBSERVE repair，Profile 仍 `EXPERIMENTAL`、N7/Goal 仍
  `in_progress`。
- 2026-08-11 pipeline 4.19/product-v32 已形成零 Provider 的离线生产候选。Plus v31 的 accepted OBSERVE
  checkpoint 后四次稳定命中 `VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY`；`212f468` 将其中唯一可证
  子集收窄为 bounded repair：关系 region 必须是已知 GROUP/REPEATED_GROUP、位于父子实体 ownership 连线上，
  且恰有一个 kind/multiplicity 兼容的既有 GROUP owner，否则保留原 fixed code，不补造 relationship、topology、
  evidence、文字或 Candidate。`7e4e70c` 发布三模型 immutable v32 Profile、pipeline 4.19 opt-in、独立 snapshot
  verifier 与 payload-free `VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SUPPORT_OWNER_NORMALIZED`；`b892503` 的真实
  PostgreSQL tracer 以一次 Document Vision 和三次 scripted stage 到达 `REVIEW_REQUIRED`；`7404c7a` 完成
  monitor/review 与 1024px E2E。Inference 185/185、registry/capability 3/3、independent verifier 2/2、real-PG
  1/1、Web 73/73/build、Playwright 1/1 PASS。本节点 Provider attempts=0，369 reservations 与三 ledger CLOSED
  状态不变。v32 仍 `EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`；clean full、冻结 Document
  Vision、fresh identity/Profile/Goal preflight 尚未运行，任何新 live ledger 都不得 OPEN。
- 2026-08-11 pipeline 4.18/product-v31 已形成零 Provider 的离线生产候选。`7e464df` 将 Plus v30 的
  `VISUAL_SEMANTIC_REPEATED_ITEM_FIELD_MISSING` 收窄为原子 bounded repair：只对已有 SLOT 使用 canonical
  evidence；每块 evidence 必须落入唯一最具体非 ROOT region，且每个缺字段 ITEM 都有可见证据，否则原样
  fail-closed，不创建字段、region、topology、文字或 Candidate。`791d4e9` 发布三模型 immutable v31 Profile、固定
  telemetry `VISUAL_GROUNDING_REPEATED_ITEM_SLOT_OWNER_NORMALIZED` 与真实 PostgreSQL 三阶段 tracer；`f6cc529`
  将三份 snapshot 纳入独立 Python verifier；`eea8b3f` 接入 monitor/review 与 diagnostics E2E。Inference
  184/184、real-PG 1/1、snapshot verifier 1/1、Node 24 Web 73/73/build、1024px Playwright 1/1 均 PASS；证据为
  `20260811-155052-web` 与 `20260811-155200-v31-diagnostics-e2e-results`。本节点 Provider attempts=0，Goal 仍为
  359 reservations，三 ledger CLOSED。用户最新 J1 已把每模型累计 exposed-token cap 提到 1.5M 并明确允许
  Plus；attempt/CNY/time 边界不变。v31 仍 `EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`；
  v31 clean full、Document Vision、fresh identity/snapshot/budget/time 尚未重跑，因此尚未 OPEN 任何 live ledger。
- 2026-08-11 product-v30 bounded live 已闭环但未晋级。clean `e5d1977` 的 full gate
  `20260811-150901` 9/9 PASS，Document Vision canary `20260811-151430` 得到 19 lines；fresh Git-blob
  identity 为 `…5b28c8af`。Flash lifecycle `ff3e5a4` PROPOSED→`4180ef8` OPEN→`5f99083` CLOSED，唯一
  wrapper 5 次均在 OBSERVE fail-closed（parent-kind、non-repeated cardinality、region-kind×2、parent
  containment），20,621 input + 22,325 output、¥0.021989、163,491 ms。Plus lifecycle `d82563f`→
  `7a8eade`→`ec0a307` 仅调用一次，命中 `VISUAL_SEMANTIC_REPEATED_ITEM_FIELD_MISSING`，随后下一次预留前
  `PROVIDER_COST_BUDGET_EXHAUSTED`，4,104 input + 3,248 output、¥0.034192、57,698 ms。两次 PROPOSED
  负探针精确 NOT_OPEN；两份 CLOSED evidence 均独立 verifier A2 PASS、0 abandoned、payload scan PASS，且
  `VISUAL_GROUNDING_ELEMENT_REGION_NORMALIZED` 均未命中。v30 没有 accepted OBSERVE/HIERARCHY/BINDING，
  Max 保持 CLOSED、未调用。Goal 现为 359 reservations（354 SETTLED、5 历史 Plus RESERVED、0 BREACHED）：
  Flash 115/771,740/¥0.370287，Plus 162/965,122/¥3.736232，Max 82/491,919/¥10.289316。product-v30
  保持 `EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`；下一步只从新的 payload-free
  OBSERVE fixed code 形成 bounded 离线假设，不扩大 final 20/60。
- 2026-08-11 pipeline 4.17/product-v30 已形成离线生产候选。`71ccbdf` 增加原子的 bounded
  evidence-owner normalization：只基于已验证 region forest 与 canonical element evidence，为未覆盖 evidence
  选择唯一最具体且 kind/multiplicity 兼容的非 ROOT region；零/多候选、unknown owner、coverage mismatch 或
  owner 上限失败均保留原 plan 并 fail-closed。`d3fedf3` 发布三模型 immutable v30 Profile、固定 telemetry
  `VISUAL_GROUNDING_ELEMENT_REGION_NORMALIZED`、独立 snapshot verifier 与真实 PostgreSQL 三阶段 tracer；
  `837c015` 接入 monitor/review 中文解释与诊断 E2E。Inference 183/183、real-PG 1/1、独立 Profile verifier、
  Node 24 Web 73/73/build、真实 replay→review→atomic Apply 1/1 与 1024px payload-free diagnostics Playwright
  1/1 均 PASS；Web 证据为 `20260811-150327-web`，浏览器证据为 `20260811-150428-inference-v30-ui` 与
  `20260811-150534-v30-diagnostics-e2e-results`。该离线节点 Provider attempts=0；当时 Goal 为 353
  reservations、三 ledger CLOSED，用量为 Flash/Plus/Max 728,794/957,770/491,919 tokens。enum/
  sibling-overlap 不猜测；后续 live 结果见上一条。
- 2026-08-11 product-v29 bounded live 已闭环且未晋级。clean `c4f92b9` 的 full gate
  `20260811-140553` 9/9 PASS，Document Vision canary `20260811-141657` 以精确 capability 得到 19 lines；fresh
  Git-blob identity 为 `…2f451e78`。首次 Flash ledger `e4ca5a6` PROPOSED→`3b2a558` OPEN→`9fc0632`
  CLOSED，因漏传 runtime enable flag 在 Provider 前以 `DOCUMENT_VISION_DISABLED` 结束，独立 verifier PASS、0
  attempts/0 tokens，Goal 不变。replacement Flash v29b `a2c82e6`→`f40a6ad`→`9454422` CLOSED/A2：5 次
  OBSERVE 全拒绝，20,596 input + 22,607 output tokens、¥0.022207；Plus v29
  `f98bfd5`→`e256e53`→`f443d86` CLOSED/A2：3 次 OBSERVE 全拒绝，12,347 input + 8,653 output
  tokens、¥0.093918，随后 fail-closed 为 `PROVIDER_COST_BUDGET_EXHAUSTED`。两模型 payload scan、CLOSED
  零写入探针均 PASS，无 abandoned/残留进程；Max 因同版本未到 HIERARCHY/BINDING 保持 CLOSED、未调用。Goal
  现为 353 reservations（348 SETTLED、5 历史 Plus RESERVED）：Flash 110/728,794/¥0.348298，Plus
  161/957,770/¥3.702040，Max 82/491,919/¥10.289316。product-v29 仍 `EXPERIMENTAL`，N6=
  `automated_verified`、N7=`in_progress`；下一步先离线收敛 OBSERVE enum/overlap/evidence-region 固定码，不能扩大
  final eval。
- 2026-08-11 pipeline 4.16/product-v29 已完成 MANY GROUP ↔ REPEATED_GROUP 双向基数归属的 bounded
  OBSERVE verifier 与 stage-local repair。`70da862` 增加 opt-in semantic policy；`dd920cc` 发布 visual
  elements prompt v9、三模型 immutable Profile 与真实 PostgreSQL tracer；`70e0f2c` 更新监控中文解释和
  Playwright 覆盖。clean revision `70e0f2c` 的 fast/server/web/inference-e2e 均 A1 PASS：Inference 182、App
  213（6 gated skip）、Web 73、真实 replay→审核→原子 Draft Apply 浏览器链路 1/1。新 PG tracer 精确执行
  OBSERVE rejected→OBSERVE→HIERARCHY→BINDING→REVIEW_REQUIRED，OCR sentinel 未进入 checkpoint。本节点
  Provider attempts=0，三份 ledger CLOSED，345 reservations 与 Flash/Plus/Max 685,591/936,770/491,919
  exposed tokens 不变；product-v29 仍 `EXPERIMENTAL`，N6=`automated_verified`、N7=`in_progress`。下一步先在
  文档 checkpoint 后完成 clean full、fresh `/2` identity、三份 snapshot 与预算/时限重算，再决定 Flash 单 case。
- 2026-08-11 product-v28 clean full 与 Flash/Plus bounded smoke 已完成。revision `0a3b90b` 的 full gate
  9/9 PASS（`.sdlc/evidence/20260811-125916-full`），Document Vision 19-line canary
  `.sdlc/evidence/20260811-130940-document-vision` PASS；fresh Git-blob identity 为 `…c669d172`。Flash v28b
  CLOSED/A2：5 attempts、44,335 tokens，全部停在 OBSERVE。Plus 首个 v28 ledger 因本地 timeout=120 超过
  60 秒合同，在 Provider 前以 `DOCUMENT_VISION_TIMEOUT_INVALID` 结束，0 attempts；独立 replacement v28b
  CLOSED/A2：5 attempts、36,204 tokens，OBSERVE 第二次 accepted，HIERARCHY 三次均以
  `VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CARDINALITY_INVALID` fail-closed，未进入 BINDING。Max 的同版本
  三阶段与质量门不成立，保持 CLOSED、未调用。累计 Flash 105/180、685,591/1,500,000、¥0.326091；Plus
  158/180、936,770/1,500,000、¥3.608122；Max 82/180、491,919/1,500,000、¥10.289316。345 reservations
  为 340 SETTLED、5 个历史 Plus RESERVED、0 BREACHED；N7 仍 `in_progress`，Profile 仍 `EXPERIMENTAL`。
- 2026-08-11 pipeline 4.15/product-v28 minimal entity ownership 离线节点已完成。`76a0635` 拒绝非根 entity
  拥有 ROOT、同一 entity 同时拥有祖先/后代 region，并要求唯一最小 spatial binding owner；`a96fec1` 将
  binding ambiguity 精确回退 HIERARCHY、保留 OBSERVE checkpoint，并发布三模型 immutable Profile；`6a8a36f`
  接入 monitor/review 固定 telemetry。verifier/codec 27/27、inference 180/180、real-PG 2/2、独立 snapshot
  1/1、Node 24 Web 73/73 与针对性 Playwright 1/1 PASS。当前 Provider attempts=0、三 ledger CLOSED；clean
  full 与 fresh `/2` pre-live identity 尚未完成，Profile 保持 `EXPERIMENTAL`。
- 2026-08-11 product-v27 三模型 single-case 已完成 CLOSED/A2。隔离 clean full `47f622b` 9/9 PASS；
  identity 为 `…960c965`。Flash 首个 v27 ledger 因 wrapper 漏传本地 OCR 属性在 Provider 前零调用结束，
  未重开；`20260811-115701-document-vision` 重验 pinned runtime 后，Flash v27b 5 attempts 仍全部停在 OBSERVE。
  Plus/Max v27 各 3 attempts 并完成 OBSERVE→HIERARCHY→BINDING，但 slot/binding matched 均为 0，分别有
  7/26 blockers、4/27 critical hallucinations；三模型均未命中 source-ancestor telemetry。最终交叉 verifier
  A2 PASS、payload scan PASS；累计 Flash 100/180、641,256/1,500,000、¥0.302686，Plus 153/180、
  900,566/1,500,000、¥3.484570，Max 82/180、491,919/1,500,000、¥10.289316。335 reservations 为
  330 SETTLED、5 历史 Plus RESERVED、0 BREACHED；三份 ledger CLOSED，Profile 继续 `EXPERIMENTAL`。
- 2026-08-11 Flash/Plus product-v26 已完成受控 single-case A2。Flash v26b 按 `36a7a9e` PROPOSED →
  `ef6440f` OPEN → `b976e5f` CLOSED：5 attempts、20,120 input + 22,858 output、157,514 ms，全部停在
  OBSERVE。Plus v26 按 `3f95ae3` → `67f33dd` → `31093a6` CLOSED：4 attempts、24,635 input + 5,008
  output、91,365 ms；OBSERVE accepted，三次 HIERARCHY 在 region-cardinality/support-not-group 间停止。
  两份独立 verifier 均 A2 PASS、payload scan PASS，v26 telemetry 均未命中，report `complete=false`。Max v26
  因同版本三阶段门未成立未调用。最新累计：Flash 95/180、598,343/1,500,000、¥0.280418；Plus 150/180、
  883,569/1,500,000、¥3.436302；Max 79/180、465,016/1,500,000、¥9.816288。324 reservations 为
  319 SETTLED、5 历史 Plus RESERVED、0 BREACHED；三份 ledger CLOSED。
- 2026-08-11 Flash v26 的先行 preflight lifecycle `f2386b6` → `32fae98` → `2eedc61` 因隔离 worktree
  `core.autocrlf=true` 改变 corpus bytes，以 `VISUAL_EVALUATION_CORPUS_IDENTITY_MISMATCH` 在 Provider、evidence
  与 Goal mutation 前 fail-closed。process/evidence lease/Goal/guard 恢复审计确认零副作用、provider attempts=0，
  未并发重跑；该 lifecycle 不计 live A2。随后以 LF clean worktree 重新计算 identity/snapshot 后才执行 v26b。
- 2026-08-11 `d3b0292` / `5ef25bd` 完成 pipeline 4.13/product-v26 unique enclosing-connected GROUP-owner
  normalization。已知非 GROUP support 只有在恰好一个 GROUP/容器 region 配对同时满足 cardinality、包围全部
  support element regions、连接 parent/child entity regions 时才归一化；零/多配对继续 fail-closed，不读取
  OCR/模型文字、不按 gold/距离排序、不改 topology。合同/Profile 22/22、独立 snapshot verifier、real-PG
  OBSERVE→HIERARCHY→ELEMENT_BINDING tracer 均 PASS；监控/审核 UI 展示 payload-free 固定码、`层级边` 范围和
  最早 HIERARCHY 修复阶段。server `.sdlc/evidence/20260811-101733-server`（197 tests、6 gated skip）、Node 24 web
  `.sdlc/evidence/20260811-101734-web`（73 tests + generate/type/lint/build）、E2E
  `.sdlc/evidence/20260811-101948-e2e`（18 passed、1 gated skip）与 runtime
  `.sdlc/evidence/20260811-102032-runtime` 均 PASS；revision `371505b` 的隔离 clean full
  `.sdlc/evidence/20260811-102845-full` 为 9/9 steps、`workingTreeDirty=false`、A1 PASS。Provider attempts=0；
  该 full gate 早于 live/final eval，最终 revision 仍须重跑，v26 继续隐藏 `EXPERIMENTAL`。
- 2026-08-11 Plus product-v25 已按 `06cef12` PROPOSED → `34e7ab3` OPEN → `e93d1f7` CLOSED 完成单 case。
  负探针主体精确 NOT_OPEN；外层摘要因 PowerShell `$Matches` 冲突失败后完成 process/evidence/Goal/guard/
  reservation 恢复审计，确认零副作用且未重跑。唯一 wrapper exit 0、194.231 秒；独立 verifier A2 PASS：
  5 attempts、25,433 input + 10,312 output、181,561 ms、payload scan PASS。第三次 OBSERVE accepted，随后
  两次 HIERARCHY 分别为 region cardinality invalid 与 support not group，未进入 BINDING，leaf-evidence 新码
  未命中。Plus 累计 146/180、853,926/1,500,000、¥3.346968；Max v25 因新假设信号/三阶段门未成立而未调用。
- 2026-08-11 Flash product-v25 已按 `a7a2a7f` PROPOSED → `a9635e4` OPEN → `edb35bc` CLOSED 完成单 case。
  负探针精确 NOT_OPEN；Document Vision 首次配置失败后确认零 Provider/进程/lease，再由
  `.sdlc/evidence/20260811-094753-document-vision` 恢复 PASS，未并发重跑 live。唯一 wrapper 211.8 秒；独立
  verifier A2 PASS：4 attempts、16,082 input + 21,338 output、188,904 ms、payload scan PASS。全部停在
  OBSERVE，leaf-evidence 新码未命中。Flash 累计 90/180、555,365/1,500,000、¥0.258103；两份 v25 live 后
  Goal 共 315 reservations（310 SETTLED、5 个历史 Plus RESERVED）、0 BREACHED，三份 ledger CLOSED。
- 2026-08-11 `f8f09b4` / `2b6eb9c` / `6cb2624` 完成 pipeline 4.12/product-v25 的 leaf-evidence
  OBSERVE verifier：SLOT 是叶子字段，其同 artifact 证据框不得严格包住另一元素证据；GROUP 容器不受该规则
  误拒。失败只产生 `VISUAL_SEMANTIC_SLOT_EVIDENCE_CONTAINS_ELEMENT`，不写失败 checkpoint，携带固定码原地
  重试 OBSERVE；历史 pipeline 继续 `LEGACY`。60 个 stage-gold scene 全部通过；real-PG 证明只重做 OBSERVE、
  OCR 只预处理一次且不进入 checkpoint/Candidate/problem；三模型 v25 immutable Profile/snapshot verifier 通过，
  均隐藏 `EXPERIMENTAL`。server `.sdlc/evidence/20260811-093552-server`（196 tests、6 gated skip）、Node 24 web
  `.sdlc/evidence/20260811-093552-web`（73 tests + type/lint/build）、E2E
  `.sdlc/evidence/20260811-093741-e2e`（18 passed、1 gated skip）与 runtime
  `.sdlc/evidence/20260811-093824-runtime` 均 PASS；Provider attempts=0，三份 ledger 保持 CLOSED。该 A1 只
  建立新的可证伪假设，不等于 live 质量或 final eval 通过。
- 2026-08-11 Max product-v24 已按 `a04691e` PROPOSED → `b8ac358` OPEN → `57b1502` CLOSED 完成单 case。
  负探针精确 NOT_OPEN，Goal state/guard、303 reservations 与 target evidence 均未变化；复用未变的 Document
  Vision canary，唯一 wrapper exit 0、92.715 秒。独立 verifier A2 PASS：17,500 input + 4,219 output、
  79,835 ms、¥0.361884、payload scan PASS。OBSERVE、HIERARCHY、ELEMENT_BINDING 均首次 accepted，只有既有
  cardinality-derived telemetry 命中；最终 slot 0/10、group 0/3、binding 0/10、field 0/13，16 critical
  hallucinations、17 blockers，v24 observation normalization 未命中。Max Goal 现为 79/180、
  465,016/1,500,000、¥9.816288；306 reservations（301 SETTLED、5 历史 Plus RESERVED），三份 ledger
  CLOSED。CLOSED clean fast `.sdlc/evidence/20260811-091152-fast` PASS；Profile 不晋级，不进入 20/60-case eval。
- 2026-08-11 Plus product-v24 已按 `3598c12` PROPOSED → `963d1e6` OPEN → `4747947` CLOSED 完成单 case。
  负探针精确 NOT_OPEN；复用未变的 Document Vision canary，唯一 wrapper exit 0、102.811 秒。独立 verifier A2
  PASS：29,666 input + 4,777 output、90,132 ms、¥0.097548、payload scan PASS。OBSERVE accepted；两次
  HIERARCHY rejection 后以 cardinality-derived telemetry accepted，ELEMENT_BINDING accepted。最终 group 1/3、
  entity 2/4、relationship 1/3，但 slot/binding 均 0/10，另有 9 critical hallucinations、12 blockers；v24
  observation normalization 未命中。Plus Goal 现为 141/180、818,181/1,500,000、¥3.213606；303 reservations
  （298 SETTLED、5 历史 Plus RESERVED），三份 ledger CLOSED。CLOSED clean fast
  `.sdlc/evidence/20260811-085833-fast` PASS；Profile 不晋级，三阶段门已满足但不直接扩大 final eval。
- 2026-08-11 Flash product-v24 已按 `9d2dfa3` PROPOSED → `ded9e78` OPEN → `ac17e0e` CLOSED 完成单 case。
  负探针精确 NOT_OPEN；首次本地 canary 因变量名错误在 adapter 前 fail-closed，确认零进程/lease/Provider 后，
  精确 canary `.sdlc/evidence/20260811-084304-document-vision` PASS。唯一 wrapper exit 0、228.589 秒；独立
  verifier A2 PASS：20,121 input + 24,819 output、216,004 ms、¥0.023884、payload scan PASS。四次 enum、一次
  sibling-overlap，全部停在 OBSERVE，三类 v24 normalization telemetry 未命中，结构计数全 0。Flash Goal 现为
  86/180、517,945/1,500,000、¥0.237813；298 reservations（293 SETTLED、5 历史 Plus RESERVED），三份 ledger
  CLOSED。CLOSED clean fast `.sdlc/evidence/20260811-085018-fast` PASS；v24 不晋级、相同 Flash 假设不重复。
- 2026-08-11 用户再次给 Flash/Plus/Max 三个稳定槽位各追加 500,000 exposed tokens；当前累计 cap 各
  1,500,000，单 authorization 仍为 500,000，attempt/CNY/time/batch 与 synthetic/CC0/payload-free 边界不变。
  `2b23617` 将 aggregate guard 升到 v3；v1/v2 只可在锁内先验证旧 state 后原子迁移，历史 reservation 不改写，
  独立 verifier 仍可重放 v1/v2/v3。定向 12/12 与 exact-clean fast
  `.sdlc/evidence/20260811-083559-fast` PASS，Provider attempts=0；当前用量 Flash 473,005、Plus 783,738、
  Max 443,297，293 reservations 与三份 CLOSED ledger 均未变化。
- 2026-08-11 `061101f` 新增 pipeline 4.11/product-v24 bounded observation normalization：只归一化
  `DOCUMENT→ROOT`、`CONTAINER→GROUP` 与允许枚举大小写；ITEM 只有在同 artifact、bbox 包含、精确
  repeatGroupId 的 REPEATED_GROUP 候选唯一时才改 parent，受影响 readingOrder 只按几何重算。未知 alias、
  零/多候选和结构增删继续 fail-closed；成功只记录三类 payload-free telemetry。contract/Profile 20/20、
  real-PG + independent verifier 2/2、clean server `.sdlc/evidence/20260811-082418-server`（193 tests、6 gated
  skip）均绿；Provider attempts=0，三份 ledger 当时 CLOSED。后续 Flash live 结果见上条，v24 仍隐藏
  `EXPERIMENTAL`。
- 2026-08-11 Flash product-v23 已按 `0c1506f` PROPOSED → `9652837` OPEN → `1185890` CLOSED 完成单 case。
  负探针精确 NOT_OPEN；精确 Document Vision canary `20260811-080747-document-vision` PASS 后唯一 wrapper 写出
  5 个 SETTLED attempts。外层 PowerShell 因 Mockito stderr warning 失败，恢复检查确认无子进程、evidence
  完成后立即 CLOSED，未重跑。独立 verifier A2 PASS：20,110 input + 24,522 output、178,163 ms、¥0.023643、
  payload scan PASS；三次 region-kind、两次 parent-kind，全部停在 OBSERVE，结构计数为 0。该 lifecycle 关闭时
  Flash Goal 为 81/180、473,005/1,000,000、¥0.213929；Goal 共 293 reservations（288 SETTLED、5 历史 Plus
  RESERVED），后续 cap 变更见上条。
- 2026-08-11 `e13bf0c` 新增 pipeline 4.10/product-v23 hybrid：完整继承 v22 的 bounded support-owner policy，
  同时绑定既有精确 RapidOCR/OpenVINO capability；每次 drain 只生成一次 ephemeral observation，由 OBSERVE、
  HIERARCHY、ELEMENT_BINDING 共享。OCR text/line ID/bbox 只作不可信 secondary evidence，不直接补造结构，也不
  进入 checkpoint、Candidate、problem、attempt、journal 或 report。Profile/能力合同 1/1、real-PG payload/
  support-owner 与独立 verifier 2/2、clean fast `.sdlc/evidence/20260811-075518-fast`、server
  `.sdlc/evidence/20260811-075612-server`（192 tests、6 gated skip）均绿；Provider attempts=0、三份 ledger
  CLOSED。三个 v23 Profile 继续隐藏 `EXPERIMENTAL`；后续 live A2 如上，未通过 OBSERVE。
- 2026-08-11 Max product-v22 已按 `e0b1d67` PROPOSED → `740d28f` OPEN → `99efc6b` CLOSED 完成单 case。
  负探针精确 NOT_OPEN，Goal/guard/285 reservations/target evidence 零变化。唯一 Provider wrapper 的测试主体
  写出 3 个 SETTLED attempts 后完成；外层 PowerShell 因 Mockito stderr warning 未取得 Maven 摘要，检查确认
  无子进程、evidence 已原子完成后立即 CLOSED，未重跑。独立 verifier A2 PASS：11,318 input + 3,163 output、
  61,032 ms、¥0.249684、payload scan PASS。OBSERVE/HIERARCHY/ELEMENT_BINDING 三次均 accepted 且无 repair，
  但 3 个期望 GROUP/relationship 实际均为 0，bindings 0/10、tree edit 30/32；未命中 v22 normalization，
  report 仍 `complete=false`。Max Goal 现为 76/180、443,297/1,000,000、¥9.454404；288 reservations 中
  283 SETTLED、5 历史 RESERVED，三份 ledger CLOSED。相同 Max v22 不再重复，20-case 前先离线收窄
  repeated-group/relationship omission 的 evidence-bounded OBSERVE 假设。
- 2026-08-11 Plus product-v22 已按 `6f65516` PROPOSED → `2d396e7` OPEN → `4f86456` CLOSED 完成单 case；
  负探针精确 NOT_OPEN，Goal/guard/280 reservations/target evidence 零变化。唯一 wrapper exit 0、138,611 ms，
  先 CLOSED 后由独立 verifier A2 重建：5 attempts、19,659 input + 7,284 output、128,862 ms、payload scan PASS。
  第 2 次 OBSERVE、第二个 HIERARCHY 计划及 ELEMENT_BINDING 均 accepted，首次实证三阶段可达；仅命中
  cardinality-derived telemetry，未命中 v22 support-owner normalization。报告仍 `complete=false`，结构/绑定
  匹配远未达标，Profile 继续隐藏 `EXPERIMENTAL`。Plus Goal 现为 136/180、783,738/1,000,000、¥3.116058；
  285 reservations 中 280 SETTLED、5 历史 RESERVED，三份 ledger CLOSED。Max 前置条件现已成立，但尚未调用；
  当时下一步是重新计算 identity/snapshot，并以新的精确 J1/额度/时限门执行 Max v22 单 case；结果见上一条。
- 2026-08-11 `edc0c28` 新增 pipeline 4.9/product-v22：仅当 relationship support 是一个已知非 GROUP、
  exact relationship region 是已验证容器、且恰有一个 observed GROUP 精确拥有该 region 时，才确定性替换
  support owner 并派生 cardinality。未知 support、非容器、零/多 owner 保留原固定码；不跨 GROUP、不排名、
  不改结构。inference 定向 36/36、独立 verifier 2/2、real-PG BINDING lease recovery 1/1、server 191
  （6 gated skip）、Node 24 Web 73、E2E 18/1 与 clean fast `.sdlc/evidence/20260811-072030-fast` 全绿；
  Provider attempts=0，三份 ledger CLOSED。product-v22 仍隐藏 `EXPERIMENTAL`；下一步仅在 fresh identity/
  snapshot 与精确 J1/额度/时限检查后做 Plus v22 单 case，实证 BINDING 前禁止 Max。
- 2026-08-11 Plus product-v21 已按 `405fa9e` PROPOSED → `d793c92` OPEN → `02872c5` CLOSED 完成单 case；
  负探针精确 NOT_OPEN，Goal/guard/275 reservations/target evidence 零变化。唯一 wrapper exit 0、174,353 ms，
  先 CLOSED 后由独立 verifier A2 重建：5 attempts、18,715 input + 8,810 output、164,553 ms、payload scan PASS。
  前两次 OBSERVE 为 parent-kind/element fixed code，第 3 次接受；两次 HIERARCHY 均为 support-not-group，未进
  BINDING，未命中 normalization telemetry。Plus Goal 现为 131/180、756,795/1,000,000、¥3.018468；280
  reservations 中 275 SETTLED、5 历史 RESERVED。CLOSED fast `.sdlc/evidence/20260811-070414-fast` PASS；
  Max 入口仍未成立，相同 v21 不再重复。
- 2026-08-11 `dda763c` 新增 pipeline 4.8/product-v21：只在 relationship 当前已知 region 的 cardinality
  或 parent/child connection 不成立时，检查唯一有效支撑 GROUP 已验证的 owned regions；恰有一个同时满足
  cardinality 与 connection 才确定性归一化。零/多 combined 候选保留既有固定 HIERARCHY 诊断，零
  cardinality 候选仍回 OBSERVE；不跨 GROUP、无距离/排序、不改 topology/entity/relationship。inference
  定向 35/35、独立 verifier 2/2、real-PG lease recovery 1/1、server app 190（6 gated skip）、Node 24 Web 73、
  E2E 18/1 与 clean fast `.sdlc/evidence/20260811-065134-fast` 全绿；Provider attempts=0，三份 ledger CLOSED。
  product-v21 仍隐藏 `EXPERIMENTAL`；live 结果见上，下一步只诊断 exact relationship-region GROUP owner
  是否能为 support-not-group 提供唯一、可证明的本地归一化条件。
- 2026-08-11 Plus product-v20 已按 `7afda44` PROPOSED → `191cf63` OPEN → `85b2000` CLOSED 完成单 case；
  负探针精确 NOT_OPEN，Goal/guard/270 reservations/target evidence 零变化。唯一 wrapper exit 0、119,361 ms，
  先 CLOSED 后由独立 verifier A2 重建：5 attempts、24,251 input + 6,086 output、109,414 ms、payload scan PASS。
  OBSERVE 首次接受 12 SLOT/1 GROUP；四次 HIERARCHY 依次为 support-not-group、两次
  relationship-region-connection-invalid、support-IDs-empty，未进 BINDING，未命中 normalization telemetry。
  Plus Goal 现为 126/180 attempts、729,270/1,000,000 tokens、¥2.910558；275 reservations 中 270 SETTLED、
  5 历史 RESERVED。CLOSED fast `.sdlc/evidence/20260811-063705-fast` PASS；Max 入口仍未成立，相同 v20 不再重复。
- 2026-08-11 `391bd52` 新增 pipeline 4.7/product-v20：旧 Profile 保持 strict；仅当模型给出的已知
  relationship region 与 evidence-derived cardinality 不兼容，且唯一支撑 GROUP 恰有一个 compatible owned
  region 时才确定性归一化。零候选回到 OBSERVE，多个候选继续固定码 fail-closed；不跨 GROUP 选择、不补元素、
  不删边、不改 topology。payload-free telemetry、stage-local checkpoint、监控 UI 与 1024 E2E 已覆盖。inference
  定向 34/34、独立 verifier 2/2、real-PG lease recovery 1/1、server app 189（6 gated skip）、Web 73、E2E
  18/1 与 clean fast `.sdlc/evidence/20260811-062623-fast` 全绿；Provider attempts=0，三份 ledger CLOSED。
  product-v20 仍为隐藏 `EXPERIMENTAL`；live 结果见上，下一本地切片收窄到 relationship region connection。
- 2026-08-11 Plus product-v19 已按 `09c3c16` PROPOSED → `7e1b98b` OPEN → `aa92ae2` CLOSED 完成单 case；
  负探针精确 NOT_OPEN，Goal/evidence 零变化。独立 verifier A2 PASS：5 attempts、24,956 input + 6,103 output
  tokens、109,700 ms、payload scan PASS。OBSERVE 首次接受 13 SLOT/1 GROUP，随后四次 HIERARCHY 均为
  `VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CARDINALITY_INVALID`，未进 BINDING。Plus Goal 现为 121/180
  attempts、698,933/1,000,000 tokens、¥2.813368；270 reservations 中 265 SETTLED、5 历史 RESERVED。
  CLOSED fast `.sdlc/evidence/20260811-060633-fast` PASS；Max 入口仍未成立，相同 v19 不再重复。
- 2026-08-11 `214fff9` 新增 pipeline 4.6/product-v19：旧 Profile 保持 strict，新 Profile 只稳定去除同一有效
  relationship support ID 的精确重复，并把 missing/empty/limit/invalid 拆为固定 payload-free code；不同 ID
  之间不择一，不补 GROUP、不删边、不猜结构。归一化 telemetry、结构码 no-crop、最早阶段恢复与 UI/E2E 已覆盖。
  定向 33/33、独立 verifier 2/2、real-PG lease recovery 1/1、server 188（6 gated skip）、Web 73、E2E
  18/1 与 clean fast `.sdlc/evidence/20260811-055541-fast` 全绿；Provider attempts=0，三份 ledger CLOSED。
  product-v19 仍为 `EXPERIMENTAL`；其 live 结果见上，下一本地切片已由 product-v20 承接。
- 2026-08-11 Plus product-v18 已按 `df166df` PROPOSED → `dca738c` OPEN → `2ee5691` CLOSED 完成单 case；
  负探针精确 NOT_OPEN，Goal/evidence 零变化。独立 verifier A2 PASS：5 attempts、20,274 input + 4,920 output
  tokens、89,402 ms、payload scan PASS。OBSERVE 首次接受 9 SLOT/0 GROUP，随后四次 HIERARCHY 均为
  `VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_INVALID`，未进 BINDING。Plus Goal 现为 116/180 attempts、
  667,874/1,000,000 tokens、¥2.714632；265 reservations 中 260 SETTLED、5 历史 RESERVED。CLOSED fast
  `.sdlc/evidence/20260811-053811-fast` PASS；Max 入口仍未成立，下一切片是本地 bounded relationship support-ID
  合同诊断与 stage-local repair。
- 2026-08-11 `4d2cc46` 新增 pipeline 4.5/product-v18：只对新 Profile 将 generic hierarchy region ownership
  failure 拆成 entity/relationship region 字段、未知引用、root coverage、parent-child connection 与
  region/cardinality 六类固定码；v17 继续 legacy generic。hierarchy prompt v6 同步 support-not-group/reuse 的
  stage-local 指令，不补 GROUP、不猜结构、不触发结构 crop。合同/Profile/prompt 31/31、独立 verifier 2/2、
  real-PG lease recovery 1/1、server 187 app tests（6 gated skip）、Web 73、E2E 18/1 与 clean fast A1 全绿；
  实现期间 Provider attempts=0，三份 ledger CLOSED。对应 Plus v18 结果见上一条；实证 BINDING 前禁止 Max。
- 2026-08-11 Plus product-v17 单 case 已按 `178bafb` PROPOSED → `8e0c31e` OPEN → `7107303` CLOSED 完成；
  负探针精确 NOT_OPEN 且 Goal/evidence 零变化。独立 verifier A2 PASS：5 attempts、27,498 input + 7,733
  output tokens、142,447 ms、payload scan PASS。OBSERVE 接受 20 SLOT/1 GROUP，随后 HIERARCHY 三次
  region ownership invalid、一次 support not group，未进 BINDING。Plus Goal 现为 111/180 attempts、
  642,680/1,000,000 tokens、¥2.634724；260 reservations 中 255 SETTLED、5 历史 RESERVED，三份 ledger CLOSED。
  Max 入口未成立；该结果随后驱动了 product-v18 的 bounded HIERARCHY region ownership/support repair。
- 2026-08-11 `31a8c6f` 新增 pipeline 4.4/product-v17：只在 relationship 的 exact GROUP/REPEATED_GROUP region
  缺 observed GROUP element owner 时从 HIERARCHY 回退 OBSERVE；已有 owner 的 GROUP reuse 仍在 HIERARCHY。
  count-based capacity 假设因无法区分上游遗漏与多余 relationship 已否决。合同/Profile 29/29、独立 verifier
  2/2、real-PG rewind/recovery 1/1、server 383（6 gated skip）、Web 73、E2E 18/1 与 clean fast A1 全绿；
  Provider attempts=0，三份 ledger CLOSED。对应 Plus v17 结果见上一条；实证 BINDING 前不调用 Max。
- 2026-08-11 Plus product-v16 单 case lifecycle 已 CLOSED/A2：5 attempts、19,201 input + 8,281 output tokens、
  147,141 ms、payload scan PASS。第二次 OBSERVE accepted 并得到 10 SLOT/1 GROUP；后三次 HIERARCHY 为
  support group reused / relationship support IDs invalid / support group reused，未到 BINDING。Plus Goal 现为
  106/180 attempts、607,449/1,000,000 tokens、¥2.517864；255 reservations 中 250 SETTLED、5 历史 RESERVED，
  三份 ledger CLOSED。v16 不再重复；后续 v17 的收窄合同见上一条，满足 live BINDING 前仍不调用 Max。
- 2026-08-11 `bb15096` 新增 pipeline 4.3/product-v16：relationship cardinality 只从唯一已验证 GROUP 的
  multiplicity 派生，旧 4.1/v15 仍严格拒绝 mismatch；多支撑、未知与非 GROUP 均 fail-closed，accepted
  HIERARCHY 只写 payload-free 派生计数。codec/Profile 定向 12/12、真实 PostgreSQL 纵向切片 1/1、server
  379 tests（6 gated skip）、Web 73、E2E 18 passed/1 gated skip 与 exact-clean fast A1 全绿；没有 Provider
  调用，三份 ledger CLOSED。该离线节点后的 Plus v16 live 结果见上一条；未实证到 BINDING 前不调用 Max。
- 2026-08-11 Plus product-v15 单 case lifecycle 已 CLOSED/A2：5 attempts、15,823 input + 9,945 output tokens、
  payload scan PASS。第三次 OBSERVE accepted 并得到 8 SLOT/1 GROUP，因此 0-GROUP rewind 未触发；后两次
  HIERARCHY 均为 `VISUAL_HIERARCHY_V2_SUPPORT_CARDINALITY_MISMATCH`，仍未到 BINDING。Plus Goal 现为
  101/180 attempts、579,967/1,000,000 tokens、¥2.413214；三份 ledger CLOSED，Max 仍未调用。相同 v15 假设
  不再重复 live，下一步先离线消除 model-owned cardinality 与 evidence-owned GROUP multiplicity 的冗余冲突。
- 2026-08-11 N7 Plus product-v14 已按单 case/5 attempts 完成 PROPOSED→负探针→OPEN→CLOSED 与独立 A2：
  18,992 input + 4,628 output tokens，首个 OBSERVE accepted，但 0 GROUP 使后续四次 HIERARCHY 全部被 bounded
  contract 拒绝，仍未触达 BINDING。`195894b` 随后新增唯一的 HIERARCHY→OBSERVE semantic rewind、事务
  checkpoint 清理、immutable prompt v7/product-v15 与 payload-free UI/E2E；clean fast/server/web/E2E A1 全绿，
  没有新增 Provider 调用。Plus Goal 当时为 96/180 attempts、554,199/1,000,000 tokens、¥2.302008；三份 ledger
  全部 CLOSED，Max 前置条件仍未成立，Goal/Profile 继续 `in_progress`/`EXPERIMENTAL`。
- 2026-08-11 用户批准 T6-5 N7 J1 delta：当前 Flash 改为 pinned
  `qwen3.7-flash-2026-07-15`，Plus/Max model ID 不变，三个预算槽位各追加 500,000 tokens，累计 cap
  1,000,000。历史用量继续计入；180 attempts、Max ¥18 / Plus ¥4 / Flash ¥0.40、batch≤5、168h、
  synthetic/CC0-only 与 payload-free 边界不变。N7 已恢复到 capability/Profile 与 Goal guard v2 freeze，
  所有旧 ledger 仍 CLOSED，尚未新增 Provider 调用。
- 2026-08-11 图片识别 vNext N6 已 `automated_verified`：新增 observation/hierarchy/binding bounded semantic
  verifier、最早阶段 repair、最多 4 个 verified selected crops、最多 16 个累计诊断，以及监控/审核共用的
  payload-free checkpoint telemetry。Flash v10/v11/v12 在同一个 repository-synthetic case 上共 15 attempts /
  101,250 actual tokens，独立 verifier 与 payload scan 均 PASS，但全部停在 OBSERVE；Flash Goal 累计
  393,034/500,000 tokens。未调用 Plus 或 Max，三份 ledger 全部 CLOSED；`de97131` exact-clean full A1 全绿。
  N7 的三阶段和预算入口未满足，所有 Profile 保持隐藏 `EXPERIMENTAL`，Goal 未完成。
- 2026-08-10 图片识别 vNext N5 已完成但 Hybrid 未晋级：实现有界 RapidOCR/OpenVINO adapter 与隐藏 v7
  Profile；qwen3.7-plus 同 3 个站牌 case 的 v4/v6/v7 field recall 分别为 4/39、0/39、0/39，v6/v7
  响应均卡在 `VISUAL_GROUNDING_CONTRACT_INVALID`，随后 Plus HTTP 403。Plus Goal 暴露量为
  485,886/500,000 tokens；全部 ledger CLOSED，v7 保持默认关闭。该缺口已由 N6 的 bounded diagnostics、
  semantic verifier 与 earliest-stage targeted repair 接续处理。
- 2026-08-10 图片识别数据结构 vNext Goal 的 N4 已完成：新增有界 overview/tile/crop、多尺度坐标回写、grounding 2.0 region forest、entity/relationship 空间归属与 checkpoint 3.0；通用视觉 Prompt 与显式公交 hint pack 分离，六份 v6 Profile 保持隐藏 `EXPERIMENTAL`。`1400edb` exact-clean Server 330 tests（5 gated skip）A1 通过，Provider attempts/reservations=0；当前进入 N5 OCR/layout adapter 与 pure/multiscale/hybrid 消融。
- 2026-08-10 图片识别数据结构 vNext Goal 的 N3 已完成：pipeline 4 使用确定性 Java materializer 将 validated element/hierarchy/binding plan 编译为 byte-stable Candidate，正常路径从 4 次降为 3 次 Provider 调用，STRUCTURE 与恢复路径均为零调用；新增 Flash/Plus/Max 三份严格 capability 与内部 v5 Profile，产品选择器仍保持 v4。图片发送边界同步收窄为最长边 4096 且总像素 16,000,000。`3d56c51` exact-clean Server 330 tests（5 gated skip）A1 通过，Provider attempts/reservations=0；当前进入 N4 多尺度 region/grounding 与 Prompt 去偏。
- 2026-08-10 图片识别数据结构 vNext Goal 的 N2 已完成：三个 Product v4 Profile 在同一 12-case sentinel 上共执行 174 attempts / 913,446 tokens / ¥7.742620，全部授权均已 CLOSED。Max/Plus/Flash final pass 均为 0/12；Max 的 entity/relation 更强但 binding 仅 3/88，Plus/Flash 更早受 hierarchy contract 限制，三者 grounding IoU@0.5 均为 0，均保持 `EXPERIMENTAL`。Max/Flash 为 A2，Plus continuation A2、初始末态 A1，聚合状态诚实记为 `live_verified_mixed_a1_a2`；`20ca000` clean fast A1 通过。当前进入 N3：以确定性 Java materializer 替代生成式 STRUCTURE，节点内零 Provider 调用。
- 2026-08-10 图片识别数据结构 vNext Goal 的 N1 已完成：新增 60-case / 45 DEV + 15 HOLDOUT 的 IMAGE_ONLY stage-gold 与确定性 raster、element/region/hierarchy/binding/Candidate 分阶段指标、校准与切片报告；新增三模型跨 ledger Goal 预算守卫、可恢复 journal、严格 identity、固定授权路径、默认关闭 runner 和独立 Python 证据重算器。`0c92181` clean exact Server 316 tests（4 gated skip）与 Eval 63 tests A1 通过，Provider attempts/reservations=0；当前进入 N2 exact-identity baseline 前置，所有 live ledger 仍未 OPEN。
- 2026-08-10 图片识别数据结构 vNext Goal 的 N0 已完成：批准 spec delta、ADR-0022、N0–N7 DAG、恢复策略与三模型各 500k-token J1 总信封均已落盘；分支 `phase/p6-visual-recognition-vnext`，fast A1 通过，Provider attempts=0。当前进入 N1：先建立 60-case IMAGE_ONLY stage-gold、阶段指标、可恢复 journal、跨账本预算守卫与独立 verifier；任何 live ledger 仍保持非 OPEN。
- 2026-08-10 T6-2e 已统一不可变数据定义的阅读体验：Draft revision 历史和 StaticSchema 详情默认展示字段树，并可切换字段表单、DSL JSON / compiled artifact；Draft 深链侧栏不再动态增加“当前 Draft”，资源与智能识别面包屑左对齐，原生下拉框使用统一系统样式。`9ea35e9` clean Web 71 / E2E 17 A1；未调用 Provider。
- 2026-08-10 T6-3a.10 已闭环运行中任务的协作式取消：监控页和历史页在服务端受理后立即显示“正在取消”，说明当前 Provider 调用结束后才进入安全检查点并禁止重复取消；attempt、费用结算与最终 CANCELLED 现由同一 PostgreSQL 事务保存，避免取消竞态回滚真实调用日志。`3fb66e2` clean Server 142 / Web 69 / E2E 17 A1；本机 live overlay 已部署，部署前后 active=0、attempts=15、reservations=12，未新增 Provider 调用。
- 2026-08-10 T6-3a.8 已发布 Product v3 串行视觉识别协议：元素盘点 → 层级规划 → 元素归属 → 数据定义；严格中间契约与最终拓扑校验防止把站牌 → 线路[] → 停靠站点[] 压扁为标量数组，并把站点中英文名和温馨提示子 Schema 纳入合成回归。`ea763cb` clean Server 138 / Web 66 / E2E 16 A1；本机 live overlay 已升级到 V014/四个 Product v3 Profile，部署前后 attempts=10、reservations=6，未新增 Provider 调用；真实模型质量验证待独立授权。
- 2026-08-10 T6-3a.7 已将历史任务固定为智能识别默认页，右上角分别进入新增识别与确定性样本；`/inference/new` 只加载 DashScope Live 输入，`/inference/samples` 独立承载零网络 Replay，并移除跨页面卡片导航。`378046e` clean Web 66 / E2E 16 A1；未调用 Provider。
- 2026-08-10 T6-3a.6 已把智能识别拆成历史任务、新增输入、识别监控、识别结果四个可深链版面；创建/重试先进入监控，结果页只承载 Candidate。Candidate save 同时兼容旧页面，把已编辑 AI 项安全归一为 `RESOLVED_BY_EDIT`。`fe9ce0d` clean Server 134 / Web 66 / E2E 16，`0375c86` clean Web 66 / E2E 16 A1；未调用 Provider。
- 2026-08-10 T6-3a.5 已修复真实 IMAGE_ONLY 证据框坐标空间错判：服务端对明确的像素坐标族换算为 0..10000 并保留 WARNING，Web 同规则兼容历史 Candidate；审核页聚合重复诊断、隐藏单 Schema 排序列并重排字段信息层级。`6fde235` clean Server 134 tests / Web 60 tests / E2E 16 passed A1，未调用 Provider。
- 2026-08-10 T6-3a.4 已把真实 IMAGE_ONLY 的纯技术格式偏差与人工语义问题分离：非法 SchemaKey 和合法标量冗余 observedKinds 由本地窄域规范化为 WARNING，低置信/未解析项仍必须人工审核；审核页新增无载荷执行日志，展示阶段、调用、Token、费用、耗时与有限问题码。`02819cc` clean Server 133 tests / Web 56 tests / E2E 16 passed A1，未调用 Provider。
- 2026-08-10 T6-3a.3 已将“源图尺寸”和“Provider 规范化尺寸”解耦：常见超大 PNG/JPEG 自动有界缩放至最长边 4096，极端源图仍受 65535 长边/268M 像素硬门保护。clean Server 131 tests / Web 55 tests A1 PASS；部署后 4097 像素幂等冲突探针证明归一化已生效且 run/artifact/reservation 计数不变。
- 2026-08-10 T6-3a.2 已修复产品上传的 Nginx 1 MiB 默认上限：`/api/` 现在允许 35 MiB，由 Spring 保持 34 MiB multipart 合同；网关 413 返回 JSON Problem，前端也能把非 JSON 错误转成可读问题。clean Web A1 PASS；真实容器 1.25 MiB/36 MiB 合成探针通过且任务/预留计数均未变化。
- 2026-08-10 T6-3a.1 已修复 Product live 的 `assessment.evidence` 结构失败：decoder 的精确有限诊断现在会进入 repair，而不再退化成通用 `LIVE_STRUCTURE_OUTPUT_INVALID`；四个新建任务 Profile 已升级到 Prompt 4 / Product v2，单次保守预留上界统一为 ¥2。clean server/web/e2e A1 PASS，部署后 readiness 正常且 Agent 新增 Provider reservations=0。
- 2026-08-10 T6-3a 产品 live 运行切片已完成：固定四个产品 DashScope Profile、可选 per-run 累计成本限额、隔离的 product-live reservation、V013 与显式 Compose live overlay；clean full A1 PASS，部署 readiness 为 enabled/configured/uploadEnabled=true，Agent 未调用 Provider且 product reservations=0。
- 2026-08-10 T6-2 四步 AI Schema 识别工作台已完成：Candidate 完整编辑、多证据、运行恢复、1024 drawer、最近任务、offline eval 与浏览器/真实 PG 旅程均已闭环；clean A1 与独立 A2 PASS（0 Blocker / 0 High / 0 Medium），最终成品视觉 J1 待用户确认。
- 2026-08-08 授权的 P1–P4 Goal 实施范围已完成并通过本地 A1 自动验证。
- 2026-08-08 的 P5 canary J1 已关闭：双 Profile 各 1 次，共 2 attempts / ¥0.054017；只证明通路，不构成质量认证。
- 2026-08-09 Flash 60-case live 已完成并经独立 A2：112 attempts / ¥0.122980，2/60 exact pass，policy=`EXPERIMENTAL`，authorization 已 CLOSED。
- 2026-08-09 pinned Plus 60-case live 已完成并经独立 A2：75 attempts / ¥0.825948，18/60 exact pass，policy=`EXPERIMENTAL`，authorization 已 CLOSED。
- 2026-08-09 Prompt v2 60-case live 已完成并经独立 A2：70 attempts / 256,153 tokens / ¥0.868772，47/60 exact pass，policy=`EXPERIMENTAL`，authorization 已 CLOSED。
- 2026-08-09 Grounded Pipeline v2 60-case live 已完成并经独立 A2：80 attempts / 278,740 tokens / ¥0.908984；JSON_ONLY 20/20 零调用、COMBINED 20/20 单次调用，IMAGE_ONLY 0/20，policy=`EXPERIMENTAL`，authorization 已 CLOSED。
- 2026-08-09 T5-9 payload-free attempt taxonomy 已完成 clean server A1 与独立 A2（0 Blocker / 0 High / 0 Medium）；未调用 Provider，不构成新的 Profile 认证。
- 2026-08-09 T5-10 IMAGE_ONLY live 诊断已完成并独立 A2 PASS：20/20 case 均 `LIVE_REPAIR_BUDGET_EXHAUSTED`，60/60 attempts 均为 `CANDIDATE_DECODE_VALUE_INVALID`；197,321 tokens / ¥0.642106，ledger 已 CLOSED。
- 2026-08-10 T5-11 已离线细分值级解码 taxonomy：enum / constructor / format 均使用有限且 owner-bound 的 contract slot，关闭 coercion；clean server A1 与独立 A2 PASS（0 Blocker / 0 High / 0 Medium），Provider attempts=0。
- 2026-08-10 T6-1 容量基线已完成：10k Draft / 100k revision / 10k Static / 10k run、10 条并发生产 Controller 旅程、共享 2 worker lanes；clean server/capacity A1 与独立 A2 PASS，Provider attempts/reservations=0，不宣称生产 SLA。
- 产品模型目录固定为 `qwen3.7-flash`、`qwen3.7-plus`、`qwen3.8-max`、`qwen3.7-max-2026-06-08`；当前可见目录使用 Product Profile v3，IMAGE_ONLY 依次绑定元素/层级/归属 Prompt 1 与 Candidate Prompt 5，单次预留上界仍为 ¥2。历史 product-v1/v2 与 pinned/评测 Profile 保持不可变并从产品选择器隐藏。
- 需求访谈已收束，v1 产品语义以 `specs/renderweave-v1.md` 为准。
- 生命周期状态：P0 `accepted`；P1–P4 `automated_verified`；P5 Flash / Plus / Prompt v2 / Grounded v2 / T5-10 诊断均为 `live_independently_reviewed`，T5-9/T5-11 与 P6/T6-1 为 `independently_reviewed`；P6/T6-2 为 `human_acceptance_pending`；T6-3a 与 T6-5 N6 为 `automated_verified`、T6-3b pending。T6-5 整体仍 active，N7 `in_progress`。所有 DashScope Profile 仍为 `EXPERIMENTAL`；历史评测授权均 CLOSED，基础 Compose 默认关闭，显式 product-live overlay 已按用户授权开放。

## 下一步
- [ ] P6/T6-5 图片识别 vNext：v34 Flash/Plus live 已 CLOSED/A2；product-v35 的 empty-source-ancestor
  contract、immutable Profile、real-PG lease recovery 与 payload-free monitor/review/E2E 已离线通过。下一步在
  docs checkpoint 后 exact-clean revision 跑 full 与 Document Vision，再 fresh 重算 evaluation identity、三份
  v35 snapshot、Goal aggregate、剩余 token/attempt/CNY/time、J1、API 配置存在性和进程/lease。全部匹配才
  优先执行 Flash 单 case/最多 5 calls 的 PROPOSED→负探针→OPEN→CLOSED smoke；Plus 只剩 1 attempt，
  不足以验证三阶段。Max 仍需 v35 同版本 live 三阶段与质量门。final 20/60、final independent verifier 与
  业务/视觉 J1 均未满足。
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
- [x] 取得 T5-10 exact J1，完成 20-case IMAGE_ONLY 诊断并立即 CLOSED；独立 A2 为 0 Blocker / 0 High / 0 Medium。
- [x] T5-11：离线细分 `CANDIDATE_DECODE_VALUE_INVALID` 的 enum/constructor/format owner-bound 有限 contract-slot 归因；clean server A1 与独立 A2 PASS，保持零 Provider 调用。
- [x] T6-1：建立可复跑的 10k/100k/10k/10k 容量夹具、列表/图查询收敛、Replay/Live 统一两 lane 与重启恢复；clean server/capacity A1 和独立 A2 PASS。
- [x] T6-2：完成四步 AI Schema 识别产品旅程、Candidate 完整编辑、多证据、恢复/重试、1024 drawer、60-case offline eval、mocked/real-PG browser gates 与独立 A2；最终成品 J1 单独 pending。
- [x] T6-2e：revision 历史与 StaticSchema 默认字段树、只读多视图、稳定 Draft 深链导航、左对齐面包屑与统一 select 视觉；clean Web/E2E A1，最终视觉 J1 仍 pending。
- [x] T6-3a：开放四个产品 Profile，支持可选任务成本限额与独立 product-live 预算账本；完成 V013、Compose live overlay、无 Provider clean full A1 与本机 readiness 验证。
- [x] T6-3a.1：修复 `assessment.evidence=null` 时精确 decode diagnostic 丢失的问题；发布 Prompt 4 / Product Profile v2，统一 ¥2 单次预留上界，并完成零 Provider server/web/e2e A1 与重新部署。
- [x] T6-3a.2：修复 Nginx 1 MiB multipart 截断与前端 HTML 413 JSON 解析崩溃；完成 clean Web A1、`nginx -t` 和两档真实网关零任务探针。
- [x] T6-3a.3：服务端自动规范化常见超大设计图并保留源图安全硬门；完成 unit、真实 PostgreSQL API、clean Server/Web A1 和部署后零 Provider 探针。
- [x] T6-3a.4：修复 IMAGE_ONLY 技术 blocker 与人工 blocker 混合导致的 `LIVE_UNSAFE_BLOCKER_SET`；新增 payload-free execution log API/审核页时间线，并完成 clean Server/Web/E2E A1。
- [x] T6-3a.5：统一图片证据坐标空间，聚合重复诊断并优化 Candidate 字段信息层级；完成 clean Server/Web/E2E A1。
- [x] T6-3a.6：兼容旧客户端 AI item 编辑状态，并拆分历史、新增、监控、结果四个深链版面；完成 clean Server/Web/E2E A1。
- [x] T6-3a.7：历史任务成为默认入口，Live 新增识别与确定性 Replay 样本彻底拆分；完成 clean Web/E2E A1。
- [x] T6-3a.8：发布 Product v3 串行视觉分析、四类严格契约、V014 与站牌三层拓扑回归；完成零 Provider clean Server/Web/E2E A1。真实 DashScope 质量验证仍需绑定 Product v3 identity 的新 J1。
- [x] T6-3a.9：定位真实 v3 run 的两次 90 秒 `STRUCTURE` deadline；发布 Product v4 240 秒 stage timeout、调用前 lease 续租与精确 `DASHSCOPE_TIMEOUT`；clean Server/Web/E2E A1 和部署前后 15 attempts / 11 reservations 零增量探针通过。
- [x] T6-3a.10：确认运行中取消已被服务端受理但 UI 无反馈，并修复取消边界上已结算 attempt 被事务回滚的问题；clean Server/Web/E2E A1 和部署前后 15 attempts / 12 reservations 零增量探针通过。
- [ ] T6-3b：完成数据库/Blob 备份恢复、missing artifact、storage full 与操作员观测演练。

## 重要发现或局部阻塞
- 本机全局 Node 为 20.20.2；正式 gate 已使用 checksum 固定的仓库局部 Node 24.19.0，不依赖或修改系统 Node。
- 已建立真实 Git 节点边界；当前工作分支为 `phase/p6-visual-recognition-vnext`。T5-6/T5-7/T5-8/T5-10 live、P6/T6-1/T6-2 与 T6-5 N6 Flash smoke 均有独立只读 A2，但仍无外部 CI/branch protection 的 A3。
- T4-4 首次 server gate 由于外层命令时限过短中断，其不完整 evidence 不作为结论；随后的完整 server/web/e2e 与 real inference journey 均为绿色。
- UI 设计数据库把本项目误路由到 hero-centric/mobile/dark SaaS；已在 page override 中拒绝，采用已确认的 dense warm editorial workbench。
- Docker product-live overlay 已成功构建并运行；readiness 已确认四个产品模型与可选成本限额。`qwen3.8-max` 精确别名尚缺当前官方目录的同等明确确认，首次用户触发若被供应商拒绝必须可读失败且不得静默降级。
- 用户 run `ea18b4f8-7910-405d-aadd-a7120e836902` 已证明 qwen3.8-max 的 OBSERVE/HIERARCHY/ELEMENT_BINDING 可达；失败是 v3 的固定 90 秒请求时限，不是模型别名拒绝。v4 已部署，旧 v3 retry 仍不可升级快照。

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
- `plans/logs/P5-T5-10.md`：20-case IMAGE_ONLY live 诊断已完成并 CLOSED；60 attempts / 197,321 tokens / ¥0.642106，独立 A2 PASS，0 Blocker / 0 High / 0 Medium。
- `plans/logs/P5-T5-11.md`：值级失败已完成 owner-bound 有限槽位细分、clean server A1 与独立 A2；0 Blocker / 0 High / 0 Medium，Provider attempts=0。
- `plans/logs/P6-T6-1.md`：10k/100k/10k/10k 容量基线、摘要投影、targeted graph 与统一两 lane 已完成 clean A1/独立 A2；Provider attempts/reservations=0。
- `plans/logs/P6-T6-2.md`：四步 AI Schema 识别工作台、完整 Candidate 审核、offline eval 与 browser/real-PG acceptance 已完成 clean A1/独立 A2；T6-2e 的不可变字段树/多视图与导航视觉增量另有 clean Web/E2E A1，最终视觉 J1 pending，Provider attempts/reservations=0。
- `plans/logs/P6-T6-3.md`：T6-3a 四产品 Profile、Product v3 串行视觉分析、¥2 单次上界、上传/图像规范化、Candidate 审核与四版面均已完成 clean A1；真实 Product v3 质量验证与 T6-3b recovery drill 分别 pending。
- `plans/logs/P6-T6-3a.9.md`：Product v4 timeout/lease 诊断、clean A1 与部署零新调用探针。
- `plans/logs/P6-T6-3a.10.md`：协作式取消反馈、原子 attempt telemetry、clean A1 与部署零新调用探针。
- `plans/logs/P6-T6-5-N2.md`：Product v4 三模型 12-case 真实阶段基线、Goal 用量、独立重算与 identity `/1` 残余；全部 ledger CLOSED。
- `plans/logs/P6-T6-5-N3.md`：pipeline 4 本地 Candidate materializer、逐模型 capability/Profile、图片像素边界与 PostgreSQL 恢复；exact-clean Server A1，Provider attempts/reservations=0。
- `plans/logs/P6-T6-5-N4.md`：多尺度视图、grounding 2.0、空间不变量、显式领域 hint 与 checkpoint 3.0；exact-clean Server A1，Provider attempts/reservations=0。
- `plans/logs/P6-T6-5-N5.md`：有界本地 Document Vision、v4/v6/v7 同 case live 消融、Plus Goal 用量、
  HTTP failure 硬停与未晋级决策；全部 ledger CLOSED。
- `plans/logs/P6-T6-5-N6.md`：bounded semantic verifier、stage-local repair、selected crops、payload-free UI、
  v15–v35 bounded verifier/normalization 增量；v35 contract/Profile/real-PG/UI/E2E 离线证据已记录。
- `plans/logs/P6-T6-5-N7.md`：pinned Flash/Goal guard v4、Provider-backed single-case CLOSED/A2
  reachability、v15–v35 实证驱动增量；v34 Flash 止于 OBSERVE，Plus accepted OBSERVE 后止于 HIERARCHY，
  v35 当前仅有离线恢复证据。
- 当前可恢复代码锚点：`phase/p6-visual-recognition-vnext` 的 `5c59ce3`；v35 codec/Profile/PG/UI 为
  `614359f` / `708522b` / `a2b8181` / `5c59ce3`。Goal 为 390 reservations，三份 ledger CLOSED，
  无 visual/Maven/OCR/evidence lease 残留。编排 Goal
  `019fec8e-a851-7952-b49b-8be76a281a57` 仍显示 `paused`，用户已明确继续同一 objective，未创建 replacement
  Goal。下一节点是 docs checkpoint、exact-clean full/Document Vision 与 fresh v35 pre-live gate；门控前不能
  直接扩大 final eval。

## v27 source-ancestor 与预算硬门 checkpoint

- `676180a`：仅当 v26 enclosing 候选为零时，沿已验证 relationship source region 祖先链寻找唯一 GROUP owner；
  cardinality/connection 仍是硬条件，zero/multiple/unknown 全部 fail-closed，不读 OCR/model text/gold、不排名、
  不改 topology。
- `e1f1a9d`：发布 Flash `qwen3.7-flash-2026-07-15`、Plus、Max 三份 immutable product-v27 Profile，并接入
  worker/checkpoint、真实 PostgreSQL tracer 和独立 evidence verifier。组合后端回归 26/26，流程到达
  `REVIEW_REQUIRED`，OCR sentinel 未持久化。
- `3a56af9`：监控/审核 UI 显示 source-ancestor fixed telemetry；Node 24 Web 73/73、受影响 Playwright 1/1，
  覆盖 1024px、键盘、WCAG 与 payload 不泄漏。
- server 首轮 `.sdlc/evidence/20260811-113055-server` 发现 5 参数 budget reservation default overload 绕过
  实现事务；`5ada0fa` 显式覆写事务入口，并发回归 10/10。修复后 server
  `.sdlc/evidence/20260811-113412-server`、web `.sdlc/evidence/20260811-113607-web`、E2E
  `.sdlc/evidence/20260811-113652-e2e`、runtime `.sdlc/evidence/20260811-113726-runtime` 全绿。
- 本节点 Provider attempts=0；累计仍为 Max 79 / 465,016 tokens / ¥9.816288，Plus 150 / 883,569 /
  ¥3.436302，Flash 95 / 598,343 / ¥0.280418；324 reservations 中 319 SETTLED、5 历史 Plus RESERVED、
  0 BREACHED。三份 ledger CLOSED，所有 v27 Profile 保持 `EXPERIMENTAL`。

## v27 full 与三模型 live checkpoint

- full：`47f622b` 在 detached LF worktree 通过 9/9 steps，`workingTreeDirty=false`；evidence 为
  `D:\Yiwer\code\RenderWeave-v27-full-47f622b\.sdlc\evidence\20260811-114304-full`。
- Flash：零调用 v27 lifecycle `7cf9709`→`ac63bc9`→`d1c076e` 保留；重验 Document Vision 后的 v27b
  `6185570`→`ea9cb87`→`a473d2f` 为 5 attempts、42,913 tokens，全部停在 OBSERVE。
- Plus：`ccfce3b`→`7f49117`→`854d652`，3 attempts、16,997 tokens，三阶段 accepted；slot/binding 0 matched、
  7 blockers、4 critical hallucinations。
- Max：仅在 Plus 同版本三阶段门成立后按 `1fa1ccf`→`705ccff`→`95be8fa` 执行，3 attempts、26,903 tokens，
  三阶段 accepted；slot/binding 0 matched、26 blockers、27 critical hallucinations。
- 三份 current evidence 在最终 Goal state 下交叉 verifier PASS；CLOSED fast 首轮环境红灯
  `20260811-121310-fast` 保留，依赖联接后的 `20260811-121335-fast` PASS。Profile 不晋级，N7 继续
  `in_progress`。

## evaluation identity `/2` checkpoint

- `cded69e`：新 identity 默认使用 Git index OID 与 canonical blob bytes，并绑定 UTF-8 path/regular mode；
  hidden index flags、dirty/untracked、non-regular/missing input 与不稳定捕获全部 fail-closed。
- 新 OPEN ledger 只允许 `/2`；`/1` 仅用于 CLOSED 历史 evidence。exact-clean Java/Python `/2` 一致为
  `fc46a428…b5a7bf`，该摘要随后续 revision 必须重算。
- clean server `.sdlc/evidence/20260811-123055-server`、fast `.sdlc/evidence/20260811-123245-fast` A1 PASS；
  新 verifier 对 v27 Flashb/Plus/Max 三份真实 CLOSED `/1` evidence 回放均 PASS、payload scan PASS。
- 本节点 Provider attempts=0、Goal 用量不变、三份 ledger CLOSED；只关闭治理债务，不改变
  `EXPERIMENTAL`/N7 `in_progress` 或 final eval/J1 硬门。

## v28 minimal entity ownership checkpoint

- `76a0635`：opt-in hierarchy/binding policy 拒绝非根 ROOT ownership、同 entity 祖先/后代冗余 ownership，并
  要求字段存在唯一最小 spatial entity owner；legacy Profile 不变。
- `a96fec1`：pipeline 4.15/product-v28 三模型 Profile、worker/checkpoint 与独立 verifier；binding ambiguity
  精确回到 HIERARCHY，保留 OBSERVE inventory/grounding。inference 180/180、real-PG 2/2、snapshot 1/1 PASS。
- `6a8a36f`：monitor/review 展示三个固定码与最早 HIERARCHY 修复阶段；Node 24 Web 73/73 + build，evidence
  `.sdlc/evidence/20260811-125512-web`；隔离 4174 Playwright 1/1 PASS。
- 当前 Provider attempts=0、335 reservations 与累计用量不变，三份 ledger CLOSED。clean full、fresh `/2`
  identity/Profile snapshot、预算与时限重算通过前不 OPEN；Profile 保持 `EXPERIMENTAL`、N7 保持 `in_progress`。

## v31 clean/full 与 bounded live checkpoint

- exact code revision `e5b4994` 在干净 detached worktree 通过 full 9/9：
  `.sdlc/evidence/20260811-155539-full`；冻结 RapidOCR/OpenVINO canary 1/1、19 lines：
  `.sdlc/evidence/20260811-160517-document-vision`。Java 与独立 Python fresh identity 一致为
  `/2:578c631edfa2948527013fc0c1831de2242891a2e87bc233376fb208f3a2c0f3`；Flash/Plus/Max v31 snapshot
  分别为 `c4a32c21…398b7`、`9cdbf6df…f8df3`、`c760ef14…edb8c`。
- Flash lifecycle 为 `4ed323f` PROPOSED → `cbda25d` OPEN → `d2fd1cf` CLOSED；唯一 wrapper 5 SETTLED
  attempts，43,776 tokens / ¥0.022675。5 次均在 OBSERVE fail-closed：region-kind×4、element-invalid×1，未命中
  SLOT-owner normalization。
- Plus lifecycle 为 `58d5530` PROPOSED → `adeac0d` OPEN → `d538638` CLOSED；唯一 wrapper 5 SETTLED
  attempts，34,770 tokens / ¥0.100380。OBSERVE 首次 accepted，随后 4 次 HIERARCHY 均以
  `VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY` fail-closed，未到 BINDING/Candidate。
- 两模型 PROPOSED/CLOSED 负探针均精确 NOT_OPEN 且 Goal/evidence 字节不变；独立 Python verifier PASS、Java
  verifier 2/2、0 abandoned、payload scan PASS。最终 369 reservations（364 SETTLED、5 历史 Plus RESERVED、
  0 BREACHED）：Flash 120/815,516/¥0.392962，Plus 167/999,892/¥3.836612，Max
  82/491,919/¥10.289316。三 ledger CLOSED、无 live/Maven/lease 残留；Max 因同版本 HIERARCHY/BINDING 门未达
  保持零调用。product-v31 仍 `EXPERIMENTAL`，N6=`automated_verified`，N7/Goal=`in_progress`，final 20/60
  未启动。

## v32 empty relationship support owner checkpoint

- `212f468`：新增 opt-in hierarchy support policy。仅当 relationship 的既有 container region 位于父实体到子实体
  的 ownership 连接上，并且该 region 只有一个兼容的既有 GROUP owner 时，才把空 support 原子归一化为该
  element ID；v31、null/missing、unknown/non-container、zero/multiple owner 与断连结构继续用原 fixed code
  fail-closed。codec 正反例与 inference 185/185 PASS。
- `7e4e70c`：pipeline 4.19/product-v32 三模型 immutable Profile 显式 opt-in，继承 v31 的 Document Vision、
  OBSERVE、hierarchy/binding semantic verifier、stage-local repair 与 Candidate materializer；独立 Python verifier
  接受并重算三份 snapshot。成功只记录数量型
  `VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SUPPORT_OWNER_NORMALIZED`，不记录 owner/prompt/OCR/模型输出。
- `b892503`：真实 PostgreSQL tracer 1/1 PASS；OBSERVE、HIERARCHY、ELEMENT_BINDING 三次 attempt 全 SUCCEEDED，
  最终 `REVIEW_REQUIRED`。Document Vision 仅一次，hierarchy attempt 同时记录通用 owner 与 empty-support 专项
  计数；OCR sentinel 未进入 checkpoint、Candidate 或 validation problems。
- `7404c7a`：monitor/review 中文解释、Web 73/73 与 build、1024px Playwright 1/1 PASS。4173 被无关 TAMP
  Node 20 原型占用时测试在浏览器前退出；未终止该用户进程，改用隔离 4174 后通过且端口无残留。
- 本 checkpoint Provider attempts=0；Goal 仍为 369 reservations（364 SETTLED、5 历史 Plus RESERVED、0
  BREACHED），Flash/Plus/Max 仍为 120/167/82 attempts、815,516/999,892/491,919 tokens 与
  ¥0.392962/¥3.836612/¥10.289316，三 ledger CLOSED。product-v32 保持 `EXPERIMENTAL`，N6 继续
  `automated_verified`，N7/Goal 继续 `in_progress`；clean full/Document Vision/fresh identity、snapshot、
  aggregate budget/time 与 process/lease 门通过前不进行 live，Max/final 20/60 门不变。

## v32 clean/full 与 Plus bounded live checkpoint

- exact `954792f` 的 clean full 9/9、Document Vision 19 lines、Java/Python `/2` identity 与三份
  v32 snapshot 均精确一致。Flash 因剩余费用小于标准 OBSERVE reservation 而保持 CLOSED。
- Plus 按 `54bc798`→`a94810c`→`5d71b3f` 完成 PROPOSED→负探针→OPEN→唯一 wrapper→
  CLOSED；A2/payload scan PASS，3 attempts / 21,316 tokens / ¥0.067226 / 0 abandoned。OBSERVE accepted，
  HIERARCHY 两次 support-element-unknown，BINDING 未执行。
- 当前 372 reservations = 367 SETTLED + 5 历史 Plus RESERVED，0 BREACHED。Flash/Plus/Max 为
  120/170/82 attempts、815,516/1,021,208/491,919 tokens、¥0.392962/¥3.903838/¥10.289316。三
  ledger CLOSED；Flash/Plus 剩余费用低于标准 OBSERVE 预留，Max 的同版本三阶段门未成立。

## v33 unknown relationship-support owner checkpoint

- `5951047`：仅当 relationship 只有一个 unknown support ID，已知 container region 在父子
  ownership 连线上且只有一个兼容 GROUP owner 时才原子归一化。歧义、多 unknown、
  non-container/disconnected 仍用原 fixed code fail-closed；不新建结构或证据。
- `7ac4259`：发布 pipeline 4.20 与三份 product-v33 immutable Profile；独立 verifier 支持并
  重算 snapshot。`edd310d`：真实 PostgreSQL tracer 到达 `REVIEW_REQUIRED`，Document Vision 一次、
  OBSERVE/HIERARCHY/BINDING 三次 stage SUCCEEDED，OCR sentinel 零持久化。
- `94060a0`：monitor/review 显示 unknown-support fixed code 与中文说明。contract 28/28、inference
  186/186、Profile/capability 3/3、independent verifier 2/2、real-PG 1/1、Web 73/73 +
  lint/typecheck/build、隔离 4187 的 1024px Playwright 1/1 PASS，端口无残留。
- 本节点 Provider attempts=0，Goal 用量不变。product-v33 仍 `EXPERIMENTAL`，N6 仍
  `automated_verified`，N7/Goal 仍 `in_progress`；通用 live fixed code 不足以证明 unknown 必属于
  relationship，所以不宣称质量改善，不启动 final 20/60。

## v34 bounded live 与 v35 empty-source-ancestor checkpoint

- exact-clean `751e412` 的 full `.sdlc/evidence/20260811-191800-full` 9/9 与 Document Vision
  `.sdlc/evidence/20260811-192239-document-vision` 19-line canary PASS；Java/Python identity 为
  `/2:dbeeb7cf9f928508b7b9f0f6148fdcf9451c69876cb7f0715397fa4fbd3d1f50`。
- Flash `e213243`→`72e25cd`→`ea5bda5`：5 attempts、43,396 tokens、¥0.022364；全部在 OBSERVE
  以 region-kind×3、parent-kind×2 fail-closed。Plus `f36195f`→`4ab12ea`→`fd7fb35`：4 actual
  attempts、30,885 tokens、¥0.096198；OBSERVE accepted，HIERARCHY 为 empty-support×1、
  support-not-group×2，第五次在 Provider 前被费用 reservation 阻断。两份独立 verifier/payload scan
  PASS、0 abandoned，三 ledger CLOSED，无 live/Maven/OCR/lease 残留。
- `614359f` 将 empty-support 的安全子集限制为：已知后代 relationship region、唯一严格祖先
  GROUP/REPEATED_GROUP owner、基数兼容且 parent/child ownership 连通；exact owner 仍优先，unknown
  support 不扩展，zero/many/disconnected/non-ancestor 全部保留原 fixed code。
- `708522b` 发布 pipeline 4.22/product-v35；`a2b8181` 的 real-PG lease recovery 证明 OBSERVE 不重放、
  HIERARCHY 专项 telemetry 只记录数量、BINDING 到 `REVIEW_REQUIRED` 且 OCR sentinel 零持久化；
  `5c59ce3` 完成 monitor/review 中文说明及隔离 4187 的 1024px E2E，未终止占用 4173 的用户 Node 20
  prototype 进程，4187 退出后无监听残留。
- v35 离线验证为 contract 31/31、inference 189/189、independent snapshot verifier 1/1、real-PG 1/1、
  Web 14 files/73 tests 与 Playwright 1/1。Goal 保持 390 reservations（385 SETTLED、5 历史 Plus
  RESERVED、0 BREACHED）；Flash/Plus/Max 分别 129/179/82 attempts、896,093/1,087,500/491,919
  tokens、¥0.435196/¥4.159620/¥10.289316。v35 仍 `EXPERIMENTAL`，N6=`automated_verified`、
  N7/Goal=`in_progress`；exact-clean full/Document Vision、v35 live、final 20/60、最终独立 verifier 与
  业务/视觉 J1 尚未完成。

## v35 exact-clean Flash live checkpoint

- exact-clean `0e52ec7` 的 full `.sdlc/evidence/20260811-201946-full` 9/9 PASS。Document Vision 首次
  `20260811-202505` 因 executable 未注入而在 Provider 前 fail-closed；确认 0 相关进程/held lease 后，
  以冻结 RapidOCR 3.9.2/OpenVINO 2026.0.0/model digests 恢复为 `20260811-202810` 1/1、19 lines。
- Java/Python `/2` identity 一致为 `…e49d37`；Flash/Plus/Max v35 snapshots 分别为
  `f84747…34d0`、`8302e6…af70`、`ba36e8…826a`。Flash 按 `d2c2c3d` PROPOSED → NOT_OPEN →
  `b795f0a` OPEN →唯一 wrapper→`a4298f3` CLOSED；CLOSED 后负探针仍精确 NOT_OPEN 且 Goal/evidence
  字节不变。
- 独立 verifier 与 payload scan PASS：1 completed、0 abandoned、5 SETTLED attempts、20,583 input +
  20,894 output、¥0.020835、151,949 ms。5 次全部停在 OBSERVE：region-kind enum×4、parent-kind×1；
  slots/groups/entities/relationships/bindings 实际均为 0，v35 hierarchy repair 未被 live 触发。
- Goal 现为 395 reservations（390 SETTLED、5 历史 Plus RESERVED、0 BREACHED）；Flash/Plus/Max 为
  134/179/82 attempts、937,570/1,087,500/491,919 tokens、¥0.456031/¥4.159620/¥10.289316。
  三 ledger CLOSED。Plus 仅剩 1 attempt，不调用；Max/final 20/60 的同版本三阶段门仍失败。product-v35
  保持 `EXPERIMENTAL`，N6=`automated_verified`、N7/Goal=`in_progress`。

## v36 contract-unique region kind checkpoint

- `fdf7d44` 新增独立 opt-in observation policy。只有 region typed shape 唯一要求 canonical kind 时才
  原子归一化：`MANY + repeatGroupId` 为 `REPEATED_GROUP`，`ONE + repeatGroupId` 为 `ITEM`；无 parent、
  `ONE`、无 repeat 且只有一个 `[0,0,10000,10000]` evidence box 时为 `ROOT`。合法但冲突的 kind 与未知
  alias 都受相同结构事实约束；SECTION/GROUP 等无法唯一决定的容器、缺失 repeat、歧义及完整 grounding
  校验失败继续原 fixed code fail-closed。
- `86b6074` 发布 pipeline 4.23 与 Flash/Plus/Max 三份 immutable product-v36 Profile；Prompt、Document
  Vision capability、maximumTotalCalls=5、stage timeout 与 v35 一致，旧 Profile/snapshot 不改写。成功只
  记录数量型 `VISUAL_GROUNDING_REGION_KIND_NORMALIZED`，不记录 region ID、坐标、OCR 或模型 payload。
- `2076684` 的真实 PostgreSQL tracer 将 CANVAS/GROUP/ROW 三个可唯一分类的输入归一化后持久化 OBSERVE
  checkpoint；lease-expiry 后只调用 HIERARCHY/BINDING 并到达 `REVIEW_REQUIRED`，OBSERVE 不重放，
  ephemeral OCR 只重算且 sentinel 未进入 checkpoint/Candidate/problems。
- `f395f90` 为 monitor/review 增加受控中文解释，并更新两页组件测试和 1024px keyboard/Axe E2E。
  inference 190/190、independent snapshot verifier 1/1、real-PG 1/1、Web 14 files/73 tests、typecheck/lint、
  Playwright 1/1 PASS；Web 证据使用本机 Node 20，只是兼容验证，不能替代最终 Node 24 gate。
- 本节点 Provider attempts=0；Goal 保持 395 reservations（390 SETTLED、5 历史 Plus RESERVED、0
  BREACHED），Flash/Plus/Max 仍为 134/179/82 attempts、937,570/1,087,500/491,919 tokens、
  ¥0.456031/¥4.159620/¥10.289316，三 ledger CLOSED。v36 保持 `EXPERIMENTAL`，N6=
  `automated_verified`、N7/Goal=`in_progress`。

### v36 Flash live disposition

- exact-clean full `20260811-211447-full` 与冻结 Document Vision
  `20260811-211916-document-vision` PASS；Java/Python `/2` identity 均为
  `e2fb024c23c95b53cae753d391cb91e20b69eb8bf25e889c3b46245d23889d2d`，Flash snapshot 为
  `cf32df2789f51ffdb893c3d56ca0425b5d36e3ef01fa8cb208e203917023a86a`。
- lifecycle 为 `5a6bfc4` PROPOSED →精确 NOT_OPEN 负探针→`220de94` OPEN→唯一 wrapper→
  `ab11a8b` CLOSED→精确 NOT_OPEN 负探针；两次探针 Goal/evidence 零写入，wrapper exit 0、
  171.790 秒，结束后 0 相关进程、0 held lease。
- 独立 verifier/payload scan PASS：1 case、0 abandoned、5 SETTLED attempts、20,619 input +
  21,850 output = 42,469 exposed tokens、¥0.021607、159,249 ms Provider latency。五次均停在 OBSERVE：
  `VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND`×3、`VISUAL_GROUNDING_SIBLING_OVERLAP`×1、
  `VISUAL_GROUNDING_PARENT_KIND_INVALID`×1；HIERARCHY/BINDING 未触达。
- Goal 现为 400 reservations（395 SETTLED、5 历史 Plus RESERVED、0 BREACHED）；Flash/Plus/Max 为
  139/179/82 attempts、980,039/1,087,500/491,919 tokens、¥0.477638/¥4.159620/¥10.289316，
  三 ledger CLOSED。v36 仍 `EXPERIMENTAL`，N6=`automated_verified`、N7/Goal=`in_progress`；同版本
  三阶段门不可达，因此不调用 Plus/Max/final 20/60，继续 payload-free bounded 离线修复。

## v37 constraint-unique GROUP kind checkpoint

- `ebd0281` 新增独立 opt-in policy：只有 ONE `GROUP` element 尚无兼容 singular GROUP region，且其
  distinct/known `regionIds` 中恰有一个 parent 非空、ONE、无 repeat、kind 未知的现有 region 时，才把该
  region 归一化为 GROUP。两个候选、已有兼容容器、MANY group、仅 SLOT owner、缺失/重复 owner 引用及
  任何剩余未知 kind 都继续 `VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND` fail-closed；v36 对同一
  input 仍拒绝。实现不读取 alias/text，不改坐标、parent、owner、element 或 Candidate。
- `b5a4555` 发布 pipeline 4.24 与 Flash/Plus/Max 三份 immutable product-v37 Profile，Prompt、Document
  Vision、calls/timeout/pricing 与 v36 不变；Java registry/capability 和独立 Python snapshot verifier 已绑定。
- `007afe6` real-PG lease-expiry tracer 在 ONE notice-group 唯一约束下归一化 1 个 kind、持久化 OBSERVE，
  恢复后只执行 HIERARCHY/BINDING 到 `REVIEW_REQUIRED`；OCR sentinel 未进入 checkpoint/Candidate/
  problems，v36/v37 恢复用例 2/2 PASS。
- `e6682b4` 将既有计数 telemetry 的审核文案扩展为“受控别名、唯一结构事实或唯一绑定约束”；Web
  14 files/73 tests、typecheck/lint、1024px keyboard/Axe Chromium 1/1 PASS。Node 20 仍只算兼容证据。
- inference 191/191、independent verifier 2/2、real-PG 2/2 全绿。本离线节点 Provider attempts=0，
  Goal 保持 400 reservations；Flash/Plus/Max 仍为 139/179/82 attempts、
  980,039/1,087,500/491,919 tokens、¥0.477638/¥4.159620/¥10.289316，三 ledger CLOSED。
  v37=`EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`。

### v37 Flash 首次 live 的 pre-provider fail-closed 结论

- exact-clean full `20260811-220155-full`、Document Vision `20260811-220627-document-vision`、
  Java/Python identity `2b498c45…daeb2` 与 Flash snapshot `2dc4b025…e9919` 均通过；lifecycle 为
  `99940ef` PROPOSED → NOT_OPEN → `045b5b9` OPEN →唯一 wrapper→ `c3223ee` CLOSED → NOT_OPEN。
- wrapper exit 0、9.667 秒；独立 verifier/payload scan PASS，但固定结果为
  `DOCUMENT_VISION_ADAPTER_MISSING`，1 completed、0 abandoned、0 Provider attempts、0 tokens、0 latency。
  原因是本次启动参数误用了 `renderweave.inference.document-vision.adapter`，产品合同实际为
  `renderweave.inference.document-vision.adapter-script`；失败发生在图片进入 Provider 之前。
- CLOSED 负探针以 `VISUAL_EVALUATION_AUTHORIZATION_NOT_OPEN` 拒绝，受监控 evidence/Goal 哈希零变化，
  结束后 0 process/0 held lease。Goal 和三模型累计用量完全不变，三 ledger CLOSED。
- 该节点只证明本地 fail-closed 与审计闭环，不构成 v37 质量证据。v37 仍 `EXPERIMENTAL`、N6=
  `automated_verified`、N7/Goal=`in_progress`；下一次只能在新 authorization、fresh gates/identity/snapshot/
  Goal/J1 preflight 下使用正确 `adapter-script` 键串行重试，Plus/Max/final 20/60 仍不调用。

### v37b Flash live disposition

- 新 lifecycle 为 `9204a49` PROPOSED → `VISUAL_EVALUATION_AUTHORIZATION_NOT_OPEN` → `0960c9f`
  OPEN →唯一 wrapper→ `4d8e48b` CLOSED → `VISUAL_EVALUATION_AUTHORIZATION_NOT_OPEN`；wrapper
  155.592 秒、0 残留 process/lease，CLOSED 探针的 evidence/Goal 哈希零变化。
- exact-clean full `20260811-221947-full`、Document Vision `20260811-222400-document-vision`、
  Java/Python identity `099b4b26…6cc0` 与 Flash snapshot `2dc4b025…e9919` 均匹配。
- 独立 verifier/payload scan PASS：1 completed、0 abandoned、5 SETTLED attempts、20,565 input +
  22,126 output = 42,691 tokens、¥0.021815、143,088 ms。五次都停在 OBSERVE：
  `VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND`×4、
  `VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID`×1；HIERARCHY/BINDING 未触达。
- Goal 更新为 405 reservations（400 SETTLED、5 历史 Plus RESERVED、0 BREACHED）；Flash/Plus/Max 为
  144/179/82 attempts、1,022,730/1,087,500/491,919 tokens、¥0.499453/¥4.159620/¥10.289316，
  三 ledger CLOSED。v37 仍 `EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`；不调用
  Plus/Max/final 20/60，下一安全切片只从新 parent-containment fixed code 与离线结构反例建立 bounded repair。

### v38 ancestor-root parent normalization offline checkpoint

- pipeline 4.25/product-v38 只扩展 v34 的唯一 parent 规则：已知、非 self、同 artifact 的当前 parent
  确实不包含非 ROOT/ITEM child，且找不到唯一最具体的常规非 ROOT 兼容 parent 时，只有沿该错误 parent
  的既有无环祖先链恰好到达 parent=null、严格包含 child 的唯一 ROOT，才把 parent 改为该 ROOT 并规范化
  reading order。missing parent、ITEM kind、artifact mismatch、cycle、equal/full box、歧义或最终完整
  `VisualGroundingPlan` 校验失败均原子保留原输入并 fail-closed；v37 行为保持不可变。
- `632e641`、`b91637b`、`060dd47`、`1504ac6` 分别完成 bounded codec、三模型 immutable v38
  Profile、real-PG checkpoint/lease recovery 与 monitor/review/E2E 文案。成功仍只暴露数量型
  `VISUAL_GROUNDING_REGION_PARENT_NORMALIZED`，不记录 region ID、坐标、图片、文字或 Candidate。
- 自动证据为 focused contract 34/34、Profile/独立 snapshot verifier 37/37、real-PG v37/v38 2/2、
  inference 192/192、Web 73/73、typecheck/lint、Playwright 7/7；本机 Web 为 Node 20 兼容证据，正式
  Node 24 由下一 exact-clean full gate 提供。Provider attempts=0，Goal 仍为 405 reservations且三 ledger
  CLOSED；v38=`EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`。
- 下一门是在本 checkpoint 的 clean revision 上运行 full/Document Vision，fresh 重算 evaluation identity、
  三份 v38 Profile snapshot、Goal/J1/time/process/lease 后，才可考虑 Flash 单 synthetic case、最多 5 calls；
  Plus/Max/final 20/60 的同版本三阶段、质量、独立复核与最终 J1 门不变。

### v38 Flash live disposition

- exact-clean `3e44974` 的 full `20260811-225452-full` 9/9（236.847 秒）与 Document Vision
  `20260811-225916-document-vision` 19-line canary PASS；Java/Python identity 一致为
  `/2:fc334bc7…8a524`，Flash v38 snapshot=`d91bc968…c9412`。
- lifecycle 为 `882c8ca` PROPOSED → NOT_OPEN → `19c726c` OPEN →唯一 wrapper→ `31109c4`
  CLOSED → NOT_OPEN。wrapper exit 0/122.906 秒；两侧负探针均无 Goal/evidence 写入，结束后 0 process/
  0 held lease。
- 独立 verifier/payload scan PASS：1 completed、0 abandoned、5 SETTLED attempts、20,595 input +
  20,202 output=40,797 tokens、¥0.020282、110,782 ms。五次全部在 OBSERVE fail-closed：invalid region
  kind×2、reading-order gap×2、JSON unknown member×1；v38 parent-normalization telemetry 未命中。
- Goal 为 410 reservations（405 SETTLED、5 历史 Plus RESERVED、0 BREACHED）；Flash/Plus/Max=
  149/179/82 attempts、1,063,527/1,087,500/491,919 tokens、¥0.519735/¥4.159620/¥10.289316，三
  ledger CLOSED。v38 仍 `EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`；Plus/Max/
  final 20/60 不启动。下一安全切片只可用 repository synthetic 反例研究 reading-order gap 的确定性
  canonicalization；unknown member 与 invalid enum 不授权读取 payload 或放宽 JSON/enum 合同。

### v39 bounded sibling reading-order offline checkpoint

- pipeline 4.26/product-v39 只处理非 ROOT sibling set：既有 reading-order 必须互异、按该值排序必须与
  `(top,left,regionId)` 的 canonical 空间顺序完全一致、且序号确有间隙；最多改变 8 个既有序号并压紧为
  `0..n-1`，重建完整 `VisualGroundingPlan` 失败则原子返回原输入。duplicate/tie、反向/位置不一致、root
  gap、missing parent、cycle、topology/overlap/ownership failure 均保持原 fixed code。
- `a08f099`、`c99a4ac`、`80d0b73`、`d0ed4d3` 分别完成 codec/contract、pipeline 4.26 与三模型
  immutable Profile、真实 PostgreSQL checkpoint/lease recovery、monitor/review/E2E。成功只暴露数量型
  `VISUAL_GROUNDING_READING_ORDER_NORMALIZED`；不读取或记录 alias/text/OCR/gold、图片、完整 prompt、
  Candidate 或模型原文，也不改 box/parent/ownership 或创建删除结构。
- 自动证据为 focused contract 35/35、跨模块 Profile/独立 verifier 38/38、real-PG v39 1/1 与 v38/v39
  pair 2/2、inference 193/193、Web 73/73、typecheck/lint、Playwright 7/7 PASS；Node 20 Web 仅为兼容
  证据，正式 Node 24 尚待 exact-clean full gate。
- 本节点 Provider=0；Goal 保持 410 reservations（405 SETTLED、5 历史 Plus RESERVED、0 BREACHED），
  Flash/Plus/Max=149/179/82 attempts、1,063,527/1,087,500/491,919 tokens、
  ¥0.519735/¥4.159620/¥10.289316，三 ledger CLOSED。v39=`EXPERIMENTAL`、N6=
  `automated_verified`、N7/Goal=`in_progress`。
- 下一门是在本 checkpoint 的 exact-clean revision 上运行 full/Document Vision，fresh 重算 evaluation
  identity、三份 v39 Profile snapshot、Goal/J1/time/process/lease。全绿后才可考虑 Flash 单 synthetic case、
  最多 5 calls；Plus 仅剩 1 attempt 不足以证明三阶段，Max/final 20/60 仍受同版本三阶段、质量、独立复核
  与最终 J1 门约束。

### v39 Flash live disposition

- exact-clean `0625a23` 的 full `20260811-233119-full` 9/9（237.61 秒）与 Document Vision
  `20260811-233555-document-vision` 19-line canary PASS；Java/Python identity 一致为
  `/2:3abc7eba…52696d`，Flash v39 snapshot=`667db9b4…d1a2bc`。
- lifecycle 为 `37cc036` PROPOSED → NOT_OPEN → `1431233` OPEN →唯一 wrapper→`678ef2e` CLOSED→
  NOT_OPEN。wrapper exit 0/90.704 秒；两侧负探针均为 0 Provider marker、7 个 watched files 零写入，
  结束后 0 process/0 held lease。
- 独立 verifier/payload scan PASS：1 completed、0 abandoned、3 Provider attempts、8,220 input + 9,069
  output actual tokens、¥0.008900 actual cost、76,442 ms。前两次 OBSERVE 分别为 invalid region-kind 与
  reading-order gap；第三次 `DASHSCOPE_NETWORK_ERROR` 无 actual usage，按 halt 合同停止并保留 worst-case
  reservation。v39 reading-order normalization 未命中，HIERARCHY/BINDING/Candidate 均未触达。
- Goal 为 413 reservations（407 SETTLED、6 RESERVED、0 BREACHED）；其中 5 个是历史 Plus RESERVED、
  1 个是本次网络失败的 Flash reservation。Flash/Plus/Max=152/179/82 attempts、
  1,105,020/1,087,500/491,919 exposed tokens、¥0.538392/¥4.159620/¥10.289316；三 ledger CLOSED。
  v39 仍 `EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`。该 CLOSED authorization 不重跑，
  Plus/Max/final 20/60 不启动；fixed code 不足以证明可进一步放宽 order/enum 合同。

### v40 payload-free reading-order diagnostic checkpoint

- `3b0d92d` 在不扩大 v39 repair 的前提下细分 GAP：root order 必须先合法；所有失败的非 root sibling set
  只有都为 duplicate/tie 时才报 `VISUAL_GROUNDING_READING_ORDER_DUPLICATE`，只有都为唯一但空间顺序冲突时
  才报 `VISUAL_GROUNDING_READING_ORDER_POSITION_INVALID`；mixed、root、canonical 超界 gap 保持 GAP。
- `b179b7e` 固定 pipeline 4.27、visual-elements Prompt 10 与三份 immutable product-v40 Profile；Prompt 10
  相对 v9 只新增 duplicate fixed-code repair 路由。`05e1b65` 证明真实 PostgreSQL lease recovery 不重放
  OBSERVE 且 OCR 零持久化；`3eff5ce` 接入 monitor/review 中文说明与 1024px E2E。
- 自动证据：contract 36/36、Profile/Prompt 20/20、independent Profile verifier 2/2、real-PG v39/v40 2/2、
  inference 195/195、Web 73/73、typecheck/lint、Playwright 7/7 PASS。Node 20 仅为 Web 兼容证据。
- Provider=0；Goal/ledger 保持 v39 结算后的 413 reservations（407 SETTLED、6 RESERVED、0 BREACHED），
  Flash/Plus/Max=152/179/82 attempts、1,105,020/1,087,500/491,919 exposed tokens、
  ¥0.538392/¥4.159620/¥10.289316，三 ledger CLOSED。
- 用户要求 v40 验证后冻结并阶段性收尾，验收口径为可运行/可恢复/可审计/可人工审核，不要求本阶段达到
  生产级可靠性。当前 v40 仍 `EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`；还需
  exact-clean full/Document Vision、fresh identity/Profile/Goal/J1/process/lease 与受控 Flash smoke。

### v40 Flash live 与阶段冻结结论

- exact-clean `af2076a` 已通过 full `20260812-001439-full` 9/9 与 Document Vision
  `20260812-002223-document-vision` 19-line canary；Java/Python identity 一致为
  `/2:902577dd…a70abd63`，Flash/Plus/Max v40 snapshot 分别为 `1f6f8cec…9e9e19`、
  `2fd63065…17dd2`、`7444e737…30708`。
- lifecycle 为 `0d38448` PROPOSED → NOT_OPEN → `5392aa1` OPEN →唯一 wrapper→`6e7f522` CLOSED→
  NOT_OPEN。wrapper exit 0/183.863 秒；后置 7 个 watched files 的字节与时间戳零变化，结束后 0 process/
  0 held lease。独立 verifier 与 payload scan PASS。
- 单 case 1 completed、0 abandoned、5 SETTLED attempts、20,699 input + 22,605 output tokens、
  ¥0.022226 actual cost、171,157 ms Provider latency。五次都停在 OBSERVE：invalid region-kind、sibling
  overlap、parent-kind、generic reading-order gap、invalid region-kind；v40 duplicate/position classifier 未命中，
  0 HIERARCHY/BINDING/Candidate。
- Goal 现为 418 reservations（412 SETTLED、6 RESERVED、0 BREACHED）；Flash/Plus/Max=157/179/82
  attempts、1,148,324/1,087,500/491,919 exposed tokens、¥0.560618/¥4.159620/¥10.289316；三 ledger
  CLOSED，evidence 5 files 与 Goal state/guard 已逐字节同步。
- v40 现在冻结为本阶段稳定工程基线：deterministic fixture/E2E 证明运行、checkpoint recovery、审计、审核/
  Apply 链路可用，真实 Flash smoke 证明失败路径安全且可解释；但它没有证明当前模型能为该合成站牌产出
  Candidate。故 product-v40 保持 `EXPERIMENTAL`，N6=`automated_verified`，N7/Goal 仍未完成；不调用
  Plus/Max/final，不声称生产可靠或识别质量验收。

### v40 产品入口与本地能力前置检查

- `f47c54a` 已将新建 live 产品目录从已知会在 STRUCTURE 改写字段身份的 v4 切换到三份冻结 v40：Plus
  默认、Max 供高难嵌套显式选择、pinned Flash 只作低成本 smoke。real-PG 合成路径严格只有 OBSERVE、
  HIERARCHY、ELEMENT_BINDING 三次 Provider reservation，随后由本地 materializer 生成 Candidate；测试
  Provider 收到 STRUCTURE/REPAIR 会失败。因此旧 v4 的 10 missing + 10 unexpected 缺陷不再是新产品路径。
- `f27f86a` 让 `live-availability` 逐 Profile 返回 payload-free readiness，并在 create/retry 的 run persistence、
  reservation、Provider 之前检查启动时 Document Vision capability 与 Profile 精确一致。缺 adapter/model 或
  identity mismatch 时 Web 禁用相应入口，服务端以固定 `DOCUMENT_VISION_*` code 拒绝且 0 Provider。
- pipeline 4.27、Prompt 10、三份 v40 Profile 与既有 evidence 均未修改；无自动跨模型 fallback，无付费调用，
  Goal 仍为 418 reservations，三 ledger `CLOSED`。这使 v40 成为可选择、可前置诊断、确定性物化的阶段性
  工程入口，但没有改变 Flash 的 OBSERVE 失败或 N7 质量证据缺口；状态仍为 `EXPERIMENTAL` /
  `automated_verified` / Goal `in_progress`。
- 隔离 clean `6906be1` 的 full `20260812-012644-full` 9/9、Document Vision
  `20260812-013158-document-vision` 1/1（19 lines）均 PASS；metadata 均绑定 exact revision 且
  `workingTreeDirty=false`。full 覆盖正式 Node 24、真实 PostgreSQL、runtime canary、独立 evidence verifier、
  v40 catalog/readiness 以及 replay→review→Apply 浏览器路径。本轮 Provider=0，门控后无 live/Maven/Java/
  Python 残留。

### product-v40 通用 Flash 选择器

- 用户将产品 Flash 精确模型从 `qwen3.7-flash-2026-07-15` 改为 `qwen3.7-flash`；Plus/Max 不变。
  `67d46c5` 新增 immutable successor Profile 并同步 Registry、API/OpenAPI、generated client、Web 与 E2E，
  原 dated v40 Profile/snapshot/evidence 保持不变、可读、可恢复，但退出新建产品目录。
- successor 保持 pipeline 4.27、Prompt、Document Vision capability、IMAGE_ONLY、5-call/0-repair 与本地
  materializer/verifier/checkpoint/telemetry/审核 Apply 合同。定向 Java 21/21、正式 Node 24 Web 73/73、
  typecheck/lint 与产品目录 Playwright 1/1 PASS。
- 本节点 Provider=0、三个 visual ledger 仍 `CLOSED`；Flash alias 继续共用既有稳定预算槽位，不重置额度或
  消费。successor 尚无新 live 质量证据，状态保持 product-v40=`EXPERIMENTAL`、N6=`automated_verified`、
  N7/Goal=`in_progress`。
- 隔离 clean `ba409e9` 的 full `20260812-015332-full` 9/9 PASS；metadata clean，inference 196/196、
  正式 Node 24 Web 73/73、独立 verifier 2/2、真实 PostgreSQL/runtime 和浏览器路径通过，offline summary
  明确为 0 Provider attempts/reservations。

### product-v41 zero-GROUP hierarchy rewind

- 用户现场 v40 Plus run 在 OBSERVE 接受 7 SLOT/0 GROUP 后连续四次以
  `VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN` 停在 HIERARCHY。最小回归定位为 support/cardinality
  解析抢先于现有 zero-GROUP prerequisite，使 cross-stage rewind 不可达。
- pipeline 4.28 在 strict hierarchy envelope 已解码、且“至少一条 relationship + inventory 0 GROUP”成立时，
  直接输出既有 `VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING` 并回到 OBSERVE；不创建 GROUP、
  不修改 support/region/cardinality，不读取或记录 payload。v40 及更早 Profile 逐字节不变。
- 三份 product-v41 immutable Profile 已进入产品目录，OpenAPI/generated client/Web 默认同步为 v41；全部继续
  `EXPERIMENTAL`，Plus 默认、Max 高难、Flash smoke 与 capability-aware admission 语义不变。
- focused contract 1/1、Registry 2/2、真实 PostgreSQL 五调用恢复 1/1、API/policy/evidence 21/21、正式
  Node 24 Web 73/73 + typecheck/lint 均 PASS。实现期间 live/key 环境关闭且 Provider=0；状态为
  `automated_verified`，任何 v41 live 都需要新的 exact J1。

### product-v42 受控运行边界

- 用户在尚未执行 v41 J1 时把边界改为最多 7 次 Provider call、360 秒/stage、单步最多 16384 output
  tokens，并要求任务累计成本不超过 ¥5；旧 v41 J1 因此失效，未读取 Key、未启用 live、Provider=0。
- 三份 additive immutable product-v42 Profile 复用 pipeline 4.28 与全部视觉合同。Plus/Flash 固定
  16384 output tokens；Max 因 exact-alias capability 尚无 advertised output 上限，fail-closed 保持 8192。
  ¥2 单次保守预留、0 repair、262144 bytes、600 秒 lease 均不变。
- 产品目录/API/OpenAPI/generated client/Web 切换到 v42；新 run 强制 `costLimitMicrosCny=1..5,000,000`，
  Web 默认 ¥5，缺失或超限在 run/reservation/Provider 前拒绝。v41 及更早 snapshot 保持不可变且历史 retry
  不升级。
- 受影响门控已通过：Profile/capability 4/4、API/policy/evidence 21/21、Node 24 Web 73/73、服务端
  inference 197/197 + app 229/229（6 skipped）、Playwright 19/19（1 live replay skipped）与原型审计。
  当前仅为 `automated_verified`；还需在固定 clean revision 上完成 full/Document Vision、fresh identity/
  snapshot 和新的精确 J1，才允许启动 live-enabled 应用。

### product-v43 两图 Candidate 验证

- v42 Plus run `95abb5c8-469c-4e0e-ab5c-173c6cf170ba` 已走满 7 calls，证明调用上限/持久化修复生效；
  但严格 OBSERVE grounding 仍因 region forest、element 和 parent containment 拒绝，另有 timeout/network，
  最终无 Candidate，实际费用 ¥0.422782。
- `2da0af8` 新增 immutable product-v43：pipeline 4.28 与 validator/materializer 不变，仅以 Prompt 11 要求
  最浅确定 region forest、合法 ROOT-only 回退、ROOT-owned SLOT、显式 containment 检查，并路由通用
  element-invalid。产品目录/API/Web 已切换 v43，v42 资源保持不可变。
- exact-clean full `20260812-065143-full` 9/9、Document Vision `20260812-065750-document-vision` 1/1
  19 lines PASS；Java/Python identity 为 `/2:17cb0b63…daaf90a5`，v43 Plus snapshot 为
  `77990399…a1021db`。
- 用户授予后续 J1 持续执行权，但每次仍自动绑定精确 revision/Profile/identity/input hash/分类/次数/费用/时限。
  两份 J1 分别创建 run `aafca06e-fc65-42c3-9253-1bd48c4daf69` 与
  `898b3e8f-ccf5-49be-84f6-0b2efdb7c13b`；前者 4 calls、后者 3 calls，均到达
  `REVIEW_REQUIRED` 并确认 Candidate revision 0、1 schema、1 image。
- 两个 v43 run 实际费用为 ¥0.141000 + ¥0.074294；连同先前 v42 诊断，任务累计 ¥0.638076，低于 ¥5
  硬上限。两张指定图片的 Candidate 目标完成；v43 仍为 `EXPERIMENTAL`，不把两例成功写成 60 例发布门或
  生产可靠性验收，全局 N7/final quality gate 继续 `in_progress`。

### product-v44 CMYK/密集序列修复

- v43 run `aafca06e-fc65-42c3-9253-1bd48c4daf69` 的中部嵌套列表在 OBSERVE 已被整体静默遗漏：
  1 root/0 child/0 array ref，9 SLOT/ONE、0 GROUP/MANY；后续阶段从未收到嵌套事实。
- 根因为 CMYK JPEG 经 RapidOCR bytes/PIL 四通道路径时丢失文字。adapter 改为显式 BGR 解码；
  真图 payload-free 重放为第一图 35 lines/10 序列候选/门命中，第二图 8/0/未命中。
- 新增 immutable product-v44 + Prompt 12；强 OCR 几何序列与像素一致但缺少 MANY GROUP/REPEATED_GROUP 时，
  以固定码拒绝并重试 OBSERVE。不读 OCR 文本、不用领域词、不自动造数组；v43 资源不变。
- 已通过 adapter 1/1、verifier/Profile/Prompt 29/29、真实 PostgreSQL workflow 1/1、API/policy/evidence
  21/21 与 Node 24 Web 73/73。开发工作树 full `20260812-121427-full` 10/10 PASS；浏览器审计统一改用
  pinned Python Playwright 1.62.0。待提交后的 exact-clean full/Document Vision 后重启 live 并追加指定图结构证据。

### product-v45 重复实例字段聚合修复

- v44 真实调用已恢复嵌套计划，但 binding 阶段发现 14 个重复实例 SLOT 映射到同一 child 字段；旧的
  完整 SLOT 覆盖与实体字段 key 唯一规则组合无解，导致固定 coverage 诊断重复到调用上限。
- 新增 immutable product-v45 + Binding Prompt 4；每个 SLOT 仍逐一绑定，仅将同实体、同一重复组的互异
  ITEM 且字段语义完全一致的重复实例观测确定性聚合为一个 Candidate field，合并有界 evidence。v44 及更早 snapshot 不变。
- 核心 31/31 与 PostgreSQL 三阶段工作流 1/1 已通过，后者确认 root + child 两个 Schema、根 MANY array
  reference、child 单字段与两份 evidence。API/Web/full/Document Vision 和真实图片复验继续推进。
