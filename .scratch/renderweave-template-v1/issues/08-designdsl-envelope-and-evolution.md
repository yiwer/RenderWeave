# 冻结 DesignDSL envelope、身份与演进规则

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 03, 04, 07

## Question

DesignDSL 顶层 envelope、版本身份、节点/definition/binding 的局部身份、严格解析、大小与深度、未知 member、无损编辑、规范化、hash、版本升级及失败封闭规则应是什么，哪些信息属于 Template envelope 而非内容事实？

## Inherited constraints

- DesignDSL 包含 `displayName` 等作者 metadata，但不包含 templateId、StaticSchemaRef、current、生命周期、readiness 或并发状态。
- 每个 revision 保存完整 DesignDSL；导入只进入本地 EditorSession，显式保存才产生 revision，同内容的每次 accepted save 仍追加 revision。
- TemplateRef 在 authored DesignDSL 中只能跟随 templateId/current，不能钉死 revision；其精确 wire 与本地 identity 仍由本票据冻结。
- 必须区分永不可确认的结构/版本/安全 hard error 与可二次确认后保存 INVALID 的依赖 ERROR。
- DesignDSL 顶层必须容纳版本封闭的 `definitions[]` union；CustomDefinition 使用 Template 内唯一稳定 definitionId、显式 PUBLIC/PRIVATE、声明类型和必填非 null literal defaultValue，数组顺序不代表求值顺序。
- definitionId 与 loopId 是独立命名空间；loopId 在单 Template 全部循环节点内唯一。重复 ID、非法 domain、默认值类型错误和 definition cycle 是 hard error。
- Template 永久 StaticSchemaRef、实际 RootDocument/customValues、AdmittedRenderInput、EditorSession 样例与未来 Workspace fixture 都不属于 DesignDSL envelope。
- 持久化字段/definition/context selector 必须保存显式 invocation 或稳定 loopId，不能保存含义随位置变化的 `$current/$parent/$root` 别名。
- 每份 DesignDSL 即使没有 Expression 也必须携带唯一顶层 `expressionProfile`；同 revision 不混用 profile，未来 profile 只能经显式迁移形成新 Template revision，不能自动升级。
- `definitions[]` 精确封闭为 `custom | mapping | expression`；definition/output/input/literal 的 ValueType 使用八种 base、全局 enum catalog 与受限同质一维 `list<T>`，未知 kind/member、null 或不自洽 literal 是 hard error。
- Binding 不在顶层聚合，而位于宿主 Design Node 的 `bindings[]`；wire 只含 bindingId、结构化 targetPropertyRef 与 source，不能再引入 nodeId/slotId。binding/definition/input 数组顺序无语义，Mapping cases 顺序有语义。
- targetPropertyRef 最多包含一次 member 与一次固定 index selector；DesignDSL 必须无损保存具体下标和静态 baseline，禁止动态 path、wildcard、任意 JSON Pointer 或创建缺失 property。
- 全局 BindingPolicyCatalog、policyId 与 Catalog revision 都不属于 DesignDSL；Template 不能声明或覆盖 bindability，保存时由当前追加式全局 Catalog 权威解析 node kind + targetPropertyRef。
- Expression source 是 exact UTF-8 JSON string、可多行、无注释且 whitespace/newline 参与 content hash；AST/IR、presence/type proof 与 constant-fold 结果都只是派生物。

## Answer

### 1. DesignDSL 根与 Template envelope

- Template revision 持久化的内容事实只是一份完整 DesignDSL；其 v1 exact root 为：

```json
{
  "dslVersion": "renderweave-design/1.0",
  "expressionProfile": "renderweave-expression/1.0",
  "displayName": "会员卡片",
  "description": "可选说明",
  "definitions": [],
  "designRoot": {}
}
```

- `definitions` 与 `designRoot` 必填；designRoot 是唯一 authored visual root，其封闭结构由票据 09–12 冻结。它不是业务 RootDocument、Editor canvas state 或 RenderDocument。
- DesignDSL 根没有 top-level bindings、Asset 清单、StaticSchemaRef、runtime inputs、capabilities、extensions 或 editor state。Binding 只能位于 designRoot 内实际消费属性的 Design Node。
- `displayName` 必填，trim 后 1–128 Unicode code points；`description` 可选，trim 后最多 2048 code points，纯空白规范化为 absent。两者不做 Unicode normalization；v1 不含 tags、owner、createdAt/updatedAt 或独立 metadata object。
- templateId、revision、StaticSchemaRef、current、lifecycle、readiness/report 与 expectedRevision 属于 Template/API envelope，不进入 DesignDSL；TemplateRef 自身按既有合同以 authored `{templateId}` 留在 DesignDSL。

### 2. API/export envelope

- bare DesignDSL 的媒体类型为 `application/vnd.renderweave.design+json`。
- 精确 Template revision export 的媒体类型为 `application/vnd.renderweave.template-revision+json`，exact envelope 为：

```json
{
  "exportVersion": "renderweave-template-revision-export/1.0",
  "identity": {
    "kind": "templateRevision",
    "templateId": "...",
    "revision": 7
  },
  "staticSchemaRef": {
    "schemaKey": "...",
    "versionTag": "..."
  },
  "contentHash": "sha256:...",
  "designDsl": {}
}
```

- export envelope 同样 strict、版本封闭且拒绝 unknown member；不含 current、lifecycle、readiness/report 或输入样例。
- 导出 current 时先一致读取并钉死当时 exact revision/hash；后续 current 漂移不改变 artifact，文件中不写 `current:true` 或其他移动标签。ACTIVE/DELETED Template 的历史均可按既有生命周期合同导出 exact revision。
- export 前必须重新验证持久 revision contentHash；完整性失败不得输出 artifact。

### 3. 版本身份与兼容矩阵

- 每份 DesignDSL 必填 exact `dslVersion` 与 `expressionProfile`；v1 分别为 `renderweave-design/1.0` 和 `renderweave-expression/1.0`。禁止 latest、SemVer range、自动协商、自动 fallback 或按底层 library version 解释。
- 平台维护只追加、不可删改的 exact compatibility pair 目录；一个 DesignDSL version 可以在未来追加兼容的新 Expression Profile，但每个 revision 仍只选择一个 pair，客户端必须完整理解两者才能保存。
- DesignDSL object/union/member、新 Node kind/property identity、ValueType/ValueSource、Mapping operator、target selector、canonicalization 或其他 wire/语义扩展需要新 dslVersion；既有 Node Property Identity 的 default/validation 不允许复用 identity 修改。Expression grammar/operator/function/type/presence/order 变化只需要新 Expression Profile。
- UI、localized label、帮助文字和不改变分类/语义的诊断 prose 不升级；追加 BindingPolicy 是明确例外，不改变 DSL version。语义错误不能以 bug fix 名义静默修正，必须形成新 exact profile。
- 一旦某 version/profile pair 被允许写入 revision，其权威 parser/validator/evaluator 语义成为永久兼容义务；读取、历史查看、copy、restore、recheck 和 Render 都不自动迁移或重写版本。
- Global Node Property Identity 是永久的 `nodeKind + propertyPathPattern`。一旦引入，其 ValueType、结构角色、default 与 propertyValidation 跨全部 DSL version 不可改变；破坏性设计必须创建新 node kind 或 propertyId 并配新 DSL version。BindingPolicy 可随后授权一个既有 identity，并对所有包含该同一 identity 的版本生效。

### 4. Strict JSON 与失败封闭

- DesignDSL 只接受无 BOM 的合法 UTF-8 strict JSON object；拒绝 comments、trailing comma、single-quoted string、NaN/Infinity、JSON5、孤立 surrogate 与所有 object 层级 duplicate key。
- JSON null 在 DesignDSL v1 全面非法；optional member 通过省略表达，业务“无”使用显式封闭 union。空字符串、false、0 与空数组是具体值，不等于 missing。
- DesignDSL-owned objects 与按对应 DSL version 解析的所有 Node property 都拒绝 unknown member/kind。未来 wire 扩展必须用新 dslVersion，不能依赖旧 reader 忽略或 opaque-preserve 后保存；BindingPolicy 新增只授权已经存在的固定 property，不新增 wire。
- parser 级 hard limits 按解压后的 bare DesignDSL 计数，并在完整建模、number expansion 和 canonicalization 前执行：最大 raw UTF-8 与 canonical bytes 均 16 MiB、JSON depth 64、单 object 1,024 members、单 array 100,000 items、全文 1,000,000 JSON values/containers、单 string 1 MiB、member name 256 UTF-8 bytes、number token 256 bytes。票据 19 必须再给 Node/Definition/Expression/Loop 等更低语义预算。
- 非法 UTF-8/JSON、duplicate key、limit violation 返回单个 parser-level problem 并停止；root/envelope/version 不能可信识别时停止后续语义分析。结构可安全遍历后才有界收集独立 hard problems，且只有没有阻断性 hard error 才查询 StaticSchema/Asset/Template external dependency。

### 5. Local authored identity

- local entity 使用明确字段 `nodeId/definitionId/bindingId/loopId/useId`；对象本身与引用均使用对应名称，不使用上下文相关的通用 `id`。这正式收紧票据 07 中 Definition `{id,...}` 的简写。
- 所有 local ID 必须是 canonical lowercase UUID v4：精确 `8-4-4-4-12`、version nibble 4、RFC 4122/9562 variant，拒绝 nil、uppercase、braces、URN 与无连字符写法。每类 ID 是 Template 内独立 namespace，同一 UUID 文本可跨 namespace 出现；input alias/propertyId/node kind 不是 local ID。
- 在线设计前端或其他创作客户端在创建 Node、Definition、Binding、Repeat、TemplateUse 时生成 UUID v4；Repeat 同时拥有普通 nodeId 与独立 loopId，Loop 只表示 runtime frame。服务端只校验格式、namespace uniqueness 与 references；missing/invalid/collision 是 hard error，普通 save 不补 ID、不换 ID、不修引用。
- 普通 save 保留未替换实体 identity；restore 与 whole-Template copy 原样保留全部 local IDs。复制实体到同 Template 时客户端为副本生成新 IDs 并同步改写内部引用。手写 JSON 同时修改 ID 与全部 refs 是合法 delete+create，服务端不强制跨 revision identity continuity。
- 显式 migration 保留可保留 IDs；若目标必须新增 identity-bearing entity，migration 要求客户端提供 UUID v4 或返回 manual-action problem，不能在普通 save 中静默生成。
- 票据 11 在 `renderweave-design/1.0` 正式冻结前把 exact structural kind `repeat/conditional` 与 Repeat-child-only `PACK` placement并入同一NodeContract草案；其wire、ABSENT policy、词法/过滤和lowering一经正式冻结便按DesignDSL Profile永久解释，后续可观察变化必须使用新dslVersion。

### 6. 语义无损与 canonical JSON

- “无损编辑”指完整保留所有受支持语义，而非保留上传 bytes。服务端不保存原始 JSON whitespace、object-member order、等价 number lexeme 或 metadata 被既定 trim 移除的空白；必须保留全部受支持 fields、semantic array order、非 metadata Unicode string 原值、exact Expression source 与 arbitrary-precision numeric value。
- RenderWeave 自有 `renderweave-design-c14n/1.0` 是 canonical authority，不直接采用带 IEEE-754 number contract 的通用 JCS：UTF-8、无 BOM/非必要 whitespace；object member 按 name 的 unsigned UTF-8 bytes 升序；string 不做 Unicode normalization，只对 quote/backslash/control 使用唯一 JSON escapes，其余 Unicode scalar 直接写 UTF-8且 `/` 不转义。
- decimal canonical token 按任意精度数学值写 plain notation：无 exponent/leading plus，移除无意义 leading/trailing zeros，`-0` 写 `0`，integer 无 decimal point。因此 `1/1.0/1.00/1e0` 都写为 `1`。Expression source 内 token 属于 exact string，不被改写。
- 保存前对无语义集合排序：definitions按definitionId、每个Node bindings按bindingId、Expression inputs按alias、TemplateUse fills按targetDefinitionId。Mapping cases、target selectors、literal list与parent children等具有match/layout/paint语义的数组保持authored order；唯一Canvas-rooted树不按nodeId重排。
- canonical writer 不展开或删除业务 default，不创建 missing property/Binding baseline，不修复 enum/color/date/time/AssetRef，也不依据当前 BindingPolicy 改写内容。显式 default-equal 值与 omitted default 可以具有不同 contentHash，default 含义由 Node Property Identity 永久冻结。

### 7. Content hash

- revision metadata 的 contentHash wire 为 `sha256:<64 lowercase hex>`，摘要输入精确为 `UTF-8("renderweave-design-content/1\0") + renderweave-design-c14n/1.0 canonical bytes`；contentHash 自身不进入 DesignDSL，canonical profile 映射是 dslVersion 永久语义的一部分。
- hash包含完整authored DesignDSL：versions、metadata、definitions、static baseline、Binding、TemplateUse的nodeId/useId/context/fills/placement/common、TemplateRef/AssetRef logical selectors、closed capability source与exact Expression source。它不包含templateId/revision/ownerScope/永久StaticSchemaRef、BindingPolicyCatalog、readiness/report、resolved Asset/contentVersion/lease/RenderResource/assetSelectionDigest、resolved child Template revision、closure/runtime context/fill、OccurrencePath/compositionViewport、CapabilityState、Clock/Random result或capabilityResultDigest。
- 因此同一 DesignDSL 在不同 Template 或 StaticSchema 下可有同 hash；dependency/current 漂移不改变 revision/hash，只影响 readiness、closure snapshot 或 Evaluation identity。contentHash 不得单独作为 Render cache key。
- object order/transport whitespace/equivalent decimal lexeme 不改变 hash，Expression source whitespace 与任何 canonical semantic content 变化会改变 hash。相同 hash 不阻止新 save revision。
- contentHash 只证明 canonical content integrity，用于 snapshot 与 confirmation binding；不表示 semantic equivalence、dedup、publish、replay、authorization、signature 或 provenance。导入文件中的 identity/Schema/hash 都是不可信 metadata，认证与 ownerScope 只能来自服务端事实。

### 8. Canonical write、confirmation 与 trusted read

- create/save/copy/restore/migration 取得完整 DesignDSL 后依次执行：pre-parse limits/strict parse → envelope/version/local structure/type/graph → metadata normalization/set sorting → external dependency validation → canonical bytes/hash → 原子 immutable revision write。dependency errors 可继续形成 canonical proposal，hard errors 零写。
- dependency-only 首阶段返回 canonical problems、`proposedContentHash` 与 confirmation token；token 同时绑定 operation、target Template/Schema、expectedRevision、hash、dependency snapshot 与 problem fingerprint。canonical-equivalent transport 改写不使 token 漂移，Expression source/semantic order/metadata/content 变化会使其失效；成功 revision hash 必须等于 proposed hash。
- API/save 响应至少返回新 revision 与 contentHash；客户端若本地表示与服务端 normalization 不同，必须从 canonical response/GET 重新同步，不能把上传 bytes 当成 persisted fact。
- trusted read 必须从 persisted JSON value 重新 canonicalize 并核对 hash；数据库 serializer 不是 canonical authority。mismatch 是内部不可恢复的 integrity problem：fail closed，不建立 TemplateSnapshot，不交给 Editor/copy/restore/migration/Evaluator/Renderer，不自动重算/回写历史，并触发运维告警而不记录/回显完整 payload。

### 9. Import、EditorSession 与旧客户端

- Editor 可导入 bare DesignDSL 或 exact revision export。export import 先严格检查 exportVersion 与 canonical contentHash；mismatch 是损坏文件。templateId/revision 只显示来源，永不在目标创建相同 identity；StaticSchemaRef 只可在创建新 Template 时作为需用户确认的预填建议，不能静默改绑现有 Template。
- bare DesignDSL 不含 StaticSchemaRef：导入现有 Template 时使用其永久 Schema；新 Template 必须在 DesignDSL 外显式选择 Schema 后再校验。strict create 仍要求全部合法，不能以 import 绕过；不兼容 path 按既有 dependency error 分类用于可确认的 save/copy/restore，而非放宽 create。
- 非 strict JSON/duplicate/illegal UTF-8/unsupported version 不能构造 DesignDSL 或权威 canvas，只能进入本地 raw repair。支持 exact pair 且结构可识别、但有 duplicate ID/dangling ref/type/domain/target/dependency error 时可以进入未保存 EditorSession 和 best-effort canvas；显式 save 时 hard errors 必须清零，只有 dependency error 可 confirmation。
- Import 本身不调用 Template save、不改变 current/readiness/report 且不创建 revision。具体 raw repair/placeholder UX 由票据 18 决定。
- 客户端只有完整理解 exact dslVersion、expressionProfile 与该 version 全部 Node/Definition wire 才可保存；不理解时只能 canonical JSON read-only、download/export、raw repair 或 explicit migration，禁止 partial model 重序列化、dropping unknown fields 或自证兼容。
- v1 不自动识别 hbads-design-v2、旧 RenderWeave 或无版本 JSON；历史转换必须使用显式 converter/migration profile。

### 10. 显式 migration

- migration 输入绑定 exact source dslVersion/expressionProfile/contentHash 与 exact target pair；transform 纯、确定、有界，不使用 Clock/Random/AssetResolver、网络、用户数据或运行时 dependency resolution。
- migration 先产生 canonical preview DesignDSL、变更摘要与问题集，不直接保存；用户确认后按完整 DesignDSL + expectedRevision 走普通 validation/save 并追加 revision。old revision 永不改写，读取/Render/copy/restore 不隐式 migration。
- downgrade 只有存在独立、显式且无损的 migration profile 时允许。迁移 target 产生的 dependency issues 使用普通保存规则，migration 本身不能通过修补或隐藏 hard error 使输出可写。

### 11. Diagnostics

- parser problems 指向原始提交/导入文本位置；结构/type/reference/dependency problem 的 authority 是 stable entity ID + property path，同时给出的 JSON Pointer 指向 canonical DesignDSL，而非 client 临时无语义数组顺序。
- client 通过 nodeId/definitionId/bindingId/loopId/useId 映射实体；Mapping cases、literal lists、selectors 等 semantic arrays 可继续用 index。TemplateValidationReport 只保留 canonical pointer，不保留 raw upload location；Expression 使用既定 zero-based UTF-16 source span。
- 可安全收集的问题按 phase → canonical JSON Pointer → stable code → entity ID 稳定排序；达到票据 19 的上限后追加 `PROBLEM_LIMIT_REACHED` 并停止昂贵检查。Problem 不回显完整 DesignDSL、Expression source、业务输入或 Asset 内容。
