# 划定 Template、Render 与 Asset 的限界上下文

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 01

## Question

Template、Template revision、DesignDSL、Render Input、Value Source、Asset、Asset Reference、Evaluation、MaterializedScene 与 Render Output 分别是什么，归属哪个限界上下文；哪些相似术语必须禁止混用，三个上下文通过什么最小合同连接？

## Answer

RenderWeave Template v1 新增三个限界上下文，既有 Schema/Validation 保持独立上游：

- **Template Design** 拥有 Template、Template revision、DesignDSL、ValueSource 与保存时语义。Template 是永久绑定精确 StaticSchemaRef、以 current revision 表示当前内容的可变聚合；revision 是不可变历史记录；DesignDSL 只是 revision 的内容事实源。
- **Asset Management** 拥有 Asset 的身份、生命周期与内部模型，并向其他上下文发布 AssetRef 值合同和 `AssetResolver: AssetRef → ResolvedAsset`。Template Design 只保存 AssetRef，Rendering 只消费 ResolvedAsset；两者都不访问 Asset 持久化模型。
- **Rendering** 拥有 RenderInput、Evaluation、RenderDSL、RenderDocument、RenderEngine、LaidOutScene 与 RenderOutput。请求开始时把 Template current 一次解析成不可变 TemplateSnapshot；本次 Evaluation 不再读取可变 Template。

依赖严格单向且只传不可变公开合同：Template Design 使用 Schema 的精确身份和 AssetRef；Rendering 消费 TemplateSnapshot、调用 Validation 与 AssetResolver；Schema、Validation 与 Asset Management 都不知道 Template 或 Rendering。上下文不共享聚合、数据库模型或无边界 common 类型。

Evaluation 把 TemplateSnapshot、RenderInput 和受控 capabilities 编译为一份符合 RenderDSL 的 RenderDocument。该文档必须已经消除 Binding、Expression、条件/循环、嵌套 Template、current 与 capability 调用，但可以保留 RenderEngine 原生的 Stack/Grid 等渲染级布局、具体文本和 ResolvedAsset 资源清单。现有 `busbox-render-engine` 正是这一边界：它接收 `haibo.dsl/1.0` document/resources/output，内部负责 layout、受限资源获取与校验、文本 shaping、绘制和 PNG/JPEG 编码。Engine 内完成最终几何后的对象称 LaidOutScene；图片及媒体描述称 RenderOutput。正式输出和权威预览都来自同一 RenderEngine；浏览器画布只能提供非权威草稿反馈。

ResolvedAsset 是请求级不可变资源清单项，至少钉死资源身份、可信 fetch URL、SHA-256、媒体类型和字节长度。RenderEngine 可按 allowlist 获取并校验内容，但不得接收 AssetRef/current 或执行 Asset 业务选择。

统一语言禁止以下混用：

- 使用 Template revision，不使用 TemplateVersion、PublishedTemplate 或 StaticTemplate。
- Connector、RenderInput、ValueSource 各有独立含义，不以 DataSource 混称。
- 领域使用 Asset、AssetRef、ResolvedAsset；“资源管理”可作中文产品名称，但 Resource 不是领域对象。
- DesignDSL 是作者语言，RenderDSL 是 RenderEngine 输入语言，RenderDocument 是其一次具体实例。
- 不再使用 MaterializedScene；最终几何场景只称 Engine 内部 LaidOutScene。
- Evaluation、RenderDocument、RenderEngine 与 RenderOutput 不统称为一个含糊对象 Render；Design Node tree 也不称 Scene。

`busbox-render-engine` 事实指针：`E:\rust-app\busbox-render-engine\crates\haibo-dsl\src\envelope.rs`、`crates\haibo-server\src\layout.rs`、`fetch.rs`、`text.rs`、`render.rs`。
