# ADR-0042：以 TemplateApplication 与事务型 persistence seam 守住聚合

- 状态：accepted
- 日期：2026-08-18
- 决策来源：Template v1 implementation Wayfinder Ticket 04；用户授权采用推荐方案
- 关联：ADR-0003、ADR-0041、TV1-T04、冻结 checkpoint
  `0b485f4a13de9d754a81d07f464730776e13c14b`

## 背景与约束

Template 是可变聚合，但它保存的每个 Template revision 都是完整、不可变的 Canonical DesignDSL snapshot。
`ownerScope` 与 exact `StaticSchemaRef` 在创建时永久绑定；current 只能随成功追加单调前进。调用者不能自报
scope/capability，保存也不能借请求重绑 Schema、覆盖 current JSON、绕过 `expectedRevision`，或把 SQL/JDBC
失败暴露给产品调用方。

同时存在三种用途不同的调用者：作者需要 create/read/save/history/export；Rendering 需要经过权威重检的一致
snapshot/closure；Asset 删除流程只需要 current-only reference proof/reservation。若把三者塞进 repository 或一个
泛化查询服务，任何调用者都可能取得不需要的聚合状态。若把每一行 CRUD、事务 callback 或 PostgreSQL lock
暴露到领域 Interface，则 Template 语义会由 Adapter 决定。

Ticket 04 只冻结接口、状态转换与未来可执行验收；不创建 Java Interface、migration、表、route 或空实现。
Ticket 06 只在同票具备真实 implementation、PostgreSQL Adapter、OpenAPI route 与测试时物化首个最小 surface。

## 决策

### 1. 三个 provider-owned Interface 各服务一种能力

Template module 最终拥有三个相互独立的 public Interface：

| Interface | 调用者 | 唯一职责 | 何时允许进入产品源码 |
| --- | --- | --- | --- |
| `TemplateApplication` | app HTTP/assembly Adapter | 作者侧 aggregate commands、current/history read 与 exact export | Ticket 06 首先只加入真实 `create/getCurrent/save` |
| `TemplateSnapshotAuthority` | Rendering | 在 render authority 下重检 current 并形成 snapshot/closure | Ticket 07 有真实 consumer 与 tests 时 |
| `AssetReferenceAuthority` | Asset app bridge | current-only AssetRef proof/reservation | Ticket 05/后续真实 Asset 删除纵切时 |

这三个 Interface 不互相继承，也不返回 persistence record。`TemplateApplication` 的 command/outcome/value types
作为其嵌套 closed types 起步，以减少 public top-level surface；新增方法必须与同票真实行为一起出现。不得为
copy/restore/delete/confirmation/history/export 或 closure 提前增加抛出 unsupported 的方法。

`DesignDslAuthority` 继续是同一 Module 内 canonical admission 的独立 Interface。`TemplateApplication` 必须
通过它取得 canonical bytes 与 content hash，不复制 parser/canonicalizer，也不接受调用者自报 hash。
Ticket 06 的 app assembly 通过 ADR-0041 所述 exact `TemplateModule.application(...)` factory 取得
`TemplateApplication`；factory 不是第四个 behavioral Interface，也不向 app 暴露 canonical Implementation。

### 2. 聚合与 revision 的事实边界

Template aggregate 只拥有以下 durable facts：

- 服务端生成、全局唯一且永不复用的 opaque `templateId`；具体文本编码不是 v1 调用者合同；
- 创建时由 Host authority 给出的不可变 `ownerScope`；
- 创建时选择并验证存在的 Schema-owned exact `StaticSchemaRef`，此后任何 mutation request 都不再接收 Schema；
- `ACTIVE | DELETED` lifecycle、唯一 current revision number、current-facing readiness/report projection；
- 从 revision 0 开始连续追加的完整 revision snapshots。

每个 revision durable record 至少包含 `{templateId, revision, canonical DesignDSL JSON value, contentHash}`。作者
metadata 只在 DesignDSL 内，不复制到可独立修改的 aggregate metadata。current 始终等于最新成功追加的 revision；
相同 content hash 的 accepted save 仍追加下一编号。restore 未来也只复制旧内容并追加，绝不回拨 current。

PostgreSQL 以完整 JSONB snapshot 保存 DesignDSL；JSONB serializer 不是 canonical authority。每个 trusted read
都把持久 JSON value 重新交给 `DesignDslAuthority`，要求重新得到的 canonical bytes 与 hash 同时匹配 durable
record；不匹配返回 `TEMPLATE_INTEGRITY_MISMATCH`、零自动修复、零历史回写且不把 payload 写入日志。

### 3. 请求输入与 Host authority 完全分离

`TemplateApplication` 每个调用接收 app 从可信 Gateway context 建立的 server-only opaque
`TemplateInvocationRef`，以及不含授权事实的 use-case command。该 reference 不是 HTTP field、不能序列化到
DesignDSL/export，也不携 raw Gateway token。

Template-owned outbound `OwnerScopeAuthority` 只有三类 closed operation：

1. `authorizeCreate(invocation)`：解析调用者的创建 scope，返回 granted scope、opaque recheck identity 与响应
   disclosure，或 denied/unavailable；
2. `authorizeExisting(invocation, storedOwnerScope, operation)`：只对 persistence 定位出的可信 scope 判断
   `READ | UPDATE | DELETE | RENDER`，返回 granted/recheck/disclosure、hidden、forbidden 或 unavailable；
3. `recheck(recheckIdentity)`：在 mutation commit、confirmation commit、export seal 或其他对应线性化点再次取得
   closed current decision。

Adapter 才把这些 operation 映射到 `template.read/create/update/delete/render` 字符串。command/request 不含
`ownerScope`、capability、role、成员、Workspace、authorized boolean 或 recheck identity。生产没有可信 Host
Adapter 时 assembly fail closed；测试 Adapter 使用 ordered exact expectations，不能自行签发 admitted result。

existing-template 调用先用 persistence 的 metadata-only locate 取得 ownerScope/lifecycle/current/Schema，完成
authority 决策后才可加载 DesignDSL。跨 scope，或既无目标 operation 又无 read 时对外折叠
`TEMPLATE_NOT_FOUND`；已有 read 但缺目标 operation 返回 `TEMPLATE_FORBIDDEN`。只有 mutation capability、没有
read 仍可提交，但成功只返回 opaque receipt；conflict 等响应不得泄露 current revision、DesignDSL、child 或
Asset detail。具备 read 时才返回 canonical authoring snapshot。

### 4. `TemplatePersistence` 是事务型 outbound seam，不是 repository API

Template module 拥有 `.spi.TemplatePersistence`；app 提供 PostgreSQL Adapter，module tests 提供 ordered
scripted Adapter。它按 aggregate use case 暴露 method-specific closed operations，而不是 `save(entity)`、泛型
CRUD、JDBC callback 或 `Map<String,Object>`：

- metadata-only `locate(templateId)`；
- integrity-bearing `loadCurrent(templateId)` 与 `loadRevision(templateId, revision)`；
- `create(AdmittedCreateCommit)`；
- `append(AdmittedAppendCommit)`。

commit 只能由 Template internal Implementation 构造，携 server-resolved scope、permanent Schema、canonical
DesignDSL/hash、expected/next revision、readiness 以及真实 current dependency projection；它不携 SQL、table/
column name、lock、transaction handle、exception 或 HTTP status。`create` outcome 只允许 created/id-collision/
unavailable；`append` 只允许 appended/not-found/deleted/revision-conflict/unavailable。Adapter 必须把 SQL state、
Spring/JDBC exception 与 unique violation 穷尽映射，不能泄漏 repository-specific failure。

初始 Ticket 06 的 kernel 不能产生 AssetRef/TemplateRef dependency，因此 commit 的真实 projection 是空集合，
readiness 是 READY，current report 是空投影；不得为未来 graph 创建假 edge 或占位 report 表。完整 DesignDSL
语义进入后，同一 Interface 的 commit 才携真实 projection，并在 Template-specific graph consistency boundary
内做 DAG/incoming-reference 检查。

### 5. create/save 的精确顺序与事务不变量

Create 的顺序固定为：

```text
authorizeCreate → DesignDSL hard admission/canonical count → exact StaticSchema existence
→ dependency validation → authority recheck → one atomic create commit
```

Create 只允许全部依赖合法的 strict success；dependency ERROR 不签发 INVALID confirmation。成功事务全成：
aggregate identity/scope/Schema + immutable revision 0 + current=0 + READY + empty/real current projections。任一步
失败均零 durable write。

Save 的顺序固定为：

```text
metadata-only locate → authorize UPDATE → DesignDSL hard admission/canonical count
→ validate against stored permanent StaticSchema and current dependencies
→ optional confirmation validation → authority recheck → one atomic append commit
```

Save command 精确携 `{templateId, expectedRevision, complete raw DesignDSL}`；future confirmed variant再额外携
opaque confirmation token。它不携 ownerScope、StaticSchemaRef、current、readiness、contentHash、patch、merge
base 或 force flag。成功事务必须同时：

1. 重新确认 target ACTIVE 且 current=`expectedRevision`；
2. INSERT `expectedRevision + 1` 的完整 immutable snapshot；
3. 把 current 单调推进到该新 revision；
4. 整体替换 current-only dependency projections；
5. 设置 READY/INVALID 与对应 current report。

任何 unique/fault/conflict/authority/dependency failure 回滚全部五项；不得留下 orphan revision、半套 edge、旧
report 配新 current 或已推进 current 配缺失 snapshot。两个并发 `expectedRevision=n` save 必须恰好一个成功，
另一个返回 `TEMPLATE_REVISION_CONFLICT`；绝不 last-write-wins 或自动 merge。

服务端只有 best-effort request debounce，不提供 command-key idempotency。响应不明时，save 由客户端按原
expectedRevision、proposed contentHash 与 trusted current 做既定 reconciliation；create/copy 重试可能产生重复
对象，不能靠 content hash 去重或把一次提交归属于某个未知请求。

### 6. closed product outcomes

每个方法拥有自己的 sealed outcome，不使用异常表示 expected domain failure，也不创建 global `Result`/
`Problem`。最低 closed shape 为：

| Operation | Success variants | 非成功 variants |
| --- | --- | --- |
| create | readable canonical current 或 opaque committed receipt | design rejected、Schema not found、forbidden、authority/persistence unavailable |
| get current | canonical current | not found、deleted、integrity mismatch、authority/persistence unavailable |
| save | readable canonical current 或 opaque committed receipt | design rejected、confirmation required、not found、forbidden、deleted、revision conflict、confirmation invalid/expired/stale、integrity mismatch、authority/persistence unavailable |
| get exact revision | canonical exact revision（ACTIVE/DELETED） | not found、revision not found、integrity mismatch、authority/persistence unavailable |
| export exact revision | exact media type + canonical export bytes | 与 exact revision read 相同；integrity failure 不产出 partial bytes |

稳定 Template code 至少区分 `TEMPLATE_NOT_FOUND`、`TEMPLATE_FORBIDDEN`、`TEMPLATE_DELETED`、
`TEMPLATE_REVISION_NOT_FOUND`、`TEMPLATE_REVISION_CONFLICT`、`TEMPLATE_STATIC_SCHEMA_NOT_FOUND`、
`TEMPLATE_DEPENDENCY_ERROR`、`TEMPLATE_CONFIRMATION_INVALID/EXPIRED/STALE`、`TEMPLATE_REF_CYCLE`、
`TEMPLATE_INTEGRITY_MISMATCH`、`TEMPLATE_AUTHORITY_UNAVAILABLE` 与 `TEMPLATE_PERSISTENCE_UNAVAILABLE`。
Design rejection 由 `TemplateApplication` 穷尽映射同 Module 的 `DesignDslAuthority` closed failure；HTTP status、
RFC problem envelope、localized message 与 redaction 只属于 app Adapter。

### 7. INVALID confirmation 是重验协议，不是保存旁路

只有结构/hard rules 全通过而 dependency ERROR 完整且未截断时，save/copy/restore 才能返回
`ConfirmationRequired`。它包含 canonical ordered bounded problems、`proposedContentHash` 与 opaque token；出现
`PROBLEM_LIMIT_REACHED` 时不得签发 token。Create、hard error、cycle、安全或预算错误永远没有 confirmation。

未来真实 dependency slice 才允许加入 Template-owned `.spi.InvalidCommitConfirmationAuthority` 及 production/
scripted Adapters；Ticket 06 不创建不可达的 SPI 或 outcome。token 必须绑定 operation、authorization subject/scope、
target permanent Schema、target/source identity、expectedRevision、content hash、完整 problem fingerprint、exact
dependency snapshot 与 expiry。confirmed request 必须重交完整 DesignDSL 与 expectedRevision；服务端重跑全部
admission/dependency checks，比较 token bindings，并再次 recheck Host authority。任何漂移返回 expired/stale/
conflict 且零写；不存在裸 `force=true`。具体短期 TTL 只在首个真实 confirmation slice 中连同 clock/key Adapter
一起冻结，本票不为不可达路径发明配置值。

### 8. read/export 与 current projection

`getCurrent` 只为 ACTIVE Template 返回 Canonical editor baseline：exact revision、permanent StaticSchemaRef、
contentHash、canonical DesignDSL、readiness 与 current report。report/readiness 只是 UI projection，读取 Editor
或 Render 时仍按各自合同权威重检。

`getRevision` 与 `exportRevision` 钉死 `{templateId, revision}`，对 ACTIVE/DELETED history 都可用；历史结果不
附 current readiness/report。export 使用冻结的
`application/vnd.renderweave.template-revision+json` envelope，先 trusted-read 校验，再一次性 seal exact bytes；
后续 current 漂移不改变 artifact。任何读取都不隐式 migration、重算/回写 hash 或重写旧 revision。

### 9. forward-only persistence 与可验证不变量

- Ticket 06 到达时选择当时未占用的下一 Flyway version；既有 migration 永不改写，也没有 down migration。
- aggregate/revision 使用普通 PK/unique/FK/check 与显式 SQL，延续 ADR-0003 的 no-immutability-trigger 选择。
- `TemplatePersistence` 不提供 revision UPDATE/DELETE、aggregate purge、Schema/scope rebinding 或 generic SQL；
  production Adapter 也不得含针对 revision content 的 UPDATE/DELETE。未来 schema evolution 只能追加 migration
  与派生列/投影，不能改旧 DesignDSL/hash。
- FK/约束不得级联删除 Template、revision 或 permanent StaticSchema binding；DELETED 仍保留全部 history。
- focused contract tests 必须通过正式 `TemplateApplication` 驱动 scripted Adapter；PostgreSQL behavior 通过
  Testcontainers 驱动同一 public Interface，覆盖 same-hash append、双连接 lost-update、故障注入 rollback、
  missing Schema zero-write、scope injection absence、permanent Schema/scope、deleted/history 与 corruption read。
- architecture/public-surface tests 锁定 API/SPI/internal ownership、两个真实 Adapter、无 JDBC/Spring 泄漏、
  persistence surface 无 update/delete/rebind/purge。测试可用 direct JDBC 注入 corruption/fault，但不能构造
  authority-only admitted/committed value或绕过 public Interface 声称产品成功。

冻结 Ticket 04 atomic requirements 的实现追踪按以下区间保持，不重编号或重发 requirements：

| Requirement range | 本 ADR 的实施落点 |
| --- | --- |
| `RW-T04-S1-001..008` | §1–2 identity、permanent bindings、lifecycle ownership |
| `RW-T04-S1-009..020` | §3–6 strict create、complete save、append/current/concurrency |
| `RW-T04-S1-021..037` | §1、§7–8 冻结 copy/restore/history ownership，但坚持 real-slice 才物化 method |
| `RW-T04-S1-038..048` | §6–7 hard/dependency/confirmation closed outcomes |
| `RW-T04-S1-049..070` | §2、§5、§8 readiness/report 与 current-only projection 原子性 |
| `RW-T04-S1-071..086` | §1、§4–5 的 snapshot/reference authority、DAG/delete transaction 边界；实现随真实 graph slice |
| `RW-T04-S1-087..092` | §5、§9 全成全不成、conflict 与 no-command-idempotency |

## 备选方案

| 方案 | 未选择原因 |
| --- | --- |
| 把 `TemplateStore` 作为 public CRUD Interface | 调用者可绕过 canonical admission、Host authority 与原子 projection，且 SQL row 成为领域合同 |
| 每个 command 一个 service/repository | authorization、integrity read 与错误映射分散，surface 浅且容易漂移 |
| 一个 generic command bus + `Result<T,E>` | operation identity、closed failure 与 disclosure 退化为字符串/泛型袋，违反 ADR-0041 |
| current JSON 原地 UPDATE，另做可选 history | 无法保证每个 accepted save 都是 immutable revision，也让 history/current 可分叉 |
| 完整 event sourcing | 当前事实只需 aggregate pointer + immutable snapshots；引入 replay/upcaster/event contract 没有额外产品价值 |
| 把 ownerScope/Schema 写入每次 save command 或 revision | 允许调用者自报/重绑永久事实，并造成历史重复与漂移 |
| 用 pending-invalid server draft 支撑 confirmation | 违反 EditorSession 本地草稿与服务端不持久化 invalid editing draft 的冻结边界 |
| 数据库 immutability trigger | 与 ADR-0003 已选择的 explicit SQL/application enforcement 冲突；普通约束、窄 SPI 与 Testcontainers 足够形成 v1 可审计边界 |

## 后果与边界

该设计把 Template 的高杠杆语义集中在一个作者 Interface 和两个专用 provider Interface；PostgreSQL、Host、
confirmation key/clock 都藏在 consumer-owned seams 后。代价是 app 需要 total outcome mapping，且 update-only
调用者必须有独立的最小回执投影。

本 ADR 只冻结实施合同，没有创建/运行 aggregate、DB、HTTP、browser 或 Renderer；自动文档/gate 通过也不
证明 Template READY。Ticket 19、Capacity formal records、Editor product records 与 Renderer 外部认证状态不变。
