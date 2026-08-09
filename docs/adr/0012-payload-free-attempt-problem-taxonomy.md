# ADR-0012：不含载荷的尝试级问题分类

- 状态：accepted
- 日期：2026-08-09
- 关联：ADR-0008、ADR-0010、ADR-0011、AC-016、AC-021
- 决策来源：Grounded Pipeline v2 的 CLOSED 60-case evidence

## 背景

Grounded Pipeline v2 的 IMAGE_ONLY 20 个 case 共发生 60 次 Provider attempt，全部以 `REJECTED` 结束。既有持久化只记录统一的 `LIVE_OUTPUT_REJECTED`，解析失败又在 workflow 中折叠为 `LIVE_STRUCTURE_OUTPUT_INVALID`，因此无法区分严格 JSON 解码、Candidate envelope、provenance、evidence、图结构或置信度状态等系统性失败。

四轮 60-case certification journal/report 已刻意不保存 Candidate、Prompt、图片、原始 JSON、异常消息或 Provider request id。更早的 canary 1.0 evidence 曾保留 Provider request id 与完整 per-case mismatch 列表，因此只按较低的历史保证处理，不归入本 ADR 的 payload-free A1/A2 证据。无论哪一种历史文件都不能在事后重新分类；下一轮必须先让系统在尝试发生时生成足够但不含业务载荷的诊断，不能靠保存原始模型输出换取可观测性。

## 决策

### 1. 每次尝试只记录稳定 code 与计数

- `InferenceAttempt` 新增 `problemCodeCounts: Map<String,Integer>`；键为系统产生的稳定大写问题 code，值为该尝试中的出现次数。
- 成功解析的 Candidate 使用与后续 `DETERMINISTIC_VALIDATE` 完全相同的 composer prevalidation 与 `CandidateValidator` 上下文生成统计；统计不改变 attempt status、repair routing、Candidate 或审核语义。
- 解码失败保留既有外部结果 `CANDIDATE_JSON_INVALID` / `LIVE_OUTPUT_REJECTED`，另映射为低基数类别：required、too-large、duplicate-member、trailing-content、unknown-member、value-invalid、shape-invalid、syntax-invalid、other。
- 分类器只检查异常类型和固定字样是否存在，永不持久化异常消息本身。

### 2. 诊断信封有确定性硬上限

- 最多 64 个不同 code；单 code 最多计 10,000 次。
- 聚合超限时饱和计数，并写入 `ATTEMPT_PROBLEM_TAXONOMY_TRUNCATED=1`；不扩展信封。
- Map 在每个构造、反序列化与聚合边界重新校验，按 key 排序并转为不可修改值。
- JSON codec 开启重复键检测、拒绝 trailing content，序列化大小上限为 16 KiB。

### 3. 明确禁止进入诊断的数据

尝试分类与新版 live evidence 不得包含 Candidate JSON、Prompt、图片/base64、RootDocument、字段名、字段值、JSON Pointer、item id、problem args、异常消息、完整 mismatch 列表、Provider response 或 request id。问题 code 必须来自严格 codec、composer 或 validator 的系统定义结果；Provider 文本不会成为 code。canary/certification/journal 在最终写入前共享 fail-closed payload guard。

这是一项 payload-free A1/A2 证据能力，不是防本地管理员篡改的 A3 机制。产品数据库已有的其他 attempt telemetry 不因本 ADR 扩权或改变。

### 4. PostgreSQL 与评测账本只追加演进

- Flyway `V010` 为 `inference_attempt` 追加非空 JSONB 列，默认 `{}`；既有记录升级后保持可读。
- 数据库要求 JSON object 且文本表示不超过 16 KiB；Java codec继续执行键、值、重复项和上限校验。
- live certification journal 升级为 1.2，report 升级为 1.2，canary summary 升级为 1.1；canary 1.1 移除 request id 并只保留 scalar evaluation metrics。journal 1.1 可只读加载，缺失的新字段解释为空 Map；OPEN 状态不得恢复旧格式。
- 新 report 按 Profile 聚合所有 attempt 的 code/count，仍不保存单条 Provider payload。

### 5. 不重写历史结论，不隐式获得 live 权限

- Grounded v2 的 CLOSED certification evidence 与 legacy canary 1.0 evidence 均保持原样；其既有 IMAGE_ONLY 拒绝不能被本实现追溯分类，legacy canary 也不会被追认成 payload-free evidence。
- 本节点只做离线假 Provider、严格 codec 与真实 PostgreSQL 验证，不读取 API key、不打开 ledger、不调用 DashScope。
- 任何新的真实归因运行都会改变 evaluation identity，必须新建不可变 Profile/方案与精确 `PROPOSED` ledger，并重新取得 J1；历史 CLOSED authorization 不得复用。

## 验证要求

- 严格 codec 的八类解码错误都有确定性单元测试，且合成敏感字段名和值不出现在 diagnostic code。
- 计数 Map 的排序、不可变性、合并、饱和、基数、键值校验、严格 JSON round-trip 均有边界测试。
- 真实 PostgreSQL workflow 覆盖空 taxonomy、解码错误、required blocker 与 JSON evidence blocker，并验证 V010 fresh migration。
- certification journal 覆盖 1.2 reload、payload 扫描和 1.1 缺字段兼容读取。
- 完整 server gate 必须在清除 key 与所有 live gate 的环境中通过；随后执行独立只读 A2。

## 后果

- 正向：下一次受控 IMAGE_ONLY 实验可回答“失败发生在哪类合同边界”，不再只有统一 REJECTED；同一统计同时可用于 repair 轮次比较。
- 代价：每个成功解析的 Provider Candidate 在 attempt checkpoint 前执行一次确定性 validation，后续 workflow 再执行一次相同纯函数以形成审核 checkpoint；共享 helper 保证两处规则一致。
- 非目标：本节点不修改 Prompt/Profile、不降低 contract/evidence/DAG 门槛、不认证任何模型，也不提供原始模型输出调试仓库。

## T5-11 决策补充：值级失败的有限槽位归因

2026-08-10 的 T5-11 根据已关闭 IMAGE_ONLY 诊断中 60/60 次
`CANDIDATE_DECODE_VALUE_INVALID`，在不读取历史原始输出的前提下细化新 attempt：

- `InvalidFormatException` 中的 Candidate 枚举按固定语义槽位生成
  `CANDIDATE_DECODE_ENUM_INVALID_*`；非枚举格式错误生成
  `CANDIDATE_DECODE_FORMAT_INVALID_*`。
- `ValueInstantiationException` 只按已知 Candidate record 与其固定非空成员生成
  `CANDIDATE_DECODE_CONSTRUCTOR_INVALID_*`；未知情形只能进入固定 `OTHER`
  或 record 级 fallback，不能拼接异常内容。
- contract slot 只能来自代码内封闭映射。Jackson 数组索引、动态 map key、字段值、
  JSON path、异常消息与类型名都不会进入 code。
- Candidate codec 同时关闭 scalar coercion、float-to-int 与 enum ordinal 读取；否则违规值
  可能被静默接受，无法形成可信归因。
- 外部错误仍为 `CANDIDATE_JSON_INVALID`；journal/report 的稳定 Map 信封与版本不变。
  历史 `CANDIDATE_DECODE_VALUE_INVALID` 继续只读可聚合，但新解码不会再产生该合并类别。

本补充不修改 Prompt、Profile、corpus、repair routing 或质量阈值，不产生新的 live 权限。
验证仅使用合成离线 fixture 与真实 PostgreSQL，覆盖 `STRUCTURE → REPAIR → REPAIR` 三阶段、
旧新 taxonomy 共存、动态值泄露负例及 coercion 旁路。
