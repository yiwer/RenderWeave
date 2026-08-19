# ADR-0043：Asset admission/resolution deep interface 与 S3 Blob seam

- 状态：accepted
- 日期：2026-08-19
- 决策来源：Template v1 implementation Wayfinder Ticket 05；用户 HITL 两轮对答（Q1–Q10）逐项确认
- 关联：ADR-0003、ADR-0041、ADR-0042、TV1-T05、冻结 checkpoint
  `0b485f4a13de9d754a81d07f464730776e13c14b`

## 背景与约束

Asset Management 的领域语义已由冻结规格决策完备（旧 map tickets 05/13 与 CONTEXT.md glossary）：IMAGE/FONT、
服务端生成的 canonical UUID v4 `assetId`、不可变 ownerScope/kind、`ACTIVE ↔ DELETED` 可恢复软删除、
`assetRevision` 乐观并发与 `contentVersion` 不可变内容版本、scope 内 SHA-256 内容寻址 Blob、封闭 `{assetId}`
AssetRef、`AssetRef → ResolvedAsset → RenderResource` 三层交接、Renderer-only fetch lease、24h 创建幂等键、
5 分钟单次删除确认 token、部署级容量水位与 fog 列表。本 ADR 只把实施 seam 收口，不重开规格语义。

ADR-0041 已冻结：Asset 不依赖 Template/Rendering artifact；Asset 拥有 `AssetRef`/`AssetResolver`/
`ResolvedAsset`；反向运行时协作经 Asset-owned outbound Port + app bridge；三个上下文各自只消费窄 Host
authority facet；`renderweave-app` 只承载 Adapter。ADR-0042 已冻结 Template 侧三 Interface 模式与事务型
persistence SPI；Asset 侧镜像该模式，但不复用 Template 的 SPI、aggregate 或 persistence 模型。

inference `BlobStore` 的 `write(artifactId)/read(locator)/delete(locator)` 是 inference-only 语义（自由
locator、可 delete、无 scope 分区）；Asset Blob 是 ownerScope 内按 SHA-256 内容寻址、永不 delete 的内部
不可变字节对象，两者不复用。

## 决策

### 1. 三个 provider-owned Interface 各服务一种能力

| Interface | 调用者 | 唯一职责 | 物化时点 |
| --- | --- | --- | --- |
| `AssetApplication` | app HTTP/assembly Adapter | 作者/产品命令与查询：幂等 create/upload、detail、catalog、metadata update、版本列表/精确下载、内部预览、replace/旧内容恢复、delete/restore | Ticket 11 起按切片物化 |
| `AssetResolver` | Rendering | closed 输入 `renderRequestId/ownerScope/resourceId/assetId/expectedKind/rendererAudience/renderDeadline` → 线性化选择 current + 签发 `ResolvedAsset` 与 Renderer-only lease | Ticket 13 有真实 consumer 时 |
| `AssetAcceptanceAuthority` | Asset internal + kernel replay | 静态 IMAGE/FONT admission → closed outcome + TechnicalDescriptor/sha256/length | Ticket 10 首个物化 |

internal assembly factory（TemplateModule 式）向 app Adapter 注入 Host facet、persistence 与 Blob
Implementation。三个 Interface 不互相继承、不返回 persistence record；command/outcome/value types 以嵌套
closed types 起步；新增方法必须与同票真实行为一起出现，不得预建抛出 unsupported 的方法。

### 2. admission kernel 与独立 replay

Ticket 10 只实现 `AssetAcceptanceAuthority`：无 DB、网络、UI、route、OpenAPI、S3/MinIO 或聚合。IMAGE 只接
静态 PNG/JPEG/WebP（64 MiB、单边 20,000 px、总像素 100,000,000；`SRGB_8BIT` 颜色合同；唯一 EXIF
orientation；拒绝 16-bit/APNG/冲突或非 canonical sRGB ICC/CMYK/YCCK/HDR/wide-gamut）；FONT 只接单 face、
non-variable、monochrome outline TTF/OTF（32 MiB；TrueType `glyf`/CFF；`cmap/GDEF/GSUB/GPOS/kern` 白名单；
拒绝 collection/COLR/CPAL/CBDT/CBLC/sbix/SVG/bitmap strike/Graphite/AAT-only/malformed table）。输出
TechnicalDescriptor、sha256、byteLength 与 `renderweave-asset-acceptance/1.0`。

验证形态镜像 T03：Java primary 经正式 Interface + 独立 Python verifier（IMAGE 用 Pillow 解码尺寸/EXIF
orientation/色彩模式/格式合法性；FONT 用 fontTools 解析 flavor/unitsPerEm/单 face/非 variable/table
白名单）重放 exact vectors；Python 覆盖不了的位级断言（如 canonical sRGB ICC 规范等价）在 manifest 中只由
Java primary 断言并显式标注 A1。`renderweave-asset-acceptance/1.0` 的语义在 T10 即完整，但其 available
登记以 T11 真实 create 纵切为界（镜像 DesignDSL 的不接线不登记纪律）。

### 3. Resolver 与 fetch lease seam

`AssetResolver` 的输入/输出与冻结规格的 closed 字段一致；selection 在单次线性化事务内提交
`(renderRequestId, resourceId)` 短期幂等记录后签发 lease。fetch URL 由 Asset-owned consumer
`AssetFetchEndpoint` Port 物化：app Adapter 校验 lease claims、签发短时签名 URL、从对象存储流式供给并验证
sha256/length。URL 只存在于 ResolvedAsset（请求内），Rendering 原样投影进 RenderResource；URL 不入 digest、
日志、trace、持久化或 Evaluation identity。Engine 只对 app origin 拉取（冻结 allowlist），绝不直发 S3
presigned URL。

### 4. AssetPersistence 与 AssetBlobPersistence SPI

- `AssetPersistence`：事务型 outbound seam，method-specific closed operations（locate/loadCurrent、
  create/upload idempotency、appendContentVersion、metadata update、catalog 稳定游标查询、delete/restore
  与确认记录随 12a/12b 物化）；commit 值只能由 Asset internal Implementation 构造，不携 SQL、table/column
  name、lock、transaction handle、exception 或 HTTP status；Adapter 穷尽映射 SQL/S3 故障，不泄漏
  repository-specific failure。
- `AssetBlobPersistence`：`store(scope, sha256, bytes)`/`exists(scope, sha256)`/`load(scope, sha256)` +
  部署级容量探针。唯一生产 Adapter 走 S3 协议：生产 OSS、本地 compose 与 E2E 用 MinIO 容器、测试用
  Testcontainers MinIO —— 同一 Adapter 代码路径，不用文件系统模拟。对象布局为单 bucket +
  `{scope}/blobs/{sha256-hex}`；store 客户端计算并校验 sha256、同键不覆盖；load 流式读取并验证
  sha256/length。凭据（endpoint/bucket/access key/secret key）只经环境变量注入，不入库、不入日志、不入
  DesignDSL。S3 客户端为 AWS SDK v2 `software.amazon.awssdk:s3`（endpoint override 与 path-style 可配置），
  随 T11 引入。

### 5. Host authority facet

Asset-owned `AssetOwnerScopeAuthority` sibling SPI（不与 Template 共享），closed 三操作：
`authorizeCreate(invocation)`、`authorizeExisting(invocation, storedOwnerScope, READ|UPDATE|DELETE|RESTORE)`、
`recheck(recheckIdentity)`；Adapter 才映射 `asset.read/create/update/delete/restore` capability 字符串。生产
没有可信 Host Adapter 时 assembly fail closed；dev/test 使用配置固定的 single-owner Adapter。请求不得自报
ownerScope/capability。

### 6. 容量水位与审计

部署级容量水位由 app 侧 PostgreSQL 事务字节计数器实现：与 contentVersion 追加同事务单调累加（Asset v1 无
物理删除，计数只增不减），阈值由部署配置，达到硬水位后只拒绝需要新 Blob 的 create/replace，复用既有
Blob、metadata、删除、恢复与 Render 不受影响；不读云侧用量指标做 admission 判定。每次有效 create、
metadata update、content replace/restore、delete 与 restore 追加有界审计事件（只记 assetId、前后
assetRevision、actorId、时间、操作类型与内容版本身份，不记原始字节、完整标签或请求），随 12a 起物化。

### 7. 切片顺序与 T10–T13 边界

```text
T10 acceptance kernel → T11 create/current/catalog 持久化纵切 → T12a replace/旧内容恢复
→ T12b delete/restore + AssetReferencePort/确认 token（blocked by Template 依赖投影票）
→ T13 AssetResolver/Renderer-only lease（blocked by T07/T08/T11）
```

- T11：幂等 create/upload（24h idempotency key，多选 = 多个独立 create 允许部分成功）、detail、catalog
  查询（默认 ACTIVE、可显式 DELETED；kind/tagsAll/tagsAny/displayName/sourceFileName 过滤；`updatedAt DESC,
  assetId ASC` 稳定游标）、metadata update（expectedAssetRevision）、内容版本列表与精确版本下载、内部预览
  （只读、需 asset.read）；V019 forward-only、OpenAPI/Web SDK、Host facet、S3 Adapter（compose/Testcontainers
  MinIO）、容量计数器同票落地。
- T12a：content replace（expectedAssetRevision，与 current 相同则 no-op）与旧内容恢复（复用 Blob 追加新
  contentVersion）；审计事件与 STALE 事实记录同票。Template 侧 STALE 消费/反向索引属于 Template 依赖投影票。
- T12b：删除影响预检（Asset-owned `AssetReferencePort` → app bridge → Template-owned
  `AssetReferenceAuthority` current-only proof + redactedCount）、5 分钟单次确认 token（绑定 actorId/
  ownerScopeId/assetId/assetRevision 与完整引用 fingerprint）、软删除、恢复。本票以 Template 依赖投影票为
  blocker。
- T13：`AssetResolver`/lease 纵切，与 T07（Evaluator seam）/T08（Rust protocol）同批接线。
- Asset picker/catalog UI 随 Editor（T09）同批，不在 T11 预建产品 route。

### 8. closed outcomes 与错误边界

稳定 code 集合以冻结规格为准（`ASSET_*`、`TEMPLATE_ASSET_*`、`ASSET_RESOLVE_*`、`RESOURCE_*` 等）；Asset
module 不返回 Template/Rendering problem，跨上下文消费方以无 default 的穷尽映射转成自有 failure。HTTP
status、RFC problem envelope、redaction 与 safe message 只属 app Adapter；路径/token/hash/URL 不出 closed
交接值。

## 备选方案

| 方案 | 未选择原因 |
| --- | --- |
| 复用 inference `BlobStore` | 自由 locator、可 delete、无 scope 分区，与 Asset Blob 语义不符 |
| PostgreSQL bytea 存 Blob | 备份/流式/水位成本高，且与生产对象存储路径背离 |
| 文件系统 blob-root | 用户选择 S3 协议 + MinIO（本地与测试同一 Adapter）；不实现第二个 Adapter |
| Rendering/app 在 process Adapter 补 fetch URL | 需重解析/重序列化 sealed RenderDocument，URL 知识扩散 |
| 共享 Template `OwnerScopeAuthority` SPI | capability 集合（含 RESTORE）与操作不同，形成跨上下文耦合 |
| 跳过 acceptance kernel 直接持久化纵切 | admission 是全部风险所在，且失去独立 replay 证据链 |
| 手写最小 S3 REST 客户端 | 签名/checksum/边界安全全部自担；AWS SDK v2 是标准企业依赖 |

## 后果与边界

Asset 的高杠杆语义集中于三个 provider Interface 与两个 SPI；S3/MinIO、AWS SDK、容量计数与 fetch 端点都藏
在 consumer-owned seam 后。代价是 app 需穷尽 outcome 映射与流式供给，T11 起引入 AWS SDK v2 依赖与 MinIO
容器（compose/Testcontainers），部署配置增加对象存储凭据与水位阈值。

本 ADR 只冻结实施合同，没有创建 Java Interface、migration、route、gate 组成或产品代码；自动文档/gate 通过
也不证明 Asset READY。Ticket 19、DesignDSL Profile available、Editor/Renderer 外部认证状态不变。
