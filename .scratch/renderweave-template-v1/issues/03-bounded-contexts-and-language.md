# 划定 Template、Render 与 Asset 的限界上下文

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 01

## Question

Template、Template revision、DesignDSL、Render Input、Value Source、Asset、Asset Reference、Evaluation、MaterializedScene 与 Render Output 分别是什么，归属哪个限界上下文；哪些相似术语必须禁止混用，三个上下文通过什么最小合同连接？

## Answer

RenderWeave Template v1 新增三个限界上下文，既有 Schema/Validation 保持独立上游：

- **Template Design** 拥有 Template、Template revision、DesignDSL、ValueSource 与保存时语义。Template 是拥有不可变 ownerScope、永久绑定精确 StaticSchemaRef、以 current revision 表示当前内容的可变聚合；revision 是不可变历史记录；DesignDSL 只是 revision 的内容事实源。
- **Asset Management** 拥有Asset身份、生命周期与内部模型，并向其他上下文发布AssetRef值合同和按实际消费位置线性化选择current、签发exact fetch lease的AssetResolver。Template Design只保存AssetRef，Rendering内部形成ResolvedAsset再投影为Engine-facing RenderResource；两者都不访问Asset持久化模型。
- **Rendering** 拥有 RenderInput、Evaluation、RenderDSL、RenderDocument、RenderEngine、LaidOutScene 与 RenderOutput。请求开始时把根 Template 及全部 authored 可达、same-scope TemplateRef current 一次冻结成一致 Template closure snapshot；本次 Evaluation 不再读取可变 Template current。

依赖严格单向且只传不可变公开合同：Template Design 使用 Schema 的精确身份和 AssetRef；Rendering 消费 Template closure snapshot、调用 Validation 与 AssetResolver；Schema、Validation 与 Asset Management 都不知道 Template 或 Rendering。上下文不共享聚合、数据库模型或无边界 common 类型。

Evaluation把Template closure snapshot、AdmittedRenderInput和受控capabilities编译为符合RenderDSL的请求级RenderDocument。该文档必须消除Binding、Expression、条件/循环、TemplateUse/TemplateRef、AssetRef current与capability调用，但可以保留RenderEngine原生Stack/Grid、静态compositionViewport等渲染级布局、具体文本及RenderResource manifest。compositionViewport只承载已展开child artboard静态subtree与固定映射规则，不是child Template callback或revision句柄。现有`busbox-render-engine`属于Rendering边界，但当前`haibo.render/1.0`既缺compositionViewport，也缺RenderResource的expiry/acceptance/technical descriptor；未来必须实现并认证独立的`renderweave-render-command/1.0`、`renderweave-render/1.0`、exact Layout/Renderer/Output Profile，不能复用旧version宣称兼容、把动态Template/Asset语义交给Engine或静默丢字段。Engine内最终几何对象称LaidOutScene；一条Command为根Canvas原子生成的一张完整PNG/JPEG及其closed metadata称RenderOutput。正式输出和权威预览都来自同一RenderEngine；浏览器画布只能提供非权威草稿反馈。

ResolvedAsset是每个实际Asset消费位置在Rendering内部形成的不可变exact选择，包含Asset/内容身份、occurrence locator、技术描述和fetch lease；RenderResource是一对一删除Asset业务身份后的Engine manifest项。RenderEngine只能按allowlist取得并校验exact bytes，不得接收AssetRef/current、调用AssetResolver或执行Asset业务选择。

统一语言禁止以下混用：

- 使用 Template revision，不使用 TemplateVersion、PublishedTemplate 或 StaticTemplate。
- Connector、RenderInput、ValueSource 各有独立含义，不以 DataSource 混称。
- 领域使用 Asset、AssetRef、ResolvedAsset；“资源管理”可作中文产品名称，但 Resource 不是领域对象。
- DesignDSL 是作者语言，RenderDSL 是 RenderEngine 输入语言，RenderDocument 是其一次具体实例。
- 不再使用 MaterializedScene；最终几何场景只称 Engine 内部 LaidOutScene。
- Evaluation、RenderDocument、RenderEngine 与 RenderOutput 不统称为一个含糊对象 Render；Design Node tree 也不称 Scene。

`busbox-render-engine` 事实指针：`E:\rust-app\busbox-render-engine\crates\haibo-dsl\src\envelope.rs`、`crates\haibo-server\src\layout.rs`、`fetch.rs`、`text.rs`、`render.rs`。
