# RenderWeave v1 产品与软件设计规格

- 状态：Approved baseline / lifecycle `planned`
- 基线日期：2026-08-07
- 产品负责人确认：需求访谈 Q001–Q225 已收束；后续语义变更走 `specs/changes/`
- 领域模型：[`docs/modeling/renderweave-v1-domain-model.md`](../docs/modeling/renderweave-v1-domain-model.md)
- 实施计划：[`plans/renderweave-v1-plan.md`](../plans/renderweave-v1-plan.md)

## 1. 产品定位

RenderWeave 最终服务于“用聚合数据 RootDocument 和 Template 生成一张或一组图片”的完整链路。v1 只建立这条链路最基础、也最需要稳定的 Schema 领域：设计可变数据定义、发布不可变版本、验证样本，并让 AI 从图片和 JSON 生成带证据、必须经人工审核的 Schema 候选。

### 1.1 主要用户

首要用户是理解 JSON、能接受 `Schema`、`fieldKey`、`revision`、JSON Pointer 等术语的技术型模板/数据结构设计者。v1 不承担零基础教学，也不提供“自然语言全自动完成一切”的模式。

### 1.2 核心价值

1. 用封闭、确定、可审计的 DSL 设计数据结构。
2. 把某个已保存 revision 发布为永不变化的 StaticSchema，切断嵌套定义变更传播。
3. 在发布前发现字段、约束、引用图和样本数据问题。
4. 用 AI 减少从视觉设计和样本数据开始建模的机械劳动，同时让证据、歧义和最终决定始终可见。

### 1.3 v1 范围

- Schema Draft 列表、创建、显式保存、revision 历史、恢复、软删除、复制。
- StaticSchema 发布、列表、详情、DSL/compiled JSON Schema 下载。
- RenderWeave DSL、类型约束、引用 DAG、系统预置 StaticSchema。
- JSON Schema 2020-12 的一次性、自包含、完全内联编译。
- 单个或批量 RootDocument 的确定性验证。
- image-only、json-only、combined 三种异步 AI Schema 推断。
- Candidate Bundle evidence 审核和 create-only 原子落库。
- 表单与一层树状/思维导图两种 Schema 编辑模式。
- 单节点 Java 服务、React Web、PostgreSQL、文件系统 BlobStore 和本地部署闭环。

### 1.4 明确非目标

- Template 设计、动态值、循环容器、动态模板和内部映射。
- 数据适配、Workspace、图片渲染和批量渲染。
- 未来功能的占位页面、空模块、数据库表或未被 v1 消费的抽象。
- 多租户、账号、登录、RBAC、应用内权限管理。
- 手机/平板 UI、离线桌面应用或原生桌面壳。
- 任意 JSON Schema 导入、raw DSL 编辑、CSV/Excel/XML/数据库/API 数据源。
- 自动发布、自动修改/合并/删除既有 Draft。
- 微服务、Redis、Kafka、RabbitMQ、GraphQL、gRPC、WebFlux/R2DBC。

## 2. 事实源与身份

### 2.1 两种 Schema

- **Schema Draft**：可变工作定义，以 `schemaKey` 标识。每次成功保存产生新的完整、不可变 `DraftRevision`。
- **StaticSchema**：发布物，以 `{schemaKey, versionTag}` 标识；创建后只读、不可变、不可删除，也不支持重编译、deprecated 或 revoked 状态。

发布不会锁住或复制一个新的 Draft。Draft 可继续修改或软删除；StaticSchema 不受影响。

### 2.2 字段身份

- Draft 字段：`schemaKey + fieldKey`。
- Static 字段：`schemaKey + versionTag + fieldKey`。
- 嵌套出现位置：在上述身份之外使用从根开始的 JSON Pointer。
- 不持久化 `fieldId`。字段改名等价于删除旧字段并新增字段。
- UI 可用仅存在内存中的本地 row key；Inference Candidate 可用 run-local opaque ID，但这些 ID 在创建 Draft 时必须丢弃。

### 2.3 Key 规则

| Key | 规则 |
|---|---|
| `schemaKey` | `^[a-z0-9][a-z0-9-]{0,62}$`；创建后不可改；删除后永久不可复用；`system-` 前缀保留 |
| `versionTag` | `^[a-z0-9][a-z0-9._-]{0,63}$`；由用户填写；同 schemaKey 下唯一；无顺序、latest 或自动递增语义 |
| `fieldKey` | 非空、大小写敏感、最多 128 UTF-8 bytes、禁止控制字符；允许任意其他 Unicode，包括 `/` 和 `~`；不做 Unicode normalization |

`fieldKey` 写入 JSON Pointer 时按 RFC 6901 转义。Schema Key 被软删除后保留 tombstone，永不重新分配给另一语义对象。

## 3. RenderWeave DSL 1.0

### 3.1 Definition envelope

身份和生命周期元数据不混入 definition。导出 Draft 时的完整 envelope 示例：

```json
{
  "identity": {
    "kind": "draft",
    "schemaKey": "customer-profile",
    "revision": 7
  },
  "definition": {
    "dslVersion": "renderweave-schema/1.0",
    "displayName": "客户档案",
    "description": "用于会员卡片的数据结构",
    "fields": [
      {
        "fieldKey": "name",
        "displayName": "姓名",
        "required": true,
        "value": {
          "type": "text",
          "constraints": { "minLength": 1, "maxLength": 80 }
        }
      },
      {
        "fieldKey": "address",
        "required": false,
        "value": {
          "type": "reference",
          "ref": { "schemaKey": "postal-address" }
        }
      }
    ]
  }
}
```

持久化 revision 的内容事实是 `definition` 完整 JSONB；API/export envelope 组合身份和生命周期信息。DSL 对未知 member 失败封闭，版本只接受 `renderweave-schema/1.0`。

### 3.2 元数据

- Schema `displayName` 必填，trim 后 1–128 Unicode code points。
- field `displayName` 可选，trim 后 1–128 code points；缺失时 UI 回退显示 fieldKey。
- Schema/field `description` 可选，trim 后最大 2048 code points；空白保存为 absent。
- RootDocument 中用户数据字符串不执行上述 trim 或 normalization。
- 字段顺序持久化，只影响展示、诊断、required 输出和编译属性顺序，不构成身份。
- 新增字段默认 `required=false`。

### 3.3 根与字段结构

- 每个 Schema 根恒为 object，不支持 scalar/array 根。
- 直接字段最多 256。
- 字段固定形状：`fieldKey`, optional `displayName`, optional `description`, `required`, `value`。
- `value.type` 只允许：`text`, `decimal`, `date`, `time`, `boolean`, `reference`, `array`。
- 空 constraints 对象必须省略。
- 定义字段缺失和 `null` 语义不同：optional 只允许 absent；字段一旦出现，`null` 永远无效。

### 3.4 Reference

Draft 可使用两种引用：

```json
{ "type": "reference", "ref": { "schemaKey": "postal-address" } }
```

```json
{
  "type": "reference",
  "ref": { "schemaKey": "postal-address", "versionTag": "v1" }
}
```

- 只有 `schemaKey` 是 `SchemaRef`，在请求开始时解析到目标 Draft current revision。
- 同时有 `schemaKey` 和 `versionTag` 是 `StaticSchemaRef`，解析到精确发布物。
- 保存 Draft 时所有引用必须存在、可解析且组合图为 DAG；不允许悬空工作态。
- StaticSchema definition 只能包含 `StaticSchemaRef`。
- 引用根深度最大 16，根计为 1。
- reference 类型没有额外值约束。

### 3.5 Array

```json
{
  "type": "array",
  "constraints": { "minItems": 1, "maxItems": 50 },
  "items": {
    "type": "reference",
    "ref": { "schemaKey": "line-item", "versionTag": "v1" }
  }
}
```

- `items` 重用 value descriptor 的 type/constraints/ref 语义，但没有 fieldKey/displayName/description/required。
- item 可为 text/decimal/date/time/boolean/reference，不可为 array 或 null。
- array constraints：`minItems`, `maxItems`, `uniqueItems`；每种最多一次。
- 单数组最多 10,000 项。
- `uniqueItems` 只允许 scalar item array；reference/object array 不支持。

### 3.6 约束通则

- 同一种约束最多出现一次。
- `enum` 与 `const` 互斥。
- `enum` 必须 1–256 个值、按类型相等去重、保留输入顺序。
- `enum`/`const` 中每个值必须同时满足该字段其他约束。
- 下界只能在 `min` 与 `exclusiveMin` 中选一个；上界同理。
- 范围不得为空；只有上下界均 inclusive 时允许相等。

| 类型 | 允许约束 | 附加规则 |
|---|---|---|
| text | `minLength`, `maxLength`, `pattern`, `enum`, `const` | 长度按 Unicode code point；运行时文本最大 65,536；pattern 最大 1,024 code points |
| decimal | `min`, `exclusiveMin`, `max`, `exclusiveMax`, `multipleOf`, `enum`, `const` | `multipleOf > 0`；按数学数值相等 |
| date | `min`, `exclusiveMin`, `max`, `exclusiveMax`, `enum`, `const` | 固定 `YYYY-MM-DD`，Gregorian 年 0001–9999 |
| time | `min`, `exclusiveMin`, `max`, `exclusiveMax`, `enum`, `const` | 固定 `HH:mm:ss`，00:00:00–23:59:59；无时区、小数秒、闰秒 |
| boolean | `const` | 不提供 enum 或其他约束 |
| reference | 无 | 完整对象由引用目标约束 |
| array | `minItems`, `maxItems`, `uniqueItems` | min/max 0–10,000，min ≤ max |

### 3.7 Regex

- 用户 pattern 使用 Java/ECMAScript 的安全交集。
- 匹配语义是 substring，不隐式添加 `^...$`。
- 禁止 backreference、lookaround、inline flag、递归结构及已知高风险构造。
- 保存前编译和安全检查；运行时使用有界输入，失败不回退到其他 regex 引擎。
- 编译器为 date/time 产生的内建 anchored pattern 不受用户 pattern DSL 限制，但必须有 golden tests。

### 3.8 Decimal

- JSON 输入只接受 JSON number，不接受 quoted number。
- parser 保留原始 token 并用 BigDecimal 语义解析，不经过 binary floating point。
- token 最大 256 bytes，precision 最大 128，normalized scale 为 -64..64。
- Schema 约束保存时规范化：去尾随零、禁用科学计数法、所有零写作 `0`。
- RootDocument 数字 token 不因验证被改写。

## 4. Draft 生命周期与图一致性

### 4.1 创建和保存

- 创建成功得到 revision 0。
- 保存必须显式触发，不做 Draft autosave。
- 每次保存携带 `expectedRevision`；成功追加完整 snapshot，revision +1。
- 无效 fieldKey、重复字段、未知 DSL member、约束冲突、悬空引用、循环引用均使保存零写。
- 编辑器本地可暂时不完整；服务端持久化态永远合法。
- Draft revision 历史不允许修改或删除。恢复旧 revision 会把其 definition 复制为新的 current revision。

所有 graph-changing create/save/delete/restore 在一个 PostgreSQL 事务中获得固定域 advisory lock，解析目标并做 cycle check；不能依靠先查后写的应用竞态窗口。

### 4.2 live Draft reference

请求开始时一次性遍历所有 SchemaRef，并冻结 `{schemaKey → revision}` map；同一请求的编译预检、验证或保存校验不能在中途读取到不同 revision。该 map 随响应返回用于诊断，但不持久化为 Draft closure。

### 4.3 删除与恢复

- Draft 是软删除；默认列表和引用选择器隐藏。
- 有 active incoming Draft references 时拒绝删除，并返回引用摘要。
- 删除成功后 outgoing edges 停用，revision/provenance/tombstone 保留，StaticSchema 不受影响。
- 恢复前重新做 key、目标解析和 DAG 校验；成功创建新 revision 并激活，失败保持 deleted。
- schemaKey 永不复用，不提供 purge Draft。

### 4.4 复制

- 可从 current Draft revision 或任意 StaticSchema 复制为新 Draft。
- 用户填写新的 schemaKey/displayName；字段定义、顺序、约束和 refs 复制。
- 不递归复制依赖，不复制历史、发布时间、发布说明或 AI provenance。
- 新 Draft revision 0，并走普通创建的全部校验。

## 5. StaticSchema 发布

### 5.1 发布命令

发布需要：

- `schemaKey`
- `expectedRevision`
- 用户填写的 `versionTag`
- optional `releaseNote`

发布只消费已经保存且仍是 current 的 exact revision；不隐式保存。Draft 中任一 `SchemaRef` 都阻断发布，用户必须先通过发布准备面板明确选择 versionTag、保存成 `StaticSchemaRef`，再单独发布。

同一 revision/content 可用多个不同 tag 发布。发布不执行兼容性阻断；可显示可选 diff，但 v1 不定义 SemVer 或 latest。

### 5.2 原子发布记录

同一事务保存：

- schemaKey + versionTag
- sourceDraftRevision
- 完整 DSL snapshot
- stable compact UTF-8 compiled JSON Schema 原文（PostgreSQL `json`，不使用 `jsonb` 重写文本）
- compilerVersion
- publishedAt UTC
- optional releaseNote

编译、大小检查或写入任一步失败则整个发布回滚。没有用户系统，因此不虚构 `publishedBy`。

### 5.3 系统预置

| StaticSchemaRef | 字段 |
|---|---|
| `system-empty@v1` | 无字段 |
| `system-basic-text@v1` | required `index: decimal(min=0,multipleOf=1)`, required `value: text` |
| `system-basic-decimal@v1` | 同 index，`value: decimal` |
| `system-basic-date@v1` | 同 index，`value: date` |
| `system-basic-time@v1` | 同 index，`value: time(HH:mm:ss)` |
| `system-basic-boolean@v1` | 同 index，`value: boolean` |

它们由 Flyway 一次插入，作为一等只读 StaticSchema 展示、选择和引用；没有对应 Draft。

## 6. JSON Schema 2020-12 编译

### 6.1 基本形态

- 根 `$schema` 固定 `https://json-schema.org/draft/2020-12/schema`。
- 不生成 `$id`、`$defs`、内部/外部 `$ref`。
- 根和每个引用对象为 `type: object`、`properties`、按字段顺序的 `required`、显式 `additionalProperties: true`。
- reference 字段直接嵌入子 StaticSchema 已保存 artifact 去掉顶层 `$schema` 后的对象 body；array reference 同样嵌入 `items`。
- 同一子 Schema 多次出现时重复内联，由 2 MiB artifact 上限控制体积。
- 编译自底向上；父节点不从子 DSL 重编译，只嵌入其不可变 artifact。

### 6.2 类型映射

| DSL | 标准 JSON Schema | 扩展 |
|---|---|---|
| text | `type: string`, length/pattern/enum/const | `x-renderweave-type: text` |
| decimal | `type: number`, numeric constraints | `x-renderweave-type: decimal` |
| date | `type: string`, exact syntax pattern, `format: date`, enum/const | ordered bounds in `x-renderweave-constraints` |
| time | `type: string`, exact `HH:mm:ss` pattern, enum/const | no incompatible `format: time`; ordered bounds in extension |
| boolean | `type: boolean`, const | type marker |
| reference | embedded object body | source marker |
| array | `type: array`, `items`, item limits, supported uniqueItems | item type markers |

JSON Schema `format` 的支持在不同 validator 中可能只是注解；日期日历有效性、time 语义、decimal 边界和扩展约束仍由 RenderWeave validator 权威执行。

### 6.3 可追踪扩展

- 每个 standalone/embedded object 根：`x-renderweave-static-schema-ref: {schemaKey,versionTag}`。
- 每个 typed node：`x-renderweave-type`。
- 非标准约束：`x-renderweave-constraints`。
- 每个 standalone/embedded artifact 根：`x-renderweave-compiler-version`。
- 嵌入子 artifact 时保留其 compiler version；允许父子版本不同。
- 不写 Draft revision、数据库 ID、fieldId 或内部表主键。

产物创建后永不重算。发现编译器缺陷时修复 compiler 并发布新 tag；旧产物继续原字节存在、可读取和引用。

## 7. RootDocument 验证

### 7.1 输入解析

- strict JSON only：拒绝 comments、trailing comma、single quote、NaN/Infinity/JSON5。
- 所有 object 层级的 duplicate key 都在 schema validation 前拒绝。
- 根必须为 object。
- 定义外的 unknown fields 允许任意 strict JSON，包括 null、object 和 array；仍受全局预算。
- 单文档最大 2 MiB、depth 32、单数组 10,000 项、定义字段 text 最大 65,536 code points。

### 7.2 目标解析

- Draft target：请求开始冻结根及全部 SchemaRef 的 revision map。
- Static target：读取精确 StaticSchema DSL snapshots 和 StaticSchemaRef 图。
- 产品内 validator 直接解释 RenderWeave DSL；不把 compiled artifact 交给通用 JSON Schema validator 作最终判定。
- 响应回显 root target 和本次解析到的依赖集合。

### 7.3 批量请求

```json
{
  "target": { "kind": "draft", "schemaKey": "customer-profile" },
  "documents": [
    { "document": { "name": "Ada" } }
  ]
}
```

- `documents` 1–20；每个最大 2 MiB，合计最大 10 MiB。
- 任一文档超过 transport/global limit 时拒绝整次请求，不返回部分结果。
- 每个文档单独返回 valid、problems、truncated；汇总通过/失败数量。
- 外层数组只是 transport envelope，不改变 RootDocument 根规则。

### 7.4 Problem

每条 validation problem 包含：

- stable `code`
- escaped `instancePath`
- `schemaPath`
- language-neutral `messageArgs`

HTTP 层外包 RFC 9457 problem details，并可增加 `traceId`, `violations`, `revision`。客户端只依赖 code/args，不解析英文或中文 prose。

顺序固定：parse/global → Schema 字段顺序 → array index → constraint precedence。类型错误后不检查该值的其他约束/子节点；类型正确时返回所有独立违反项。每文档最多 100 条，达到上限立即 `truncated=true`。

## 8. AI Schema 推断

### 8.1 输入模式与上限

| 模式 | 输入 | 事实优先级 |
|---|---|---|
| image-only | 1–10 PNG/JPEG | 视觉文本、标签/值、分组、重复区域；不生成业务数据 |
| json-only | 1–20 同根 object samples | concrete JSON 结构与类型 |
| combined | 图片 + 同根 samples，无 1:1 映射要求 | JSON 决定 concrete 结构/类型，图片补名称/语义；不可调和冲突阻断 |

图片每张 ≤10 MiB、总计 ≤30 MiB、最长边 ≤4096；拒绝动画和非 PNG/JPEG。服务端校验 magic/header、EXIF orientation，解码后转换为 sRGB、去 metadata 的规范化图片；原始 bytes 随 staging 成功结束即删除，只持久化规范化产物。

JSON inference samples 每份 ≤256 KiB、总计 ≤2 MiB、depth ≤32；模型只接收确定性统计与有界片段，不默认发送全部样本正文。

### 8.2 有界流程

```text
NORMALIZE
→ OBSERVE
→ STRUCTURE (JSON deterministic profiler first, LLM for semantics/grouping)
→ DETERMINISTIC_VALIDATE
→ CRITIQUE
→ REPAIR (max 2 rounds)
→ USER_APPROVAL
→ ATOMIC_CREATE
```

- 各 stage 有 profile 固定的 output token、timeout、tool call、total call 和费用预算。
- provider/network retry 仅由应用执行：network/408/429/5xx 最多 2 次，尊重 Retry-After；其他 4xx/refusal 不重试；invalid structured output 进入 repair。
- 所有 attempt 都计入预算。预算用尽安全失败，不自动升级模型。
- 默认不引入单独 OCR；只有评测证明收益才提交 spec delta。

### 8.3 推断规则

- 100% 样本出现率不自动推出 required；需要图片/用户明确证据和逐项确认。
- 不从有限样本自动固化 min/max/enum；只可作为需确认 suggestion。
- scalar 类型冲突可降级为 text 并产生 warning；object/array/scalar 冲突和 heterogeneous array 阻断。
- concrete + null：用 concrete 类型并记录 null adaptation warning；all-null 阻断；DSL 永不 nullable。
- 每个 nested object 生成独立 CandidateSchema，通过 reference 连接；不产生 inline object。
- object array 对字段取并集，缺失字段 optional；结构冲突阻断，不产生 union。
- 所有观察数组为空时 item type 未解析并阻断；combined 模式只有明确视觉重复证据且逐项确认才可提出 item 候选。
- nested array 直接阻断，不降级成 text。
- 相同 shape 不自动复用/合并 Schema。

### 8.4 Candidate 与 Evidence

- 一个 run 恰好一个 root CandidateSchema，零到多个从 root 可达的 child；不允许孤立新 Schema 或环。
- Candidate 是独立宽松模型，可表达 unresolved type/ref/conflict；正式 DSL 绝不放宽。
- 每个 candidate schema/field 有 run-local opaque ID，支持 key 改名后 evidence、confidence 和状态仍能关联。
- 每个 AI item 包含 evidence、`inferred` 和 confidence。
- 图片 evidence 可有多个 bbox；坐标为规范化后图片、左上原点、整数 0..10000，越界/反向不 clamp，直接 blocker/repair。
- JSON evidence 使用 sample index + JSON Pointer；模型值匹配仍标 inferred。

低置信 AI item 必须逐项结束为 `CONFIRMED`、`RESOLVED_BY_EDIT` 或 `REMOVED`，不提供 confirm-all。

审核允许新增/删除/重排 Schema/field，修改 key、metadata、type、constraints/ref。用户新增项为 `source: USER`，无伪造 evidence/confidence；编辑 AI 项保留原 evidence 并进入 `RESOLVED_BY_EDIT`。删除仍被 bundle 引用的 child 形成 blocker。

Candidate 保存 original immutable、current autosave、apply 时 final；不保留每次编辑 revision。autosave 使用 `expectedCandidateRevision`。

### 8.5 原子创建

AI v1 只能 create-only：

- Candidate 可在用户明确确认后引用既有 Draft/Static；不按 shape 自动复用，不选择 latest。
- 任一 proposed schemaKey 与 active Draft、tombstone 或同 bundle 冲突，整包零写。
- 一个 PostgreSQL 事务冻结 final candidate、创建全部 Draft revision 0、写 edges/creationSource、重跑 DAG/约束校验并把 run 标为 COMPLETED。
- 失败全部回滚，run 回到 REVIEW_REQUIRED；已上传素材保留。
- AI tool surface 不存在 publish、update、delete、SQL、filesystem 或 arbitrary HTTP。

### 8.6 Durable job

```text
QUEUED → RUNNING → REVIEW_REQUIRED → APPLYING → COMPLETED
             └──────────────→ FAILED / CANCELLED
```

- PostgreSQL job + lease + checkpoint 是事实源；内存调度只负责 wake-up。
- single-node 默认最多两个并行 AI run，其余 queued。
- server restart/lease expiry 在同 run 从最近安全 stage 恢复；已完成 stage 不重复。
- queued 立即取消；running cooperative cancel；review 可取消；applying transaction 开始后不可取消。
- 模型调用已产生的费用即使取消也计入统计。
- FAILED/CANCELLED 是终态；人工重试创建新 run，记录 `retryOfRunId`，复用规范化输入并重新预算。
- SSE 仅通知状态变化：每 run 单调 sequence、至少一次、支持 Last-Event-ID；客户端去重后 GET authoritative snapshot。

### 8.7 Provider/Profile 安全边界

- v1 首个 live adapter 是 DashScope 的 OpenAI-compatible Chat Completions HTTP endpoint；领域层只依赖 provider-neutral port，协议 DTO、HTTP client 与 `DASHSCOPE_API_KEY` 只存在于 application adapter。
- 首批模型 Profile 为 `dashscope-qwen37-flash-v1`（`qwen3.7-flash`）和 `dashscope-qwen38-max-v1`（`qwen3.8-max`），均先保持 `EXPERIMENTAL`；同一金标集分别评测后才能决定默认或升级路由。
- Profile 是 repo-versioned resource，保存 provider/model/prompt/structured output/budgets/evaluation identity；run 保存完整 snapshot。
- API Key 只来自外部 secret，不进入 DB、Profile、UI、日志或错误。
- 每次 call 使用 `response_format={"type":"json_object"}`、关闭 thinking、禁用 provider tools/search；prompt 必须明确要求 JSON。合法 JSON 仍须经过 Candidate codec、确定性 validator 和 bounded repair。
- 图片只从服务端规范化 artifact 编码为 Base64 Data URL；adapter 不接受用户提供的远程 URL。每个 stage 显式携带最小所需上下文。
- 不伪造 `store:false` 等跨 provider 语义；只有 DashScope 官方协议明确支持且合同测试覆盖的 retention 参数才发送。应用自身不持久化完整 provider request/response。
- 不保存 chain-of-thought。完整 provider I/O 只在受控 Run storage 政策允许时保存，常规日志永不包含。
- 应用可在无 API Key/无 certified Profile 时启动；确定性功能正常，AI 创建返回稳定 NOT_CONFIGURED/NOT_CERTIFIED problem。
- 上传授权与 live worker 授权是两个独立的部署门，均默认关闭；配置 Key 或选择/预览文件都不会调用模型。新建 live run、复制历史输入的 live retry 以及 queued recovery 必须经过同一组 worker/upload/credential 门。每次开始前明确展示 provider/model/profile、输入范围、费用上界和外部传输提示，由用户点击启动。
- 每个不可逆 provider call 之前，按 UTF-8 文本字节、消息 framing、默认非高分辨率视觉 token 上界及 Profile 最大输出 token 计算保守费用上界，并按 Flash 的 32K/256K 输入长度阶梯同步提高输入与输出单价；超过单 Profile 或全局剩余预算时零调用失败。reservation 是追加式费用账本：创建时以行锁验证 run 存在，此后保留 immutable run UUID 审计值且不随 run 删除；provider 返回实际 usage 后只允许向不超过预留的值结算。
- 当前 P5 live 授权只覆盖仓库合成数据、全局最多 6 次 provider attempt、累计费用上限 ¥1；retry/repair 也计 attempt 和费用，耗尽即安全停止。该授权在 2 次 canary 后已关闭，versioned authorization ledger 阻止旧测试被再次运行；真实业务数据、重新启用 worker/upload 或任何新增调用都需要新的逐次 J1。
- Provider 返回 `Retry-After` 时本次 run 安全失败，不做无视服务端窗口的即时重试；人工或调度恢复必须形成新的明确授权边界。

### 8.8 AI 质量发布门槛

版本化金标语料不少于 60 bundles：image/json/combined 各 20；覆盖中英混合、标量、数组、引用、null/type conflict、未知字段、低信息和 prompt injection。素材只用自制、合成或 CC0，不使用客户数据。45 个开发回归案例 + 15 个发布保留案例；保留案例用于针对性修复后转入开发集并补新例。

完整 60 例全局及每种模式分别达到：

| 指标 | 门槛 |
|---|---:|
| Bundle contract | 100% |
| Schema/entity F1 | ≥0.90 |
| field micro-F1 | ≥0.90 |
| supported type accuracy | ≥0.95 |
| parent-child edge F1 | ≥0.95 |
| evidence coverage | 100% |
| DAG validity | 100% |
| critical hallucination | 0 |

15 个保留案例独立要求 contract/evidence/DAG 100% 且 critical hallucination 0；其他 F1 在保留集扩到每种模式 20 例前只观察。未达标 Profile 可显示为实验/未认证，不能成为默认生产 Profile。

## 9. Web 产品体验

### 9.1 页面

v1 只有：

1. Draft list/editor/history/copy/delete/restore。
2. StaticSchema list/detail/download。
3. Inference run create/progress/review。
4. RootDocument sample validator。

不展示后续 Template/Workspace/Render 的 disabled navigation。

### 9.2 Schema editor

- Form 与一层 left-to-right map 共享一个 `EditorSession/useReducer`。
- root 左侧、fields 垂直排列在右侧；array item/reference summary 可再向右一级。
- @xyflow/react 为 controlled view；节点不能自行创建 edge；drag 只改变字段顺序，不保存坐标。
- deterministic auto-layout；无方向切换、双侧布局或任意 grouping。
- 所有 map 操作有 form/keyboard 等价路径。
- undo/redo 保存最近 100 个 semantic actions；连续 typing 合并。save 不清空，reload 清空；可 restore saved definition。
- local stable rules 在 blur/操作时反馈；graph/server rules 只在显式 save 执行，不做 per-keystroke remote validation。
- raw DSL 只读 preview/copy，不可编辑。引用 child 只显示 summary/navigation，不 inline 编辑。
- 离开 dirty editor 和 `beforeunload` 都拦截；没有 local autosave。

### 9.3 冲突

服务端 409 revision conflict 后：保留本地 EditorSession，获取 server current，展示结构化 diff，允许导出本地或 reload server；不提供 force overwrite 或自动 merge。

### 9.4 Candidate review

复用相同 form/map editor，加 bundle navigation、evidence overlay、confidence、blocker 和 item resolution；不出现“保存 Draft”或“发布”按钮，唯一写入动作是全部门通过后的原子创建。

推断入口和审核详情使用一致的四步进度：准备输入 → 受控识别 → 逐项校对 → 原子创建。运行中展示人类可读 stage；状态机许可时可取消，FAILED/CANCELLED 只允许显式 retry 创建新 run。上传选择必须提供逐文件检查和移除，但选择、预览或切换 Profile 均不得触发 Provider。

Candidate form 是完整键盘路径：支持新增、删除、上移/下移 Schema 与 field，编辑合法类型对应的 constraints，并在一项具有多张图片 evidence 时逐张切换和查看各自 bbox。map 与 form 共享相同顺序和选择；不要求拖拽，不提供 confirm-all。

审核页显示已处理 AI item 数、仍待处理项、blocker/warning 和原子创建 readiness checklist；自动保存稳定、服务端 blocker 为零且 run 仍为 REVIEW_REQUIRED 时才可提交。

### 9.5 视觉和响应

- light-only warm editorial workbench；cream/coral/dark contextual panel。
- semantic Tailwind tokens、owned components、Radix primitives、Lucide icons；不引入 Ant/MUI 全量框架。
- `#a9583e` 承担白字 primary action；`#cc785c` 只作 accent。
- 全面验收 1280×720、1440×900；1024×768 用 inspector drawer 保留所有操作；低于 1024 显示不支持提示。
- 正式支持 Chrome/Edge 最近两个稳定版本；Firefox/Safari best-effort；不支持移动浏览器/WebView。
- 核心流程目标 WCAG 2.2 AA；axe 无 serious/critical，人工键盘走查；canvas 屏幕阅读器语义由等价 form 路径补偿。

### 9.6 Client architecture

- React 19.2.x、TypeScript strict、Vite 8.1.x、Node 24 LTS、npm + package-lock。
- React Router 持有 route/list state；unsaved editor state 只在 session reducer。
- TanStack Query 是 server state 真相；SSE 只 invalidates/refetches。mutation 不自动 retry。
- 由固定版本 `@hey-api/openapi-ts` 生成 Fetch types/SDK；SSE 手写，不生成 React Query/Zod。

## 10. 服务端、数据与 API

### 10.1 模块

Java 21 / Spring Boot 4.1.x modular monolith：

- `renderweave-schema`：DSL、constraints、Draft/Static lifecycle、reference graph、compiler。
- `renderweave-validation`：RootDocument parser/validator；单向依赖 schema public API。
- `renderweave-inference`：job/candidate/evidence/profile/provider port；依赖 schema + validation public API。
- `renderweave-app`：Spring MVC、JdbcClient/NamedParameterJdbcTemplate、transactions、Flyway、worker、HTTP/adapters。

不建立通用 `common` 模块。ArchUnit 证明模块方向和禁止能力。

### 10.2 PostgreSQL

- 所有环境只使用 PostgreSQL；测试由 Testcontainers 提供。
- DSL snapshot 使用 `jsonb`；compiled JSON Schema 使用 `json`/exact text，读取时不重新序列化。
- revision/Static 保存完整 snapshot；reference edges 是同事务 projection，不做字段拆表。
- application/domain/repository 强制 Static append-only；数据库保留 PK/unique/FK/check，不增加 UPDATE/DELETE immutability trigger。
- Flyway 是唯一 schema migration 入口；不由 ORM 自动建表。生产数据访问用明确 SQL，不使用 JPA。

### 10.3 HTTP contract

- REST/JSON，base `/api/v1`，OpenAPI 3.1.2 source file；breaking contract 才进入 `/api/v2`。
- OpenAPI `info.version` 是 contract release，不等于 path version。
- Java controller/DTO 手写并做 contract verification；不生成 Java domain/server code。
- RFC 9457 `application/problem+json` 扩展 `code`, `traceId`, `violations`, `revision`。
- list pagination：page 从 1、default 20、max 100、返回 total；sort 只接受 whitelist 并附稳定 tie breaker。
- 只有创建 InferenceRun 要求 `Idempotency-Key`；相同 key+same input 返回原 run，不同 input 409。其他资源依赖 natural identity/revision。
- production same-origin：Web `/`、API `/api/v1`；dev Vite proxy；不配置 wildcard CORS。

## 11. 部署、存储和可观测性

- 单节点：独立 Web artifact、Spring Boot API、PostgreSQL、persistent filesystem BlobStore；Docker Compose 是 reference topology。
- 不承诺 HA；不引入 Redis/queue service。
- Inference raw input 只 staging；normalized image/metadata-stripped artifact 持久化，直到用户永久删除最后引用 run。
- v1 不做自动 expiry、Blob quota、容量预检或容量看板；由部署方监控磁盘。
- storage write failure → run FAILED + `STORAGE_WRITE_FAILED`，清理未登记临时文件，零 Schema 写入。
- 应用内不做 backup scheduler/UI。PostgreSQL 与 BlobStore 由运维在同一维护窗口备份；恢复后只读 integrity check，缺失素材标 `ARTIFACT_MISSING`，不影响 Schema。
- Actuator 暴露 liveness/readiness/database health。
- 结构化 JSON logs 带 traceId，任务日志带 inferenceRunId；指标覆盖 API、job、model usage/cost/retry。
- 不绑定 Prometheus/Grafana/云遥测，只保留标准导出能力。

## 12. 容量与质量边界

- 验收基线：10 个同时活跃桌面会话、默认最多 2 个并行 inference runs。
- 数据规模基线：10,000 SchemaKey、100,000 Draft revisions、10,000 StaticSchema、10,000 InferenceRun。
- 超出可继续运行，但 v1 无性能承诺；Phase gate 必须在该数据量上记录测量，不凭空声称 SLA。
- compiled artifact ≤2 MiB；RootDocument/input/batch 上限见前文。

非 AI 发布验证：

- DSL/compiler/validator：unit + golden + property tests。
- revision/graph/publication/concurrency：PostgreSQL Testcontainers integration tests。
- OpenAPI lint/breaking check + generated TypeScript typecheck。
- EditorSession reducer/component + Playwright critical journeys。
- 每个关键 Rule/AC 映射测试；coverage 是报告，不设置可刷的统一百分比门槛。

## 13. 验收标准

| AC | 可观察行为 | 主要 Rules | 验证 | 期望保证 |
|---|---|---|---|---|
| AC-001 | 用户只能创建/保存完整合法的 object-root Draft；任一字段/约束/ref 问题零写。 | R-SCH-001, R-SCH-004 | domain + PG integration | A1；release A2 |
| AC-002 | Schema/field identity、key 语法、不可改/不可复用和无 fieldId 契约成立。 | R-SCH-002, R-SCH-003 | unit + architecture + API contract | A1/A2 |
| AC-003 | expectedRevision 冲突不覆盖；历史只读；restore 产生新 revision。 | R-SCH-005, R-SCH-006 | concurrent Testcontainers | A1/A2 |
| AC-004 | Draft refs 全部可解析，request snapshot 稳定，所有组合图无环且 depth≤16。 | R-SCH-004, R-REF-001, R-REF-002 | property + concurrent integration | A1/A2 |
| AC-005 | incoming ref 阻止删除；soft delete/restore/tombstone/edges 行为符合规格。 | R-REF-003 | PG integration + API E2E | A1/A2 |
| AC-006 | 发布只消费 exact saved revision + explicit tag + Static refs，事务失败零发布。 | R-STA-001..003 | integration + fault injection | A1/A2 |
| AC-007 | Static 和 system presets 可读/引用但不能修改、删除或重编译。 | R-STA-001 | repository/architecture/API tests | A1/A2 |
| AC-008 | 七种 DSL type、全部 constraint、decimal/date/time/regex 规则准确执行。 | R-DSL-001..005 | table/golden/property tests | A1/A2 |
| AC-009 | array item、nested array、uniqueItems 和 10k 上限正确。 | R-DSL-002, R-DSL-006 | golden/property tests | A1/A2 |
| AC-010 | 自底向上完全内联 artifact、extensions、version 和 2 MiB rollback 可复现。 | R-CMP-001..003 | byte golden + integration | A1/A2 |
| AC-011 | strict JSON/duplicate/null/unknown/global limit 和 DSL validation 权威性正确。 | R-VAL-001, R-VAL-002, R-CMP-003 | parser/property/integration | A1/A2 |
| AC-012 | 1–20 文档按稳定顺序产生完整、上限 100 的问题和解析快照。 | R-VAL-003 | golden + API contract | A1/A2 |
| AC-013 | 表单/map 同一 state，切换、undo/redo、显式 save、dirty guard 无损。 | R-UX-001, R-UX-002 | reducer/component/Playwright | A1 + J1 |
| AC-014 | 冲突保留本地，1024/1280/1440 操作完整，键盘路径与 WCAG 门槛满足。 | R-UX-003 | Playwright + axe + manual keyboard | A1 + J1 |
| AC-015 | 三种 inference mode、输入限额、归一化和显式外部确认正确。 | R-INF-001 | replay/integration/E2E | A1/A2 + J1 live |
| AC-016 | bounded pipeline、merge/null/array/object 推断和 blocker 规则确定。 | R-INF-002, R-INF-006 | 60-case replay/eval | A2 |
| AC-017 | evidence/confidence/candidate IDs、逐项低置信处置和审核编辑完整。 | R-INF-005 | component + integration + eval | A1/A2 + J1 |
| AC-018 | create-only materializer 全有或全无，冲突回 review，工具面无 publish/update/delete。 | R-INF-003, R-INF-004 | fault/concurrency + architecture | A2 |
| AC-019 | job lease/checkpoint/SSE/cancel/restart/manual retry 不重复调用或创建。 | R-INF-007 | deterministic worker + PG fault tests | A2 |
| AC-020 | provider secret、预算、retry、store:false、无 CoT/敏感日志、缺配置降级正确。 | R-INF-001, R-INF-004, R-OPS-002 | adapter contract + safe canary | A2 + J1 live |
| AC-021 | 60-case、mode slice 和 holdout 质量门槛决定 certified status。 | R-INF-008 | independent evaluation | A2；生产 policy J1 |
| AC-022 | OpenAPI 3.1.2、RFC9457、pagination、SSE 和 generated Fetch SDK 一致。 | R-API-001 | contract lint/generation/E2E | A1/A2 |
| AC-023 | modular dependencies、explicit SQL、JSONB snapshot、edge projection 和 graph locks 正确。 | R-SCH-005, R-REF-002 | ArchUnit + Testcontainers | A1/A2 |
| AC-024 | single-node deploy、health/log/metrics、storage failure、backup integrity 和 run deletion 可操作。 | R-OPS-001, R-OPS-002 | Compose canary + recovery drill | A1/A2 + J1 ops |
| AC-025 | 产品、API、DB 和 UI 中不存在 Template/Workspace/Adapter/Renderer 占位实现。 | R-SCOPE-001 | architecture + route/schema inventory review | A1/A2 |

## 14. 产品级退出场景

v1 只有在以下五条端到端 journey 全部通过后才可进入人工验收：

1. 子 Draft → 子 Static → 根 Draft 精确引用 → 根 Static → 下载完全内联 artifact。
2. 同一批 RootDocument 分别对 saved Draft 和 exact Static 验证，诊断稳定且 unknown fields 被接受。
3. image/json/combined 上传 → durable job → evidence review → blocker resolution → atomic Draft Bundle，published count 始终不变。
4. Form/Map 无损切换、undo/redo、revision conflict 和 1024 drawer 路径完整。
5. worker restart/cancel/failure/concurrent apply 不留下部分 Schema、重复调用或重复费用记录。

自动 gate 全绿但 J1 UI/业务判断待确认时，生命周期状态只能是 `human_acceptance_pending`，不能报告 `accepted`。
