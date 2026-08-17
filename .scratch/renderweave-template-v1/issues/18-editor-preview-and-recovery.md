# 定义编辑、校验、预览与恢复体验

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 04, 14, 15, 16, 17

## Question

编辑器何时允许暂时无效状态、何时严格拒绝保存；autosave/显式保存、expectedRevision 冲突、历史恢复、无损 round-trip、权威预览刷新、capability 导致的变化、错误定位、键盘与可访问性体验应如何定义？

## Inherited constraints

- 导入和编辑可在本地 EditorSession 暂时无效；服务端只接受完整显式保存，不持久化 autosave/Patch。
- 依赖 ERROR 可经绑定精确问题集的二次确认保存为 INVALID；hard error 永远拒绝。INVALID/STALE 可继续编辑和看本地草稿画布，但不能权威预览或 Render。
- 打开编辑器必须重检最新 current；current report 不保留 revision 绑定且可能被旧异步结果覆盖，UI 不得把它冒充权威结论。
- 历史恢复只在 ACTIVE Template 中追加新 revision；DELETED 不可恢复，但其历史可只读、导出或复制。
- `displayName` 等 metadata 位于 DesignDSL，修改使用同一个 expectedRevision 并产生内容 revision。
- RootDocument/customValues 样例只保存在本地 EditorSession；未来 Workspace 输入预设不进入 DesignDSL 或 Template revision。本票据不得把样例保存伪装成 Template autosave。
- 编辑器必须区分 optional runtime ABSENT、Schema 中不存在的 field path、Custom default/definition graph hard error与 child fill 依赖 ERROR，并定位到稳定 definitionId/loopId/JSON Pointer。
- PUBLIC/PRIVATE CustomDefinition 与显式 child fill 是调用边界；UI 不得提供自动同名继承或让 external customValues 定向嵌套 Template。
- imported DesignDSL 的 node-local Binding 与 exact Expression source 必须无损 round-trip；本地可暂时保留 unknown/越界/重叠 target，但显式保存时这些结构问题是不可确认 hard error。
- 属性面板只使用全局 BindingPolicyCatalog 展示当前允许目标；Catalog 新增只扩展 UI 能力，旧 Template 无迁移，Template 内容不能保存 policyId、Catalog revision 或自报 target type。
- editor 对 array reorder 必须明确采用“保持数字下标”或原子重写 targetPropertyRef；不得依赖不存在的 item identity。删除 Binding 后继续显示原 authored static baseline。
- 本地 Expression parser/linter 不构成第二语义权威；server problem 必须映射 exact source 的零基 UTF-16 span，未来 profile 不能静默重写 source/whitespace 或自动升级。
- UI 必须把“无 Binding 使用 baseline”与“已有 Binding 但 ABSENT/ERROR/类型或属性约束失败”分开；后者不能显示为成功 fallback preview。
- “无损编辑”只承诺 DesignDSL 语义，不保留上传 JSON whitespace/object order/equivalent number lexeme；Editor 必须保留所有受支持 fields、semantic arrays、Unicode values 与 exact Expression source，并在 save 后接受服务端 canonical form。
- 不合法 UTF-8/JSON、duplicate key 或 unsupported exact profile 只能进入 local raw repair；支持 pair 且结构可识别的 hard/dependency-invalid content 可 best-effort 投影，但 hard error 未清零前服务端零写。
- 客户端创建 local entity 时生成 canonical UUID v4；server 不修复 missing/collision。copy local entity 必须 remap 引用，restore/whole Template copy 保留 IDs；canonical semantic problem 通过 entity ID + property path 映射，而非依赖无语义数组 index。
- 旧客户端若不完整理解 dslVersion/expressionProfile/Node wire 必须只读、export、raw repair 或显式 migration，不能 partial reserialize。Migration preview 必须绑定 source contentHash，用户确认后才按 expectedRevision 保存新 revision。
- 编辑器导入 exact export 时验证 exportVersion/contentHash，但不得信任或采用文件 templateId/revision/StaticSchemaRef；现有 Template 永久 Schema 优先，新建 Template 的 Schema 预填仍需用户确认。
- TemplateValidationReport pointer 指向 canonical current；parser problem 可指向 raw import buffer，后者不持久化。达到有界 problem limit 后必须明确展示 truncation，而不是假装只存在已列问题。
- Editor 必须从 exact dslVersion 的 NodeContractCatalog 无损投影全部 closed Node/property/ContentModel，并从当前只追加 BindingPolicyCatalog生成 Binding 操作；不支持 Slot、unknown property opaque round-trip、implicit font fallback 或 UI-only默认值保存语义。
- children/runs/points/commands/rows/columns 重排都有语义；其中 Run/Point/Command Binding 使用具体 index，编辑器选择“保持项目意图”时必须原子重写 target index。bindings 本身按 bindingId canonical sort，不得用 UI order制造求值优先级。
- Render Request 未显式设置 DPI 时权威预览使用 96 DPI；DPI 是 session/request choice，不写入 DesignDSL。Canvas 物理 mm size 与 font pt size应在 UI 中明确区分。
- EditorSession 可以暂时承载布局 hard error，但显式保存必须拒绝 HUG/FILL cycle、非法 Stack fillWeight、Grid track/span/FRACTION-on-HUG、QR 非正方形及 `VISIBLE + maxLines`；这些不属于可二次确认的 dependency ERROR。修复后只能保存 authored DesignDSL，不能把浏览器派生 coordinate/LayoutBox 写回。
- UI 必须把浏览器 constraint feedback 标为非权威，并能展示 authority 使用的 exact Layout Profile；权威预览失败时不保留旧 Scene 冒充当前结果。若提供 LayoutTrace，只展示授权且有界的 occurrence box/clip/overflow/paint 诊断，不显示原始输入、完整文本、DesignDSL 或 Asset bytes。
- EditorSession 可以暂时保留空 Repeat/Conditional children、非法 items/condition type、词法越界或不兼容 PACK，但显式保存必须把这些结构/类型/ContentModel问题作为不可确认 hard error拒绝；server不补 children、loopId、scope或placement。
- Repeat `items` 与 Conditional `condition` 是直接结构 ValueSource，UI必须提供各自的 typed source 与 `ERROR|EMPTY`/`ERROR|FALSE` absent policy，而不是 Binding开关或静态 baseline。accepted ABSENT、显式空集与 runtime ERROR要作为三个不同状态呈现。
- copy/move/delete Repeat subtree 必须在本地原子 remap或验证所有 nodeId/bindingId/loopId与domain引用；无法保持词法可达时操作应停止或留下明确hard problem，不能自动改成invocation/祖先domain。
- 权威预览必须经服务端请求级sidecar把opaque occurrenceId映射为有权限的OccurrencePath，以定位原输入loopIndex与合成role；浏览器不得接收完整sidecar或把路径当稳定作者identity。修复INVALID内容只能修改/移除引用后显式保存，浏览器草稿不得因跳过失败item而显示伪成功结果。
- EditorSession可暂时承载missing/DELETED/INVALID child、Schema mismatch、失效PUBLIC fill target与无兼容Layout Profile，并在二次确认后保存当前为INVALID；duplicate useId/fill target、非法ContextSelector语法/lexical domain、children/placement与TemplateRef cycle始终hard error零写。
- ContextSelector的合法runtime ABSENT必须把ERROR、SKIP与fill source ABSENT使用child default三种体验分开；SKIP不显示host/gap/child error，但完整closure dependency问题仍阻止权威预览。UI不得以本地条件分支掩盖失效TemplateRef。
- child current变更使父current异步STALE/READY/INVALID而不创建父revision；编辑器打开与每次权威预览重新冻结closure。report必须定位parent nodeId/useId/context/fill，并以有界OccurrencePath展示实际child错误，不能回显context/fill值或完整child DesignDSL。
- Template只允许same-ownerScope TemplateRef与copy；任何未来cross-scope export/import必须显式重选TemplateRef/AssetRef，v1编辑器不得提供保留失效跨scope ref或自动深复制closure的隐式动作。
- 每次显式保存、打开与权威预览都必须从canonical DesignDSL/完整closure重新检查全部authored AssetRef occurrence；missing/DELETED/kind mismatch可沿二阶段确认保存为INVALID，但非法AssetRef shape/UUID或unknown member是不可确认hard error。同一assetId可合法重复，报告可聚合显示却必须保留每个canonical pointer与安全entity locator。
- Asset replace/delete/restore事件只使ACTIVE-current反向依赖中的Template进入STALE并异步重检，不创建Template revision；metadata变化不触发。编辑器必须防止旧异步report或旧preview覆盖较新的draft/current结果，且不能把最终一致readiness当作Render授权。
- 草稿Asset预览、picker、metadata与历史读取要求调用者`asset.read`；Authoritative Preview使用Renderer-only请求内RenderDocument/lease，浏览器不得提交、接收、下载或持久化RenderDocument、document digest、sidecar、lease、fetch URL、hash、contentVersion或完整ResolvedAsset transcript。失败后必须撤下旧权威结果，允许带明确非权威标识的本地placeholder继续编辑。
- 根PUBLIC custom override中的每个imageRef/fontRef atom即使未消费也在请求admission要求same-scope、ACTIVE、kind与caller `asset.read`；成功值可传入child fill但不重复授权。authored child Asset则在获准Render根Template后由内部same-scope Resolver处理，错误展示须按Template/Asset read权限脱敏。
- 已开始的权威preview不因后续Asset replace/delete/restore自动取消：已resolve occurrence保持exact，尚未resolve occurrence观察新状态，最后一次resolve后发生的变化不改本次输出；下一次preview重新admit。UI若主动取消或请求deadline/lease expiry，则本次零输出且不得续签、重选current或恢复旧preview。
- Editor必须从exact Expression Profile无损投影closed capability sources；unknown operation/member/null/args、自报version或Capability出现在非Expression-input位置可在本地暂存修复，但显式保存是不可确认hard error。Web模拟只作静态/草稿反馈，不能成为Clock/Random authority。
- 每次新的Authoritative Preview使用服务端新renderRequestId和新CapabilityState；仅同一操作的内部幂等恢复重放。编辑器不得提供锁定时间/seed、复用旧state/result digest或把Workspace fixture伪装成capability；业务指定时间只能作为普通RootDocument/Custom输入。
- capability runtime failure必须撤下旧权威preview、零RenderDocument/Output且不改变TemplateReadiness。公共问题默认stable code；有对应Template read时可定位definitionId/input alias与有界授权InvocationPath，但不得显示Clock/Random值、nonce、fingerprint、provider原始错误或完整child路径。
- 每次权威preview operation只对应根Canvas的一张完整PNG或JPEG；公共可调参数仅为format、正整数DPI及JPEG quality，省略时有效值分别为96 DPI与quality 90。preview不能crop、resize、导出单Node、覆盖Canvas背景、单独输出child viewport或把多格式包装为一次Engine请求。
- preview cancel是Rendering转发给Engine的best-effort控制：在atomic output seal前生效则零输出，seal后无效；浏览器断开连接不等于可靠cancel。UI只使用public renderOperationId，不得接收或重用Engine requestId、rendererCommandDigest、deadlineAt或cancel wire。
- Authoritative Preview只有在完整图片length/digest/result核验成功后才能替换当前结果；partial transport、deadline、cancel、resource/layout/raster/encode/trace失败一律删除临时bytes并撤下旧权威结果。可继续显示的本地草稿或Asset placeholder必须持续标为非权威。
- 正式输出与权威preview都由同一Evaluator、exact Layout/Renderer/Output Profile和Engine路径完成；同一有效参数必须byte-identical。LayoutTrace只在单独授权且成功时作为有界附件展示，不能在失败后保留partial trace，也不写入EditorSession、DesignDSL、revision或Workspace。

## Answer

### 1. 保存、恢复、冲突与预览基线

- Authoritative Preview 只针对已保存的 Template current，不接纳未保存 EditorSession 作为权威 Evaluation 输入。本地无未保存更改时可直接预览 current；存在未保存更改时，主动作是“保存并预览”，底层仍严格先执行完整显式保存，再仅在保存成功且当次权威重检可形成 READY snapshot 时启动独立 preview operation。保存失败或经确认保存为 INVALID 时不启动预览。
- 服务端不保存 autosave、Patch 或 EditorSession。客户端可把 DesignDSL 工作副本与最小编辑状态保存为当前设备上的 Local recovery draft，用于异常关闭后由作者显式恢复、导出或放弃；它不创建 revision、不同步到其他设备，也默认不持久化 RootDocument、customValues、预览图片或 Asset bytes。
- 每个 EditorSession 同时最多只有一个 Template save/invalid-confirmation mutation 在途。在途期间暂停 authored mutation，但允许浏览结构、属性与问题；成功后以服务端返回的 canonical DesignDSL、revision 与 contentHash 整体重建基线，失败后原样保留本地工作副本并解除暂停。v1 不引入 submitted/working 双层草稿、响应后操作重放或后台保存队列。
- `expectedRevision` 继续是所有保存的并发前提。冲突时 v1 不提供三方 diff、自动 merge 或逐项挑选：保留本地完整 DesignDSL，只显示远端已有新 current，并允许作者一次明确确认“以本地完整内容覆盖”；确认后客户端重新读取最新 revision，把它作为新的 expectedRevision 再次提交完整 DesignDSL并重新执行全部校验。确认前或重试期间再次漂移就返回新的 conflict并要求重新确认；该流程不是静默或无条件 last-write-wins。
- Invalid commit confirmation 只允许绑定完整且未截断的 dependency ERROR 集合。任何 hard error 或 `PROBLEM_LIMIT_REACHED` 都禁止签发/使用确认并保持零写；作者必须先减少问题并重新校验，直到能看见本次将确认的完整问题集。

### 2. 恢复生命周期、预览新鲜度与历史恢复

- 每个 Template 在当前设备最多保留一份 Local recovery draft，记录其 base revision、base contentHash、完整 DesignDSL 工作副本、最小编辑状态及更新时间；本地 authored command 成功后以有界 debounce 替换该记录，不保留本地草稿历史。成功保存并进入 clean、作者明确放弃、Template 删除或记录超过 7 天时清除；浏览器配额回收仍可提前丢失，因此该记录只作 best-effort，不构成持久性承诺。
- 恢复记录的 base 与服务器 current 相同才可直接恢复为该 current 上的 dirty EditorSession。current 已前进时，UI 必须先显示基线已变化，再允许恢复本地完整草稿；恢复本身不提交，后续保存仍按 Round 1 的 Template conflict overwrite 重新确认。任何情况下都不自动采用本地草稿或覆盖服务器 current。
- Editor 内部导航离开 dirty EditorSession 时提供“保存 / 保留本地草稿并离开 / 取消”；保存不可用或失败时仍可选择后两者。浏览器关闭、刷新和崩溃只能使用平台允许的 best-effort `beforeunload` 与 Local recovery draft。再次打开时显示“恢复 / 导出 / 放弃”，不自动载入或提交恢复内容；RootDocument、customValues、预览图片与 Asset bytes 不随恢复记录保存。
- EditorSession 只有一个 Authoritative Preview result slot 和一个活跃 preview operation。其 basis 由已保存 current、当前无本地内容分歧状态、RootDocument/customValues 样例、format、DPI、JPEG quality 与 LayoutTrace 选择共同确定；任一 DesignDSL 编辑、输入样例或参数变化、current revision/readiness变化都会立即撤下已显示图片并要求重新预览，普通面板开合或选择移动不影响 basis。
- 发起新 preview 时先 best-effort cancel 旧 operation，并立即让旧 operation 失去展示资格；作者主动 cancel 也立即清空 result slot。cancel 可能在 Engine seal 后失效，但任何旧 operation 的迟到成功或失败都必须被客户端 generation guard 丢弃，不能重新显示、覆盖或恢复旧图片。v1 不提供 preview 历史、并行结果槽或自动重跑。
- Save-and-preview 是两个顺序且非原子的操作。Template save 一旦成功就永久追加 revision；后续 preview 的取消、deadline、依赖漂移、Capability、资源、布局、编码或传输失败均不回滚保存。UI 必须分别呈现“保存成功”和“预览失败/取消”，不得把后者归类为保存失败或把整个工作流伪装成单事务。
- ACTIVE Template 的历史恢复只能从 clean EditorSession 发起；存在未保存内容时，必须先保存、导出或放弃本地草稿。恢复确认只展示 exact source/current revision、contentHash 与权威校验问题，不提供三方或语义 diff；命令携带 current expectedRevision，把 source DesignDSL 追加为新 revision，并可沿既有完整 dependency ERROR 二次确认流程进入 INVALID。current 再次漂移则零写并要求重新确认；DELETED Template 仍只允许只读、导出或把 exact 历史复制为新 Template。

### 3. 编辑模式、结构命令、撤销与问题焦点

- 编辑器只有三个显式且互斥的内容模式。Structured Editor 要求客户端完整理解 exact dslVersion、expressionProfile、NodeContractCatalog 与全部 closed wire，可投影正常或 best-effort invalid canvas；Raw Repair 用于非法 UTF-8/JSON、duplicate key 或其他无法构造可信 DesignDSL 的原始导入缓冲，只允许修复、替换、下载或放弃原始输入；Compatibility Read-only 用于输入完整但客户端不理解 exact profile/wire 的内容，只允许查看安全元信息、导出或进入显式 migration。任何模式都不得 partial reserialize、丢弃 unknown member 后保存或把浏览器猜测当作兼容解释。
- Raw Repair 只有在原始缓冲变为 strict UTF-8 JSON、无 duplicate key、exact profile pair 受支持且客户端能够完整构造 closed model 后，才能显式进入 Structured Editor；转换前不显示结构树、属性检视器、best-effort canvas、Template save 或 Authoritative Preview。Compatibility Read-only 只有成功完成绑定 source contentHash 的 migration preview 并由作者接受后，才能以 migration 输出进入 Structured Editor；原内容始终可导出且不被原地改写。
- Structured Editor 允许连续属性、数值、文本、Expression source、ValueSource 与直接 JSON 修复暂时产生 hard/dependency problem，以支持逐步编辑；导入的受支持内容也可从 invalid 状态开始。但 create/copy/delete/move/reparent/reorder 等离散结构命令必须在单个本地命令内原子生成/remap全部 nodeId、definitionId、bindingId、loopId、useId、domain ref、target ref 与 placement。无法证明结果满足身份唯一、词法可达、ContentModel、PACK/placement 和引用重写前提时，拒绝整个命令并返回可定位原因，不留下部分修改或让服务端猜测修复。
- undo/redo 是单一 Canonical editor baseline 内的本地结构命令历史，可经过暂时 invalid 状态但每一步都必须恢复一份完整 EditorSession 工作副本。成功 Template save、Template conflict overwrite、revision restore 或 whole-Template copy/create 后，客户端必须采用服务端 canonical DesignDSL/revision/contentHash 建立新 baseline，并清空 undo/redo；跨 baseline 的旧内容只通过不可变 Template revision 历史恢复，不能重放旧本地命令。Import 接受与 migration preview 接受本身不写服务端：它们只清空旧 undo/redo，并把完整输出作为仍锚定原 baseline 的新 dirty working draft；只有后续普通 save 成功才建立新 canonical baseline。
- 对 runs、points、commands、rows、columns 等语义数组执行 Structured Editor reorder 时，客户端固定采用“保持作者移动项目”的单一规则：按同一 permutation 原子重写全部受影响 numeric targetPropertyRef，使 Binding 继续指向被移动的原项目。若任一引用无法安全重写，或重写后形成越界、duplicate target、ancestor/descendant overlap，则拒绝整个 reorder。v1 不提供“保持数字下标”的第二交互模式，也不发明 item identity。
- 编辑器使用一个统一、键盘可达的问题面板，明确分组为不可保存 hard problem、可二次确认的 dependency ERROR，以及仅属于当前 operation 的 runtime/preview problem；stable code 是语义，中文信息由 code/message args 投影。每项在授权范围内保留 canonical JSON Pointer、nodeId/definitionId/bindingId/loopId/useId、Target/ConsumerPropertyRef 或 exact Expression 零基 UTF-16 span，原始业务值与无权 child path 继续脱敏。
- 本地 lint 绑定 EditorSession 内容 generation，服务端 current report 绑定被读取的 current revision/contentHash，save/import/migration problem 绑定提交的 canonical content，preview problem 绑定 public renderOperationId 与 Authoritative Preview basis。任何 basis/generation 不匹配的迟到 report、validation 或 preview result 直接丢弃，不能覆盖更新状态。显式 save/preview 失败后焦点移到带总数、分类和 truncation 状态的问题摘要，不擅自改变画布/树选择；作者激活某一问题后才导航并聚焦对应实体、属性或 source span。

### 4. 打开与漂移、导入迁移、反馈和可访问性

- 打开 ACTIVE Template 时，客户端先读取并校验 trusted canonical current，建立 Canonical editor baseline，同时把旧 readiness/report 视为不可用于当前结论并显示“权威重检中”。只要 exact model 受支持且 integrity gate 通过，就可进入 Structured Editor 并允许本地编辑或显式 save；Authoritative Preview 在当次 current/closure 重检成功前保持禁用。只有绑定该 current revision/contentHash 的新 report 才可替换检查状态。
- 远端 current 在 clean EditorSession 中前进时，客户端自动采用新的 trusted canonical DesignDSL/revision/contentHash、清空 undo/redo、撤下 preview，并以非阻塞状态说明 revision 已更新；尽量保留仍存在的面板、展开与实体选择，但它们不属于内容事实。dirty EditorSession 永不被远端内容覆盖，只标记 canonical baseline 已过期并保留草稿；后续 save 进入 Round 1 的 conflict overwrite。readiness/依赖事件同样必须匹配 current，任何 STALE/INVALID 或重检开始都会撤下 preview。
- Template 在编辑期间进入 DELETED 时，立即禁止编辑、保存和 preview，切换到 DELETED 的只读历史/导出/复制能力；未保存 working draft 与 Local recovery draft 不被自动删除，仍可导出，但不能借恢复或冲突覆盖重新激活原 Template。
- Editor import、migration preview 接受和 Local recovery 恢复在替换 working draft 前共用 dirty guard：先保存、导出、保留现有 recovery 后离开，或明确放弃。接受后完整输出成为新的 dirty working draft、清空旧 undo/redo，但零服务端写入；随后只有普通 save 才能追加 revision。支持 exact pair 的 invalid 内容进入 Structured Editor best-effort 状态，无法构造可信 model 的内容进入 Raw Repair，不完整理解的 exact wire 进入 Compatibility Read-only。
- exact revision export import 必须先核验 exportVersion 与 canonical contentHash；文件 templateId/revision/StaticSchemaRef/contentHash 只展示来源，不能授予身份、权限或替换目标事实。导入现有 Template 时永久 StaticSchemaRef 不变；创建新 Template 时，文件 StaticSchemaRef 只能作为需作者明确确认且重新授权/校验的预填建议，bare DesignDSL 则必须单独选择 Schema。任何跨 scope TemplateRef/AssetRef 不自动映射、深复制或保留授权。
- save 成功后始终以 trusted server canonical response/GET 重建 baseline。若 metadata trim、set-like sorting 或 equivalent decimal normalization 造成表示变化，UI 仅显示可展开的非阻塞类别/数量摘要，不弹完整 diff；exact Expression source、Unicode、semantic array order与全部 supported field 必须保持。响应 contentHash/integrity 不一致，或出现合同未允许的语义变化时 fail closed：不把可疑内容装入 Structured Editor，保留可导出的本地提交副本，显示兼容性错误并重新读取 trusted current；服务端已提交 revision 的事实不被客户端伪装成回滚。
- 仅当完整 Template closure 声明 Clock/Random Evaluation Capability 时，在 preview 控制附近持续显示“每次权威预览结果可能变化”；每次新 preview 仍创建新 CapabilityState。浏览器草稿可显示显著非权威的模拟/placeholder，但 UI 不展示或持久化实际 Clock/Random result、snapshot、nonce、call position、fingerprint、result digest或内部恢复状态。
- 全局快捷键只用于传统、本地或明确操作：`Ctrl/Cmd+S`触发显式 save；`Ctrl/Cmd+Z`与`Ctrl/Cmd+Shift+Z`执行undo/redo，Windows同时支持`Ctrl+Y` redo；`Delete/Backspace`仅在结构树或画布拥有明确焦点且焦点不在文本/编辑控件时删除可删除选中项，并可undo；`Escape`只关闭临时面板/菜单/dialog并按触发源恢复焦点。Authoritative Preview、cancel、invalid confirmation、conflict overwrite、restore、import/migration接受与删除Template/Asset不设全局快捷键，必须由具名按钮和明确确认触发；快捷键在输入法组合期间不得执行。
- 受支持桌面 viewport 内以 WCAG 2.2 AA 作为 v1 目标：结构树使用正确 tree/roving-focus 语义，inspector、Binding、问题、历史、导入和preview核心流程可仅用键盘完成；画布选择在树/问题面板有等价入口；状态、Binding和严重度不只依赖颜色；全部控件有可见焦点与可访问名称；进度/非阻塞变化使用克制的 polite live region，阻塞错误由聚焦摘要表达，避免重复播报；遵循 reduced-motion。200% zoom 下控制面板仍可滚动操作，二维画布允许必要双轴平移；低于既定桌面编辑阈值时显示自身可键盘/读屏操作的“不支持当前宽度”状态，不伪装为可编辑移动端。核心键盘流在任何阶段都不得形成keyboard trap。Binding的type-invalid与property-invalid必须以可访问文本和状态分别标识，不能合并成无差别generic error。被脱敏或不可定位的问题仍须以授权可见信息保持可理解，且不得伪造locator或placeholder。每个控件按适用情况暴露稳定的name、role、value、validation、expanded与selected语义。核心流程在平台支持的high-contrast设置下保持完整可操作。

### 5. 空保存、结果不明与确认顺序

- Structured Editor 只在 working draft 与 Canonical editor baseline 存在 canonical semantic content 差异时开放 save；clean 状态不允许作者通过 UI 追加无意义的相同 contentHash revision，但领域/API合同仍允许受控调用方显式保存相同 hash。dirty 判断只能使用 exact profile 的完整 canonicalization或自 canonical baseline 以来的可靠完整命令状态，不能用 transport JSON bytes、object order或浏览器表单 touched 标记替代。
- Template save 或 invalid-confirmation save 在 transport 层结果不明时进入 Save reconciliation：保持 authored mutation 锁、working draft、proposed contentHash 与 Local recovery draft，不把超时报告为成功或失败，也不盲目重发。客户端先读取 trusted current；若 current revision 已超过原 expectedRevision 且 contentHash 等于 proposed hash，则采用该 current 并报告“内容已在服务器确认”，但不宣称证明具体请求归属；若 current 仍精确等于原 expectedRevision，则允许作者显式重试；若 revision 已前进且 hash 不同则进入 Template conflict overwrite；若目标 DELETED 则进入只读/导出状态；revision 回退、integrity mismatch 或无法解释的状态 fail closed。
- trusted current 暂时不可读时，Save reconciliation 保持 unknown，不允许新的 Template mutation、preview 或清除 Local recovery draft；作者仍可导出 working draft，或明确保留恢复记录后离开。重新打开该 Template 时必须先恢复 reconciliation，再进入普通编辑。只有 trusted canonical baseline 已采用后才清理 recovery、解除 mutation lock并决定是否继续 Save-and-preview。
- create/copy 没有足以防止重复对象的 command-key；响应结果不明时 UI 不自动重试，先刷新 Template 目录并显示“操作结果未知”。若当前合同无法可靠关联新对象，作者必须检查目录后自行决定是否再次创建/复制，并在重试前看到可能产生重复对象的明确提示；v1 不为编辑器私自增加来源 lineage、客户端 identity或伪幂等键。
- Template conflict overwrite、Invalid commit confirmation、revision restore/migration接受及删除等确认保持各自精确语义，不合并成通用“强制继续”。例如 conflict 后先确认以本地完整内容覆盖最新 current，再由重新执行的权威校验按新的 content/dependency snapshot单独产生 invalid confirmation token；任一中间漂移都回到对应步骤，不能复用旧确认。
- Template save/confirmation mutation 发出后不提供 cancel，因为客户端不能据此证明服务端未提交；应用内 mode替换、import/restore或离开动作在获得终态或进入明确 unknown reconciliation 前被阻止。浏览器仍可能被强制关闭，此时只依赖 Local recovery draft；unknown 状态允许带恢复记录离开，但下次必须先 reconciliation。Authoritative Preview cancel 继续是独立的 best-effort Rendering 控制，不能类推为保存取消。

### 6. 明确排除与后继

- v1 编辑器不提供服务端 autosave/Patch、实时协作/session lock、自动或三方 merge、语义 diff、无条件 last-write-wins、preview history、并行preview槽、跨设备草稿同步、Workspace fixture、旧权威图片回退、partial model保存或通用force确认。
- 本票据只冻结产品工作流、状态、领域语言和后续实施/验收约束，不创建 Template/Asset API、数据库、EditorSession存储、前端产品路由、Evaluator/Renderer能力或其他产品代码。Ticket 19继续填写所有已命名容量数值，并冻结跨语言、跨平台、安全和WCAG验收证据等级。
