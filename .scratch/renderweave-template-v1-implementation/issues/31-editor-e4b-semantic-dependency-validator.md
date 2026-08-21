# 实现 Editor E4b StaticSchema 与 child-fill 语义依赖校验

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 07, 15, 17, 19, 20, 30（均已 resolved）

## Question

如何补齐 T30 明确留出的完整 E4 dependency surface：对 admitted canonical DesignDSL 中全部
StaticSchema field path 建立 exact type/presence proof；按真实 Repeat 祖先关系解析 invocation/loop lexical
domain；让 Repeat items、Conditional condition、普通 ValueSource 与 TemplateUse ContextSelector 消费正确的
proof；并针对 child current 的永久 StaticSchema 与 PUBLIC CustomDefinition 校验 selector/fill，使 path 不存在、
Schema/type/presence 漂移、target 消失或转 PRIVATE 成为可确认的 dependency ERROR，而非法 lexical domain、
child canonical/content integrity 与既有 cycle/cross-scope/hard/limit 继续零写？

## Answer（本票冻结的实施决定）

1. **Template-owned 单一语义解释**：App/Rendering 不复制 DesignDSL walker。`renderweave-template` 在已准入
   canonical bytes 上建立内部 semantic view；child current 也携带 exact canonical bytes，并在使用前重新 admission、
   核对 canonical bytes/contentHash。破坏该不变量属于不可确认 integrity hard error。
2. **exact StaticSchema definition authority**：Schema provider 的 exact-reference authority 返回 immutable
   `SchemaDefinition`，Template 只按 `StaticSchemaRef` 读取；不使用通用 JSON Schema validator，不修改、删除或重编译
   StaticSchema。reference traversal 大小写敏感、RFC 6901 解码，禁止数字下标/wildcard 穿越 array；路径不存在或
   consumer 类型不兼容是 dependency ERROR，合法 optional ancestor 形成 MAY_BE_ABSENT proof 而不是不存在。
3. **loop lexical context**：按 authored tree preorder 建立 `loopId → ancestor loops + item context`。Repeat 自己的
   items 在父域解析；descendant 只可读取自身或祖先 loop，sibling/descendant/self-items 越界是 hard error。
   scalar list item 精确映射到 `system-basic-{text|decimal|date|time|boolean}@v1`；reference array item 保留 exact
   item StaticSchemaRef，不按 shape 推断。
4. **全部 field-path occurrence**：definitions、Expression inputs、Mapping input/result、node bindings、Repeat items、
   Conditional condition、TemplateUse selector/fills 中每个 context source 都必须解析到允许的 closed type。
   Repeat items 只接受五种 scalar list 或 exact reference array；Conditional condition 必须 boolean；普通 ValueSource
   禁止以 reference object/array 冒充 scalar/list。Schema 驱动的 path/type/presence 不兼容属于 dependency ERROR；
   仅由 authored definition/domain 决定的越界或已声明类型不兼容属于 hard error。
5. **TemplateUse selector**：`{kind:"empty"}` 只匹配 child 永久 `system-empty@v1`。context selector 的空 pointer
   选择所选 invocation/loop 的 exact context；非空 pointer 必须结束于 reference-typed object；proof 中 exact
   StaticSchemaRef 必须等于 child 永久 StaticSchemaRef。missing/wrong kind/ref mismatch 均为 dependency ERROR，
   `SKIP` 不掩盖保存期问题。
6. **PUBLIC fill**：每个 fill target 必须存在于 child current、kind=`custom`、exposure=`PUBLIC`；source 在
   TemplateUse 所在 lexical domain 静态求型，definition/loopIndex/context 均与 target `valueType` 精确同型。
   missing、非 Custom、PRIVATE 或类型漂移是 dependency ERROR；duplicate target/非法 source wire 仍由 admission
   hard reject。省略 fill 与合法 runtime ABSENT 继续使用 child default，本票不改变运行时求值顺序。
7. **确认与并发**：新问题复用 T30 bounded report、problem fingerprint、五分钟 opaque token、fresh revalidation 与
   SERIALIZABLE dependency snapshot fence。StaticSchema exact ref 永久不可变，无新可变事实表；child revision/
   contentHash/staticSchema 已在 snapshot 中，canonical bytes 由 contentHash 绑定，因此无需 migration 或新 token wire。
8. **稳定诊断**：新增 code 只携带 category/severity/canonical pointer 与有界安全参数；不得回显 StaticSchema DSL、
   child DesignDSL、RootDocument 或值。problem 继续按 pointer/code UTF-8 排序并受 200 项/字节预算；truncated 仍 hard。
9. **产品面复用**：OpenAPI/Web 已能渲染任意 bounded dependency problem 并执行具名“仍保存为 INVALID”。本票不新增
   API version、generated SDK 字段、Web placeholder 或 route；只增加回归证明新 code 沿既有 E4 流程正确出现、确认、
   漂移失效和成功保存 INVALID。
10. **边界**：不实现 E5 reconciliation、E6 preview、E7 recovery、E8 import、E9 完整 locator/a11y、copy/restore
    confirmation、Renderer/Profile/daemon output/READY、provider/真实数据/API Key/付费调用。Expression 运行时求值、
    capability demand 与 RenderDocument materialization 仍属 ADR-0044 的 Rendering authority；本票只完成 E4 的静态
    dependency admission/readiness 语义，不宣称完整 Renderer 或 Template v1 READY。

## TDD、验证与完成信号

- 先写 Template pure RED：StaticSchema scalar/reference/array/optional path、nested ref、非法 traversal、loop ancestor/
  sibling/self-items、Repeat/Conditional 类型、empty/context selector、child missing/PRIVATE/type/schema drift、stable
  problem ordering/budget 与 child integrity hard。
- 再写 App/PostgreSQL RED：authority 返回 exact definition、child current canonical bytes、READY/confirmed INVALID 的
  snapshot fence 与 readiness recheck；不新增 migration。
- 局部 focused Maven → `template` → `server`/`web`（Web 只做既有 generic contract 回归）→ `fast` → 最终 `full`；
  PostgreSQL 语义只用 Testcontainers。
- 完成后改为 `resolved / automated_verified`，更新 map/plan/log/NOTES，形成单一 verified local commit 且 worktree
  clean；不 push/tag/PR，不升级 Editor/Renderer/Template v1 READY。

## 完成结果（2026-08-21）

- `renderweave-template` 新增唯一语义 walker：在 admitted canonical DesignDSL 上解析全部 context source，按
  exact immutable `SchemaDefinition` 证明 scalar/list/reference/type/presence；RFC 6901 大小写敏感遍历允许
  Static reference 逐层进入，array index/wildcard 与不存在路径均 fail closed。
- Repeat items 在父域解析，descendant 仅可见自身与祖先 loop；五种 scalar list 映射到 exact
  `system-basic-*@v1`，reference list 保留 item StaticSchemaRef。Node binding 的 bindability 仍由
  `BindingPolicyCatalog` 决定，永久属性 ValueType 由 `NodeContractCatalog` 提供。
- TemplateUse 对 child current canonical bytes 重新 admission 并核对 contentHash；empty/context selector 必须精确
  匹配 child 永久 StaticSchema，fills 必须命中 child current PUBLIC CustomDefinition 且 source 同型。Schema/child
  漂移进入 T30 confirmation，lexical/integrity/cycle/cross-scope/limit 继续 hard 零写。
- App authority 从 StaticSchema 不可变 DSL 解析 exact definition，并为 dependency resolution/snapshot fence 读取
  child current canonical bytes；PostgreSQL readiness recheck 已证明 child definition drift 会把 parent 降为 INVALID。
  未新增 migration、OpenAPI 字段、generated SDK、route 或产品占位面。
- pure Template 覆盖 10 组 semantic scenarios，并显式覆盖 array 非法穿越、Repeat 非列表、ancestor/self/out-of-scope
  lexical domain、PUBLIC fill/schema/type drift、child integrity、UTF-8 稳定排序与 201 项共享 budget 截断；App
  Testcontainers focused 10/10，Web 复用既有 generic confirmation flow。
- 分级证据：`template` `.sdlc/evidence/20260821-140538-template/`（Schema 20、Template 78、kernel
  Java/Python 211/211）、`server` `.sdlc/evidence/20260821-134445-server/`（8/8 reactor、App 344、0 failures）、
  Node 24 `web` `.sdlc/evidence/20260821-135643-web/`（19 files/127 tests + build）、`fast`
  `.sdlc/evidence/20260821-135722-fast/`。最终 exact-manifest `full` 目录仅在 commit handoff 报告，不反写本票。
- 生命周期最高为 `automated_verified`。T30+T31 关闭 Editor E4 dependency-save 语义，但不外推到 E5–E9、产品 route、
  Renderer/Profile/formal records/J1/A3/READY。Provider attempts/API Key reads/open authorization 均为 0；未发送
  真实数据、未调用付费外部模型，未 push/tag/PR。
