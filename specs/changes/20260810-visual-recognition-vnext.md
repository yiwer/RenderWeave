# Spec Delta：图片识别数据结构 vNext 质量升级

- 状态：approved
- 触发任务：P6/T6-5
- 触发证据：Product v2 真实站牌结果发生层级压扁；Product v3 真实运行虽完成 OBSERVE/HIERARCHY/ELEMENT_BINDING，但 STRUCTURE 两次超时；Product v4 尚无绑定当前流水线的真实分阶段质量报告
- 影响 AC/规则：AC-015、AC-016、AC-017、AC-019、AC-020、AC-021、AC-022；新增 AC-VR-001..010；ADR-0020、ADR-0021、ADR-0022、ADR-0023、ADR-0029
- 再锚定关系：用户于 2026-08-10 接受图片识别串行流程审查结论，并明确批准按推荐建立 Goal、自动采用决策并落 ADR；本 delta 成为后续实现和验收基准。

## 冲突或新事实

pipeline 3 已能确定性阻止计划内的 `route[] -> stop[]` 被最终 Candidate 再次压扁，但它只能证明
Candidate 忠于模型自己产生的视觉计划，不能证明第一阶段没有漏掉图片元素，也不能证明元素、区域、
层级和字段归属与图片空间关系一致。现有 `CRITIQUE` 只路由合同问题，不是重新观察图片的语义审查。

同时，STRUCTURE Prompt 要求模型逐字复制已验证的 entity、relationship、binding 和 evidence；除局部
UUID 与未校准 confidence 外，该步骤本质是确定性编译，却仍重复发送全部图片并消耗一次 Provider
调用。当前四个产品模型使用同一 JSON 请求策略，但官方 capability 并不完全相同。

## 变更

### ADDED

1. 新建不可变 `renderweave-inference-pipeline/4.0` 和对应 vNext Profile；历史 pipeline/Profile/run
   snapshot 保持可读、可恢复、不可修改。
2. 为 IMAGE_ONLY 建立不少于 60 个版本化 stage-gold bundles，45 DEV + 15 HOLDOUT，覆盖：
   - 中英混合、小字号、密集文本、旋转/缩放；
   - 一至三层实体、ONE/MANY、重复卡片/行/站点；
   - 公交站牌、菜单、价签、时刻表、海报、表单和通用信息板；
   - 多图片互补/重复、低信息、视觉歧义和 prompt injection。
3. 评测除最终 Candidate 指标外，新增 element/group precision/recall、region grounding IoU、entity/edge
   F1、binding accuracy、tree edit、stage survival、repair yield、置信度校准、Token/费用/延迟。
4. 增加确定性多尺度 view planner。overview、tile、targeted crop 均携带到原规范化图片 0..10000
   坐标的可逆 transform；模型 evidence 最终只能引用原 artifact 坐标。
5. 增加版本化 region/grounding 合同，至少表达 regionId、parentRegionId、kind、multiplicity、
   readingOrder、repeatGroupId、bbox、直接 evidence 和元素所属区域。必须确定性检查包含、重叠、顺序、
   重复组一致性和无环关系。
6. 增加 `DocumentVisionPreprocessor` 窄端口。OCR/layout 结果只作为本次 run 的受限观察输入；原始 OCR
   文本不得进入常规日志、评测 evidence 或长期 payload 存储。参考实现必须本地执行、默认零网络，
   缺能力时 vNext Profile 明确 unavailable，不静默退化成未标识的旧流程。
7. 通用 Prompt 与领域 Hint Pack 分离。默认 `GENERIC`；首个可选包为 `TRANSIT_BOARD`。领域包必须
   版本化、显式选择且参与 Profile/evaluation identity，不能把站牌术语注入所有图片。
8. 增加只读图片语义 verifier：仅返回有限 issue code、受影响 region/entity/element ID 和 evidence，
   不直接改 Candidate。repair 只重做被 issue 定位的最早错误阶段和必要 crop，不全图盲目重建。
9. 增加跨 authorization 的 Goal 总预算守卫。baseline、ablation、final certification 即使使用不同
   evaluation identity，也共享同一模型 token/费用上限，不能通过新建 ledger 重置额度。

### MODIFIED

1. pipeline 4 的 STRUCTURE 改为本地确定性 Candidate materialization，不调用 Provider。所有 UUID、
   Schema/field/reference/array、source、evidence 和保守 assessment 均由已验证计划生成。
2. baseline Provider 调用从四次降为三次；额外调用预算优先给语义 verifier 或单个定向 repair，不能
   重新用于生成式 Candidate 编译。
3. OCR/layout 是否进入最终默认路径由版本化消融门决定：在 dense/small-text slice 上 field recall
   绝对提升至少 0.05、critical hallucination 不增加且成本/延迟有完整记录时启用；否则保持可插拔但
   不成为默认生产依赖。
4. 产品 vNext 当前评测模型目录只包含经 capability contract 验证的 `qwen3.8-max`、`qwen3.7-plus`、
   `qwen3.7-flash-2026-07-15`。历史 `qwen3.7-flash` Profile/ledger 保持不可变，但不再用于新的 N7
   assignment。不支持结构化输出或未完成 canary 的模型不得复用同一请求模板进入目录。
5. JSON Object 仍只被视为语法边界；所有中间合同继续严格 decode/validate。输出 token 设置必须按
   stage 评测，任何截断都以稳定 taxonomy 失败，不能交给宽松 JSON repair 猜测。
6. 审核/监控页展示新的 perception/region/verifier/targeted-repair 阶段、有限 issue code 和 stage-level
   指标，但不显示原始 OCR 文本、Provider response 或 chain-of-thought。

### REMOVED

- pipeline 4 不再调用 Provider 生成已被视觉计划完全决定的 Candidate。
- 通用 Prompt 不再硬编码 station/route/stop/notice/fare 等公交领域规则。
- 不再把合同合法或计划自洽等同于图片语义正确。

## 新增验收标准

| AC | 可观察行为 | 最低证据 |
|---|---|---|
| AC-VR-001 | 60 个 IMAGE_ONLY stage-gold bundles 可确定性生成/加载，DEV/HOLDOUT、领域与难度切片固定 | A1；final A2 verifier |
| AC-VR-002 | baseline/ablation/final 的 identity、assignment、attempt、token、费用可恢复且跨账本总预算不可绕过 | A2 + J1 |
| AC-VR-003 | 同一 validated plan 只产生一个 byte-stable 语义 Candidate；STRUCTURE provider attempts=0 | A1/A2 |
| AC-VR-004 | region containment、reading order、repeat group、element ownership 与原图坐标可确定性验证 | A1/A2 |
| AC-VR-005 | OCR/layout 原始文本不进入常规日志/evidence；adapter 缺失时 fail-readable、零网络降级 | A1/A2 |
| AC-VR-006 | GENERIC 不携带公交偏置；TRANSIT_BOARD 只在显式选择时生效且不能修改事实边界 | A1 + live slice |
| AC-VR-007 | verifier 只能报告 bounded issue；targeted repair 不重做无关成功 stage | A1/A2 |
| AC-VR-008 | 三个模型各有独立 capability/profile/pricing/timeout/output contract，不静默降级模型 | A1 + canary J1 |
| AC-VR-009 | 监控和审核页可读展示 stage、区域、问题、费用和恢复状态，1024/1280/1440 操作完整 | A1 + J1 UX |
| AC-VR-010 | 最终最佳 Profile 满足既有 AC-021 门槛；未达标模型保持 EXPERIMENTAL 且不成为默认 | A2 + policy J1 |

## Live J1 授权信封

用户于 2026-08-10 批准本 Goal 内真实 DashScope 调用，并要求普通权限/决策不阻塞。执行仍主动收窄为：

- 模型：`qwen3.8-max`、`qwen3.7-plus`、`qwen3.7-flash`；不得替换或追加其他模型；
- 每模型 baseline + ablation + final 合计输入与输出最多 500,000 tokens；
- 每模型最多 180 attempts、每批最多 5 cases；
- 仅仓库自制、合成或 CC0 数据，不发送客户/真实业务数据；
- 费用硬上限按当前最坏输出单价收窄为 Max ¥18、Plus ¥4、Flash ¥0.40；免费额度只会减少实际费用，
  不扩大 token/attempt 上限；
- 每个 OPEN ledger 最长 168 小时，并在对应批次完成、Goal 完成、预算耗尽、Provider 拒绝或异常时立即
  CLOSED；跨 ledger Goal guard 继续累计；
- ledger 在调用前必须绑定 exact immutable Profile、prompt、corpus、evaluator、workflow/build identity；
- 常规证据 payload-free，不保存图片、OCR 原文、Prompt、Provider response/request ID 或 Candidate 原文。

本信封不是立即 OPEN 的 ledger。只有实现树和 evaluation identity 冻结、负探针与受影响 gate 通过后，
才由受跟踪的精确 ledger 打开；不满足前置时 Provider attempts 必须为 0。

### 2026-08-11 J1 Delta：Pinned Flash 与追加 Goal Token

用户 yiwer 于 2026-08-11 明确要求把本 Goal 的 Flash 模型从浮动 `qwen3.7-flash` 改为精确快照
`qwen3.7-flash-2026-07-15`，Plus 与 Max 的模型 ID 不变，并给三个模型预算槽位各追加 500,000 total
tokens。该 delta 只覆盖原信封的 Flash 模型身份和 Goal token cap：

- 三个预算槽位的新累计上限均为 1,000,000 tokens；历史用量继续计入，不能因 Profile、ledger 或 Flash
  精确模型 ID 变化而重置；`qwen3.7-flash` 与 `qwen3.7-flash-2026-07-15` 共享同一 Flash 槽位；
- attempts 上限仍为每槽位 180，费用硬上限仍为 Max ¥18、Plus ¥4、Flash ¥0.40；本 delta 没有追加费用；
- 每批最多 5 cases、每个 OPEN ledger 最长 168 小时、repository-synthetic/CC0-only、payload-free evidence、
  exact identity 与立即 CLOSED 条件全部不变；
- 新 Flash 必须使用新的 immutable capability/Profile snapshot。官方资料确认该精确快照支持图像输入、
  JSON structured output、可关闭 thinking、1M context 与 64K advertised output；产品 Profile 仍收窄为
  10 images、8,192 output tokens、thinking/tools/remote media 全关；
- 新假设先以 Flash 单 case canary 证明 OBSERVE→HIERARCHY→ELEMENT_BINDING 三阶段合同可达。只有该门、
  exact J1、费用、次数和时限仍满足时才进入 Max；Plus 可重新参与 N7，但仍受相同阶段与预算停止条件。

### 2026-08-11 J1 Delta 2：再次追加 Goal Token

用户 yiwer 于 2026-08-11 继续给三个稳定模型预算槽位各授权 500,000 exposed tokens。该授权是前一 delta
之上的增量，不重置任何历史用量：

- `qwen3.7-flash-2026-07-15`、`qwen3.7-plus`、`qwen3.8-max` 三个稳定槽位的累计上限各为
  1,500,000 tokens；旧 Flash alias 仍与 pinned Flash 共用同一槽位；
- 每个独立 authorization 仍最多 500,000 tokens；每槽位仍最多 180 attempts，费用硬上限仍为 Flash ¥0.40、
  Plus ¥4、Max ¥18，没有追加费用；
- 每批最多 5 cases、每个 OPEN ledger 最长 168 小时、repository-synthetic/CC0-only、payload-free evidence、
  exact identity/Profile snapshot、单 wrapper 与立即 CLOSED 条件全部不变；
- Plus 继续允许调用；是否调用任一模型仍由 fresh Profile/identity、当前累计 attempts/token/CNY、阶段可达性与
  N7 停止条件共同约束，增加 token cap 不等于放宽质量门或费用门。

官方 capability/pricing 事实源：

- `https://platform.qianwenai.com/docs/developer-guides/getting-started/vision-models`
- `https://platform.qianwenai.com/docs/developer-guides/text-generation/thinking`
- `https://www.qianwenai.com/models/qwen3.7-flash-2026-07-15`

## 影响面

- 用户价值/范围：提升复杂、密集、重复和多层视觉数据定义的召回与可解释性；仍不进入 Template、
  Workspace 或渲染。
- 实现与数据：新增 eval corpus/harness、view/region/grounding contract、OCR/layout port、local materializer、
  verifier、Prompt/Profile、可能的 forward-only migration 和 Web stage 展示。
- 验证与发布：先冻结旧 v4 小规模 baseline，再以同一 stage gold 做消融和 vNext final；旧质量结果不继承。
- DAG/预算：按 `plans/renderweave-visual-recognition-vnext-plan.md` 串行执行，节点间独立 commit。
- 恢复影响：源码使用节点 commit revert；数据库只用 forward migration/补偿；历史 run/profile/checkpoint
  保持只读；已发生模型费用不可恢复，只能原子关闭 ledger 并阻止后续调用。

## 决策

- 批准人：yiwer
- 日期：2026-08-10
- 结论与理由：采用审查推荐的“先度量、再替换编译、再增强感知、最后认证”路径；普通实现取舍由 Agent
  选择并写 ADR，不逐项等待批准；真实调用严格受原信封及 2026-08-11 后续 J1 delta 的三槽位累计
  1.5M-token cap、单 authorization 500k cap、每槽 180 attempts、Flash/Plus ¥10 与 Max ¥18 Goal cost cap、
  固定时限和精确账本约束。

## 2026-08-11 Flash/Plus 费用与 24h J1 delta

- 批准人：yiwer；批准对象：同一 vNext Goal 的 `qwen3.7-flash-2026-07-15` 与
  `qwen3.7-plus` synthetic/CC0-only live evaluation。
- 授权窗口按保守的机器锚点固定为 `2026-08-11T09:51:55Z` 至
  `2026-08-12T09:51:55Z`，不得滚动续期。
- Flash 与 Plus 的稳定槽位 Goal 费用硬上限分别设为 **¥10 总额**；不解释为在旧上限上再加
  ¥10。Max 保持 ¥18，且仍受“同版本 live 三阶段 + 质量 + 当次 J1”前置约束。
- 每槽 1,500,000 exposed tokens、180 attempts、单 authorization 500,000 tokens、batch≤5、
  `REPOSITORY_SYNTHETIC_ONLY`、payload-free evidence 与串行 ledger 生命周期均不变。
- 每次 OPEN 前仍须冻结 clean evaluation identity、精确的当次 immutable Profile snapshot（当前候选为 v34）、case、次数、ledger
  费用和时限；先做单 case/最多 5 attempts，只有 OBSERVE→HIERARCHY→BINDING 同版本 live 可达才
  进入 Max 或 final 20/60。

### 2026-08-11 v33 串行执行结果与停止门

- exact-clean `15b5d00` 的 full 9/9 与冻结 Document Vision canary 通过；Java/独立 Python
  evaluation identity 一致为 `/2:84e6c3f7d70825a29d9e4bdfe8070d16323b0eb9e830619359aa2294c3ff97e7`，
  Flash/Plus v33 snapshot 分别为 `6d55bc7e…39901` / `763412d2…dd44`。Goal guard 在首个 OPEN
  reservation 内原子迁移为 v4，历史 reservation 未改写。
- Flash 按独立 ledger 完成 PROPOSED→NOT_OPEN 负探针→OPEN→唯一 wrapper→CLOSED；独立 verifier
  PASS、4 attempts / 37,181 tokens / ¥0.019870 / 0 abandoned / payload scan PASS。四次均在 OBSERVE
  fail-closed，固定码为 invalid-region-kind×2、parent-containment×1、parent-invalid×1。
- Plus 在 fresh preflight 后完成同一生命周期；独立 verifier PASS、5 attempts / 35,407 tokens /
  ¥0.159584 / 0 abandoned / payload scan PASS。前四次 OBSERVE 分别为 sibling-overlap、
  evidence-outside-region×2、parent-kind；第五次 OBSERVE accepted，但 5-call ledger 上限随即在
  HIERARCHY 前 fail-closed。
- v33 没有形成同版本 accepted HIERARCHY/BINDING 或可审核 Candidate，因此 Max 与 final 20/60
  均不得启动。三份 ledger 最终 CLOSED；Goal 为 381 reservations（376 SETTLED、5 个历史 Plus
  RESERVED、0 BREACHED）。Profile 继续 `EXPERIMENTAL`，N6 继续 `automated_verified`，N7/Goal
  继续 `in_progress`。

### 2026-08-11 v34 unique-existing-parent 离线候选

- `14e02b8` 只对已有非 ROOT region 的错误 parent link 做 bounded normalization：候选必须同 artifact、
  严格包含、kind/repeat-group 兼容且唯一最具体；ROOT、相等 box、零/多候选、循环、超过 8 个替换或
  完整 forest 校验失败均原子回退。enum、sibling overlap 与歧义继续 fail-closed。
- `10f11b3` 发布 pipeline 4.21 与三份 product-v34 immutable Profile；`029277a` 明确保留 v30/v31 的
  evidence-owner 与 repeated-item SLOT-owner 安全继承。成功只记录
  `VISUAL_GROUNDING_REGION_PARENT_NORMALIZED` 数量，不记录 ID、坐标或 payload。
- `abb52a3` 以真实 PostgreSQL 证明 OBSERVE checkpoint 后 lease-expiry 接管只继续 HIERARCHY/BINDING，
  Provider OBSERVE 不重放；ephemeral OCR 可确定性重算但不持久化。`de18000` 完成 monitor/review 与
  1024px payload-free E2E。
- 验证为 inference 188/188、独立 snapshot verifier 2/2、real-PG 57/57、Node 24 Web 73/73 + build、
  Playwright 1/1。该节点 Provider attempts=0、381 reservations 与三份 CLOSED ledger 不变；v34 继续
  `EXPERIMENTAL`，N6=`automated_verified`、N7/Goal=`in_progress`。只有 fresh clean gate、identity/
  snapshot/budget/time/lease preflight 后才可执行 Flash bounded smoke；Plus/Max/final eval 的既有门不变。

### 2026-08-11 v34 live 结果

- exact-clean `751e412` 的 full 9/9、Document Vision 19 lines、双实现 identity 与三份 v34 snapshot 通过。
- Flash 5 次均在 OBSERVE fail-closed；Plus 首次 OBSERVE accepted 后在 HIERARCHY 返回 empty-support×1、
  support-not-group×2，第五次在 Provider 前由 cost reservation 停止。两份独立 verifier/payload scan PASS，
  0 abandoned，三 ledger CLOSED。
- Goal 为 390 reservations（385 SETTLED、5 历史 Plus RESERVED、0 BREACHED）：Flash/Plus/Max 分别
  129/179/82 attempts、896,093/1,087,500/491,919 tokens、¥0.435196/¥4.159620/¥10.289316。
  v34 未形成同版本 accepted BINDING/Candidate，Max 与 final 20/60 不启动。

### 2026-08-11 v35 empty-source-ancestor support 候选

- pipeline 4.22/product-v35 只扩展 empty support：exact relationship-region GROUP owner 仍优先；exact owner
  为零时，仅当已知 relationship region 有一个严格祖先 GROUP/REPEATED_GROUP owner、基数兼容且连接
  parent/child entity ownership，才用该已有 owner 补全 support 并归一化关系区域。
- unknown support、same-region、zero/many、non-ancestor、disconnected 或后续 semantic verifier 失败时，
  继续原 fixed code fail-closed。不得创建 element/region/entity/relationship/evidence/crop/Candidate，不得读取
  文字或按距离/gold 排名。
- 成功只暴露数量型
  `VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED` 及既有通用计数。
  `614359f`/`708522b`/`a2b8181`/`5c59ce3` 已完成 codec、immutable Profile、real-PG lease recovery、
  monitor/review 与 1024px E2E；v35 仍 `EXPERIMENTAL`。
- 当前只具备自动证据；必须在 exact-clean revision 上重新跑 full/Document Vision 并 fresh 重算 identity、
  v35 snapshots、Goal/J1/time/process/lease 后，才可优先执行 Flash 单 synthetic case/最多 5 calls。Plus
  只剩 1 Goal attempt，不足以证明三阶段；Max/final eval 的同版本三阶段、质量、独立复核与最终 J1 门不变。

### 2026-08-11 v35 exact-clean Flash live 结果

- exact-clean `0e52ec7` 通过 full 9/9 与 Document Vision 19-line canary；Java/Python identity 为
  `/2:e623107c…e49d37`，三份 v35 snapshots 精确匹配。首次 Document Vision 缺 process 配置的失败在
  0 Provider/子进程/held lease 下恢复，不并发重跑。
- Flash lifecycle `d2c2c3d`→NOT_OPEN→`b795f0a`→唯一 wrapper→`a4298f3`→NOT_OPEN 完整闭合。
  独立 verifier/payload scan PASS：5 attempts、41,477 tokens、¥0.020835、0 abandoned。
- 五次均在 OBSERVE 因 invalid-region-kind×4、parent-kind×1 fail-closed；结构计数全 0，未到 HIERARCHY，
  不构成 v35 质量或三阶段证据。Plus 仅剩 1 attempt，不调用；Max/final 20/60 不启动。
- Goal 为 395 reservations（390 SETTLED、5 历史 Plus RESERVED、0 BREACHED）。product-v35 保持
  `EXPERIMENTAL`，N6=`automated_verified`、N7/Goal=`in_progress`。

### 2026-08-11 v36 contract-unique region kind 候选

- pipeline 4.23/product-v36 只允许从 typed region shape 唯一决定 canonical kind：
  `MANY + repeatGroupId` 必为 `REPEATED_GROUP`，`ONE + repeatGroupId` 必为 `ITEM`；parent 为空、ONE、
  repeat 为空且单个 evidence box 精确覆盖 artifact 时必为 `ROOT`。
- SECTION/GROUP/non-repeat container、缺失 repeat、零/多解释、非法 parent/children/repeat/forest 或后续
  semantic verifier 失败继续原 fixed code fail-closed。不得创建或删除 region/edge/element/evidence/entity/
  relationship/crop/Candidate，不得读取文字或按 gold/距离排名。
- 成功只记录 `VISUAL_GROUNDING_REGION_KIND_NORMALIZED` 数量。`fdf7d44`/`86b6074`/`2076684`/
  `f395f90` 已完成 bounded codec、三模型 immutable Profile、real-PG lease recovery 与 monitor/review E2E；
  OBSERVE checkpoint 不重放，OCR sentinel 零持久化。
- 自动证据为 inference 190/190、independent snapshot verifier 1/1、real-PG 1/1、Web 73/73、
  typecheck/lint、Playwright 1/1；本机 Web 使用 Node 20，不能替代正式 Node 24 gate。本节点 Provider
  attempts=0、Goal 用量不变、三 ledger CLOSED。
- product-v36 保持 `EXPERIMENTAL`，N6=`automated_verified`、N7/Goal=`in_progress`。exact-clean full/
  Document Vision、fresh identity/snapshot/budget/J1/process/lease、Flash bounded live、final 20/60、最终
  independent verifier 与业务/视觉 J1 尚未完成；Plus 仅剩 1 attempt，不用于凑数。

#### v36 Flash live 规格状态

在 exact-clean full/Document Vision 与 fresh identity/Profile/Goal/J1/process/lease 门通过后，Flash 按
PROPOSED→NOT_OPEN→OPEN→唯一 wrapper→CLOSED→NOT_OPEN 完成单 synthetic case。独立 verifier 与
payload scan PASS：5 SETTLED attempts、42,469 exposed tokens、¥0.021607、0 abandoned；两次负探针
Goal/evidence 零变化，结束后无残留进程或 lease。

三次 `VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND`、一次
`VISUAL_GROUNDING_SIBLING_OVERLAP`、一次 `VISUAL_GROUNDING_PARENT_KIND_INVALID` 使全部调用停在
OBSERVE。该结果不授权读取或记录 invalid enum 原文、模型输出、region identity、坐标或 Candidate，也不
授权通用 alias/geometry 猜测。product-v36 保持 `EXPERIMENTAL`，N6=`automated_verified`、N7/Goal=
`in_progress`；Plus/Max/final 20/60 必须继续等待同版本三阶段、质量门、独立复核和最终 J1。

### 2026-08-11 v37 constraint-unique GROUP kind 候选

- pipeline 4.24 允许从 typed ownership constraint 唯一决定 GROUP：ONE GROUP element 的 regionIds 必须
  distinct 且全部已知、没有现存兼容 singular GROUP owner，并且恰有一个 parent 非空、ONE、无 repeat、
  kind 未解析的 owner region。只有该唯一候选可改为 GROUP。
- 多个候选、已有兼容 owner、MANY group、SLOT-only ownership、缺失/重复/未知 owner reference、root/
  repeat shape 或任何剩余 unknown kind 必须原 fixed code fail-closed。不得读取或记录 unknown alias、OCR、
  displayName、model text、box payload 或 gold；不得改 parent/readingOrder/box/ownership，亦不得创建删除
  region、element、evidence 或 Candidate。完整 forest/ownership/semantic verifier 必须重跑。
- `ebd0281`/`b5a4555`/`007afe6`/`e6682b4` 已完成 codec、immutable Profile、real-PG recovery 和
  monitor/review E2E；自动证据为 inference 191/191、snapshot verifier 2/2、recovery 2/2、Web 73/73、
  typecheck/lint、Playwright 1/1。Provider=0、Goal/ledgers 不变。
- product-v37=`EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`；exact-clean gates、fresh
  identity/snapshot/budget/J1、bounded Flash live、final 20/60 与最终 J1 尚未完成。

#### v37 首次 Flash live 的规格状态

exact-clean gates、fresh identity/Profile/Goal/J1/process/lease 均通过，且 PROPOSED→NOT_OPEN→OPEN→
唯一 wrapper→CLOSED→NOT_OPEN lifecycle 与独立 verifier/payload scan 完整闭合。但唯一 outcome 是
`DOCUMENT_VISION_ADAPTER_MISSING`，0 Provider attempts/tokens/cost；启动参数误用了非合同 `adapter`，
实际属性为 `renderweave.inference.document-vision.adapter-script`。该授权已 CLOSED、不得重放，Goal 用量
不变。这不满足任何 live 质量或三阶段 AC；product-v37 保持 `EXPERIMENTAL`、N7/Goal=`in_progress`。
任何重试必须使用新的 immutable authorization、fresh gates/preflight 与正确 adapter-script 绑定。

#### v37b Flash live 规格状态

新的 immutable authorization 使用正确 adapter-script，并完成完整 lifecycle、fresh gates/preflight 与
独立 verifier/payload scan。5 attempts/42,691 tokens/¥0.021815 全部停在 OBSERVE：invalid region-kind×4、
parent-containment×1。该 fixed-code evidence 不授权读取或记录 unknown alias、模型文本、region identity、
box 或 Candidate，也不授权任意扩大 parent geometry；只有 repository synthetic 反例能证明唯一约束时才可
新增 bounded repair。Goal 为 405 reservations，三 ledger CLOSED。product-v37 仍 `EXPERIMENTAL`、
N6=`automated_verified`、N7/Goal=`in_progress`，Plus/Max/final eval 门未满足。

### 2026-08-11 v38 unique-containing-root-ancestor 候选

- pipeline 4.25 只扩展错误 parent containment 的可证明子集：当前 parent 必须已知、非 self、同 artifact
  且确实不包含非 ROOT/ITEM child；常规唯一最具体兼容 parent 搜索必须为空；沿当前 parent 的既有无环
  ancestor chain 必须恰好到达 parent=null、严格包含 child 的唯一 ROOT。只有此时可把 parent 改为该 ROOT
  并 canonicalize reading order，且完整 `VisualGroundingPlan` 必须重新通过。
- missing parent、ITEM kind、artifact mismatch、cycle、equal/full box、零/多候选或任何最终 topology/
  overlap/ownership/semantic failure 继续 fixed-code fail-closed。不得读取 text/alias/OCR/model payload/gold，
  不得改变 box 或创建、删除 region/edge/element/evidence/crop/Candidate；v37 policy 保持 immutable。
- `632e641`/`b91637b`/`060dd47`/`1504ac6` 已完成 codec、三模型 immutable Profile、real-PG
  checkpoint recovery 与 monitor/review E2E。自动证据为 contract 34/34、Profile/independent verifier
  37/37、real-PG 2/2、inference 192/192、Web 73/73、typecheck/lint、Playwright 7/7；Node 20 Web 只算
  兼容检查。
- 本节点 Provider=0、Goal 仍为 405 reservations、三 ledger CLOSED。product-v38=`EXPERIMENTAL`、
  N6=`automated_verified`、N7/Goal=`in_progress`；exact-clean full/Document Vision、fresh identity/
  snapshot/budget/J1/process/lease、Flash bounded live、final 20/60 与最终业务/视觉 J1 尚未完成。

#### v38 Flash live 规格状态

exact-clean full/Document Vision、双实现 identity、Profile snapshot 与 Goal/J1/process/lease preflight 均
通过。Flash 按 `882c8ca`→NOT_OPEN→`19c726c`→唯一 wrapper→`31109c4`→NOT_OPEN 闭合；独立
verifier/payload scan PASS：5 attempts、40,797 exposed tokens、¥0.020282、0 abandoned。

五次全部在 OBSERVE fail-closed：invalid region-kind×2、reading-order gap×2、JSON unknown member×1；
v38 parent normalization 未命中。这些 fixed codes 不授权读取 unknown member 名、region ID、order 值、坐标
或模型文本，也不授权宽松 JSON/enum。后续若增加 reading-order repair，必须只依赖已验证 sibling forest 与
repository synthetic 反例证明唯一总序，并在 tie/duplicate/拓扑或最终 plan 失败时原子拒绝。

Goal 为 410 reservations，Flash/Plus/Max 累计为 149/179/82 attempts、
1,063,527/1,087,500/491,919 tokens 与 ¥0.519735/¥4.159620/¥10.289316；三 ledger CLOSED。
product-v38 仍 `EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`，Plus/Max/final eval 门未满足。

### 2026-08-11 v39 unique canonical sibling-order 候选

- pipeline 4.26 只扩展 reading-order gap 的确定性子集：只处理 parent 非空的 sibling set；existing order
  必须互异，按 order 排序必须与首 evidence box 的 `(top,left,regionId)` canonical 顺序完全一致，并且
  当前序号非连续。最多改变 8 个既有 order 并压紧为 `0..n-1`，随后完整 `VisualGroundingPlan` 必须重验。
- root gap、duplicate/tie、反向/位置不一致、missing parent、cycle、topology/overlap/ownership 或最终
  semantic failure 继续 fixed-code fail-closed。不得读取 alias/text/OCR/model payload/gold，不得改变 box、
  parent、element ownership 或创建删除 region/edge/element/evidence/crop/Candidate；strict JSON unknown-member
  与 enum 合同保持不变。成功只记录数量型 `VISUAL_GROUNDING_READING_ORDER_NORMALIZED`。
- `a08f099`/`c99a4ac`/`80d0b73`/`d0ed4d3` 已完成 codec、三模型 immutable Profile、real-PG
  checkpoint recovery 与 monitor/review E2E。证据为 contract 35/35、跨模块 38/38、real-PG v39 1/1 与
  v38/v39 2/2、inference 193/193、Web 73/73、typecheck/lint、Playwright 7/7；Node 20 Web 只算兼容检查。
- 本节点 Provider=0；Goal 为 410 reservations，Flash/Plus/Max 累计 149/179/82 attempts、
  1,063,527/1,087,500/491,919 tokens 与 ¥0.519735/¥4.159620/¥10.289316，三 ledger CLOSED。
  product-v39=`EXPERIMENTAL`、N6=`automated_verified`、N7/Goal=`in_progress`；exact-clean full/Document
  Vision、fresh identity/snapshot/budget/J1/process/lease、Flash bounded live、final eval/A2 与最终业务/视觉
  J1 尚未完成。

#### v39 Flash live 规格状态

exact-clean full/Document Vision、双实现 identity、Profile snapshot 与 Goal/J1/process/lease preflight 均通过。
Flash 按 `37cc036`→NOT_OPEN→`1431233`→唯一 wrapper→`678ef2e`→NOT_OPEN 闭合；独立 verifier/
payload scan PASS：1 completed、0 abandoned、3 attempts、17,289 actual tokens、¥0.008900 actual cost。

前两次 OBSERVE 分别以 invalid region-kind 和 reading-order gap fail-closed；第三次网络错误无 actual usage，
按 immutable Goal 合同保留 worst-case reservation并停止。v39 normalization 未命中，HIERARCHY/BINDING/
Candidate 均未触达。fixed code 不授权读取 region/order/enum/model payload，也不足以证明进一步放宽现有
canonical order 或 strict enum 合同；CLOSED authorization 不得重跑。

Goal 为 413 reservations（407 SETTLED、6 RESERVED、0 BREACHED）；Flash/Plus/Max 累计为
152/179/82 attempts、1,105,020/1,087,500/491,919 exposed tokens 与
¥0.538392/¥4.159620/¥10.289316，三 ledger CLOSED。product-v39 仍 `EXPERIMENTAL`、N6=
`automated_verified`、N7/Goal=`in_progress`；Plus/Max/final eval 门未满足。

### 2026-08-12 v40 payload-free reading-order diagnostic 与阶段冻结

- pipeline 4.27 继承 v39 的全部 bounded repair，不新增 normalization。只有原始 shape 会报 reading-order
  GAP、root orders 已连续，且全部失败的非 root sibling set 可归入同一原因时，才细分 fixed code：全部
  duplicate/tie → `VISUAL_GROUNDING_READING_ORDER_DUPLICATE`；全部 existing-order/position 冲突 →
  `VISUAL_GROUNDING_READING_ORDER_POSITION_INVALID`。mixed、root gap、canonical 超界 gap 仍返回 GAP。
- 分类只使用已解码的数量/相等性/相对顺序，不能写入 region ID、order 值、box、图片、OCR、完整 prompt、
  Candidate 或模型原文；strict JSON/enum、parent/overlap/ownership、完整 semantic verifier 与 8-value cap 不变。
- Prompt 10 相对 v9 只加入 duplicate fixed-code 的阶段内重算说明；三份 product-v40 Profile 绑定 pipeline
  4.27 且继续 `EXPERIMENTAL`。real-PG 证明 lease expiry 后从 HIERARCHY 续跑到 REVIEW_REQUIRED，OBSERVE
  不重放且 OCR sentinel 零持久化；monitor/review/E2E 可解释 fixed code。
- 自动证据：contract 36/36、Profile/Prompt 20/20、独立 verifier 2/2、real-PG v39/v40 2/2、inference
  195/195、Web 73/73、typecheck/lint、Playwright 7/7 PASS；Provider=0，Goal/ledger 不变。

用户要求 v40 完成后冻结为本阶段稳定基线，并以可运行、可恢复、可审计、可人工审核为阶段性可用口径；
不要求也不得声称生产级可靠性。exact-clean full/Document Vision、fresh identity/Profile/Goal/J1/process/
lease 与受控 Flash smoke 尚未完成，因此当前仍为 N6=`automated_verified`、N7/Goal=`in_progress`。

#### v40 Flash live 与冻结后的规格状态

exact-clean full/Document Vision、双实现 identity、三份 Profile snapshot、Goal/J1/process/lease preflight
均通过。Flash 以 `0d38448`→NOT_OPEN→`5392aa1`→唯一 wrapper→`6e7f522`→NOT_OPEN 闭合；独立
verifier/payload scan PASS，5 attempts/43,304 tokens/¥0.022226 均在 authorization 与 Goal 上限内。

五次调用都在 OBSERVE fail-closed，未产生 HIERARCHY、BINDING 或 Candidate。因此“阶段可用”限定为：
本地 Document Vision 和确定性流水线可运行；checkpoint/lease 可恢复；fixed code、Token、费用、延迟可审计；
通过合同的 Candidate 具备人工审核、编辑、移除和 Apply E2E。它不保证任意图片或当前 Flash smoke 会产出
Candidate，也不满足生产可靠性、final eval 或最终业务/视觉 J1。

pipeline 4.27、Prompt 10 与 product-v40 Profile 现冻结且保持 `EXPERIMENTAL`。三 ledger CLOSED；Plus/Max/
final 不调用。N6=`automated_verified`，N7/Goal 仍未完成；后续算法变化必须使用新版本与新的精确授权。

### 2026-08-12 product-v40 工程入口 delta

用户明确要求普通图片识别入口切换到冻结 v40，并认可复杂站牌不应以 Flash 是否成功作为流水线工程可用性的
唯一判断。本 delta 批准以下产品语义变化，但不修改 AC-021/AC-VR-010 的质量晋级结论：

- 新 live run 只接纳三份精确 product-v40 Profile，按 Plus、Max、pinned Flash 排序；全部仅支持
  `IMAGE_ONLY` 且保持 `EXPERIMENTAL`。Plus 为默认平衡选择，Max 面向高难度嵌套结构，Flash 明确只建议
  低成本 smoke；不自动跨模型 fallback。
- 历史 product-v4 与更早 Profile/run snapshot 保持不可变、可读、可恢复，但不再允许创建新产品 run。
  pipeline 4.27 继续由本地 materializer 生成 Candidate，STRUCTURE/REPAIR Provider attempts 必须为 0。
- `live-availability` 必须返回逐 Profile 的 payload-free readiness；Document Vision capability 缺失或与
  Profile 绑定 ID 不匹配时，create/retry 在 run/reservation/Provider 前以固定 code fail-closed。UI 不显示
  本地路径、模型资产、OCR 文本、图片、Prompt 或 Provider payload。
- 本次只批准工程试用入口，不把 v40 改写为生产默认或质量认证。N6=`automated_verified`、N7/Goal=
  `in_progress`；final eval、独立 verifier 与最终 J1 门仍未满足。
