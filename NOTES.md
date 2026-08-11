# NOTES.md

## 当前目标与进度
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
- [ ] P6/T6-5 图片识别 vNext：N0–N6 已形成独立 checkpoint；v15–v24 的 bounded verifier/repair、hybrid
  observation 与三模型单 case lifecycle 均已有 clean A1/A2。Flash v23/v24 未通过 OBSERVE；Plus/Max v24
  均完成 OBSERVE→HIERARCHY→BINDING，但 stage-gold 质量未达门，Max v24 更有 16 critical hallucinations、
  17 blockers。相同 v22/v23/v24 假设不重复，下一安全切片是零 Provider 的 stage-gold replay：在 OBSERVE
  增加不读取模型原文的 bounded semantic verifier，并验证 earliest-stage repair/checkpoint/UI/E2E；形成新且
  可离线证明的假设前，不进入 20/60-case final eval。
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
  Flash v10–v12 A2 诊断、v15–v24 bounded verifier/observation 增量；Plus/Max v24 三阶段可达但未晋级。
- `plans/logs/P6-T6-5-N7.md`：pinned Flash/Goal guard v3、十六份单 case CLOSED/A2 reachability、v15–v24
  bounded verifier/observation 增量；Plus/Max v24 已到 BINDING，Flash v23/v24 未通过 OBSERVE，质量仍未达门。
- 当前恢复点：`phase/p6-visual-recognition-vnext` 的 `57b1502` Max v24 CLOSED/A2；v24 实现为 `061101f`
  clean server A1，guard v3 为 `2b23617` clean fast A1。编排 Goal `019fec8e-a851-7952-b49b-8be76a281a57`
  因 turn interrupt 当前显示 `paused`，用户已明确继续同一 objective，未创建 replacement Goal。三模型 v24
  live 均已闭环；下一安全切片是零 Provider 的 OBSERVE bounded semantic verifier 与 earliest-stage replay。
