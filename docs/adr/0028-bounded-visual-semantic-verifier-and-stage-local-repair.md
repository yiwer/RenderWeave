# ADR-0028：有界视觉语义验证器与最早阶段修复

- 状态：accepted
- 日期：2026-08-11
- 关联：AC-VR-007、AC-VR-009、P6/T6-5 N6、ADR-0022、ADR-0027

## 背景与约束

v8 解决了 OBSERVE 的 JSON array 形状后，v9 在仓库合成 `transit-board-v3` 上稳定通过 OBSERVE，但四次
HIERARCHY 都因 `VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP` 或
`VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN` 被拒绝。payload-free stage evidence 显示 OBSERVE 实际产出
18 个 SLOT、0 个 GROUP，而 gold 为 10 个 SLOT、3 个 GROUP。此时继续重试 HIERARCHY 无法补回上游遗漏的
GROUP；增加调用次数只会让下游引用不存在或错误类型的元素。

已有严格 record/拓扑 validator 能证明“输出结构合法”，却不能证明 `REPEATED_GROUP` 已被抽象成可形成子
Schema 的 GROUP element。若直接在 materializer 中猜测或补建 GROUP，会把服务端推断伪装成模型证据，也无法
区分真实重复容器与装饰性布局。历史 Profile、Prompt 和 evidence 必须保持不可变。

## 决策

1. 新增版本化 `renderweave-visual-semantic-verifier/1.0`。它只消费已通过严格解码和空间不变量的本地 plan，
   输出有限白名单 issue code 与 `earliestStage`，不保存模型文本、异常 message、动态 id 或原始坐标。
2. OBSERVE 边界先执行四项有界检查：每个 `REPEATED_GROUP` 有 MANY/GROUP element；GROUP 只拥有 GROUP 或
   REPEATED_GROUP region；每个 ITEM 至少有一个 SLOT；GROUP cardinality 与重复 region 一致。失败不写入
   checkpoint，只记录固定 taxonomy，并在 OBSERVE 原地重试。
3. HIERARCHY 边界要求每个 GROUP 恰好支撑一条 relationship、每条 relationship 恰好由一个 GROUP 支撑，
   且 relationship region 与该 GROUP 的 owned region 一致；ELEMENT_BINDING 要求字段绑定到能够覆盖其区域的
   最近实体。通常两类失败分别路由到 HIERARCHY 与 ELEMENT_BINDING；若 HIERARCHY 已提出 relationship、但
   上游 inventory 根本没有可支撑关系的 GROUP，则这是 OBSERVE omission，必须回到 OBSERVE，而不是让下游补造。
4. 重试请求继续使用既有 `retryProblemCodes`，只重做最早失败阶段；已成功的更早 checkpoint 保留。问题码按
   stage 有界累积、去重、排序且最多 16 个，避免后一次修复遗忘前一次约束。selected crop 只从已验证
   grounding checkpoint 派生，最多 4 个，不把派生图片或局部 ID 持久化。
5. 新增 immutable v10/v11/v12 Prompt/Profile：v10 固化 OBSERVE 语义检查，v11 增加 hierarchy/binding verifier、
   stage-local crop 和更细 JSON/ownership taxonomy，v12 增加 bounded retry union。历史 v8..v11 的资源与
   snapshot 不改写；三模型 Profile 均保持隐藏。
6. 监控与审核共用 payload-free execution telemetry，只展示阶段/checkpoint、受控区域类别、固定 issue code、
   token/费用/延迟和恢复状态。它不展示动态 region/entity/element ID、原始坐标、OCR、图片、Prompt、
   Provider response 或 chain-of-thought；1024 宽度使用既有响应式布局与 drawer 合同。
7. verifier 只拒绝，绝不自动增加、改名、改类型或重连模型 plan。连续失败、预算用尽或无新假设仍 fail-closed；
   Profile 在完整 stage/eval 门槛前保持 `EXPERIMENTAL` 且不进入产品选择器。

## 备选方案

| 方案 | 优点 | 风险 | 结论 |
|---|---|---|---|
| 只强化 HIERARCHY prompt | 改动小 | 上游没有 GROUP 时不可解，已连续四次实证 | 不采用 |
| materializer 自动把重复 region 变成 GROUP | 零额外调用 | 伪造语义证据、掩盖模型遗漏 | 不采用 |
| 下游失败后整条 pipeline 重跑 | 可能碰巧成功 | 重复费用、丢失已验证阶段、难恢复 | 不采用 |
| 最早阶段 verifier + stage-local retry | 定位清晰、预算可控、可恢复 | 需为每阶段维护明确 invariant | 采用 |

## N6 实施与受控实证

- `4290227` 完成 observation/hierarchy/binding bounded verifier、earliest-stage retry、已成功 checkpoint 保留、
  selected crop 与 PostgreSQL crash/retry/cancel 回归；`f0ebe77` 完成 owner/slot 级固定 taxonomy 和最多 16 个
  stage-local retry code union；`d5afadf` 完成监控/审核 telemetry、1024 keyboard/axe 与 real-PG E2E。
- Flash 对同一个仓库合成 `transit-board-v3` 依次执行 v10、v11、v12 单 case smoke。三次均遵守
  PROPOSED → 负探针 → OPEN → CLOSED，分别 5 attempts / 32,086 tokens、5 / 32,897、5 / 36,267；独立
  verifier 均 PASS、0 abandoned、payload scan PASS。
- 三次 smoke 的 15 个 attempts 全部停在 OBSERVE。v12 已把错误从 enum 推进到 parent-kind/cardinality，但
  最后仍以 `VISUAL_GROUNDING_PARENT_KIND_INVALID` 结束，没有触达 HIERARCHY/BINDING。A2 只证明授权、预算、
  taxonomy 和 evidence 闭环，不证明三阶段模型质量。
- Flash Goal 累计 71 attempts、393,034/500,000 exposed tokens、¥0.169035；Plus 保持
  485,886/500,000 且不再调用，Max 保持 428,816/500,000。三阶段入口门未满足，因此没有调用 Max；三份
  ledger 终态均为 `CLOSED`。
- 上述额度与 Plus 决策是 N6 freeze 时的历史快照。后续 2026-08-11 J1 delta 把三个稳定预算槽位的累计 cap
  提高到 1,000,000 tokens，并重新允许 Plus；同日第二次 delta 再为每槽位追加 500,000，当前累计 cap 为
  1,500,000。历史用量仍完整计入，没有重开或改写任何 CLOSED ledger。
- exact-clean `de97131` full gate A1 全绿，证据为 `.sdlc/evidence/20260811-020246-full`；不存在 A3，最终
  业务/视觉 J1 与 N7 final eval 仍未满足。

## 后果与验证

- 正向：扁平化在 OBSERVE 即被捕获；失败码直接指出修复阶段；HIERARCHY 不再承担补造上游元素的职责；
  操作员可以在不接触载荷的情况下读到阶段、区域类别、问题、费用和恢复状态。
- 代价：更严格的语义门可能降低一次通过率；Prompt 增加少量输入 token；通用视觉中的装饰性重复需要 corpus
  负例防止过度建模。
- 验证：missing GROUP、cardinality、empty ITEM、invalid GROUP ownership 单元负例；同阶段重试与上游
  checkpoint 保留的 PostgreSQL 回归；payload-free taxonomy；v10 小 canary 后独立 evidence 重算。
- 回退：停止创建 v10 run 即可；历史 v8/v9 snapshot 继续可读。若某类重复被证实为非数据语义，应以新的
  verifier/prompt version 和 corpus gold 调整，不放宽既有版本。

## N7 hierarchy 结构修复增量

Plus v12 的单 case reachability 首次通过 OBSERVE 后暴露两个新事实：`VISUAL_HIERARCHY_V2_ENTITY_INVALID`
把 entity ID、schema key、display name、support list 四类确定性失败压成一个码；同时既有 crop selector 对
所有 `VISUAL_HIERARCHY*` 重试都附加 GROUP crop，即使错误只需修 JSON 结构。随后一次 Provider 400 没有
可安全归因的响应载荷，因此不把它宣称为 crop 导致，只消除本地可证明的不必要请求扩张。

`98ba3d0` 将 entity/relationship 字段失败拆成固定 payload-free taxonomy；结构错误只携带已验证 checkpoint
与精确问题码，不再追加 crop，只有 `VISUAL_SEMANTIC_HIERARCHY_*` / `VISUAL_SEMANTIC_BINDING_*` 才派生
视觉 crop。immutable hierarchy prompt v5 与三模型 product-v14 Profile 固化相同修复合同，仍全部隐藏且
`EXPERIMENTAL`。单元、worker/real-PG 集成、exact-clean server 与 fast A1 已通过；该增量没有 Provider
调用，也不改变 Max 的三阶段入口门。

Plus product-v14 随后以仓库合成 `transit-board-v3` 完成单 case、5 attempts 的
PROPOSED → 负探针 → OPEN → CLOSED smoke。独立 verifier A2 重建出 18,992 input + 4,628 output tokens、
84,107 ms、0 abandoned、payload scan PASS。首个 OBSERVE accepted，但 inventory 为 9 SLOT、0 GROUP；后续
四个 HIERARCHY attempts 分别因 support unknown、parent count 与 relationship support IDs 被拒绝，仍未触达
BINDING。该实证说明“合法但扁平的 OBSERVE”能够把不可修复的上游 omission 推迟到 HIERARCHY。

`195894b` 因此新增唯一的 bounded cross-stage rewind：当且仅当 HIERARCHY response 含 relationship、上游
inventory 为 0 GROUP 时，verifier 输出固定码 `VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING` 与最早阶段
OBSERVE。持久层只允许 REJECTED HIERARCHY + 该精确单码事务性写入 NORMALIZE checkpoint，保留调用计数并清空
下游 plan/Candidate；通用 `InferenceStage` 仍是严格前向状态机。恢复后的 worker 只重做 OBSERVE，携带固定问题码，
不携带 crop 或旧 plan。immutable element prompt v7 与 product-v15 Profile 固化该合同；UI 将问题归到 OBSERVE，
但不显示局部 ID、坐标或 payload。clean fast/server/web/E2E A1 全绿，且本增量 Provider attempts=0。v15 Profile
继续隐藏 `EXPERIMENTAL`，其存在不构成 live 授权或 Max 入口成立。

Plus product-v15 的后续单 case smoke 同样完成完整 ledger lifecycle 与独立 A2：5 attempts、15,823 input +
9,945 output tokens、179,859 ms、0 abandoned、payload scan PASS。前两次 OBSERVE 被 grounding contract 拒绝，
第三次 accepted 并得到 8 SLOT、1 GROUP；因此 0-GROUP rewind 条件正确地没有触发。随后两次 HIERARCHY 都因
`VISUAL_HIERARCHY_V2_SUPPORT_CARDINALITY_MISMATCH` 被拒绝，仍未进入 BINDING。该结果保留了 verifier 的
“只拒绝、不改写”边界，同时暴露出 model-owned relationship cardinality 与 evidence-owned GROUP multiplicity
之间的冗余冲突；在形成新的版本化、可离线验证假设前，不再用同一 Profile 重复 live。

## N7 evidence-owned relationship cardinality 增量

`bb15096` 新增 `renderweave-inference-pipeline/4.3` 与三模型 immutable product-v16 Profile。模型仍负责提出
entity、relationship endpoint、field key、region 和 supporting element；只有 relationship `cardinality` 不再
作为第二事实源。4.3 要求每条 relationship 恰好引用一个已知 GROUP，并从该 GROUP 已验证的 `multiplicity`
确定内部 cardinality。多支撑、未知元素或非 GROUP 支撑分别以固定码 fail-closed；旧 4.1/v15 入口仍保留
model-asserted cardinality 与 mismatch 拒绝语义，历史 snapshot 可原样重放。

这不是 semantic verifier 自动改写模型 plan：verifier 继续只拒绝；确定性 codec 在构造受验证 hierarchy 前消除
一个可由上游证据唯一推导的冗余字段。成功的 HIERARCHY attempt 只记录 payload-free
`VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED` 计数，监控 UI 不展示模型值或局部 ID。独立 evidence
verifier 同步识别 4.3 Profile snapshot。合同单测覆盖旧严格模式、派生模式和三类 fail-closed 负例；真实
PostgreSQL 脚本 provider 证明一个矛盾的 model cardinality 可由三个唯一 GROUP 证据确定，并继续完成
OBSERVE→HIERARCHY→BINDING 与 Candidate materialization。

该增量仅为 clean A1 离线假设。fast/server/web/E2E 均通过且没有 Provider 调用；三份 ledger 保持 CLOSED，
Profile 保持 `EXPERIMENTAL`。只有新的精确 identity/snapshot 单 case live 实证触达 BINDING 后，才能讨论 Max
或 final eval；本 ADR 不把离线可达性写成 live 模型质量。

Plus product-v16 随后完成新的 PROPOSED → 负探针 → OPEN → CLOSED 单 case smoke。独立 verifier A2 重建
5 attempts、19,201 input + 8,281 output tokens、147,141 ms、0 abandoned 与 payload scan PASS。第二次 OBSERVE
accepted，得到 10 SLOT、1 GROUP；后三次 HIERARCHY 分别以 support group reused、relationship support IDs
invalid、support group reused 拒绝。原 cardinality mismatch 不再出现，但 BINDING 仍不可达，因此 4.3 只证明
新假设改变了失败边界，不构成晋级。进一步离线审查否决了“relationship 数量大于 distinct GROUP 数量就回退”
的初步假设：多出的 relationship 也可能是 HIERARCHY 幻觉，应由 HIERARCHY 删除，数量差本身不能证明 OBSERVE
漏掉了 GROUP。

## N7 relationship-region GROUP owner 增量

`31a8c6f` 新增 pipeline 4.4/product-v17，并把回退条件收窄为可由现有 plan 证明的上游遗漏：relationship 必须先
通过 entity-region ownership 结构校验，且其 exact `regionId` 指向已验证 `GROUP` 或 `REPEATED_GROUP` region，
但 inventory 中没有任何 GROUP element owner 精确拥有该 region。只有此时 verifier 才输出
`VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_REGION_GROUP_MISSING` 与 `earliestStage=OBSERVE`。若 region 已有 owner 而模型
复用同一 GROUP 支撑多条 relationship，既有 `VISUAL_HIERARCHY_V2_SUPPORT_GROUP_REUSED` 仍留在 HIERARCHY；代码
不按数量推断、不复制 GROUP，也不从 hierarchy 反向物化 observation。

4.4 只为新 Profile 调整诊断顺序；4.3/v16 与更早 immutable Profile 保持原有 support-first 诊断。PostgreSQL store
仅对白名单中的精确单码 REJECTED HIERARCHY 允许回到 OBSERVE，multi-code 或其他错误仍被通用状态机拒绝。element
prompt v8 只携带固定 repair instruction；独立 evidence verifier、监控说明与 1024 keyboard E2E 同步识别 4.4，
不展示模型原文、图片、局部 ID、完整 prompt 或 Candidate。

单元合同覆盖 legacy v16、exact owner omission、合法修复与 owned GROUP reuse；真实 PostgreSQL 测试证明 checkpoint
清空上游 plan、保留 provider call 计数，并在 lease-expiry 后从 OBSERVE 恢复到 HIERARCHY、BINDING 和 Candidate。
server `.sdlc/evidence/20260811-045814-server`（383 tests、6 gated skip）、web
`.sdlc/evidence/20260811-045937-web`（73 tests + build）、E2E `.sdlc/evidence/20260811-050010-e2e`
（18 passed、1 gated skip）与 clean fast `.sdlc/evidence/20260811-050115-fast` 均为 A1 PASS。本增量没有 Provider
调用，三份 ledger 保持 CLOSED；Profile 继续 `EXPERIMENTAL`，必须先用新的精确 identity/snapshot 实证三阶段。

Plus product-v17 随后以 `178bafb` PROPOSED → `8e0c31e` OPEN → `7107303` CLOSED 完成单 case lifecycle；
PROPOSED 负探针精确命中 `VISUAL_EVALUATION_AUTHORIZATION_NOT_OPEN`，Goal state/guard、255 reservations 与 target
evidence 均未变化。唯一 live wrapper exit 0 后先 CLOSED 再读取 evidence。独立 verifier A2 重建 5 attempts、
27,498 input + 7,733 output tokens、142,447 ms、0 abandoned 与 payload scan PASS。OBSERVE 首次接受并记录
20 SLOT/1 GROUP；四次 HIERARCHY 依次为三次 `VISUAL_HIERARCHY_V2_REGION_OWNERSHIP_INVALID` 和一次
`VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP`，没有触发 exact-owner rewind，也没有进入 BINDING。该结果说明 4.4
仍只改变可证明的失败边界，不构成 Profile 晋级；Max 的三阶段前置条件仍未成立。

## N7 hierarchy region repair taxonomy 增量

v17 live 的连续三次 `VISUAL_HIERARCHY_V2_REGION_OWNERSHIP_INVALID` 合并了 entity region 字段、relationship
region 字段、未知引用、root coverage、parent/child connection 与 region/cardinality 六类固定失败，stage-local retry
无法知道应修哪一项。`4d2cc46` 新增 pipeline 4.5/product-v18：只有新 Profile 启用
`DETAILED_FIXED_CODES`，把上述原因拆成固定、payload-free code；v17 与更早 Profile 继续使用
`LEGACY_GENERIC`，历史诊断顺序和 snapshot 不变。

hierarchy prompt v6 只为这些 code 与既有 support-not-group/group-reused 增加定界修复说明；它仍要求从已验证
groundingPlan/elementInventory 精确复制 ID，不允许本地代码补 GROUP、猜层级或读取模型原文。结构码不触发 crop，
checkpoint 保留成功 OBSERVE 并在 HIERARCHY 原地重试。合同/Profile/prompt 31/31、独立 verifier 2/2 与真实
PostgreSQL lease-expiry 恢复 1/1 通过；server `.sdlc/evidence/20260811-052610-server`、web
`.sdlc/evidence/20260811-052610-web`、E2E `.sdlc/evidence/20260811-052745-e2e`、clean fast
`.sdlc/evidence/20260811-052853-fast` 均为 A1 PASS。实现期间三份 ledger 均 CLOSED、Provider attempts=0；
product-v18 保持 `EXPERIMENTAL`，仍需新的 identity/snapshot 单 case live 验证。

Plus product-v18 随后以 `df166df` PROPOSED → `dca738c` OPEN → `2ee5691` CLOSED 完成单 case lifecycle；
PROPOSED 负探针精确命中 `VISUAL_EVALUATION_AUTHORIZATION_NOT_OPEN`，Goal state/guard、260 reservations 与
target evidence 均未变化。唯一 wrapper exit 0 后先 CLOSED 再读取 evidence。独立 verifier A2 重建 5 attempts、
20,274 input + 4,920 output tokens、89,402 ms、0 abandoned 与 payload scan PASS。OBSERVE 首次接受并记录
9 SLOT/0 GROUP；四次 HIERARCHY 均为 `VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_INVALID`，没有进入
BINDING，也没有命中新增 region taxonomy。该稳定固定码把下一本地切片收窄到 relationship support ID 合同；
在三阶段实证可达前仍禁止 Max，product-v18 不晋级。

## N7 relationship support ID canonicalization 增量

v18 的 generic support-ID code 仍把缺失、空列表、超限、未知 ID 与精确重复混在一起。`214fff9` 新增
pipeline 4.6/product-v19：旧 Profile 继续 `STRICT`；只有新 Profile 使用
`CANONICALIZE_EXACT_DUPLICATES`，先逐项验证 ID 来自已验证 element inventory，再按输入顺序只去除同一有效
ID 的精确重复。代码不会在多个不同 ID 之间选择，也不会新增 GROUP、删 relationship、猜 owner 或改写
OBSERVE；去重后仍有多个不同 ID 时继续由既有 support-count 合同 fail-closed。

缺失、空列表、超限和单项非法引用分别产生固定、payload-free code；成功去重只记录
`VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_IDS_NORMALIZED`。这些结构问题不请求 `TARGETED_CROP`，checkpoint
保留已接受的 OBSERVE/grounding。hierarchy prompt v7 只允许绑定 supplied relationship region 的唯一已提供
GROUP，不允许从不同候选中择一。

合同/Profile/prompt 定向 33/33、独立 verifier 2/2 与真实 PostgreSQL lease-expiry 恢复 1/1 通过；后者证明
精确重复被归一化后 HIERARCHY 可接受并进入 BINDING，恢复时只重做最早未完成的 BINDING。server
`.sdlc/evidence/20260811-055056-server`、web `.sdlc/evidence/20260811-055228-web`、E2E
`.sdlc/evidence/20260811-055259-e2e` 与提交后 clean fast `.sdlc/evidence/20260811-055541-fast` 均为 A1
PASS。实现和门控期间 Provider attempts=0、三份 ledger 保持 CLOSED。product-v19 仍为 `EXPERIMENTAL`；上述
结果只证明离线合同与恢复路径，不证明真实模型能完成三阶段，也不打开 Max 入口。

Plus product-v19 随后以 `09c3c16` PROPOSED → `7e1b98b` OPEN → `aa92ae2` CLOSED 完成单 case lifecycle；
PROPOSED 负探针精确命中 `VISUAL_EVALUATION_AUTHORIZATION_NOT_OPEN`，Goal state/guard、265 reservations 与
target evidence 均未变化。唯一 wrapper exit 0 后先 CLOSED 再读取 evidence。独立 verifier A2 重建 5 attempts、
24,956 input + 6,103 output tokens、109,700 ms、0 abandoned 与 payload scan PASS。OBSERVE 首次接受并记录
13 SLOT/1 GROUP；四次 HIERARCHY 均为
`VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CARDINALITY_INVALID`，没有进入 BINDING，也没有命中 support-ID
normalization telemetry。该结果把下一本地切片收窄到 relationship region/cardinality 合同；不得重复相同 v19，
在三阶段实证可达前仍禁止 Max，product-v19 不晋级。

## N7 unique evidence-owned relationship region normalization 增量

v19 已证明 relationship cardinality 可由唯一支撑 GROUP 的已验证 multiplicity 决定，但模型连续把 relationship
绑定到与该 cardinality 不兼容的已知 region。`391bd52` 新增 pipeline 4.7/product-v20；v19 与更早 Profile 继续
`STRICT`。新 policy 只在当前 relationship region 已知但 cardinality 不兼容时，检查该 relationship 唯一有效
支撑 GROUP 已在 OBSERVE checkpoint 中验证的 owned regions：恰有一个 cardinality-compatible region 才确定性
替换；零个时输出 `VISUAL_SEMANTIC_GROUP_REGION_INVALID` 并把最早修复阶段路由到 OBSERVE；多个时拒绝选择，
继续由既有 `VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CARDINALITY_INVALID` fail-closed。

该 policy 不处理未知 region，不跨 GROUP 选择，不合成/删除 element、entity 或 relationship，不修改 endpoint、
field key、topology 或已验证 OBSERVE。成功替换只记录 payload-free
`VISUAL_HIERARCHY_RELATIONSHIP_REGION_NORMALIZED`；结构诊断仍不请求 crop，accepted OBSERVE/grounding checkpoint
继续保留。三模型 product-v20 Profile 复用 immutable elements-v8/hierarchy-v7/bindings-v3 prompts，保持隐藏
`EXPERIMENTAL`。

inference 定向 34/34、独立 verifier 2/2、真实 PostgreSQL lease-expiry 恢复 1/1 通过；后者同时覆盖 exact
support-ID 去重与 unique region normalization，证明 HIERARCHY 可进入 BINDING，崩溃恢复只重做 BINDING。
server `.sdlc/evidence/20260811-062224-server`、Node 24 web `.sdlc/evidence/20260811-062433-web`、E2E
`.sdlc/evidence/20260811-062513-e2e` 与提交后 clean fast `.sdlc/evidence/20260811-062623-fast` 均为 A1 PASS。
实现和门控期间 Provider attempts=0，三份 ledger 保持 CLOSED。以上仍只是 repository synthetic 输入的本地合同
证据；随后只能在 fresh evaluation identity/Profile snapshot 与独立 J1 ledger 下执行 Plus product-v20 单 case，
且只有 live 实际触达 BINDING 才可考虑 Max。

Plus product-v20 随后以 `7afda44` PROPOSED → `191cf63` OPEN → `85b2000` CLOSED 完成单 case lifecycle；
PROPOSED 负探针精确命中 `VISUAL_EVALUATION_AUTHORIZATION_NOT_OPEN`，Goal/guard 哈希、270 reservations 与
target evidence 均未变化。唯一 wrapper exit 0、119,361 ms，且无遗留进程；先 CLOSED 后读取 evidence。独立
verifier A2 重建 5 attempts、24,251 input + 6,086 output tokens、109,414 ms、0 abandoned 与 payload scan PASS。
OBSERVE 首次接受并记录 12 SLOT/1 GROUP；四次 HIERARCHY 依次为 support-not-group、两次
relationship-region-connection-invalid、support-IDs-empty，未进入 BINDING，也未命中 region normalization
telemetry。Plus Goal 累计为 126 attempts、729,270 tokens、¥2.910558。该结果把下一本地切片收窄到已验证
GROUP-owned region 与 parent/child entity ownership 的 connection 合同；三阶段仍不可达，Max 入口继续关闭，
product-v20 不晋级。

## N7 unique connected relationship region normalization 增量

v20 live 两次稳定暴露 `VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CONNECTION_INVALID`，说明 cardinality-compatible
还不足以证明 relationship region 连接 parent/child entity ownership。`dda763c` 新增 pipeline 4.8/product-v21；
v20 与更早 Profile 语义保持不变。新 policy 仅在当前已知 relationship region 的 cardinality 或 connection
不成立时，检查该 relationship 唯一有效支撑 GROUP 在 OBSERVE checkpoint 中已验证的 owned regions，并同时过滤
cardinality compatibility 与 parent/child connection。恰有一个 combined-compatible region 才确定性替换；零个或
多个不择一，继续由既有 detailed HIERARCHY fixed code fail-closed；若连 cardinality-compatible region 都不存在，
仍输出 `VISUAL_SEMANTIC_GROUP_REGION_INVALID` 并回到 OBSERVE。

该增量不处理未知 region，不跨 GROUP，不按距离或模型顺序排名，不合成/删除 region、element、entity 或
relationship，也不修改 endpoint、field key 或 topology。成功归一化复用 payload-free
`VISUAL_HIERARCHY_RELATIONSHIP_REGION_NORMALIZED`，accepted OBSERVE checkpoint 与 no-crop 结构诊断保持不变。
三模型 product-v21 Profile 复用 immutable elements-v8/hierarchy-v7/bindings-v3 prompts，继续隐藏
`EXPERIMENTAL`。

inference 定向 35/35、独立 verifier 2/2、真实 PostgreSQL lease-expiry 恢复 1/1 通过；server
`.sdlc/evidence/20260811-064835-server`、Node 24 web `.sdlc/evidence/20260811-065005-web`、E2E
`.sdlc/evidence/20260811-065038-e2e` 与提交后 clean fast `.sdlc/evidence/20260811-065134-fast` 均为 A1 PASS。
实现与门控期间 Provider attempts=0，三份 ledger 保持 CLOSED。该证据只证明 repository synthetic 合同可达；
该 local A1 当时只允许重新计算 evaluation identity/Profile snapshot 后，以独立精确 J1 ledger 执行 Plus
product-v21 单 case；只有 live 实际触达 BINDING 才可考虑 Max。

Plus product-v21 随后以 `405fa9e` PROPOSED → `d793c92` OPEN → `02872c5` CLOSED 完成单 case lifecycle；
PROPOSED 负探针精确命中 `VISUAL_EVALUATION_AUTHORIZATION_NOT_OPEN`，Goal/guard 哈希、275 reservations 与
target evidence 均未变化。唯一 wrapper exit 0、174,353 ms，且无遗留进程；先 CLOSED 后读取 evidence。独立
verifier A2 重建 5 attempts、18,715 input + 8,810 output tokens、164,553 ms、0 abandoned 与 payload scan PASS。
前两次 OBSERVE 分别为 parent-kind/element fixed code，第三次 accepted；随后两次 HIERARCHY 均为
support-not-group，未进入 BINDING，也未命中 region normalization telemetry。

该结果没有建立 Max 入口，也不支持重复相同 v21。下一本地诊断只检查：当 relationship support ID 指向非 GROUP
时，relationship 的 exact known region 是否恰有一个已验证 GROUP owner，可在不依赖距离、顺序、模型原文或
跨 GROUP 猜测的前提下提供唯一替换；若零个或多个，必须保持既有 fixed code fail-closed。

## N7 unique exact-region GROUP-owner support normalization 增量

`edc0c28` 新增 pipeline 4.9/product-v22；4.8 与更早 Profile 保持原语义。新 policy 只在 relationship 的
support list 经逐项验证、去重后恰有一个已知但非 GROUP 的 element 时继续；relationship 的原始 `regionId`
还必须是已验证的 `GROUP` 或 `REPEATED_GROUP` region，并且 inventory 中恰有一个 GROUP element 精确拥有该
region。只有这三个条件同时成立，codec 才把 support ID 替换为该 owner，再从其 multiplicity 派生 cardinality。

未知 support 保留 `VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN`；非容器/未知/非法 region、零 owner 或多个
owner 均不归一化，继续由既有固定码 fail-closed。实现不按距离、reading order 或模型文本排名，不跨 region/GROUP，
不新增或删除 element/entity/relationship，不修改 endpoint、field key、topology 或 Candidate。成功路径只记录
payload-free `VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_OWNER_NORMALIZED` 计数；旧 exact-duplicate support 与 v21
connection-aware region normalization 继续复用。

合同/Profile/prompt 定向 36/36、独立 evidence verifier 2/2 与真实 PostgreSQL lease-expiry 恢复 1/1 通过；
后者证明唯一 owner 替换后可进入 ELEMENT_BINDING，恢复时只重做 BINDING。server
`.sdlc/evidence/20260811-071714-server`（191 tests、6 gated skip）、Node 24 web
`.sdlc/evidence/20260811-071856-web`（73 tests + type/lint/build）、E2E
`.sdlc/evidence/20260811-071927-e2e`（18 passed、1 gated skip、无 console/page error）与提交后 clean fast
`.sdlc/evidence/20260811-072030-fast` 均为 A1 PASS。实现与门控期间 Provider attempts=0、三份 ledger CLOSED；
product-v22 仍隐藏 `EXPERIMENTAL`。在随后的 live 前，这些 repository synthetic 证据尚不构成三阶段、Max
入口、final eval 或用户验收；当时下一步只能在重新计算 identity/Profile snapshot 且精确 J1/额度/时限仍有效
时做 Plus v22 单 case。

Plus product-v22 随后以 `6f65516` PROPOSED → `2d396e7` OPEN → `4f86456` CLOSED 完成单 case lifecycle。
PROPOSED 负探针精确命中 `VISUAL_EVALUATION_AUTHORIZATION_NOT_OPEN`，Goal/guard 哈希、280 reservations 与
target evidence 均未变化。唯一 wrapper exit 0、138,611 ms，先 CLOSED 后由独立 verifier A2 重建 5 attempts、
19,659 input + 7,284 output tokens、128,862 ms、0 abandoned 与 payload scan PASS。第二次 OBSERVE accepted；
第一次 HIERARCHY 因 relationship region/cardinality 拒绝，第二次 HIERARCHY accepted 且只记录
`VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED=1`；随后 ELEMENT_BINDING accepted。由此首次建立 live
OBSERVE→HIERARCHY→BINDING 三阶段可达性，但没有命中 v22 support-owner normalization telemetry，不能把结果
归因于该规则。报告仍 `complete=false`，slot/group/entity/relationship/binding 与 gold 的匹配远未达标，故
Profile 继续隐藏 `EXPERIMENTAL`。Max 的阶段前置条件现已成立，但调用仍须使用新的 exact identity/Profile
snapshot、独立精确 J1 ledger、剩余额度与有效时限；本 ADR 不把 reachability 写成 final 质量或验收通过。

Max product-v22 在该前置条件成立后，以 `e0b1d67` PROPOSED → `740d28f` OPEN → `99efc6b` CLOSED 完成
同一 synthetic case。PROPOSED 负探针保持 Goal/guard、285 reservations 与 target evidence 零变化。唯一一次
Provider wrapper 的测试主体原子写出 3 个 SETTLED attempts；外层 PowerShell 因 Mockito stderr warning 未能
返回 Maven 摘要。恢复检查确认没有存活子进程、lease evidence 已完成后立即 CLOSED，未并发或串行重跑。
独立 verifier A2 重建 11,318 input + 3,163 output tokens、61,032 ms、¥0.249684、0 abandoned 与 payload scan
PASS。三个阶段均一次 accepted、无 fixed-code repair，但结果为 11 slots / 0 groups、1 entity / 0 relationships、
11 bindings 且只有 1 slot、1 entity 匹配，bindings 0/10、tree edit 30/32。该结果证明 Max 也能执行完整三阶段，
却更明确证明“合同可达”不等于“嵌套结构正确”；v22 support-owner normalization 同样未命中。Profile 与 Goal
继续 `EXPERIMENTAL` / `in_progress`，在形成新的 evidence-bounded OBSERVE repeated-group omission 假设前，
不重复 Max v22，也不把单 case 扩大为 20/60-case final eval。

## N7 support-owner hybrid observation 增量

Max v22 的 0 GROUP/relationship 结果证明：当 OBSERVE 与 HIERARCHY 都没有提出重复结构时，bounded verifier
没有合法证据可据以补造 GROUP 或 relationship。本地代码不得从 gold、领域词或几何距离直接合成结构；新的假设
只能增加已绑定且不落盘的感知证据，再让原有三阶段合同和 verifier 判断。

`e13bf0c` 因此新增 pipeline 4.10/product-v23 hybrid。它完整继承 4.9 的 evidence-derived cardinality、
relationship-region prerequisite、fixed-code repair、exact support dedupe、connection-aware region normalization
与 unique exact-region GROUP-owner support policy，同时绑定既有的
`rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1` capability 和
`renderweave-document-vision-observations-prompt/1.0`。Flash、Plus、Max 各有独立 immutable Profile；三者都保持
产品选择器不可见和 `EXPERIMENTAL`。

每次 drain 只执行一次本地 Document Vision，OBSERVE、HIERARCHY、ELEMENT_BINDING 共享同一份 ephemeral
observation。OCR text、line ID 与 bbox 只作为不可信 secondary evidence 进入当次请求，不直接创建 field、entity、
relationship 或 Candidate，也不进入 checkpoint、Candidate、problem、attempt、journal、report 或普通日志。
真实 PostgreSQL scripted-provider 纵切同时证明三阶段复用、support-owner normalization 仍命中和 payload 不落盘；
Profile/能力合同、独立 Python snapshot verifier 也通过。提交后 clean fast
`.sdlc/evidence/20260811-075518-fast` 与 exact-revision server
`.sdlc/evidence/20260811-075612-server`（192 tests、6 gated skip）均为 A1 PASS；Provider attempts=0，三份
ledger 仍 CLOSED。

这些证据只证明 4.10 的组合语义、恢复边界和审计边界，不证明 OCR 能恢复 transit-board 的重复组。下一步如执行
单 case，仍必须重新计算 evaluation identity/Profile snapshot，并使用精确、有效、额度内的 J1 ledger；未产生新
A2 质量证据前不得扩大到 final eval 或晋级 Profile。

## N7 v23 Flash hybrid smoke

Flash product-v23 随后按 `0c1506f` PROPOSED → `9652837` OPEN → `1185890` CLOSED 完成单 case lifecycle。
PROPOSED 负探针精确命中 `VISUAL_EVALUATION_AUTHORIZATION_NOT_OPEN`，Goal/guard、288 reservations 与 target
evidence 均未变化。OPEN 前重新计算的 evaluation identity 为
`renderweave-visual-evaluation-tree-sha256/1:d6f2be493cfcbfc5f6f1232b75067ba31e3322765529126fba24b5a84983b2be`，
Flash v23 snapshot 为 `9053885261116ded0de3cc04d2e7ebe01f130fa78748c95b3f2da81c6782d102`；精确
Document Vision canary `.sdlc/evidence/20260811-080747-document-vision` 通过后才进入 Provider。

唯一 wrapper 的测试主体写出 5 个 SETTLED attempts；外层 PowerShell 将 Mockito stderr warning 提升为
`NativeCommandError`，因此没有取得 Maven 自身退出码。恢复检查确认没有 Java/Maven/OCR 子进程，state/report
已完成、无 RESERVED/BREACHED 后立即 CLOSED，未重跑。独立 verifier A2 PASS：20,110 input + 24,522 output
tokens、178,163 ms、¥0.023643、0 abandoned、payload scan PASS。五次均停在 OBSERVE：三次
`VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND`、两次 `VISUAL_GROUNDING_PARENT_KIND_INVALID`；实际
slot/group/entity/relationship/binding 全为 0。OCR secondary evidence 没有突破 OBSERVE 合同，v23 不晋级、
不重复。

## N7 bounded observation normalization 增量

上述 A2 只暴露固定码，不能授权读取或持久化模型原文。`061101f` 因此新增 pipeline 4.11/product-v24，并把
修复边界限制为可离线证明的 observation drift：只接受合同已明确列出的 `DOCUMENT→ROOT`、
`CONTAINER→GROUP` 与允许枚举的大小写归一化；ITEM 只有在同 artifact、bbox 包含、`repeatGroupId` 精确匹配的
`REPEATED_GROUP` 候选恰好一个时才改父节点。父节点变化后，只按已验证 bbox 的 top/left/id 重算受影响 sibling
的 `readingOrder`。未知 alias、零个或多个候选、未知 parent、拓扑/元素/实体/relationship 增删继续 fail-closed。

成功路径只记录 payload-free `VISUAL_GROUNDING_REGION_KIND_NORMALIZED`、
`VISUAL_GROUNDING_ITEM_PARENT_NORMALIZED` 与 `VISUAL_GROUNDING_READING_ORDER_NORMALIZED` 计数；不保存被替换
值、模型响应、OCR、图片或完整请求。4.11 继续继承 4.10 的 ephemeral Document Vision、4.9 的 hierarchy
normalization、deterministic materializer、stage-local recovery 与审核边界，历史 Profile 不改写，三个 v24 Profile
仍隐藏 `EXPERIMENTAL`。

合同/Profile 定向 20/20、真实 PostgreSQL + 独立 verifier 2/2 通过；纵切同时命中 3 个 kind、1 个唯一 parent、
1 个 readingOrder 与后续 support-owner telemetry，并完成 OBSERVE→HIERARCHY→BINDING，OCR sentinel 未落盘。
提交后 clean server `.sdlc/evidence/20260811-082418-server` 为 193 tests、6 gated skip A1 PASS；实现与门控
Provider attempts=0，三份 ledger CLOSED。该证据仍不是 live 模型质量；v24 只能在 fresh identity/snapshot、
精确 J1、额度和时限有效时先做 Flash 单 case，Plus 虽已获准也不得与其并发。

## N7 Goal guard v3 增量

用户第二次追加每槽位 500,000 exposed tokens 后，`2b23617` 将跨 ledger token cap 从 1,000,000 提高到
1,500,000，但保持单 authorization 500,000、180 attempts 与 Flash ¥0.40 / Plus ¥4 / Max ¥18 不变。v3 只允许
字段完全匹配的 v1 或 v2 guard 在文件锁内迁移：先用旧 cap 验证完整 state，再原子替换 guard；reservation 与
state 文件不重写。非精确旧 guard 和运行中 tamper 继续 fail-closed。

定向测试覆盖 v1→v3、v2→v3、state 字节不变、旧/新 Flash 共槽位、非精确迁移拒绝及独立 verifier 对
v1/v2/v3 的重放，共 12/12 PASS；exact-clean fast `.sdlc/evidence/20260811-083559-fast` PASS。当前 293 条
reservation（288 SETTLED、5 历史 Plus RESERVED）和三份 CLOSED ledger 未变化，Provider attempts=0。token
空间增加不放宽费用门或 N7 三阶段/质量停止条件。

## N7 Flash v24 smoke

Flash v24 以 fresh identity `…ef0a049`、snapshot `d4c2f0…a085` 执行 `9d2dfa3` PROPOSED → `ded9e78` OPEN →
`ac17e0e` CLOSED。负探针精确 NOT_OPEN，Goal state/guard 与 293 reservations 未变化。首次本地 canary 因使用了
产品变量名而缺少测试专用变量，在 adapter 启动前 fail-closed；确认无 Java/Maven/OCR 进程、无 target evidence
和 Provider 后，按测试合同修正并得到 `.sdlc/evidence/20260811-084304-document-vision` PASS。唯一 live wrapper
exit 0，228.589 秒；ledger 在读取 evidence 前已 CLOSED。

独立 verifier A2 重建出 1 completed、0 abandoned、5 SETTLED attempts、20,121 input + 24,819 output、
216,004 ms、¥0.023884 增量和 payload scan PASS。四次固定码为
`VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND`，一次为 `VISUAL_GROUNDING_SIBLING_OVERLAP`；全部停在 OBSERVE，
三类 v24 normalization telemetry 均未命中，实际 slot/group/entity/relationship/binding 全为 0。Flash Goal 变为
86 attempts、517,945/1,500,000 tokens、¥0.237813；总账本 298 reservations（293 SETTLED、5 历史 Plus
RESERVED），三份 ledger CLOSED。

因此 v24 仍为 `EXPERIMENTAL`，相同 Flash 假设不重复。该 A2 没有提供可安全新增未知 enum alias 的依据；不能
读取模型原文来猜值。Plus 已获用户许可且 v22 已到达 BINDING，故下一可区分假设是使用 fresh identity/snapshot
做 Plus v24 单 case，验证同一 pipeline/OCR/bounded policy 的模型差异；在新信号出现前不扩大 final eval，也不
调用 Max。

## N7 Plus v24 smoke

Plus v24 以 fresh identity `…9b077fb`、snapshot `fbf24e…deba0` 执行 `3598c12` PROPOSED → `963d1e6` OPEN →
`4747947` CLOSED。负探针精确 NOT_OPEN，Goal state/guard 与 298 reservations 未变化。Document Vision 的代码、
可执行文件、adapter 和 models 均未变，复用刚通过的 `.sdlc/evidence/20260811-084304-document-vision`。唯一
wrapper exit 0、102.811 秒；ledger 在读取 evidence 前已 CLOSED。

独立 verifier A2 重建出 1 completed、0 abandoned、5 SETTLED attempts、29,666 input + 4,777 output、
90,132 ms、¥0.097548 增量和 payload scan PASS。OBSERVE accepted；两个 HIERARCHY rejection 后，第三个
HIERARCHY 通过已存在的 `VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED` bounded telemetry accepted，随后
ELEMENT_BINDING accepted。最终 group 1/3、entity 2/4、relationship 1/3，但 slot 0/10、binding 0/10、field
1/13，9 critical hallucinations、12 blockers；三阶段可达不等于质量达标，v24 observation normalization 也仍未
命中。

Plus Goal 变为 141 attempts、818,181/1,500,000 tokens、¥3.213606；总账本 303 reservations（298 SETTLED、
5 历史 Plus RESERVED），三份 ledger CLOSED。Profile 继续 `EXPERIMENTAL`。该结果满足 Max 的三阶段入口门，
但只授权在 fresh identity/snapshot、精确 J1、Max attempts/token/CNY 与时限仍有效时做一个 Max v24 case；不
直接扩大为 final eval。

## N7 Max v24 smoke

Max v24 以 fresh identity `…4f5f41`、snapshot `83c5c9…fceb9` 执行 `a04691e` PROPOSED → `b8ac358` OPEN →
`57b1502` CLOSED。负探针精确 NOT_OPEN，Goal state/guard、303 reservations 与 target evidence 均未变化；
Document Vision 的代码、可执行文件、adapter 与 models 未变，复用 `.sdlc/evidence/20260811-084304-document-vision`。
唯一 wrapper exit 0、92.715 秒；ledger 在读取 evidence 前已 CLOSED。

独立 verifier A2 重建出 1 completed、0 abandoned、3 SETTLED attempts、17,500 input + 4,219 output、
79,835 ms、¥0.361884 增量和 payload scan PASS。OBSERVE、HIERARCHY、ELEMENT_BINDING 都在首次尝试 accepted，
HIERARCHY 只命中既有 `VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED=1`；三类 v24 observation
normalization telemetry 均未命中。最终 slot 0/10、group 0/3、entity 1/4、relationship 0/3、binding 0/10、
field 0/13，16 critical hallucinations、17 blockers；合同可达没有转化为语义质量。

Max Goal 变为 79 attempts、465,016/1,500,000 tokens、¥9.816288；总账本 306 reservations（301 SETTLED、
5 历史 Plus RESERVED），三份 ledger CLOSED。clean fast `.sdlc/evidence/20260811-091152-fast` PASS。该结果否决
直接扩大 20/60-case final eval，也不授权读取模型原文来猜测新规则；下一步只能从 stage-gold 与 payload-free
metrics 离线证明 OBSERVE 的 bounded semantic verifier，并保持 earliest-stage repair、checkpoint 与人工审核边界。

## N7 leaf-evidence OBSERVE verifier 增量

v24 的 Plus/Max A2 同时表现为 expected SLOT 大量缺失、actual SLOT 过量与 GROUP 缺失；该固定指标支持一个不
依赖模型原文的可证伪假设：模型可能把承载多个可见字段的容器误标为 scalar SLOT。`f8f09b4` 新增 opt-in
`SLOT_LEAF_EVIDENCE_REQUIRED` policy。它只消费已通过 JSON/grounding/坐标合同的本地 inventory：同 artifact
上，一个 SLOT 的证据框若严格包含另一个不同 element 的证据框，就拒绝该 OBSERVE plan，并输出固定码
`VISUAL_SEMANTIC_SLOT_EVIDENCE_CONTAINS_ELEMENT`。相等框、边界相交、其他 artifact 和 GROUP 容器均不触发；
verifier 不改 kind、不补 GROUP、不删除元素，也不读取 OCR/model text。

`2b6eb9c` 以 pipeline 4.12/product-v25 将 policy 接入真实 workflow。失败不形成 OBSERVE checkpoint，只在
OBSERVE 原地重试并携带固定问题码；Document Vision 仍只执行一次，且 OCR text/line ID 不进入 checkpoint、
Candidate、problem 或 attempt。三个 v25 Profile 复用 immutable elements-v8/hierarchy-v7/bindings-v3 prompt，
只新增 pipeline/Profile identity；历史 pipeline 4.11 及更早版本继续 `LEGACY`。Flash/Plus/Max Profile 全部隐藏、
`EXPERIMENTAL`，独立 Python verifier 接受其精确 snapshot。

stage-gold 回放对 60 个 scene 全部无误报；真实 PostgreSQL tracer 证明第一次 container-sized SLOT 被拒、第二次
OBSERVE 修复后才进入 HIERARCHY/BINDING，Provider stage 序列精确为 OBSERVE、OBSERVE、HIERARCHY、
ELEMENT_BINDING。`6cb2624` 让监控/审核共用 UI 显示固定码、中文解释、`证据区域` 与最早 OBSERVE 修复阶段，
继续明确不展示 OCR、图片、Prompt 或 Provider 原文。

clean A1 evidence：server `.sdlc/evidence/20260811-093552-server`（196 tests、6 gated skip）、Node 24 web
`.sdlc/evidence/20260811-093552-web`（73 tests + type/lint/build）、E2E
`.sdlc/evidence/20260811-093741-e2e`（18 passed、1 gated skip）和 runtime
`.sdlc/evidence/20260811-093824-runtime` 全部 PASS。该增量 Provider attempts=0，三份 ledger 保持 CLOSED；
它只建立下一次单-case smoke 的 bounded hypothesis，不满足 final eval、Profile 晋级或 Goal 完成条件。

## N7 Flash / Plus v25 smoke

Flash v25 在 fresh evaluation identity `…9c77ac2` 与 Profile snapshot `656df9…db2` 下，按 `a7a2a7f`
PROPOSED → `a9635e4` OPEN → `edb35bc` CLOSED 完成单 case。负探针精确 NOT_OPEN；Document Vision 首次
canary `.sdlc/evidence/20260811-094501-document-vision` 失败后确认无 Provider、子进程或 evidence lease，再以
精确测试配置恢复为 `.sdlc/evidence/20260811-094753-document-vision` PASS，未并发重跑 live。唯一 wrapper
211.8 秒；ledger 在读取 evidence 前 CLOSED。独立 verifier A2 PASS：4 SETTLED attempts、16,082 input +
21,338 output、188,904 ms、payload scan PASS；全部因 enum/region 固定码停在 OBSERVE，leaf-evidence 规则未
命中。Flash 累计变为 90 attempts、555,365/1,500,000 tokens、¥0.258103。

Plus v25 复用同一 fresh tree identity、以 snapshot `ff5a1a…51a` 按 `06cef12` PROPOSED → `34e7ab3` OPEN →
`e93d1f7` CLOSED 完成单 case。负探针测试主体精确命中 NOT_OPEN；外层摘要因 PowerShell `$Matches` 名称冲突
失败后，检查 Goal/guard 哈希、310 reservations、target evidence 与存活进程均无变化，故没有重跑。唯一 live
wrapper exit 0、194.231 秒，并在 evidence 读取前 CLOSED。独立 verifier A2 PASS：5 SETTLED attempts、25,433
input + 10,312 output、181,561 ms、payload scan PASS。第三次 OBSERVE accepted；随后两次 HIERARCHY 分别为
relationship region cardinality invalid 与 support not group，未进入 BINDING，也未命中 leaf-evidence 固定码。
最终 slot matched 3/10、group 1/3、entity/relationship/binding matched 均为 0，tree edit 20/20，report 仍
`complete=false`。Plus 累计变为 146 attempts、853,926/1,500,000 tokens、¥3.346968。

v25 没有为 leaf-evidence 假设提供 live 命中，且 Plus 未证明该版本三阶段可达，因此不调用 Max v25，不把历史
v24 的可达性当作新假设的替代证据。三份 ledger 均为 CLOSED；两份 A2 smoke 仍只是诊断，不授权 final eval、
Profile 晋级或 Goal 完成。

## N7 unique enclosing-connected GROUP-owner normalization

Plus v25 揭示一个 bounded 次序死锁：exact-region support-owner normalization 在 connection-aware relationship
region normalization 之前执行；当非 GROUP support ID 与 relationship region 同时错误时，两条规则都无法单独
前进。`d3b0292` 以 pipeline 4.13/product-v26 新增
`CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_ENCLOSING_CONNECTED_GROUP_OWNER`。它仅在 support 是已知非 GROUP、
source region 已知，且恰有一个 GROUP/容器 region 配对同时满足 cardinality、包围全部 support element regions、
并连接 parent/child entity regions 时，才先归一化 support owner；随后既有规则可确定性归一化 relationship
region。零个或多个配对继续以原固定码 fail-closed；不读取 OCR/模型文字、不按距离或 gold 排名、不增删结构。

新 telemetry `VISUAL_HIERARCHY_RELATIONSHIP_ENCLOSING_SUPPORT_OWNER_NORMALIZED` 与既有 owner/region/cardinality
固定计数一起进入 checkpoint/attempt。`5ef25bd` 在监控与审核共用 UI 显示固定码、中文解释、`层级边` 范围和
最早 HIERARCHY 修复阶段，仍不展示图片、OCR、Prompt、Candidate 或 Provider 原文。定向合同/Profile 22/22、
独立 snapshot verifier、真实 PostgreSQL OBSERVE→HIERARCHY→ELEMENT_BINDING tracer 均 PASS；ambiguity 负例保持
`SUPPORT_NOT_GROUP`，OCR sentinel 未进入 checkpoint/current/problems。

提交后 A1 evidence：server `.sdlc/evidence/20260811-101733-server`（197 tests、6 gated skip）、Node 24 web
`.sdlc/evidence/20260811-101734-web`（73 tests + generate/type/lint/build）、E2E
`.sdlc/evidence/20260811-101948-e2e`（18 passed、1 gated skip、无 console/page error）与 runtime
`.sdlc/evidence/20260811-102032-runtime` 全部 PASS。实现期间 Provider attempts=0，三份 ledger CLOSED。v26
提交与治理同步后又在隔离 clean worktree 对 revision `371505b` 完成
`.sdlc/evidence/20260811-102845-full`：9/9 steps、`workingTreeDirty=false`、A1 PASS，Provider attempts=0。该证据是
当前 v26 revision 的受控 full gate，不替代 final eval 后所需的最终 revision gate。v26 仍是隐藏 `EXPERIMENTAL`
的可证伪假设；其后续 live 结论如下，仍没有 final 20/60、final independent verifier 或业务/视觉 J1。

## N7 Flash / Plus v26 smoke 与预检恢复

第一次 Flash v26 lifecycle 在隔离 worktree 中按 `f2386b6` PROPOSED → `32fae98` OPEN → `2eedc61` CLOSED；负探针
通过，但该 worktree 的 `core.autocrlf=true` 改变了 corpus 字节。wrapper 在 8.5 秒内以固定码
`VISUAL_EVALUATION_CORPUS_IDENTITY_MISMATCH` fail-closed，发生在 evidence/Goal mutation 与 Provider adapter 之前。
恢复审计确认无子进程、无 target evidence、Goal state/guard 哈希不变、provider attempts=0；因此没有并发重跑，
该 lifecycle 只作为预检恢复证据，不计入 live A2 report。随后以 `core.autocrlf=false` 重建 clean worktree，重新计算
corpus SHA、evaluation identity `…530a2696` 与各模型 Profile snapshot。

Flash v26b 在 snapshot `3730ade4…69431` 下按 `36a7a9e` PROPOSED → `ef6440f` OPEN → `b976e5f` CLOSED。
负探针精确 NOT_OPEN；RapidOCR 3.9.2/OpenVINO 2026.0.0 capability 与 synthetic canary 通过。唯一 wrapper exit 0、
170,815 ms，ledger 在读取 evidence 前 CLOSED。独立 verifier A2 PASS：1 completed、0 abandoned、5 SETTLED
attempts、20,120 input + 22,858 output、157,514 ms、payload scan PASS。五次均在 OBSERVE 被 enum、sibling-overlap
或 parent-containment 固定码拒绝，实际结构计数全为 0，v26 telemetry 未命中。Flash 累计为 95 attempts、
598,343/1,500,000 tokens、¥0.280418。

Plus v26 在 snapshot `c9682a4d…57bb` 和同一 fresh identity 下按 `3f95ae3` PROPOSED → `67f33dd` OPEN →
`31093a6` CLOSED。负探针精确 NOT_OPEN；唯一 wrapper exit 0、104,039 ms，先 CLOSED 后独立重放。A2 PASS：
1 completed、0 abandoned、4 SETTLED attempts、24,635 input + 5,008 output、91,365 ms、payload scan PASS。
OBSERVE 首次 accepted；三次 HIERARCHY 依次为 relationship region cardinality invalid、support not group、region
cardinality invalid，未进入 BINDING，也未命中 enclosing-owner telemetry。最终 slot 11/10、group 1/3、entity/
relationship/binding matched 全为 0、tree edit 20/20，report `complete=false`。Plus 累计为 150 attempts、
883,569/1,500,000 tokens、¥3.436302。

v26 没有证明同版本三阶段可达，因此 Max v26 的显式入口门不成立，Max 保持 79 attempts、465,016 tokens、
¥9.816288。Goal 共 324 reservations（319 SETTLED、5 个历史 Plus RESERVED、0 BREACHED），三份 ledger CLOSED。
下一安全假设只在离线合同中检查：已知非 GROUP support 若在已验证 observation inventory 中恰有一个 ancestor
GROUP，且该 owner/region 仍满足既有 cardinality、enclosure 与 connection 条件，是否可以在现有 enclosing-owner
规则之前确定性归一化。该假设不得读取模型原文/OCR/gold，不得按距离或顺序排名，也不得合成、补删结构；先以
TDD、真实 PostgreSQL checkpoint 和 payload-free telemetry 证伪，再决定是否存在新的单-case live 门。

## v27 决策增量：唯一 source-ancestor GROUP owner

接受 pipeline 4.14 的有界 fallback，但不修改 pipeline 4.13 或任何历史 Profile：

1. 先完整执行 v26 enclosing-owner 规则；若候选为一个则沿用 v26，若候选为多个则直接 fail-closed，只有候选
   严格为零才允许进入 v27 fallback。
2. fallback 只读取已经合同化的原始 `relationship.regionId` 与 region forest，沿 source region 的 ancestor 链
   枚举容器；候选必须由恰好一个 observed GROUP element 拥有、cardinality-compatible，并连接 relationship
   parent/child entity ownership。最终 GROUP/region 配对仍必须唯一。
3. zero/multiple ancestor owner、未知 region/support、非容器 ancestor 或 connection 不成立均保留既有 fixed
   diagnostic；不得以距离、reading order、OCR/model text、gold 或候选排名打破歧义，不得补造/删除 entity、
   relationship、GROUP 或 binding。
4. 成功时只追加 payload-free
   `VISUAL_HIERARCHY_RELATIONSHIP_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED` 计数；checkpoint、Candidate、常规
   日志与 evidence 不包含原始图片、OCR、完整 prompt 或模型响应。

`676180a` 固化 codec 与 ambiguity 负例，`e1f1a9d` 固化三模型 product-v27 Profile、worker、真实 PostgreSQL
三阶段 tracer 与独立 snapshot verifier，`3a56af9` 固化监控/审核 UI 和 E2E。三份 Profile 均为
`EXPERIMENTAL`、最多 5 calls、0 repair rounds；Profile 资源存在不等于 live 授权或质量晋级。

受影响 gate 期间发现并修复预算 reservation 的事务入口缺陷：接口 5 参数 default method 可能绕过实现方法的
`@Transactional`，使预算行锁早于汇总与 insert 释放。`5ada0fa` 显式覆写该入口，使 `FOR UPDATE`、消费汇总与
ledger insert 处于同一事务，并把并发上限回归重复 10 次。首轮红灯证据保留在
`.sdlc/evidence/20260811-113055-server`；修复后 server `20260811-113412`、Node 24 web `20260811-113607`、
E2E `20260811-113652`、runtime `20260811-113726` 均 A1 PASS。该治理修复是 live 前置硬门，不提高模型质量
结论。

v27 实现与 gate 全程 Provider attempts=0、三份 ledger CLOSED、Goal 用量不变。是否执行单-case smoke 仍需
fresh evaluation identity、Profile snapshot、预算/时限重算与独立 ledger lifecycle；是否晋级仍由 final eval、
final verifier、full gate 和业务/视觉 J1 共同决定。

## N7 v27 三模型 smoke 与不晋级决定

revision `47f622b` 在 LF detached clean worktree 通过 full gate：9/9 steps、`workingTreeDirty=false`、A1，
evidence 位于 `D:\Yiwer\code\RenderWeave-v27-full-47f622b\.sdlc\evidence\20260811-114304-full`。随后 Java 与
独立 Python 对同一 clean tree 重算出 evaluation identity `…960c965`；Flash/Plus/Max product-v27 snapshot
分别为 `a0e159…128394`、`e0ed97…b6a94c`、`8c083d…a77624`。

Flash 的首个 v27 lifecycle `7cf9709` PROPOSED → `ac63bc9` OPEN → `d1c076e` CLOSED 在 Provider 前以
`DOCUMENT_VISION_DISABLED` 完成：独立 verifier PASS，但 attempts/tokens/latency 全为 0，Goal/guard 与 324 个
reservations 不变，因此不计 live smoke。没有重开 ledger；当前 revision 的 pinned RapidOCR 3.9.2、OpenVINO
2026.0.0 与三份模型 digest 经 `.sdlc/evidence/20260811-115701-document-vision` 重验，synthetic case 为 19 行。

replacement Flash v27b 按 `6185570` → `ea9cb87` → `a473d2f` 完成。唯一 wrapper 185.983 秒；最终交叉独立
verifier A2 PASS：5 attempts、20,110 input + 22,803 output、174,008 ms、payload scan PASS。五次均在 OBSERVE
停止，三次 region-kind enum、两次 parent-kind；实际结构全为 0，source-ancestor telemetry 未命中。

Plus v27 按 `ccfce3b` → `7f49117` → `854d652` 完成。唯一 wrapper 55.463 秒；A2 PASS：3 attempts、
14,618 input + 2,379 output、43,867 ms。OBSERVE、HIERARCHY、ELEMENT_BINDING 均 accepted，只命中一次既有
cardinality-derived 计数；最终 group/entity/relationship matched 为 1/2/1，slot/binding matched 均为 0，
7 blockers、4 critical hallucinations，source-ancestor telemetry 未命中。

Plus 已建立同版本三阶段可达性后，Max v27 才按 `1fa1ccf` → `705ccff` → `95be8fa` 执行。唯一 wrapper
130.128 秒；A2 PASS：3 attempts、20,645 input + 6,258 output、118,138 ms。三阶段 accepted、既有
cardinality-derived 计数为 2，但 slot/binding 仍 0 matched，26 blockers、27 critical hallucinations；新增
telemetry 仍未命中。该质量比 Plus 更差，不扩大到 final 20/60。

最终三模型交叉 verifier 全部 PASS、0 abandoned、payload scan PASS；Goal 为 335 reservations（330 SETTLED、
5 个历史 Plus RESERVED、0 BREACHED）：Flash 100 attempts / 641,256 tokens / ¥0.302686，Plus 153 /
900,566 / ¥3.484570，Max 82 / 491,919 / ¥10.289316。三份 ledger 均 `CLOSED`；clean fast 首次因隔离
worktree 缺 `node_modules` 留下环境红灯 `20260811-121310-fast`，联接既有 pinned dependencies 后
`20260811-121335-fast` PASS。

结论仍是 `EXPERIMENTAL`：v27 本地合同可达且三模型 live 三阶段可达，但新增 normalization 没有获得 live
命中，单 case 质量不满足晋级门。没有 final 20/60、final revision full、final independent verifier 或用户
业务/视觉 J1，因此不得报告 Goal 完成或 Profile accepted。

## N7 v28 最小实体区域 ownership 增量

v27 的 payload-free 结果证明三阶段可达并不等于字段归属正确：一个 entity 同时拥有祖先与后代 region，或非根
entity 直接拥有 ROOT region 时，既有 binding 最近 owner 规则可能在错误的 hierarchy ownership 上得到形式上
唯一的答案。`76a0635` 因而新增两个 opt-in policy，只供新版本使用：HIERARCHY 拒绝非根 entity 拥有 ROOT，
也拒绝同一 entity 同时拥有严格祖先/后代 region；ELEMENT_BINDING 只接受覆盖字段 region 的唯一最小空间
entity owner。零候选或多个同等最小候选继续 fail-closed，旧 Profile 的 verifier 顺序与语义不变。

`a96fec1` 发布 pipeline 4.15 与 Flash/Plus/Max 三份 immutable product-v28 Profile，并把上述 policy 接入 worker、
checkpoint 与独立 Profile snapshot verifier。binding 阶段发现多个同等最小 owner 时使用固定码
`VISUAL_SEMANTIC_HIERARCHY_BINDING_OWNER_AMBIGUOUS`，其最早可修阶段是 HIERARCHY；因为只重做 BINDING 无法改变
entity-region ownership。持久层只允许 `REJECTED ELEMENT_BINDING` 且问题集精确为该单码时回到 HIERARCHY，
保留已验证 OBSERVE inventory/grounding 并清空 hierarchy/binding；其他逆向 stage transition 仍拒绝。

定向验证包括 verifier/codec 27/27、inference 180/180、真实 PostgreSQL 两条 tracer 与独立 snapshot 1/1；其中
一条证明冗余 ownership 只重做 HIERARCHY，另一条以精确 5 次 scripted stage call 从 BINDING ambiguity 回到
HIERARCHY 并最终到达 `REVIEW_REQUIRED`。`6a8a36f` 在监控与审核 UI 显示三个固定码、中文解释与最早
HIERARCHY 修复阶段；Node 24 Web gate 73/73、typecheck/lint/build PASS，evidence 为
`.sdlc/evidence/20260811-125512-web`，隔离端口的 1024 keyboard/axe/payload-free Playwright 场景 1/1 PASS。

本增量只消费已验证 region graph、entity ownership 与 field evidence，不读取模型原文、OCR、图片、完整 prompt、
Candidate 或 gold，不按距离/reading order 排名，也不补造或删除 topology。实现与门控至此 Provider attempts=0，
三份 ledger 仍 CLOSED，累计预算仍为 Flash 641,256、Plus 900,566、Max 491,919 / 各 1,500,000 tokens。
product-v28 继续隐藏 `EXPERIMENTAL`；完成 clean full、fresh `/2` identity/Profile snapshot 与全部 pre-live 硬门前
不创建 OPEN ledger。

## N7 v28 clean full、受控 smoke 与停止决定

revision `0a3b90b` 在 detached clean worktree 完成 full gate 9/9，`workingTreeDirty=false`，A1 evidence 为
`.sdlc/evidence/20260811-125916-full`；同 revision 的 pinned Document Vision 19-line canary evidence 为
`.sdlc/evidence/20260811-130940-document-vision`。Java 与独立 Python 对 LF clean tree 一致得到 Git-blob
evaluation identity `renderweave-visual-evaluation-tree-sha256/2:c97c0ea01d6f9096b25a11f8faf2b6b45b7f55ad8f2336916379af98c669d172`；
Flash/Plus/Max product-v28 snapshot 分别为 `85444b…1b3026`、`90057d…0b2`、`2470b5…9644`。

Flash 首个 lifecycle `54b4e9d` → `7a4778d` → `61088f4` 在 CRLF checkout 因 corpus resource bytes 漂移于
Provider/evidence/Goal 之前失败并 CLOSED；Git-blob `/2` identity 本身未漂移。没有重开该 authorization，而是
在 LF clean worktree 以 replacement `701f774` → `10789d8` → `c39672f` 完成。独立 verifier A2 PASS：
5 attempts、20,112 input + 24,223 output、196,049 ms、payload scan PASS。五次 OBSERVE 均被 enum/parent-kind
合同拒绝，未形成结构，v28 ownership telemetry 无机会命中。

Plus 首个 v28 lifecycle `0f8a0dd` → `6668708` → `4215a14` 的 wrapper 配置把 Document Vision timeout 设为
120 秒，超过实现的 1..60 秒合同，因此以固定码 `DOCUMENT_VISION_TIMEOUT_INVALID` 在 Provider 前完成；A2 PASS、
0 attempts/tokens/latency，Goal 不变。该历史授权永久 CLOSED。确认合法 60 秒边界后，独立 replacement v28b
按 `2a69291` → `fcd3abc` → `4add041` 完成；A2 PASS：5 attempts、27,680 input + 8,524 output、151,360 ms、
payload scan PASS。OBSERVE 首次因 evidence-outside-region 拒绝、第二次 accepted；随后三次 HIERARCHY 均以
`VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CARDINALITY_INVALID` fail-closed，未进入 BINDING。质量标量仅
slot 1/10 matched、group 0/3、entity/relationship/binding 0，tree edit 20/20，final Candidate 未形成。

因此 v28 没有建立同版本 accepted OBSERVE→HIERARCHY→BINDING，更没有满足质量/J1 门；Max 保持 CLOSED、没有
调用。最终 Goal 为 345 reservations（340 SETTLED、5 个历史 Plus RESERVED、0 BREACHED）：Flash slot
105 attempts / 685,591 tokens / ¥0.326091，Plus 158 / 936,770 / ¥3.608122，Max 82 / 491,919 / ¥10.289316；
每槽仍受 180 attempts、1,500,000 tokens 与既有 CNY 上限约束。product-v28 继续 `EXPERIMENTAL`，N6 继续
`automated_verified`，N7 继续 `in_progress`；下一假设必须先离线解决 HIERARCHY 同码重复失败并通过真实
PostgreSQL/受影响 gate，不能放宽 cardinality 合同或直接扩大 final eval。

## N7 product-v29：把 group-region 基数矛盾前移到 OBSERVE

v28 Plus 的 payload-free evidence 显示 OBSERVE 已接受、HIERARCHY 连续三次以
`VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CARDINALITY_INVALID` 拒绝。现有 OBSERVE verifier 只从
REPEATED_GROUP 向 MANY GROUP 检查 ownership；它没有阻止 MANY GROUP 只拥有 singular GROUP。后者会让
HIERARCHY 从 GROUP 推导 MANY，却无法在已验证 region forest 中找到兼容关系区域。

`70da862` 因此增加 opt-in 的双向 group-region cardinality policy：每个 MANY GROUP 必须拥有至少一个
REPEATED_GROUP，每个 REPEATED_GROUP 仍必须至少有一个 MANY GROUP owner；singular GROUP 只能拥有 singular
container。失败继续使用既有固定码并固定为最早 OBSERVE，legacy policy 不变。该判断只消费已验证 element/
region graph，不读取 OCR、模型文字、图片、完整 prompt/Candidate 或 gold，也不补造 repetition。

`dd920cc` 以 pipeline 4.16、visual-elements prompt v9 和三份 immutable product-v29 Profile 接入 worker、
checkpoint 与独立 snapshot verifier。真实 PostgreSQL tracer 证明四次 bounded stage call 可沿 OBSERVE rejected→
OBSERVE accepted→HIERARCHY→BINDING 到达 `REVIEW_REQUIRED`；重试丢弃未验证 OBSERVE plan、复用一次本地
Document Vision 结果，OCR sentinel 不进入 checkpoint。`70e0f2c` 同步监控中文解释、组件测试与 Playwright。

detached clean `70e0f2c` 的 fast/server/web/inference-e2e 证据分别为 `20260811-135547`、`20260811-135605`、
`20260811-135807`、`20260811-140010`，全部 A1 PASS；Inference 182、App 213（6 gated skip）、Web 73，真实
replay→review→atomic Draft Apply 浏览器链路 1/1。本增量 Provider attempts=0，三份 ledger CLOSED，Goal
aggregate 与 345 reservations 不变。

该结果只证明 bounded verifier 与 stage-local recovery 可达，不证明真实模型会满足合同或质量阈值。
product-v29 继续 `EXPERIMENTAL`，N6 继续 `automated_verified`，N7 继续 `in_progress`。新的 live 前必须基于
包含本 ADR checkpoint 的 clean tree 重跑 full gate，并重新计算 `/2` identity、三份 Profile snapshot、1.5M
per-slot aggregate budget 与时限；仍从 Flash 单 case/最多 5 calls 开始。

## N7 product-v29：bounded live 结果

clean `c4f92b9` 的 full gate `20260811-140553` 9/9 PASS，Document Vision canary
`20260811-141657` 以精确 capability 处理 synthetic transit board 并得到 19 lines。Java 与独立 Python 对 clean
Git blob 重算得到 `/2:f45a9bc00047e562c349af884ee7b6918ba8025772c033e4fe8e3aac2f451e78`；Flash/Plus
product-v29 snapshot 分别为 `0f990982…f247a20`、`2e8d913c…41d559`。

首次 Flash lifecycle 为 `e4ca5a6` PROPOSED → `3b2a558` OPEN → `9fc0632` CLOSED。唯一 wrapper 在 Provider
前以 `DOCUMENT_VISION_DISABLED` 完成；根因是路径与模型虽已绑定，但 Spring runtime 的显式 enable flag 未传入。
该 CLOSED evidence 经独立 verifier 重建为 1 completed case、0 attempts、0 tokens、0 cost、payload scan PASS，Goal
state/hash 保持不变。它没有被重开或并发重跑；replacement 把
`RENDERWEAVE_DOCUMENT_VISION_ENABLED=true` 明确写入授权范围并重新走完整生命周期。

Flash v29b lifecycle 为 `a2c82e6` PROPOSED → `f40a6ad` OPEN → `9454422` CLOSED。PROPOSED 负探针零写入；
唯一 wrapper 5 次均停在 OBSERVE，独立 verifier PASS：20,596 input + 22,607 output tokens、¥0.022207、
159,460 ms、0 abandoned、payload scan PASS。固定码依次为
`VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND`、同码、`VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID`、
`VISUAL_GROUNDING_PARENT_KIND_INVALID`、再次 enum invalid。

Flash 没有越过 OBSERVE，但 Plus product-v28 曾到 HIERARCHY，因此 Plus v29 仍有诊断价值。其 lifecycle 为
`f98bfd5` PROPOSED → `e256e53` OPEN → `f443d86` CLOSED；授权成本上界按剩余额度收窄到 ¥0.18。唯一 wrapper
完成 3 次 OBSERVE，独立 verifier PASS：12,347 input + 8,653 output tokens、¥0.093918、152,058 ms、0
abandoned、payload scan PASS；固定码为 `VISUAL_GROUNDING_SIBLING_OVERLAP`、
`VISUAL_GROUNDING_ELEMENT_EVIDENCE_OUTSIDE_REGION` 两次，随后下一次预留被
`PROVIDER_COST_BUDGET_EXHAUSTED` fail-closed。Flash/Plus 的 CLOSED 后置探针均精确 `NOT_OPEN`，Goal
state/guard、evidence tree 与 reservations 零漂移，无残留 Maven/OCR/live 进程。

最终 Goal 为 353 reservations（348 SETTLED、5 个历史 Plus RESERVED、0 BREACHED）：Flash slot 110 attempts /
728,794 tokens / ¥0.348298，Plus 161 / 957,770 / ¥3.702040，Max 82 / 491,919 / ¥10.289316。v29 没有同
版本 accepted OBSERVE/HIERARCHY/BINDING，Max 门槛不成立，保持 CLOSED、未调用。该结果证明治理与恢复链路
fail-closed，但不证明质量；product-v29 继续 `EXPERIMENTAL`，N6=`automated_verified`，N7=`in_progress`。
下一 bounded 假设只能离线处理 OBSERVE enum、sibling overlap 与 evidence-region 归属，不得放宽 verifier、读取
Provider 原文或直接扩大 final eval。

## N7 product-v30：唯一最具体 evidence owner 归一化

Plus product-v29 的 payload-free 结果两次命中
`VISUAL_GROUNDING_ELEMENT_EVIDENCE_OUTSIDE_REGION`。该错误与 enum/overlap 不同：当 region forest 已通过形状、
父子包含、kind、reading order 与 artifact coverage 校验，element evidence 也已 canonicalize 到原图坐标时，是否有
唯一空间 owner 可由本地代码确定，无需查看模型原文或 OCR 文字。

决策如下：

1. 新 policy 仅在 pipeline 4.17/product-v30 opt-in；4.16 及更早 Profile 保持原行为。现有 owner 只要覆盖至少
   一块该 element evidence 就保留。对每块未覆盖 evidence，只考虑包含它的非 ROOT region，并取没有更深兼容
   descendant 的候选。
2. SLOT 可使用任意非 ROOT region；ONE GROUP 只兼容 GROUP；MANY GROUP 只兼容 REPEATED_GROUP。这不是按距离
   排名，也不创建 region、GROUP 或 topology。
3. inventory 与 ownership ID 不一致、owner 引用未知 region、候选为零或多个、归一化结果为空或超过 8 owners 时，
   整个 plan 原子回退为原输入，由既有 `VISUAL_GROUNDING_ELEMENT_*` 固定码 fail-closed。不得部分提交。
4. 成功时只记录数量型 `VISUAL_GROUNDING_ELEMENT_REGION_NORMALIZED`；checkpoint、attempt 与 UI 均不记录完整
   prompt、模型输出、OCR、图片或 Candidate。rejected OBSERVE 没有可信 plan，因此本决策不从其 region 选择 crop。
5. 三份 immutable product-v30 Profile 继续使用 visual-elements v9、hierarchy v7、bindings v3 与同一 pinned
   Document Vision capability；均为 `EXPERIMENTAL`，不加入默认 product-live selector。

`71ccbdf` 完成 codec 合同，完整 inference 183/183 PASS。`d3fedf3` 接入 worker/Profile/独立 Python snapshot
verifier；真实 PostgreSQL tracer 以三次 provider stage（OBSERVE、HIERARCHY、ELEMENT_BINDING）到达
`REVIEW_REQUIRED`，Document Vision 仅一次，OCR sentinel 未进入 checkpoint/Candidate。`837c015` 接入
monitor/review 中文说明。Node 24 Web gate `20260811-150327-web` 为 14 files/73 tests + build PASS；真实 replay
浏览器 Apply `20260811-150428-inference-v30-ui` 1/1 PASS；1024px diagnostics Playwright
`20260811-150534-v30-diagnostics-e2e-results` 1/1 PASS。两次 UI 预检红灯分别来自已有 4173 端口占用与新增第二个
“区域树”后旧 strict locator 不唯一，均未触发 Provider；改用空闲端口/集合 locator 后通过。

本增量 Provider attempts=0，Goal 仍为 353 reservations（348 SETTLED、5 历史 Plus RESERVED、0 BREACHED）；
Flash/Plus/Max 仍为 110/161/82 attempts、728,794/957,770/491,919 exposed tokens 与
¥0.348298/¥3.702040/¥10.289316，三 ledger CLOSED。product-v30 仍 `EXPERIMENTAL`，N6 仍
`automated_verified`，N7/Goal 仍 `in_progress`。只有包含本节的 clean revision 通过 full、fresh `/2` identity、
三份 snapshot、aggregate budget/time 与 exact J1 后，才可优先做 Flash 单 case/最多 5 calls；Max 仍受同版本
accepted OBSERVE/HIERARCHY/BINDING 及质量门约束。

## N7 product-v30：bounded live 结果

clean `e5d1977` 的 full gate `20260811-150901` 9/9 PASS，Document Vision canary
`20260811-151430` 使用冻结 capability 得到 19 lines。ledger-only lifecycle 不进入 evaluation identity，Java
重算 `/2:640ada0dfd69700da79b213815386b6cdebb1ed9fc26143ffe4447cf5b28c8af`；Flash/Plus v30
snapshot 分别为 `e11a708f…77a3e` 与 `ce966122…8552f`。所有输入仍是 repository synthetic。

Flash 按 `ff3e5a4` PROPOSED → `4180ef8` OPEN → `5f99083` CLOSED；PROPOSED 负探针精确 NOT_OPEN，唯一
wrapper 176,686 ms。独立 verifier A2 重建 5 SETTLED attempts、20,621 input + 22,325 output、¥0.021989、
163,491 provider ms、0 abandoned、payload scan PASS。五次全部停在 OBSERVE，固定码为 parent-kind、
non-repeated cardinality、region-kind 两次与 parent-containment；没有 accepted plan，也没有 normalization
telemetry。

Flash 没有给出 v30 新信号，但 Plus v29 的 evidence-outside-region 是本 policy 的直接来源，且用户重新允许
Plus，因此执行一个更窄的 Plus smoke：`d82563f` PROPOSED → `7a8eade` OPEN → `ec0a307` CLOSED，授权成本
上限仅 ¥0.10。负探针同样零写入；唯一 wrapper 74,883 ms。独立 verifier A2 重建 1 SETTLED attempt、4,104
input + 3,248 output、¥0.034192、57,698 provider ms、0 abandoned、payload scan PASS。该次在 OBSERVE 命中
`VISUAL_SEMANTIC_REPEATED_ITEM_FIELD_MISSING`，下一次预留前由 `PROVIDER_COST_BUDGET_EXHAUSTED`
fail-closed；同样没有 normalization telemetry。

两份 evidence 在最终 CLOSED revision 上交叉使用同一 Goal state 重放 PASS。Goal 最终为 359 reservations
（354 SETTLED、5 历史 Plus RESERVED、0 BREACHED）：Flash 115 attempts / 771,740 tokens / ¥0.370287，
Plus 162 / 965,122 / ¥3.736232，Max 82 / 491,919 / ¥10.289316。v30 未形成 accepted OBSERVE，更未形成
同版本 HIERARCHY/BINDING 或可审核 Candidate；Max 门失败、保持 CLOSED，final 20/60 不启动。该结果支持
fail-closed 治理与恢复合同，却没有支持质量晋级；product-v30 保持 `EXPERIMENTAL`，N6 保持
`automated_verified`，N7/Goal 保持 `in_progress`。下一决策只能从这些 payload-free fixed codes 形成新的
bounded 离线 verifier/repair/no-progress 假设，不能读取 Provider 原文、猜测 enum/parent 或补造结构。

## N6/N7 product-v31：重复 ITEM 字段 owner 的有界归一化

Plus v30 在完整 shape/grounding 前置合同之后以
`VISUAL_SEMANTIC_REPEATED_ITEM_FIELD_MISSING` fail-closed。一般 evidence-owner normalization 保留能覆盖 SLOT
evidence 的粗 REPEATED_GROUP owner 是正确的，但 semantic verifier 还要求每个 ITEM 至少拥有一个可见 SLOT。
当 region forest 与 canonical bounding boxes 已验证时，这个更窄的归属缺口可以由本地代码唯一判定。

决策如下：

1. 新 policy 仅由 pipeline 4.18/product-v31 opt-in，4.17/product-v30 及更早版本保持原行为。它只修改已有 SLOT
   的 owner，不创建元素、region、GROUP、关系或 Candidate。
2. 候选 SLOT 的每一块 canonical evidence 必须完全位于某个 ITEM region 内；每块 evidence 只能选择唯一最具体
   非 ROOT region。每个当前缺少可见 SLOT 的 ITEM 都必须被至少一块 eligible evidence 覆盖。
3. inventory/ownership ID 不全、unknown owner、root-only、零或多个最具体候选、缺任一 ITEM、空结果或超过 8
   owners 时，整个 plan 原子回退原输入，并由同一 `VISUAL_SEMANTIC_REPEATED_ITEM_FIELD_MISSING` fail-closed。
   不允许部分提交、按距离/gold 排名或修改 evidence/topology。
4. 成功只记录数量型 `VISUAL_GROUNDING_REPEATED_ITEM_SLOT_OWNER_NORMALIZED`。完整 prompt、模型输出、OCR、图片、
   Candidate 与 owner payload 均不进入常规 telemetry/evidence；rejected OBSERVE 仍不能提供 selected crop。
5. 三份 immutable product-v31 Profile 继续使用 visual-elements v9、hierarchy v7、bindings v3 与 pinned Document
   Vision capability；均为 `EXPERIMENTAL`，不加入默认 product-live selector。

`7e464df` 完成 codec 正反例，证明 v30 仍拒绝粗 owner、v31 只在完整唯一 evidence 条件下归一化。`791d4e9`
接入 worker/Profile/telemetry；真实 PostgreSQL tracer 严格执行 OBSERVE、HIERARCHY、ELEMENT_BINDING 三次 scripted
provider stage 并到达 `REVIEW_REQUIRED`，Document Vision 仅一次，OCR sentinel 未进入 checkpoint/Candidate/
problems。`f6cc529` 令独立 Python verifier 重算三份 v31 snapshot。`eea8b3f` 接入 monitor/review 中文说明与最早
OBSERVE repair scope。

验证为 inference 184/184、real-PG 1/1、independent snapshot verifier 1/1、Node 24 Web 14 files/73 tests +
build、1024px diagnostics Playwright 1/1、Axe serious/critical=0、payload sentinel=0；证据为
`.sdlc/evidence/20260811-155052-web` 与
`.sdlc/evidence/20260811-155200-v31-diagnostics-e2e-results`。首次浏览器预检发现 4173 被既有 Node 20
prototype 占用，测试在启动浏览器前退出；该用户进程未被终止，随后使用隔离空闲端口通过，且测试端口无残留。

本增量 Provider attempts=0；Goal 保持 359 reservations（354 SETTLED、5 历史 Plus RESERVED、0 BREACHED），
Flash/Plus/Max 保持 115/162/82 attempts、771,740/965,122/491,919 exposed tokens 与
¥0.370287/¥3.736232/¥10.289316，三 ledger CLOSED。最新用户 J1 将每模型累计 exposed-token cap 提到 1.5M 并
允许 Plus；180 attempts、既有 CNY 与时限边界不变。product-v31 仍 `EXPERIMENTAL`，N6 仍
`automated_verified`，N7/Goal 仍 `in_progress`。clean full、Document Vision、fresh identity/snapshot/aggregate
preflight 通过前不得 OPEN；Max 与 final 20/60 的三阶段、质量和 J1 门保持不变。

## N7 product-v31：bounded live 结果

exact code revision `e5b4994` 的 clean full `.sdlc/evidence/20260811-155539-full` 9/9 PASS，冻结 Document
Vision canary `.sdlc/evidence/20260811-160517-document-vision` 1/1、19 lines。ledger-only lifecycle 不进入
identity；Java 与独立 Python 重算 `/2:578c631edfa2948527013fc0c1831de2242891a2e87bc233376fb208f3a2c0f3`。
Flash/Plus/Max v31 snapshot 分别为 `c4a32c21…398b7`、`9cdbf6df…f8df3`、`c760ef14…edb8c`。每次 live
前均重新核对 clean revision、Profile、Goal、CNY/token/attempt、expiry、API 配置存在性、RapidOCR 路径、进程与
OS evidence lease；未读取或输出 Key 值。

Flash 按 `4ed323f` PROPOSED → `cbda25d` OPEN → `d2fd1cf` CLOSED；授权限于一个 repository synthetic case、
5 attempts / 60k tokens / ¥0.029。唯一 wrapper exit 0、256.3 秒；独立 verifier A2 重建 5 SETTLED attempts、
20,581 input + 23,195 output、¥0.022675、242,988 provider ms、0 abandoned、payload scan PASS。四次固定码为
`VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND`，一次为 `VISUAL_GROUNDING_ELEMENT_INVALID`，均停在 OBSERVE，
没有 normalization、HIERARCHY、BINDING 或 Candidate。

Plus 因 v30 repeated-item-field 信号具有直接诊断价值，按 `58d5530` PROPOSED → `adeac0d` OPEN → `d538638`
CLOSED；授权限于同一 synthetic case、5 attempts / 60k / ¥0.25。唯一 wrapper exit 0、111.6 秒；A2 重建
5 SETTLED attempts、29,630 input + 5,140 output、¥0.100380、98,363 provider ms、0 abandoned、payload scan
PASS。首个 OBSERVE accepted；其后四个 HIERARCHY attempt 均以
`VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY` fail-closed，未到 BINDING/Candidate。两模型的 PROPOSED
与 CLOSED 负探针均精确 `VISUAL_EVALUATION_AUTHORIZATION_NOT_OPEN`，Goal/guard/evidence 字节与 reservation
计数不变；所有调用完成后才读取 payload-free evidence。

最终 Goal 为 369 reservations（364 SETTLED、5 历史 Plus RESERVED、0 BREACHED）：Flash
120 attempts / 815,516 tokens / ¥0.392962，Plus 167 / 999,892 / ¥3.836612，Max
82 / 491,919 / ¥10.289316。两份 live evidence、Goal state、full 与 Document Vision evidence 已逐文件 SHA-256
同步回主 worktree，三份 ledger CLOSED，无 live/Maven/OS lease 残留。v31 只证明真实 fail-closed 与 OBSERVE
可达，不满足同版本 accepted HIERARCHY/BINDING；Max 门失败、未调用，final 20/60 不启动。product-v31 保持
`EXPERIMENTAL`，N6 保持 `automated_verified`，N7/Goal 保持 `in_progress`。下一安全切片只能围绕 payload-free
`RELATIONSHIP_SUPPORT_IDS_EMPTY` 建立本地唯一、bounded、no-progress 可证明的 HIERARCHY repair，不得读取
Provider 原文、补造关系或放宽 verifier。

## N6/N7 product-v32：空 relationship support 的唯一既有 owner 归一化

Plus v31 已接受 OBSERVE，并在同一 checkpoint 上四次稳定返回空的
`relationship.supportingElementIds`。relationship 本身、`regionId`、父子 entity ownership 与 OBSERVE 的
region/element inventory 都已通过各自合同，因此存在一个不依赖模型原文或 OCR 文字的、更窄本地可判定子集。

决策如下：

1. 新 policy 仅由 pipeline 4.19/product-v32 opt-in；pipeline 4.18/product-v31 及更早版本对空 support 继续返回
   `VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY`。null 继续返回 MISSING，二者不混淆。
2. 只允许消费 relationship 已声明的既有 region。该 region 必须已知且 kind 为 GROUP 或 REPEATED_GROUP，必须
   位于 parent entity region → relationship region → child entity region 的 ownership 连接上，并且恰好有一个
   kind=GROUP、multiplicity 与 region cardinality 兼容的既有 element owner。
3. unknown/non-container region、断连 ownership、零或多个 owner、ID/region 不完整及任何 validation failure 都
   原子保留空 support，并由原 fixed code fail-closed。不得创建或改写 relationship、entity、region、evidence、
   OCR/文字、selected crop 或 Candidate，也不得按 gold/距离/模型置信度排名。
4. 成功仍计入通用 `VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_OWNER_NORMALIZED`，并额外记录数量型
   `VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SUPPORT_OWNER_NORMALIZED`。两个计数均不携带 ID、owner 或 payload。
5. 三份 immutable product-v32 Profile 继承 v31 的 visual-elements v9、hierarchy v7、bindings v3、pinned Document
   Vision、semantic verifier、stage-local repair 与 deterministic materializer，继续为 `EXPERIMENTAL` 且不加入
   默认 product-live selector。

`212f468` 完成 codec 正反例与专项计数，旧 v31、ambiguous/non-container/disconnected/null 均保持原诊断；
`7e4e70c` 接入 pipeline/Profile/worker/独立 snapshot verifier；`b892503` 的真实 PostgreSQL tracer 仅调用一次
Document Vision，随后 OBSERVE→HIERARCHY→ELEMENT_BINDING 全部 accepted 并到达 `REVIEW_REQUIRED`，OCR sentinel
未进入 checkpoint/Candidate/problems；`7404c7a` 将专项计数接入 monitor/review 与 diagnostics E2E。

当前验证为 inference 185/185、Profile registry/capability 3/3、independent verifier 2/2、real-PG 1/1、Node 24
Web 73/73 + build、1024px Playwright 1/1、payload sentinel=0。本增量 Provider attempts=0，Goal 仍为 369
reservations，Flash/Plus/Max 累计用量仍为 120/167/82 attempts、815,516/999,892/491,919 tokens 与
¥0.392962/¥3.836612/¥10.289316；三 ledger CLOSED。product-v32 仍 `EXPERIMENTAL`，N6 仍
`automated_verified`，N7/Goal 仍 `in_progress`。在 clean full、冻结 Document Vision、fresh evaluation identity、
三份 Profile snapshot、aggregate token/attempt/CNY/time、API 配置存在性、进程与 evidence lease 全部通过前，
不得 OPEN live；Max 与 final 20/60 的三阶段、质量、独立复核和 J1 硬门不变。

## N7 product-v32 live 反馈与 product-v33 未知 relationship support 决策

exact `954792f` 在隔离 clean worktree 通过 full 9/9（`20260811-164653-full`）与冻结
Document Vision 19-line canary（`20260811-165243-document-vision`）。Java 与独立 Python 重算
evaluation identity 一致为
`renderweave-visual-evaluation-tree-sha256/2:d3057906b8f5523109725d95a85e5e414957763f159dac14084389fa0e452fca`；
Flash/Plus/Max product-v32 snapshot 依次为 `cadc8c7f…adfbb`、`1d839ca5…b86c7`、
`7a2e42e2…c5477`。

Flash 剩余费用上限已低于标准 OBSERVE reservation，因此保持 CLOSED。Plus 按
`54bc798` PROPOSED → NOT_OPEN 负探针 → `a94810c` OPEN → 唯一 wrapper → `5d71b3f`
CLOSED 完成一个 repository-synthetic case；独立 verifier 与 payload scan PASS，重建 3 个
SETTLED attempts、17,217 input + 4,099 output tokens、¥0.067226、73,882 provider ms、0 abandoned。
OBSERVE accepted；两次 HIERARCHY 均以 `VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN` 拒绝，
第四次调用在 Provider 前由 `PROVIDER_COST_BUDGET_EXHAUSTED` 阻断，未到 BINDING。旧
empty-support 修复未在 live 中命中，而该通用 fixed code 不能证明 unknown ID 必然属于
relationship，所以不把结果写成 v32 质量失败或 v33 有效性证明。

新决策只收窄该 fixed code 中可由已验证结构唯一证明的 relationship 子集：

1. pipeline 4.20/product-v33 显式 opt-in；v32 及更早 policy 字节与行为不变。
2. 一条 relationship 只允许出现一个未知 support local ID；其已知 `regionId` 必须为
   GROUP/REPEATED_GROUP，位于 parent→relationship→child ownership 连接上，且只有一个
   multiplicity-compatible 的已有 GROUP owner。
3. non-container、ambiguous、disconnected、多个不同 unknown ID 或任一前置失败时，原子
   保留原 plan 并用旧 fixed code fail-closed。不新建字段、关系、region、evidence、文字或
   Candidate，不从 rejected stage 生成 crop。
4. 成功只增加数量型 `VISUAL_HIERARCHY_RELATIONSHIP_UNKNOWN_SUPPORT_OWNER_NORMALIZED`，
   并保留通用 support-owner 计数；不记录 ID/owner/payload。
5. 本地成功只证明 deterministic bounded repair 可达，不证明 Provider 的 unknown 必属于
   relationship，不允许因此扩大 live 或放宽 semantic verifier。

`5951047`、`7ac4259`、`edd310d`、`94060a0` 分别完成 codec 合同、pipeline/Profile、真实
PostgreSQL tracer 与 monitor/review UI/E2E。当前已通过 contract 28/28、inference 186/186、
Profile/capability 3/3、independent snapshot verifier 2/2、real-PG 1/1、Web 73/73 + lint/typecheck/build 与
1024px Playwright 1/1。product-v33 继续 `EXPERIMENTAL`；N6 继续 `automated_verified`，N7/Goal
继续 `in_progress`，最终 revision 的 clean gates 与 final eval/J1 仍未完成。

## N7 product-v33：恢复费用后的 live 反馈与 OBSERVE parent-link 边界

guard v4 的新 J1 只恢复了 Flash/Plus 的费用入口，没有改变三阶段和质量门。exact-clean `15b5d00`
通过 full 9/9 与冻结 Document Vision；随后 Flash/Plus 各自严格执行 PROPOSED→NOT_OPEN 负探针→
OPEN→唯一 wrapper→CLOSED，CLOSED 后才读取 evidence。两份独立 verifier 均 PASS、0 abandoned、
payload scan PASS，未保存模型/OCR 原文、图片、完整 prompt 或 Candidate。

Flash 的 4 次 OBSERVE 分别以 region-kind enum×2、parent-containment、parent-invalid fail-closed；Plus
前四次为 sibling-overlap、element-evidence-outside-region×2、parent-kind，第五次才接受 OBSERVE，
但 5-call authorization 已耗尽，未调用 HIERARCHY/BINDING。v33 unknown-support normalization 因而没有
真实命中；这个结果不否定其本地合同，也不能声称质量改善。

新的安全决策边界是：enum 与 partial sibling overlap 仍不能从 fixed code 唯一映射；evidence-owner
已有 v30 bounded policy，歧义时继续 fail-closed。下一候选只能针对已有 region 的错误 parent link，
并且必须由同 artifact 的严格几何包含、允许的 kind/repeat-group 关系与唯一最具体既有 parent 同时证明；
零/多候选、ROOT、缺 parent、相等 box、循环风险、超过有界数量或任一全局 forest 校验失败时必须原子
保留原输入。不得创建 region/topology/evidence、读文字、按距离/gold 排名或从 rejected OBSERVE 选择 crop。
该候选在测试、版本化 Profile、真实 PostgreSQL 恢复、telemetry 与 UI/E2E 全部闭环前不构成新的 live 许可。

## N6/N7 product-v34：唯一既有 region parent 归一化

v33 的 Flash/Plus live 只通过 payload-free fixed code 暴露了 parent-invalid、parent-kind 与
parent-containment；enum、sibling overlap 和 evidence-owner 歧义仍没有唯一安全映射。`14e02b8` 因此只对
已有、非 ROOT region 的错误 parent link 启用新 policy：候选必须来自同一 artifact，严格几何包含 child，满足
SECTION/GROUP 或匹配 repeatGroupId 的 REPEATED_GROUP→ITEM 合同，并且是唯一最具体的既有 parent。ROOT、相等
box、零/多候选、循环风险、超过 8 个替换或完整 forest 重验失败均原子返回原输入；不创建 region/topology、
不读取文字、不按距离或 gold 排名，也不从 rejected OBSERVE 生成 crop。

`10f11b3` 以 pipeline 4.21 和三份 immutable product-v34 Profile 显式 opt-in，并只记录数量型
`VISUAL_GROUNDING_REGION_PARENT_NORMALIZED`。`029277a` 用回归锁定 v34 继续继承 v30 的 evidence-owner 与
v31 的 repeated-item SLOT-owner bounded repair，避免版本升级静默丢失既有安全能力。旧 Profile、Prompt 与
policy 字节不改写，v34 仍隐藏且为 `EXPERIMENTAL`。

`abb52a3` 的真实 PostgreSQL tracer 在 OBSERVE 接受一次唯一 parent 归一化后让 lease 过期，再由新 worker 从
HIERARCHY checkpoint 接管并完成 BINDING 到 `REVIEW_REQUIRED`。因为 OCR observation 按合同是 ephemeral、禁止
持久化，恢复时允许确定性 Document Vision 重算；Provider OBSERVE 严格只调用一次。专项 telemetry 仅出现在
OBSERVE，OCR sentinel 未进入 checkpoint、Candidate 或 problems。`de18000` 将 fixed code 与中文说明接入
monitor/review，并由 1024px keyboard/Axe Playwright 验证 payload 不展示。

当前离线证据为 contract 30/30、inference 188/188、独立 snapshot verifier 2/2、real-PG 整类 57/57、Node 24
Web 14 files/73 tests + build、Playwright 1/1；Web 与浏览器证据分别为
`.sdlc/evidence/20260811-190723-web` 和
`.sdlc/evidence/20260811-191314-v34-diagnostics-e2e-results`。本增量 Provider attempts=0，Goal 仍为
381 reservations，三份 visual ledger 均 CLOSED。它证明有界合同、恢复与审核面，不证明真实 v34 质量；N6
保持 `automated_verified`，N7/Goal 保持 `in_progress`，final 20/60、最终独立 verifier 与业务/视觉 J1 仍是硬门。

## N6/N7 product-v35：empty support 的唯一严格祖先 GROUP owner

v34 live 使边界更精确：Flash 5 次均在 OBSERVE fail-closed；Plus 首次 accepted OBSERVE 后，HIERARCHY
出现一次 `VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY` 与两次
`VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP`。后者不能安全重写；前者也只有在现有 region forest 与 entity
ownership 同时给出唯一证明时才能修复。

本 ADR 增补以下决策：

1. pipeline 4.22/product-v35 独立 opt-in，v34 及更早 policy、Prompt、Profile snapshot 不变。
2. empty `supportingElementIds` 先执行既有 exact relationship-region owner 规则；exact owner 为零时，
   只允许已知 relationship region 的严格祖先 GROUP/REPEATED_GROUP owner。候选必须唯一、基数兼容，且
   relationship region 位于 parent/child entity region 的连接上。
3. 成功把 support 设为已有 GROUP local ID，并把 relationship region 归一化为该 GROUP 的已有容器 region；
   不新建 element、region、relationship、entity、evidence、文字、crop 或 Candidate。
4. unknown support 不使用祖先规则；zero/many、same-region、non-ancestor、disconnected、cardinality mismatch
   或任一后续 semantic verifier 失败均保留原 fixed code，原子不改变输入。
5. 成功仅记录数量型
   `VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED`，同时保留通用 owner 与
   relationship-region normalized 计数；不记录 local ID、坐标或 payload。

`614359f` 完成正反例合同，`708522b` 发布 pipeline/Profile 与独立 snapshot verifier，`a2b8181` 用真实
PostgreSQL lease-expiry 场景证明 OBSERVE checkpoint 不重放、HIERARCHY/BINDING 到 `REVIEW_REQUIRED`、OCR
sentinel 零持久化，`5c59ce3` 将 code、中文说明、scope 与 earliest HIERARCHY repair 接入 monitor/review 和
1024px Playwright。contract 31/31、inference 189/189、snapshot verifier、real-PG、Web 73/73、Playwright
1/1 均通过。

这是 `automated_verified` 的 bounded semantic verifier 增量，不是 Provider 质量证明。v35 仍
`EXPERIMENTAL`；exact-clean full/Document Vision、v35 live、final eval、最终独立 verifier 与 J1 均属 N7。

## N7 product-v35：exact-clean Flash live 结果

`0e52ec7` 在独立 worktree 通过 full 9/9 与冻结 Document Vision 19-line canary；后者首次因 process
configuration 缺失 fail-closed，确认 0 Provider、子进程和 held lease 后才按精确路径重跑通过。Java 与独立
Python 对 `/2` identity 一致得到 `…e49d37`，三份 v35 Profile snapshot 也逐字节一致。

Flash 的 `d2c2c3d` PROPOSED、`b795f0a` OPEN、`a4298f3` CLOSED 两侧均有 NOT_OPEN 负探针；唯一
wrapper 164.057 秒，CLOSED 后才读取 evidence。独立 verifier PASS 且 payload scan PASS：5 attempts、
41,477 exposed tokens、¥0.020835、0 abandoned。全部仍在 OBSERVE fail-closed，固定码为 invalid region
kind 四次、parent kind 一次；没有 accepted OBSERVE，因而 empty-source-ancestor HIERARCHY policy 未被
真实调用触发，也没有 Candidate 或可用质量指标。

该结果否定“v35 已形成同版本三阶段证据”，不否定 bounded repair 的自动合同。Plus 只剩一个 Goal attempt，
不能验证三阶段；Max 与 final 20/60 不启动。下一安全工作仅可依据 payload-free fixed-code 设计新的 bounded
OBSERVE 修复，并继续保持 enum/拓扑歧义 fail-closed。product-v35 仍 `EXPERIMENTAL`，N6 仍
`automated_verified`，N7/Goal 仍 `in_progress`。

## N6/N7 product-v36：由合同唯一结构事实决定 region kind

v35 Flash 的四次 invalid-region-kind 与一次 parent-kind 只提供 fixed code，不提供原文或具体 alias。
因此不能建立通用 alias 表，也不能猜测 SECTION/GROUP。可安全收窄的仅是 region 自身 typed shape 已经
唯一排除其他 canonical kind 的情况。本 ADR 增补以下决定：

1. pipeline 4.23/product-v36 独立 opt-in；v35 及更早 policy、Prompt、Profile snapshot 与失败优先级不变。
2. `multiplicity=MANY` 且 `repeatGroupId` 非空时，合同唯一要求 `REPEATED_GROUP`；
   `multiplicity=ONE` 且 `repeatGroupId` 非空时，唯一要求 `ITEM`。
3. 只有 parent 为空、`multiplicity=ONE`、`repeatGroupId` 为空、恰有一个证据且其 canonical box 精确覆盖
   整个 artifact 时，才把 kind 归一化为 `ROOT`。这组条件任一缺失都不推断 ROOT。
4. SECTION/GROUP/non-repeat container 仍有多种合法解释；缺 repeat、零/多结构候选、非法 parent/children、
   重复组或完整 forest 校验失败均用既有 fixed code fail-closed。实现不创建/删除 region、edge、element、
   evidence、entity、relationship、crop 或 Candidate，不读取文字，也不按 gold/距离排名。
5. 成功只记录数量型 `VISUAL_GROUNDING_REGION_KIND_NORMALIZED`。合法但与唯一结构事实冲突的 kind 和
   未知 alias 走同一 bounded 分类；所有后续 `VisualGroundingPlan` 与 semantic verifier 仍完整执行。

`fdf7d44` 完成正反例与旧 policy 拒绝回归；`86b6074` 发布三模型 immutable Profile，并让 Java/独立
Python snapshot verifier 接受 pipeline 4.23；`2076684` 用真实 PostgreSQL lease-expiry 场景证明 OBSERVE
checkpoint 不重放、HIERARCHY/BINDING 到 `REVIEW_REQUIRED` 且 OCR sentinel 零持久化；`f395f90` 将
telemetry 接入 monitor/review 和 1024px payload-free E2E。

当前 inference 190/190、snapshot verifier 1/1、real-PG 1/1、Web 73/73、typecheck/lint 与 Playwright 1/1
通过。本机 Web 使用 Node 20，只能算兼容检查；Node 24、exact-clean full/Document Vision、fresh identity/
snapshot 与任何 live 均属于下一门。该增量为 `automated_verified`，product-v36 仍 `EXPERIMENTAL`；
N7/Goal 仍 `in_progress`。

### product-v36 Flash live 结论

exact-clean full `20260811-211447-full`、冻结 Document Vision `20260811-211916-document-vision`、
Java/Python identity `e2fb024c…89d2d` 与 Flash snapshot `cf32df27…a86a` 均通过。Flash 按
`5a6bfc4` PROPOSED → NOT_OPEN → `220de94` OPEN →唯一 wrapper→ `ab11a8b` CLOSED → NOT_OPEN
完成；两次负探针零写入，wrapper exit 0、171.790 秒，关闭后无残留进程或 lease。

独立 verifier/payload scan 重建为 1 case、0 abandoned、5 attempts、42,469 tokens、¥0.021607。
五次都在 OBSERVE fail-closed：invalid region-kind enum 三次、sibling overlap 一次、parent kind 一次。
因此本 ADR 的 bounded v36 classifier 通过工程合同与恢复验证，但尚未证明真实三阶段可达；不得由这些
fixed code 猜测模型 alias、region identity、坐标或 Candidate。product-v36 保持 `EXPERIMENTAL`，N6 仍为
`automated_verified`，N7/Goal 继续 `in_progress`；下一增量只能由 payload-free 结构分类与离线反例驱动。

## N6/N7 product-v37：GROUP owner 的唯一未满足类型约束

v36 的 invalid-region-kind 仍只提供 fixed code，因此 v37 不建立 alias 表。新增的唯一信息源是同一
OBSERVE document 内已经 typed 的 `GROUP` element 与其 region ownership。pipeline 4.24 只允许以下
约束传播：element 必须为 `GROUP/ONE`；其 distinct `regionIds` 必须全部引用现有 region；当前没有已经
兼容的 `GROUP/ONE/no-repeat` owner；且恰有一个 unresolved owner region 为 parent 非空、ONE、无 repeat。
只有这时，该 region 为满足现行 group-region/cardinality verifier 所唯一需要的 GROUP kind。

两个 unresolved owners、已有兼容 GROUP、MANY group、SLOT-only owner、缺失/重复引用、root/repeat shape
或任何其他未解析 kind 都原子 fail-closed。分类器不读取 unknown alias、OCR、displayName、evidence text 或
gold，不创建/删除 region/edge/element/evidence，也不改 parent、readingOrder、box 或 ownership；完整
`VisualGroundingPlan`、ownership consistency 和 semantic verifier 仍随后执行。v36 policy 保持 immutable。

`ebd0281` 完成正反例，`b5a4555` 发布三份 immutable product-v37 Profile，`007afe6` 用 real-PG
lease-expiry 证明 accepted OBSERVE 不重放并恢复到 `REVIEW_REQUIRED`，`e6682b4` 更新 monitor/review/E2E
解释。inference 191/191、snapshot verifier 2/2、v36/v37 recovery 2/2、Web 73/73、typecheck/lint 与
Playwright 1/1 PASS。本节点为 `automated_verified`，product-v37 仍 `EXPERIMENTAL`，N7/Goal 仍
`in_progress`。

### product-v37 首次 live：Document Vision 配置在 Provider 前 fail-closed

在 exact-clean full/Document Vision 与 fresh identity/Profile/Goal/J1/process/lease 门通过后，Flash 账本按
`99940ef` PROPOSED → NOT_OPEN → `045b5b9` OPEN →唯一 wrapper→ `c3223ee` CLOSED → NOT_OPEN
闭合。独立 verifier/payload scan PASS，但唯一 evaluation outcome 为
`DOCUMENT_VISION_ADAPTER_MISSING`：1 completed、0 abandoned、0 Provider attempts、0 tokens。

这是启动器把正式 `renderweave.inference.document-vision.adapter-script` 键误写成非合同键
`renderweave.inference.document-vision.adapter` 所致；本地预处理在网络调用前拒绝，未产生模型质量信号。
该授权不可重放。后续若在相同 product-v37 上重试，必须创建新的 immutable authorization，重新通过 clean
gate 和全部 fresh preflight，并显式验证正确 adapter-script binding；不得从这次零调用结果放宽 semantic
verifier、Plus/Max 或 final eval 门。product-v37 保持 `EXPERIMENTAL`，N7/Goal 保持 `in_progress`。

### product-v37b Flash live 结论

使用正确 adapter-script 的新授权按 `9204a49`→NOT_OPEN→`0960c9f`→唯一 wrapper→`4d8e48b`→
NOT_OPEN 完整关闭；clean full/Document Vision、fresh identity/snapshot/Goal/J1/process/lease 与独立
verifier/payload scan 全部通过。A2 重建为 5 attempts、42,691 tokens、¥0.021815、0 abandoned。

四次 `VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND` 和一次
`VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID` 使五次全部停在 OBSERVE。v37 的 constraint-unique GROUP
规则没有获得 live 命中，且 fixed code 仍不揭示 unknown alias、region identity 或坐标；不得据此添加通用
alias 或任意扩张 parent box。下一 bounded 设计只能结合已解析 forest/typed ownership 与 repository synthetic
反例证明唯一 containment 修复，否则保留原拒绝。product-v37 仍 `EXPERIMENTAL`，N6=
`automated_verified`、N7/Goal=`in_progress`；Plus/Max/final eval 门未成立。

## N6/N7 product-v38：错误 parent 链上的唯一包含 ROOT 祖先

v37b 只给出一次 `VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID`，没有暴露 region identity、坐标或模型
payload，因此 v38 不允许任意扩大 box、按距离挑 parent 或推断语义。pipeline 4.25 只在 v34 的常规唯一
最具体非 ROOT parent 搜索为零时增加一个结构上可证明的 fallback：child 必须不是 ROOT/ITEM；当前 parent
必须已知、非 self、与 child 同 artifact 且确实不包含 child；沿当前 parent 的既有 ancestor chain 必须无环、
引用完整，并恰好到达一个 parent=null、严格包含 child 的 ROOT。此时只能把 child 的 parent 指向该既有
ROOT，并对受影响 sibling reading order 做 canonical normalization，最多沿用既有 8 个替换上限。

missing parent、ITEM parent-kind 约束、artifact mismatch、cycle、equal/full box、零/多常规候选、任何非唯一
祖先状态，以及重建完整 `VisualGroundingPlan` 后的 topology/overlap/ownership/semantic failure，均原子返回
原 plan 并保留原 fixed code。实现不读取 text、alias、OCR、gold，不创建/删除 region、edge、element、
evidence、crop 或 Candidate。v37 policy 与三份 Profile 保持 immutable。

`632e641` 完成 codec 正反例，`b91637b` 发布三份 immutable product-v38 Profile 并把 pipeline 固定为
`renderweave-inference-pipeline/4.25`，`060dd47` 用真实 PostgreSQL 证明 OBSERVE checkpoint 后 lease
expiry 只继续 HIERARCHY/BINDING 到 `REVIEW_REQUIRED` 且 OCR sentinel 不持久化，`1504ac6` 更新
monitor/review 与 E2E 的 payload-free 解释。自动证据为 focused contract 34/34、Profile/独立 snapshot
verifier 37/37、real-PG v37/v38 2/2、inference 192/192、Web 73/73、typecheck/lint 与 Playwright 7/7。
本机 Web 使用 Node 20，只是兼容检查；正式 Node 24 由 exact-clean full gate 提供。

本节点 Provider attempts=0，Goal 仍为 405 reservations，Flash/Plus/Max 累计仍为 144/179/82 attempts、
1,022,730/1,087,500/491,919 tokens 与 ¥0.499453/¥4.159620/¥10.289316，三 ledger CLOSED。
因此它只把 N6 保持为 `automated_verified`，不构成 live 三阶段或质量证据；product-v38 仍
`EXPERIMENTAL`，N7/Goal 仍 `in_progress`。任何 v38 live 必须先在同一 clean revision 重跑 full/Document
Vision，并 fresh 绑定 evaluation identity、Profile snapshot、Goal/J1/time/process/lease。

### product-v38 Flash live 结论

exact-clean `3e44974` 已通过 full 9/9（`20260811-225452-full`）和冻结 Document Vision 19-line canary
（`20260811-225916-document-vision`）。Java 与独立 Python identity 均为
`renderweave-visual-evaluation-tree-sha256/2:fc334bc7dc28508a0202881eb9754f612f34542347627f471af9d6659558a524`，
Flash v38 canonical snapshot 为 `d91bc9681b4532a0f6edd7ed52e58a46daea42407cae9d87d37abda53c2c9412`。

授权按 `882c8ca` PROPOSED → NOT_OPEN → `19c726c` OPEN →唯一 wrapper→ `31109c4` CLOSED →
NOT_OPEN 闭合。wrapper exit 0、122.906 秒；独立 verifier/payload scan PASS，重建为 1 completed、
0 abandoned、5 attempts、40,797 exposed tokens、¥0.020282、110,782 ms。两侧负探针的 Goal/evidence
哈希均不变，结束后没有相关进程或 held lease。

五次均停在 OBSERVE：`VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND`×2、
`VISUAL_GROUNDING_READING_ORDER_GAP`×2、`VISUAL_GROUNDING_JSON_UNKNOWN_MEMBER`×1；v38 的
`VISUAL_GROUNDING_REGION_PARENT_NORMALIZED` 未命中。这不否定离线 bounded contract，但没有建立同版本
HIERARCHY/BINDING 或 Candidate 质量证据。unknown member 与 invalid enum 仍不允许读取 payload、建立 alias
或放宽严格 JSON；下一安全假设只可从 repository synthetic forest 证明 sibling reading order 的唯一
canonicalization，并保持不可唯一时 fail-closed。

Goal 更新为 410 reservations（405 SETTLED、5 历史 Plus RESERVED、0 BREACHED）；Flash/Plus/Max 为
149/179/82 attempts、1,063,527/1,087,500/491,919 tokens 和
¥0.519735/¥4.159620/¥10.289316，三 ledger CLOSED。product-v38 保持 `EXPERIMENTAL`，N6=
`automated_verified`、N7/Goal=`in_progress`；Plus/Max/final eval 不启动。

### product-v39 unique-order compaction 决策

v39 只把 `VISUAL_GROUNDING_READING_ORDER_GAP` 的一个可证明子集纳入 bounded verifier：只遍历具有
parent 的非 ROOT sibling set；既有 order 必须互异，按既有 order 的总序必须与 evidence 首框的
`top`、`left`、`regionId` canonical 顺序完全一致，而且当前序号必须非连续。最多改变 8 个 reading-order
值并压紧到 `0..n-1`；任何完整 `VisualGroundingPlan` 重建失败都原子返回原 plan。root gap、duplicate/tie、
反向或位置不一致、missing parent、cycle、topology/overlap/ownership failure 继续 fail-closed。

该规则不读取 alias/text/OCR/gold/model payload，不改变 box、parent、element ownership，不创建或删除
region/edge/element/evidence/crop/Candidate；strict JSON unknown-member 与 enum 合同也不变。成功只记数量型
`VISUAL_GROUNDING_READING_ORDER_NORMALIZED`。`a08f099` 完成 codec 正反例，`c99a4ac` 固定 pipeline
4.26 与三份 immutable product-v39 Profile，`80d0b73` 用真实 PostgreSQL 证明 OBSERVE checkpoint 后
lease 接管只继续 HIERARCHY/BINDING 且 OCR sentinel 零持久化，`d0ed4d3` 完成 monitor/review/E2E 解释。

自动证据为 focused contract 35/35、跨模块 Profile/独立 verifier 38/38、real-PG v39 1/1 与 v38/v39
pair 2/2、inference 193/193、Web 73/73、typecheck/lint、Playwright 7/7。Node 20 Web 只算兼容证据；
exact-clean Node 24 full 尚未执行。本节点 Provider=0、Goal 保持 410 reservations、三 ledger CLOSED。
因此 N6 仍为 `automated_verified`，product-v39 仍 `EXPERIMENTAL`，N7/Goal 仍 `in_progress`；live 三阶段、
质量、final eval、最终独立复核与业务/视觉 J1 均未由本决策满足。

### product-v39 Flash live 结论

exact-clean `0625a23` 已通过 full 9/9（`20260811-233119-full`）和冻结 Document Vision 19-line canary
（`20260811-233555-document-vision`）。Java/独立 Python identity 均为
`renderweave-visual-evaluation-tree-sha256/2:3abc7ebae12956a55ba6145f5157a448571f374d5f23344b730cb818ed52696d`，
Flash v39 canonical snapshot 为 `667db9b4238eb95bcbe69ec38625b48eb059d681ba7d01419487645d95d1a2bc`。

授权按 `37cc036` PROPOSED→NOT_OPEN→`1431233` OPEN→唯一 wrapper→`678ef2e` CLOSED→NOT_OPEN
闭合。wrapper exit 0、90.704 秒；独立 verifier/payload scan PASS，1 completed、0 abandoned、3 attempts、
8,220 input + 9,069 output actual tokens、¥0.008900 actual cost、76,442 ms。前两次 OBSERVE 分别为
invalid region-kind 与 reading-order gap；第三次网络错误无 actual usage，按既有 halt/recovery 合同保留
reservation。全程没有读取或持久化模型 payload，结束后 0 process/held lease。

v39 normalization 未命中，且 HIERARCHY/BINDING/Candidate 未触达，因此本 live 既不否定离线 contract，
也不建立质量改善证据。仅凭现有 fixed code 不能判断 gap 是 duplicate/tie、位置冲突还是其他不可唯一状态，
不得读取 payload 或放宽 strict enum/order。product-v39 保持 `EXPERIMENTAL`，N6=`automated_verified`、
N7/Goal=`in_progress`；Plus/Max/final eval 不启动。

### product-v40 fixed-code refinement 与冻结决策

决定把 v39 保留的 reading-order GAP 做 payload-free 诊断细分，而不继续扩大 repair。pipeline 4.27 仅在
root order 已连续、完整 plan 原本会报 GAP、且所有失败非 root sibling set 的原因一致时改变 fixed code：
duplicate/tie 统一为 `VISUAL_GROUNDING_READING_ORDER_DUPLICATE`，唯一 order 与 canonical position 冲突
统一为既有 `VISUAL_GROUNDING_READING_ORDER_POSITION_INVALID`。只要出现 root gap、mixed 原因或 canonical
超界 gap，就保留 GAP。该分类不改变任何 region/order/box/parent/ownership，也不创建结构。

`3b0d92d` 固定正反例与 v39 immutability；`b179b7e` 绑定 pipeline 4.27、Prompt 10 和三份 immutable
Profile；`05e1b65` 的 real-PG tracer 证明 OBSERVE checkpoint/lease recovery 与 OCR 零持久化；`3eff5ce`
完成审核 UI/E2E。自动证据为 contract 36/36、Profile/Prompt 20/20、独立 verifier 2/2、real-PG pair
2/2、inference 195/195、Web 73/73、typecheck/lint、Playwright 7/7。

用户决定 v40 验证后停止继续试探式放宽并冻结为阶段基线。阶段验收只主张工程可用：可运行、可恢复、
可审计、可人工审核；在同版本三阶段、质量、final eval、最终 J1 未满足时继续标记 `EXPERIMENTAL` /
`automated_verified`，不等价于生产晋级。

### product-v40 live 反馈与最终冻结

exact-clean `af2076a` 的 full 9/9（`20260812-001439-full`）与冻结 Document Vision 19-line canary
（`20260812-002223-document-vision`）通过。Java/独立 Python identity 均为
`renderweave-visual-evaluation-tree-sha256/2:902577dd1ac48b17fa96efd9a0c3f3a37c340f9a2af248548715e228a70abd63`；
三份 v40 Profile snapshot 与冻结值一致。

Flash 授权按 `0d38448` PROPOSED→NOT_OPEN→`5392aa1` OPEN→唯一 wrapper→`6e7f522` CLOSED→
NOT_OPEN 闭合。独立 verifier/payload scan PASS：1 completed、0 abandoned、5 SETTLED attempts、20,699
input + 22,605 output tokens、¥0.022226、171,157 ms。五次 OBSERVE 依次报告 invalid region-kind、sibling
overlap、parent-kind、generic reading-order gap、invalid region-kind；没有 HIERARCHY、BINDING 或 Candidate。
generic GAP 表明本次 shape 落在 v40 明确保留的 root/mixed/超界或其他不可唯一分类边界，不能据此新增 repair。

因此本 ADR 冻结 pipeline 4.27/Prompt 10/product-v40，不再以剩余额度追逐单次成功。工程可用性由 exact
full、真实 PostgreSQL recovery、Document Vision 与浏览器审核/Apply E2E 建立；真实模型识别成功率没有建立，
所以 product-v40 继续 `EXPERIMENTAL`、N6=`automated_verified`，N7/Goal 未完成。后续若解除冻结，必须使用
新版本、fresh identity/Profile/J1 与新的有界假设，不得改写本结论。
