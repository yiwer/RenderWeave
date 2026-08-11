# 定义 Asset Management 产品边界与生命周期

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: none

## Question

首版管理哪些 Asset 类型；Asset 的身份、内容版本、逻辑 current、元数据、文件夹/标签、上传/导入、去重、替换、删除/恢复、引用保护、最小所有权和权限边界分别是什么；哪些处理能力必须留在 fog？

## Inherited constraints

- Template Design 只反向索引 ACTIVE current DesignDSL 的 AssetRef；历史 revision 不阻塞 Asset 生命周期操作。
- Asset 引用事实变化必须能使受影响 Template 进入 STALE 并触发重检；Asset 删除是拒绝、允许后使 Template INVALID，还是采用其他规则，仍由本票据决定。
- TemplateReadiness 只检查与具体调用者无关的 Asset 存在性、生命周期和类型合同；请求级权限失败不能污染全局 Template 状态。

## Answer

首版 Asset Management 只管理 `IMAGE` 与 `FONT`。Asset 是以全局唯一、不透明 `assetId` 标识的稳定聚合；`ownerScopeId`、`assetId` 与 `kind` 创建后永久不变。生命周期只有 `ACTIVE ↔ DELETED`：删除是允许恢复相同身份的软删除，v1 永久保留聚合、全部内容版本与字节，不提供物理清除。跨 scope 转移、共享和身份复用均不允许。

Asset 同时拥有两个单调值：`assetRevision` 是 metadata、current content、删除与恢复的乐观并发令牌，不形成可恢复的完整聚合历史；`contentVersion` 从 0 开始，只标识不可变 `AssetContentRevision`。每个内容版本保存服务端计算的 SHA-256、规范 mediaType、byteLength、原始基础文件名、种类特有技术 metadata、接纳 Profile、操作者与时间。内容恢复通过复用旧 Blob、追加新 contentVersion 并推进 current 完成，绝不回拨或修改旧版本。

用户 metadata 是必填、可变、非唯一的 `displayName` 与字符串标签集合。displayName 去除首尾空白、NFC 后为 1–200 个 Unicode 标量；标签最多 20 个，每个去除首尾空白、NFC 后为 1–64 个 Unicode 标量，按 Unicode case-fold 后去重但保留展示写法。标签更新整体替换并携带 `expectedAssetRevision`，没有 Tag 聚合、层级或全局重命名。`sourceFileName` 属于内容版本，只保留不含路径和控制字符、最多 255 个 Unicode 标量的基础文件名；Asset 详情可投影 current 内容的技术字段。

`AssetAcceptanceProfile v1` 是 Asset Management 自己拥有的稳定合同，而不是对某个在线 Renderer capabilities 的动态查询。IMAGE 只接受静态 PNG/JPEG/WebP，编码字节最多 64 MiB、任一边最多 20,000 px、总像素最多 100,000,000；FONT 只接受最多 32 MiB 的 TTF/OTF，并拒绝字体集合与 variable font；空文件一律拒绝。提供 Render 服务的 Engine Profile 必须先证明是该接纳 Profile 的兼容超集；不兼容会阻止该部署提供 Render，而不能反向改变既有 Asset 有效性。服务端必须计算 hash/长度、检查真实 magic 并完整解码图片或解析字体，所有检查成功后才原子创建可见 ACTIVE Asset。部署可增加 fail-closed 安全 admission hook，但 v1 不标准化恶意软件、许可证或 quarantine 裁决；内部缩略图和字体样张是可丢弃、剥离 metadata 的派生缓存，不是 Asset、contentVersion 或可引用内容。

首版只接收客户端上传的字节，不抓取任意 URL、不解包 ZIP。UI 多选只是多个独立 CreateAsset，允许逐文件部分成功；上传暂存不是产品可见 Asset。创建要求幂等键，结果保留 24 小时并绑定 ownerScope、操作者与完整请求指纹：同键同输入重放原结果，同键异输入冲突，过期后视为新请求。主动重复上传始终创建新 assetId；只有幂等重放复用身份。

Blob 是 ownerScope 内部、按 SHA-256 内容寻址的不可变字节对象；不同 Asset 与内容版本可以在同一 scope 复用 Blob，但不得跨 scope 暴露或全局去重。内容替换携带 `expectedAssetRevision`，先完成验证与安全暂存，再原子追加 contentVersion、切换 current 并增加 assetRevision；失败不得暴露新版本或改变 current。若验证后的内容与 current 完全相同，替换成功但为 no-op，不增加任何 revision、事件或 Template STALE。metadata 的规范化值未变化时同样 no-op。

DesignDSL 的 AssetRef 是封闭的 `{assetId}` 逻辑选择器，不保存 kind、contentVersion、hash、文件名或 Profile；所在 DesignDSL 属性声明预期 `IMAGE/FONT`。Asset picker 只提供 ACTIVE Asset，但导入或编辑可保留不存在、DELETED 或类型不匹配的 AssetRef，并沿用 Template 的依赖问题二阶段确认保存为 INVALID。

按已确认的非常规运行语义，Evaluator 对每个实际执行到的 AssetRef 出现位置独立读取该时刻的 Asset current，不按 assetId 做请求内 memoization；因此一次 Evaluation 中相同 assetId 的不同出现位置允许解析到不同 contentVersion。每次成功解析各自产生不可变 ResolvedAsset，包含 assetId、精确 contentVersion、kind、SHA-256、规范 mediaType、byteLength 与短期 Renderer 专用受信 fetch URL；已经解析的条目固定其字节，后续出现位置若因删除或不可用而失败，则整个 Evaluation 失败且不产生部分 RenderDocument/RenderOutput。RenderDocument 必须为同一 assetId 的不同精确内容分配可区分的资源身份，RenderEngine 只允许受信 Asset 服务来源并校验 hash 与长度。

调用者获准 Render 某 Template 后，Rendering 使用内部能力解析该 Template 的同 ownerScope Asset，不再逐个要求调用者具备 `asset.read`；直接目录、详情、版本、预览或下载仍需 read。TemplateReadiness 只检查 actor-independent 的存在、ACTIVE 与预期 kind。current contentVersion 改变、删除和恢复会经可靠可重放事件使 ACTIVE-current 反向索引中的 Template 进入 STALE；displayName、tags、sourceFileName 等 metadata 变化及相同内容 no-op 不触发。Template Design 幂等消费事件并周期 reconciliation，打开编辑器与每次 Render 继续作权威兜底检查。

被 ACTIVE current DesignDSL 引用不阻止 Asset 删除。应用层删除编排通过 Template Design 提供的窄 `AssetReferenceAuthority` 获取 current-only 引用 proof 与影响报告，再让 Asset Management 签发有效期 5 分钟、单次使用的确认 token；Asset Management 核心不读取 Template 聚合或持久化模型。token 绑定 actorId、ownerScopeId、assetId、assetRevision 与完整引用 fingerprint。Template current 变更取得按 assetId 排序的读 reservation，删除取得独占 reservation 后重算 proof；任一 Asset、引用、token 或权限事实漂移均零写并要求重新确认，不共享聚合、表或数据库事务。删除者看到完整影响数量，只看到其有权读取的 Template 明细，其他项计入 `redactedCount`；成功删除后相关 Template 先 STALE 再重检为 INVALID。

恢复要求 `expectedAssetRevision`，重新激活相同 assetId 与删除前 current，并触发相关 Template 重检；恢复本身不新建 contentVersion。具有管理权限的操作者可在已删除目录查看 metadata、内容历史、精确下载与恢复，但 AssetResolver 始终拒绝 DELETED。Asset Management 暴露的宿主映射能力只有 `asset.read/create/update/delete/restore`：update 包含 metadata、内容替换与旧内容恢复；不建立角色、成员、Workspace 或逐 Asset ACL。跨 scope 或没有 read 的直接查询统一表现为 NOT_FOUND，同 scope 缺少具体 mutation 能力表现为 FORBIDDEN，内部 Resolver 不产生操作者授权错误。

每次有效 create、metadata update、content replace/restore、delete 与 restore 都追加有界审计事件，只记录 assetId、前后 assetRevision、actorId、时间、操作类型和内容版本身份，不记录原始字节、完整标签或请求。首版产品面包含创建、目录查询、详情、metadata 更新、内容版本列表、精确版本下载、内容替换、旧内容恢复、内部预览、删除影响预检、软删除、恢复及 Rendering-only Resolve；目录默认 ACTIVE，可显式查询 DELETED，支持 kind、`tagsAll`/`tagsAny`、displayName/sourceFileName 大小写不敏感查询，并按 `updatedAt DESC, assetId ASC` 稳定游标分页。

Asset 命令与解析返回 namespaced 稳定 code 和有界字段，至少定义 `ASSET_NOT_FOUND`、`ASSET_FORBIDDEN`、`ASSET_DELETED`、`ASSET_REVISION_CONFLICT`、`ASSET_KIND_MISMATCH`、`ASSET_CONTENT_INVALID`、`ASSET_CONTENT_UNSUPPORTED`、`ASSET_CONTENT_LIMIT_EXCEEDED`、`ASSET_IDEMPOTENCY_CONFLICT`、`ASSET_DELETE_CONFIRMATION_REQUIRED`、`ASSET_DELETE_CONFIRMATION_EXPIRED`、`ASSET_DELETE_CONFIRMATION_STALE`、`ASSET_STORAGE_CAPACITY_EXCEEDED`、`ASSET_DEPENDENCY_UNAVAILABLE` 与 `ASSET_RESOLUTION_FAILED`；不能要求客户端解析自然语言。v1 不设 ownerScope 产品配额，但 Blob 存储必须有部署级容量水位与 fail-closed admission breaker：达到硬水位后只拒绝需要新 Blob 的 create/replace，复用既有 Blob、metadata、删除、恢复与 Render 不受影响。

明确留在 fog：DOCUMENT/SVG/动画及其他媒体、URL/ZIP 导入、文件夹和 Tag 聚合、跨 scope 转移/分享/公共链接、裁剪/缩放/转码/压缩/字体子集化/AI 处理、标准化杀毒/许可证/quarantine 工作流、Blob GC/硬清除/保留期、scope 计费配额、全局去重、资源复制合并、全文/模糊检索和原子批处理。
