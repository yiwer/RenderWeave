# 收口 Template v1 的安全、容量与验收合同

Type: grilling
Status: open
Blocked by: 05, 14, 15, 16, 18

## Question

在所有语义决策完成后，Template、Asset、Evaluator、RenderDocument 与 RenderEngine 的权限矩阵、能力否定清单、大小/深度/循环/资源/延迟预算、并发和失败边界是什么；哪些可执行场景与证据等级足以让最终规格进入实施规划？

## Inherited constraints

- Template mutation 只有 best-effort 服务端防抖，没有 command-key 幂等；验收必须覆盖超时重试可能重复创建/复制以及客户端协调责任。
- current report 不绑定 revision，旧异步结果允许覆盖；验收必须证明编辑器打开和每次 Render 的权威重检能阻止 INVALID 内容产生 RenderOutput。
- Template 本地 revision/current/dependency projections/readiness/report 必须原子；Template closure snapshot 不允许混合并发 current。
- DELETED 是保留历史但不可恢复的终态；incoming ACTIVE TemplateRef 阻止删除，TemplateRef graph 必须是 DAG。
- Asset 的 ownerScope/kind/identity 不可变，能力面为 asset.read/create/update/delete/restore；直接跨 scope/不可读查询隐藏为 NOT_FOUND，Rendering 使用内部同 scope Resolver。
- AssetAcceptanceProfile v1 固定 IMAGE 64 MiB/20,000 px edge/100M pixels 与 FONT 32 MiB，并要求真实字节完整解析；验收必须证明 Renderer profile 是其兼容超集。
- Asset 不设 scope 配额且 v1 不 GC；部署必须有新 Blob fail-closed 容量 breaker。创建幂等结果保留 24 小时，删除确认 token 为 5 分钟单次并绑定完整引用 proof。
- AssetRef 按出现位置独立解析、允许一次 Evaluation 混用同一 Asset 的不同 contentVersion；验收必须同时证明单个 ResolvedAsset 精确、晚到失败零输出、RenderDocument 资源身份不碰撞。
- RenderInput 的字节、深度、customValues 条目数和 strict-JSON 结构预算必须在 last-wins 分组和 ignored-target 消解前施加；duplicate loser、unknown/PRIVATE winner 虽不做声明类型校验，也不能绕过全局 admission 预算。
- 验收必须证明 RootDocument 每次按 TemplateSnapshot 精确 StaticSchema 验证、未知字段对 Evaluator 不可见、optional missing 为 typed ABSENT、有效 PUBLIC winner 类型错误使整个请求零 Evaluation/零输出。
- 验收必须覆盖 customValues omitted、duplicate last-wins、loser 不做值级校验、unknown/PRIVATE 静默忽略，以及 authored child fill missing/PRIVATE 使父 Template INVALID 的非对称合同。
- 原始 RootDocument、完整 customValues、AdmittedRenderInput 与 frame 不得进入普通日志或审计转储；problem collection 必须有上限且不能回显完整敏感输入。
- 必须冻结并验证 DesignDSL/Expression source 字节、Definition/Binding/input/case 数量、graph/dependency chain、AST node、decimal precision/scale、list length、nested target 与 runtime operation/call budgets；限制在解析、presence analysis 与 lazy execution前的适当边界 fail-closed。
- conformance corpus 必须覆盖 Java authority 与 Web feedback 对 type/presence、BigDecimal、Unicode text/blank/safe-regex、lazy branch、Mapping first-match、formatDecimal、source span 和 error code 的一致性；第三方 expression library 不能成为验收语义来源。
- 安全验收必须证明 Expression 无任意 member/index/eval/IO，Capability 只能作为显式 Expression input，Binding/Mapping/Custom/fill 不能直接调用 capability，未知 RootDocument 字段也不能经任意 JSON path 泄漏。
- BindingPolicyCatalog 的新增必须保证 key/target set 不重叠且已有 Policy 字段不可变；服务端不能信任 Template 自报 bindability/type，前端限制也不能替代保存与 Evaluation 权威校验。
- 验收必须覆盖 node-local nested target 的存在性/越界/重复/祖先重叠、静态 baseline 依赖索引，以及 Binding ABSENT/ERROR/type/propertyValidation failure 零 RenderDocument且绝不回退 baseline。
- DesignDSL parser 硬上限已冻结为 decompressed raw/canonical 16 MiB、depth 64、object members 1,024、array items 100,000、total values 1,000,000、string 1 MiB、member name/number token 256 bytes；验收必须证明这些限制在完整 model、decimal expansion、canonicalization 与 dependency IO 前 fail closed。
- 必须为 `renderweave-design-c14n/1.0` 与 domain-separated SHA-256 建独立 conformance corpus，覆盖 UTF-8 member ordering/escaping、Unicode、equivalent decimal/-0、semantic vs set-like arrays、metadata trim、exact Expression source、cross-Template same-content hash 与 trusted-read mismatch。
- 必须证明 unknown member/kind/null/duplicate/unsupported pair 不能被 Web partial model、数据库 JSON serializer、generic JSON mapper 或 migration 绕过；每个 admitted dslVersion/expressionProfile pair 的永久 parser/validator/evaluator compatibility 进入发布验收。
- contentHash 不是签名、authorization、provenance 或 Render cache identity；验收必须覆盖 Asset/Template current 与 BindingPolicy drift 不改 authored hash，同时 closure/input/capability/asset occurrence 进入完整 Evaluation identity。
- parser failure 单问题停止、semantic 有界稳定收集、external dependency 延后、canonical pointer + stable entity target、`PROBLEM_LIMIT_REACHED` 与 sensitive-content redaction 都必须有 executable acceptance evidence；具体更低语义计数和 problem cap 由本票据冻结。
- migration 必须 pure/bounded、exact source/target/hash、preview-first、no implicit save/read-time upgrade；需要新 local identity 时只能使用 client-provided UUID v4 或 manual action，不能让普通 save 生成。
