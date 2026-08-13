# 定义编辑、校验、预览与恢复体验

Type: grilling
Status: open
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
