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
  提高到 1,000,000 tokens，并重新允许 Plus；历史用量仍完整计入，没有重开或改写任何 CLOSED ledger。
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
