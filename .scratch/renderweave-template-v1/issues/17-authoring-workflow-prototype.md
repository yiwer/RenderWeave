# 原型验证在线 Template 与 Asset 创作工作流

Type: prototype
Status: open
Blocked by: 05, 07, 09, 10, 11, 12, 13, 15

## Question

一个不进入产品代码的 throwaway 原型应如何呈现 Template/Asset 管理、画板、节点树、属性与 Binding、布局、循环、嵌套、权威预览和错误，使技术型/低代码作者能够验证核心工作流与信息架构？

## Inherited constraints

- Asset 目录默认 ACTIVE，支持 kind、tagsAll/tagsAny、名称搜索、稳定游标和 DELETED 视图；首版无文件夹、Tag 聚合、分享或内容变换。
- Asset picker 只显示 ACTIVE，但编辑器必须能展示导入/历史内容中的 missing、DELETED 与 kind mismatch AssetRef，并允许沿用二阶段确认保存 INVALID。
- 删除流程必须展示完整影响数量、可见 Template 明细与 redactedCount，并能表达确认 token 因引用漂移失效；恢复后相关 Template 会重新检查。
- 多文件上传是独立逐文件结果，不是原子批次；内容历史可查看、精确下载并通过追加新 contentVersion 恢复。
- 原型必须把 RootDocument、根 customValues 与 DesignDSL 明确分开；输入样例只属于本地 EditorSession，不能表现为 Template revision 内容。
- definitions UI 使用 CustomDefinition/Computed Definition 语言，展示稳定 definitionId、PUBLIC/PRIVATE、默认值与显式 invocation/loopId domain，不能称为通用 DataSource 或暗示 `$parent/$root` 动态作用域。
- Binding/Loop/TemplateUse 检视器必须可见 exact Schema context、typed ABSENT、零基 index 与显式 child fill；子调用不能暗示自动继承父数据或同名参数。
- 外部 unknown/PRIVATE custom override 的静默忽略与 authored child fill 失效导致父 INVALID 必须在原型中呈现为不同场景。
- Binding UI 必须以当前 Node kind 与全局追加式 BindingPolicyCatalog 生成可绑定 property picker；Template 不能自行开启 bindability，Catalog 未列出的属性不出现 Binding 操作。
- Binding 编辑器必须呈现 node-local `targetPropertyRef`，覆盖 property/member/fixed index 及最多一次 member+index 的组合；host node 隐式，不显示 nodeId/slotId 连线模型。
- 每个 bindable property 始终保留可编辑 static baseline；UI 要区分“没有 Binding，使用 baseline”“Binding 成功覆盖”“Binding 存在但 ABSENT/ERROR，权威预览失败”，不能暗示 runtime fallback。
- 数组重排若作者意图保持原 item，原型必须演示同步重写 numeric target index；越界、duplicate target 与 ancestor/descendant overlap 应定位为不可保存 hard error。
- definitions UI 必须分别支持 Custom、ordered Mapping 与显式-input Expression source；复杂未支持 expression syntax 直接报 profile error，不展示“已解析但稍后才实现”的功能。
- 浏览器 lint/画布只是非权威反馈；保存/预览问题必须能展示服务端 stable code、JSON Pointer、definitionId/bindingId 与 UTF-16 source span，同时不回显实际输入值。
- 原型创建 Node/Definition/Binding/Loop/TemplateUse 时由前端生成 canonical lowercase UUID v4；服务端不会补 ID。复制 subtree/definition 必须演示成组 remap IDs/refs，whole Template copy/restore 则保留 local IDs。
- Import 必须覆盖 bare DesignDSL 与 exact revision export：strict/unsupported version 进入 raw repair，结构可识别的无效内容进入 best-effort canvas，identity/Schema 只展示来源且不能静默覆盖目标 Template。
- Save 后 UI 必须以服务端 canonical DesignDSL/revision/contentHash 重新同步，能观察 metadata trim、set-like definitions/bindings/inputs 排序和等价 decimal token canonicalization，而不丢 exact Expression source。
- 原型必须提供 exact dslVersion/expressionProfile 不受支持时的只读/export/migration 状态，禁止旧客户端对 partial model 保存；显式 migration 先预览 canonical output/changes/problems，再走普通 save。
- Export UI 区分 bare DesignDSL 与 exact Template revision envelope，并明确 contentHash 不是签名、文件 identity 不能授予 Schema/Template/Asset 权限。
