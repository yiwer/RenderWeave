# 定义 RenderEngine 与图片输出合同

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 15

## Question

RenderEngine 如何消费 RenderDocument，并在内部完成 layout、资源获取/校验/解码、文本 shaping、LaidOutScene、绘制和编码；首版支持哪些画板与图片输出基数、格式、尺寸、像素密度、色彩、透明度、字体和错误语义；哪些 Engine Profile 参数属于版本化输入？

## Inherited constraints

- RenderDocument已经只含concrete static nodes/property values、静态compositionViewport与一对一exact RenderResource manifest；不得包含ValueSource、Binding、BindingPolicy、Expression、Definition、ABSENT、loop frame、TemplateUse/TemplateRef、ContextSelector/fill、AssetRef/assetId/contentVersion、child revision读取句柄、CapabilityState/contract/CallPosition/result digest或capability call。Engine不得读取Clock、entropy、deadline作为模板值，也不能判断concrete property来源。
- exact交接合同已经冻结为Renderer Command `renderweave-render-command/1.0`、RenderDSL `renderweave-render/1.0`、canonical profile `renderweave-render-c14n/1.0`与Layout Profile `renderweave-layout/1.0`；不得宣称兼容或降级到旧`haibo.render/1.0`/`haibo.dsl/1.0`。
- RenderDocument顶层只含`dslVersion/layoutProfile/canvas/resources`，使用strict JSON、closed static kind与展开default；所有decimal是最多六位、无exponent的canonical number。Renderer Command至少携带`contractVersion/requestId/renderDocumentDigest/document`，本票据负责补齐deadline、取消、DPI、output及Engine Profile exact字段。
- RenderDocument Node只携带`rwocc_`加16位小写十六进制先序ordinal的opaque occurrenceId；完整OccurrencePath/sourceNodeId及resource回接只存在于Rendering请求级sidecar。Engine problem返回occurrenceId/resourceId，不能接收Template/node/loop/use身份。
- `renderDocumentDigest`覆盖包括fetchUrl/expiresAt在内的完整canonical bytes，只用于本次传输核验，不是Evaluation identity、cache key或公共值。同一活跃请求的transport retry必须重发相同requestId/bytes/digest，不能重新seal、续签lease、resolve current或建立CapabilityState。
- RenderEngine 不读取 DesignDSL 或全局 BindingPolicyCatalog，也不重新验证表达式类型；它只按 RenderDSL 及 exact Layout/Renderer/Output Profile 对已经求值的 property 与资源完成 layout、shaping、绘制和编码。
- 任一资源lease/fetch/hash/media/length/descriptor/decode/shaping或layout/encoding失败必须零RenderOutput；不能回退DesignDSL static baseline、其他URL/content/font或产生部分成功图片。Engine只返回稳定code、opaque occurrenceId/resourceId与安全参数，Rendering经请求sidecar补回获准的OccurrencePath/ConsumerPropertyRef并按权限脱敏。
- DesignRoot、Canonical DesignDSL、Design content hash、evaluationFingerprint与evaluationResultDigest都停留在Template/Evaluation边界；RenderEngine不把它们当成RenderDocument identity或输出缓存键。若本票据定义Output cache，key必须在完整Evaluation后由evaluationResultDigest组合exact Renderer/Output Profile并独立授权、partition。
- authored physical geometry 使用 mm、typography 使用 pt，固定关系为 `1in = 25.4mm` 与 `1pt = 1/72in`。DPI 只属于 Render Request并在effective Command中展开；省略时必须使用 96 DPI，不得回写 Canvas、Template 或 DesignDSL，也不得改变 Image authored HUG 的 aspect-ratio 语义。
- 本票据必须冻结 mm/pt 到 pixel 的精确 arbitrary-decimal calculation、Canvas/bleed pixel rounding、最大像素检查时点与 transparent background encoding；不能让平台/Skia/browser 的环境默认值成为语义权威。
- Run的exact FONT RenderResource是唯一字体来源，Renderer不得使用系统默认、built-in/family fallback或静默missing-glyph fallback；必须fetch并复核single-face、non-variable、flavor/unitsPerEm与exact bytes。Text shaping、vertical writing、distributed alignment、stroke、ellipsis与shrink-to-fit必须由exact Layout/Renderer Profile及一致性语料证明。
- RenderEngine 必须拒绝缺失/未知 Layout Profile，并按 `renderweave-layout/1.0` 从仍含布局规则的 RenderDocument 产生只在请求内存在的 LaidOutScene；不得要求 Evaluator 预先给 final coordinates，也不得把派生 box/glyph 写回 Template/RenderDocument。布局行为改变必须发布新 Profile，不能以 engine patch 热替换旧语义。
- exact Layout Profile必须原生定义compositionViewport：先以source trim约束递归layout静态child、绘制concrete background并clip source，再把整个artboard按CONTAIN/CENTER映射及clip host。nested viewport递归执行，letterbox透明；child bleed、独立output、Template callback或host-driven child reflow一律不存在。
- resource准备必须按manifest顺序串行：只允许HTTPS exact-origin/path-prefix allowlist，禁止redirect/proxy environment/cookie/caller header/range/transparent compression和原URL fallback；流式限制声明/总字节并依次核验length、lowercase SHA-256、kind/media/magic、IMAGE orientation/尺寸/frameCount或FONT descriptor。只有transport/5xx可在同URL/expiry/deadline内有界重试，4xx/integrity/decode失败不重试且不能调用Resolver续签。
- exact bytes是最终事实，Asset technical descriptor是必须匹配的证明。IMAGE只用orientation后logical dimensions且忽略DPI；FONT只用exact OpenType bytes。Renderer Profile必须是`renderweave-asset-acceptance/1.0`兼容超集，不一致零输出并产生脱敏内部信号，不修改Asset或Template状态。
- logical current、assetId和resourceId不得作为共享内容cache key。只有持有trusted ownerScope partition的组件可按scope/kind/hash/length/media及exact Renderer Profile复用verified bytes/decode；无scope partition的Engine只能请求内缓存。cache hit仍要求本lease有效且不得改变manifest顺序、budget或first error。
- Layout Profile必须固定binary64求值约束、Unicode/BiDi/vertical shaping版本与feature set、32次Text shrink搜索、Stack/Grid有限分配顺序以及transform/opacity/clip/paint stack；Layout conformance corpus比较box、break、bounds、clip、paint order、error与tolerance，Renderer/Output corpus再验证exact pixel与encoding bytes。
- 本票据只在 LaidOutScene 完成后使用 Render Request DPI（省略 96）把 physical surface 降为 pixels；hinting、sampling 与 module/pixel quantization不能反馈 measure/layout。可选 LayoutTrace 只能是授权、请求级、有界诊断投影，失败不得与部分 Scene/Output 一起返回。

## Answer

### 1. 输出基数、权威边界与版本维度

- 一条 Renderer Command 精确对应一个 RenderDocument 根 Canvas，并原子地产生一张完整图片。Repeat、Conditional、TemplateUse 与 `compositionViewport` 都只影响该图片内部内容；child viewport 永不成为独立输出。多 Template、多输入、多变体、多 DPI 或多格式由 Rendering 在 Engine 外编排为多条独立 Command，不存在 `outputs[]`、page、batch 或部分成功语义。
- 输出始终覆盖根 Canvas 的完整 physical surface，即 trim 加四边 authored bleed；调用方不能要求 node export、crop、resize、target width/height、rotation、background override、bleed toggle 或任意二次排版。唯一 Canvas 与无 multi-Canvas flow 的约束保持不变。
- RenderEngine 是 Rendering 限界上下文内的内部组件。公共客户端只能调用 Rendering API，不能提交或取得 Renderer Command、RenderDocument、LaidOutScene、requestId、digest、fetch lease 或 Engine control；Engine 也不能读取 Template、DesignDSL、RootDocument、调用者 token 或业务身份。
- 首批兼容维度相互独立：`renderweave-layout/1.0` 冻结 measure/arrange/shaping；`renderweave-renderer/1.0` 冻结资源解释、颜色、raster、sampling、码制与像素算法；`renderweave-output-png/1.0` 和 `renderweave-output-jpeg/1.0` 分别冻结编码字节与 metadata。服务端维护不可变的 exact compatibility table，为一个获准的公共 output 请求唯一选择完整组合；不存在 caller profile selection、`latest`、协商、fallback 或同一 subtree 混用 Profile。
- 一次 Evaluation 可以在 Engine 外产生多条不同 output Command，但每条都重新进入 Engine；v1 不做跨请求 RenderOutput cache。Engine 输出只是请求瞬态值，不自动创建文件、URL、Artifact、Workspace 项、revision 或历史记录。
- 正式输出与Authoritative Preview没有不同的Engine purpose或quality路径：两者都经过完整Evaluation、同一compatibility table和同一RenderEngine。preview可以提交普通的较低DPI或另一获准格式，但只要有效Command的文档、Profile、DPI与quality相同，图片bytes就必须相同；浏览器本地画布始终非权威。

### 2. Renderer Command、输出选择与 canonical identity

- Renderer Command 是 strict、closed、UTF-8 JSON，拒绝 duplicate key、unknown member、`null`、unknown exact Profile 与非 canonical scalar。PNG envelope的closed字段形状如下；为避免重复票据15，示例中的`document:{}`只是完整RenderDocument的排版占位，并非可单独通过验证的文档：

```json
{
  "contractVersion": "renderweave-render-command/1.0",
  "requestId": "00000000-0000-4000-8000-000000000000",
  "rendererProfile": "renderweave-renderer/1.0",
  "deadlineAt": "2026-08-13T12:34:56.789Z",
  "renderDocumentDigest": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
  "document": {},
  "output": {
    "profile": "renderweave-output-png/1.0",
    "dpi": 96
  },
  "diagnostics": {
    "layoutTrace": false
  }
}
```

- `requestId` 是 Rendering 生成的 canonical lowercase UUID v4。`document` 是票据 15 的完整 RenderDocument；`renderDocumentDigest` 必须先按 `renderweave-render-c14n/1.0` 核验。`deadlineAt` 是 Rendering 依据服务端 policy 展开的绝对 UTC deadline，wire 固定为带三位毫秒的 RFC 3339 `YYYY-MM-DDTHH:mm:ss.sssZ`；公共调用者最多请求 duration，不能提交任意绝对时刻。
- PNG output object 只含 `{profile,dpi}`；JPEG 只含 `{profile,dpi,quality}`。`dpi` 是正整数，公共省略值在构造 Command 前展开为 96；JPEG `quality` 是 `1..100` 整数，省略时展开为 90。output object 不再携带冗余 `format`，profile 唯一决定格式与编码合同。
- exact Command canonical profile 为 `renderweave-render-command-c14n/1.0`。它复用 Render canonical UTF-8 member ordering、escaping、最短 integer 与禁止 unknown/null 的规则；语义数组保持既定顺序，canonical writer 不修复非法输入。
- `rendererCommandDigest = "sha256:" + lowercaseHex(SHA-256(UTF8("renderweave-render-command/1\0") || canonicalEffectiveCommandBytes))`。该值只用于 Engine 的 request registry、重发冲突判定与 cancel，不进入 Command、图片 bytes、公共响应、日志、Template/Evaluation identity、Artifact 或跨请求 cache。
- 公共 API 只暴露 `PNG | JPEG`、DPI 与 JPEG quality。Rendering 按不可变映射展开 exact profiles/default；相同公共参数在旧映射仍受支持时不得被后台换成新算法。改变映射或可观察输出必须发布新 API/Profile 组合。
- Command HTTP entity media type固定为`application/vnd.renderweave.render-command+json;version=1.0`；嵌入的`contractVersion`仍是wire authority，media parameter不能替代或放宽它。

### 3. Deadline、准入、幂等重发与取消

- Engine channel 必须加密并认证 workload identity；具体 mTLS/service-mesh 机制属于部署。认证失败在解析 JSON 前拒绝，Command 内不携带用户 credential、bearer token 或权限声明。
- 同一 `requestId` 的 Command、cancel 与重发必须路由到同一线性化 registry shard。第一个合法 Command 在容量判断前建立 `{requestId, rendererCommandDigest, deadlineAt}` reservation；同 ID、同 canonical digest 的请求 join/replay 同一个 inflight 或已 seal 的 terminal result，同 ID、不同 digest 或不同 deadline 返回 `RENDER_REQUEST_CONFLICT`。
- Engine 使用有界 FIFO admission queue。active registry lookup 先于 queue admission；queue wait 计入 deadline且可取消。队列满返回非 terminal `RENDER_ENGINE_BUSY`，reservation 保留到原 deadline，同 digest 可重试；只有实际进入队列才算 accepted execution。并发 slot、queue length 与等待上限由票据 19 冻结。
- `deadlineAt` 对 queue、resource、layout、raster、encode、trace 与 seal 全过程生效。Engine 入站时只把该绝对时刻换算一次为 monotonic remaining time；exact resend不能重置或延长。输出必须在 deadline 前 seal；已 seal 响应的网络传输可以越过 deadline，但 deadline 后不再开始 join/replay。
- cancel 是 authenticated internal control，wire 精确为：

```json
{
  "contractVersion": "renderweave-render-cancel/1.0",
  "requestId": "00000000-0000-4000-8000-000000000000",
  "rendererCommandDigest": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
  "deadlineAt": "2026-08-13T12:34:56.789Z"
}
```

- cancel HTTP entity media type固定为`application/vnd.renderweave.render-cancel+json;version=1.0`。
- cancel 可先于 Command 到达并建立保留到 deadline 的 tombstone；同 digest 重复取消幂等，不同 digest 冲突。取消在原子 output seal 前线性化成功时返回 `RENDER_CANCELLED` 且零输出；seal 后到达不改变成功。HTTP disconnect 只表示调用方停止等待，不是可靠 cancel。
- 固定 cooperative checkpoint 至少覆盖 queue、每次 fetch/retry、decode、font parse、layout阶段、每次 Text shrink iteration、paint chunk、encode chunk 与 seal。不可中断底层调用完成后的结果必须丢弃，slot 直到计算真正停止才释放；各阶段最长 checkpoint latency 与总 deadline 由票据 19 冻结。
- registry/shard 状态丢失时，同 requestId 返回 `RENDER_REQUEST_STATE_LOST`，绝不猜测或重新执行；调用方只能以新 public render operation 完整重新 Evaluation。public `renderOperationId` 与 Engine `requestId` 分离，不能充当 capability key 或 replay token。
- request registry、cancel tombstone、RenderDocument、lease、临时图片与 trace 都按请求隔离并采用内存或加密暂存，只保留到 deadline 以支持 join/replay；不进入普通日志、dump、backup、审计或历史。清理失败只触发运维告警，不延长公共可见生命周期。

### 4. 固定执行顺序、资源重试与原子 seal

- Engine 的可观察执行顺序固定为：`Command parse/profile → deadline/request registry → RenderDocument digest/strict validation → output dimensions/capacity → manifest-order resources → measure/arrange/shaping → final geometry/code feasibility → raster/paint → encode → image hash/result/optional trace → atomic seal`。不可观察的纯准备可以并行，但 first error、预算、资源选择与结果必须等价于该串行顺序。
- 资源按 manifest encounter order串行处理。同一 fetch URL 只有 transport failure 或 5xx 可按 Renderer Profile 的固定 attempt/backoff 重试，且无 jitter；每次 attempt 前重检 deadline、lease expiry与累计预算。4xx、expiry、length/hash/media/magic/descriptor/decode错误均零重试。exact attempts、backoff、fetch byte与duration上限由票据 19 冻结。
- Engine 不做跨请求 raw/decoded resource cache，因为 RenderDocument 不携带可信 ownerScope partition。请求内可按 `kind + sha256 + byteLength + mediaType + exact Renderer Profile` 复用已验证 raw/decode结果，但每个 occurrence 的 lease、manifest顺序、first error与预算仍独立检查；logical current、assetId、resourceId或URL都不是 cache key。
- Engine 完成 encoded bytes、content hash、closed result metadata及获准 trace 后，在同一 registry critical section执行最后一次 cancel/deadline检查并原子 seal。seal 前任何失败都删除临时 bytes/trace并返回一个 terminal problem；seal 后 cancel失败、transport截断可由同 digest exact replay取得同一结果。
- Engine success 与 terminal problem 互斥；没有 partial Scene、partial page、placeholder、warning image、best-effort resource、旧图片回退或成功加警告。Runtime失败不产生业务写入，也不删除、恢复、替换 Asset 或改变 Template readiness/revision。

### 5. Surface、DPI 与 device geometry

- 根 Canvas trim 在 pt 中是 `[0,0,widthPt,heightPt]`；physical surface 是 `[-leftBleedPt,-topBleedPt,widthPt+rightBleedPt,heightPt+bottomBleedPt]`。输出尺寸只由该 surface 与有效整数 DPI 派生：

```text
widthPx  = ROUND_HALF_UP((widthPt  + leftBleedPt + rightBleedPt) × dpi / 72)
heightPx = ROUND_HALF_UP((heightPt + topBleedPt  + bottomBleedPt) × dpi / 72)
```

- 上式使用精确任意精度十进制求乘除，只对整个 surface width/height 各舍入一次；不得先舍入 trim、bleed 或各边再相加。任一结果为零、超出 edge/pixel/encoded-byte预算或无法安全分配时，在资源 fetch 和 surface allocation 前以稳定 output/raster budget code失败。
- device surface 左上是 `(0,0)`，pixel center 是 `(x+0.5,y+0.5)`，hard clip 是 `[0,widthPx) × [0,heightPx)`。trim origin 由精确 `leftBleedPt × dpi / 72`、`topBleedPt × dpi / 72` 平移，允许落在 subpixel；layout、box、glyph、clip 与 transform都不做 pixel snap，四边也不单独round。
- Canvas background 覆盖整个含 bleed surface。PNG 保留 Canvas 的 RGBA alpha；JPEG 必须在 Output Profile 内以固定 opaque white `#FFFFFFFF` 作最后 matte，调用方不能选 matte。DPI 只影响 device lowering和输出metadata，不反馈 layout、shaping、HUG、line break或Image aspect ratio。

### 6. IMAGE、颜色、orientation 与 fetch 安全

- Renderer Profile 唯一输出颜色合同为 `SRGB_8BIT`。IMAGE technical descriptor新增必填常量 `colorEncoding:"SRGB_8BIT"`；Asset admission只接纳无profile/标准sRGB声明/固定canonical sRGB ICC三种等价输入，并拒绝冲突、损坏或任意其他ICC、CMYK/YCCK、HDR与wide-gamut。Engine必须依据 exact bytes重新证明，不信任 descriptor 断言。
- 首批可解码子集精确为：静态 PNG 的合法 grayscale/indexed/RGB/RGBA 组合只允许 1/2/4/8-bit、合法 `tRNS`，拒绝16-bit与APNG；JPEG只允许8-bit baseline/progressive grayscale或YCbCr，拒绝CMYK/YCCK、12-bit及扩展格式；WebP只允许静态 lossy/lossless及alpha，拒绝animation。任何输入先确定性解码、应用orientation并转换为straight RGBA8 sRGB，再进入固定integer premultiplication。
- 只解释颜色/alpha与唯一一份有效EXIF orientation。重复或冲突的orientation直接失败；orientation精确应用一次。输入DPI、time、GPS、thumbnail、软件标记及其他metadata全部忽略且不传播。
- fetch URL必须是canonical HTTPS，拒绝userinfo、fragment、非canonical host/port、dot-segment和percent-encoding allowlist绕过；origin/path-prefix按segment boundary匹配。每次connect都重新执行DNS/egress policy，禁止redirect、proxy environment、cookie、range、caller header与透明compression；Profile固定request headers，只接受`200`、identity body与唯一且等于声明值的`Content-Length`。
- Command admission时每个 `expiresAt` 必须不早于 `deadlineAt + Renderer Profile safety margin`，否则视为内部handoff违约。每次attempt前仍检查expiry；只要完整bytes已在expiry前下载并通过length/hash/media/magic/descriptor验证，随后过期不使本请求内bytes失效，也不允许renewal。

### 7. FONT、shaping 与缺字失败

- 首批 FONT 子集是单face、non-variable、monochrome outline TTF/OTF：允许TrueType `glyf`或CFF轮廓及`cmap/GDEF/GSUB/GPOS/kern`；拒绝collection、COLR/CPAL、CBDT/CBLC、sbix、SVG、bitmap strike、Graphite、AAT-only及malformed/contradictory table。`faceIndex=0`、flavor与unitsPerEm必须和 exact bytes一致。
- 每个Run只使用其exact `fontResourceId`。不得读取system locale、系统/built-in/family fallback或替换glyph；实际文本缺glyph返回`FONT_GLYPH_MISSING`，不能绘制`.notdef`。underline需要有效`post` metrics，strike需要有效`OS/2` metrics；只有实际使用对应decoration且metrics无效时返回`FONT_DECORATION_METRICS_MISSING`。
- v1没有authored locale、language或OpenType feature开关。Layout Profile依据固定Unicode data从文本推断script，以`und`作为language，并按writingMode与固定Unicode BiDi/vertical orientation/line/grapheme/shaping版本和feature set执行；环境、OS、CPU或字体发现顺序不得改变结果。

### 8. Raster、alpha、sampling、QR 与 Barcode

- 权威Renderer只使用CPU raster；不允许GPU或环境相关hinting/LCD subpixel。文字与vector使用Renderer Profile固定的grayscale antialias、coverage、stroke、clip、blend和integer rounding。任一OS/CPU/SIMD target只有独立通过exact corpus后才能READY。
- decoded straight RGBA8按`ROUND_HALF_UP(channel × alpha / 255)`转成premultiplied sRGB8，`alpha=0`时RGB强制为0；opacity、coverage与Porter-Duff source-over使用Profile固定8-bit premultiplied运算和顺序，不在线性光空间混合。PNG编码前按Profile固定规则unpremultiply，完全透明像素RGB仍为0。
- Image在orientation与sRGB归一化后采样。device pixel centers固定在half-integer；`NEAREST`使用Profile固定最近邻及tie规则，`LINEAR`使用premultiplied RGBA8 bilinear；source边缘clamp，不使用透明延伸、mipmap或更高阶filter。CONTAIN bars保持透明，JPEG matte只在最终编码前应用。
- QR/Barcode在完整祖先、Node及compositionViewport world transform累计后，只允许translation、正uniform scale与任意rotation；reflection、skew及non-uniform scale返回`TRANSFORM_UNSUPPORTED`。最终物理box和DPI确定device pitch后，包含quiet zone的local module grid取能放入box的最大整数module pitch并居中；pitch小于1px返回`MODULE_TOO_SMALL`。先以nearest绘制local integer grid，再按固定nearest规则旋转，不反馈layout。
- QR只使用UTF-8 byte mode并总是写ECI 26；error correction来自authoring值，选择可容纳内容的最小version 1–40，再按标准penalty选最低mask，同分取更小mask id；quiet zone固定四个module。内容超限返回`CODE_CONTENT_TOO_LARGE`。
- EAN-8、EAN-13与UPC-A输入必须已经包含有效check digit，quiet zone分别为`7X/7X`、`11X/7X`、`9X/9X`。Code 128只接受printable ASCII，在code set B/C间选择最少codeword方案，同分先选更早switch、再选B，计算标准checksum，quiet zone为`10X/10X`。所有Barcode只绘制bars到完整usable height，不绘制human-readable text。

### 9. PNG/JPEG Output Profile 与字节确定性

- PNG输出固定RGBA8、non-interlaced、sRGB声明和`pHYs`；pixels-per-meter为`ROUND_HALF_UP(dpi × 5000 / 127)`。chunk集合/顺序、filter选择、compression/zlib参数、CRC与encoder实现全部由 `renderweave-output-png/1.0` 的机器manifest和golden corpus冻结，不能依赖库默认值或使用旧引擎的ceil行为。
- JPEG输出固定RGB8、baseline、non-progressive、4:4:4，quality使用有效Command整数；JFIF 1.02写入精确integer DPI，并携带Output Profile固定的canonical sRGB ICC payload。marker/table顺序、quantization、Huffman/entropy行为及encoder实现由 `renderweave-output-jpeg/1.0` 的机器manifest和golden corpus冻结。
- 两种格式都不得写入time、software、EXIF、XMP、随机标识或来源metadata。相同静态Node与exact资源bytes、Renderer/Layout/Output Profile、DPI及JPEG quality必须产生byte-identical encoded image；requestId、deadline、lease URL/expiry、retry/cache路径和trace开关不得改变图片bytes。
- layout中间结果按Layout Profile tolerance验收；最终pixels必须exact，最终encoded image必须byte-exact。任何可观察pixel变化要求新Renderer Profile；只有pixel不变而编码bytes/metadata变化时要求新Output Profile；layout/shaping/break变化要求新Layout Profile，并在pixels也变化时同步新Renderer compatibility组合。

### 10. Success result、HTTP 交付与 LayoutTrace

- Engine在完整编码后计算`contentSha256`，它只等于最终encoded image bytes的SHA-256，不是Evaluation identity、cache key、授权值或业务Artifact ID。closed成功metadata是 `renderweave-render-result/1.0`，成员精确为`contractVersion/requestId/rendererProfile/dslVersion/layoutProfile/outputProfile/format/mediaType/widthPx/heightPx/dpi/byteLength/contentSha256`；JPEG额外必填`quality`，PNG禁止该成员。
- 未请求trace的普通成功以raw `image/png`或`image/jpeg`响应，并固定携带`Content-Type`、`Content-Length`、标准`Content-Digest: sha-256=:<base64-sha256>:`以及`RenderWeave-Result-Version/Request-Id/Renderer-Profile/DSL-Version/Layout-Profile/Output-Profile/Format/Width-Px/Height-Px/DPI`；JPEG再携带`RenderWeave-Quality`。Rendering在向公共调用者释放前核对headers、Command、body length与digest。
- `diagnostics.layoutTrace`默认`false`，只有Rendering先独立授权才可设为`true`。成功trace为closed `renderweave-layout-trace/1.0`，按RenderDocument preorder投影Canvas、synthetic occurrence、`visible:false`与`opacity:0`项；每项只可含opaque occurrenceId、LayoutBox/ContentBox、world transform、PaintBounds/EffectivePaintBounds、clip kind/AABB、适用时paintIndex与overflow flags。诊断binary64统一量化六位`HALF_EVEN`，`-0`写0；量化结果永不反馈布局或绘制。
- trace成功响应使用`multipart/related`，固定两part且无额外part：第一part是media type `application/vnd.renderweave.render-result-with-trace+json;version=1.0`的closed `{result,layoutTrace}` JSON，第二part是raw image。两part各有Content-Digest；Rendering必须核验part数量、顺序、media type、requestId、closed JSON、length与hash后才原子释放。trace超限返回`RENDER_LAYOUT_TRACE_LIMIT_EXCEEDED`且零图片，不截断；调用方可另发不带trace的新请求。
- Engine只把opaque trace返回Rendering；Rendering在seal后依据请求sidecar和权限投影安全定位，删除未授权字段而不是输出placeholder。sidecar、trace、multipart临时内容与定位映射随deadline清理。
- TCP/HTTP chunk不是产品上的partial output。接收方只有在完整length、digest、result及body全部核验后才可展示或保存；截断时删除临时内容并按相同Command exact replay。v1不自动持久化成功bytes，是否向已授权公共响应stream由Rendering决定。

### 11. Problem、阶段、重试分类与 HTTP 映射

- Engine terminal failure只返回一个closed `renderweave-render-problem/1.0`：`{contractVersion,requestId,code,engineStage,occurrenceId?,resourceId?,parameters}`；不适用的optional member必须省略而非`null`。每个code的`parameters`是closed、低基数、安全对象，禁止自由文本、path、业务文本、URL/token/hash、raw cause或stack。是否允许重试由Rendering拥有的code catalog决定，Engine不返回布尔retry hint。
- problem HTTP entity media type固定为`application/vnd.renderweave.render-problem+json;version=1.0`；HTTP status只作transport映射，客户端以stable `code`判断语义。
- `engineStage`只允许：`COMMAND_ADMISSION | REQUEST_CONTROL | DOCUMENT_ADMISSION | OUTPUT_PREFLIGHT | RESOURCE_PREPARATION | LAYOUT | SHAPING | RASTERIZATION | ENCODING | TRACE_PROJECTION | OUTPUT_SEAL`。Command/document admission可按canonical字段有界收集；进入resource之后严格返回第一个错误，顺序为manifest、tree preorder、property declaration与Run index。
- 稳定控制code至少为`RENDER_ENGINE_BUSY/RENDER_REQUEST_CONFLICT/RENDER_REQUEST_STATE_LOST/RENDER_CANCELLED/RENDER_DEADLINE_EXCEEDED`；资源code沿用`RESOURCE_LEASE_EXPIRED/FETCH_FAILED/LENGTH_MISMATCH/HASH_MISMATCH/MEDIA_MISMATCH/DECODE_FAILED/FONT_GLYPH_MISSING`并增加`FONT_DECORATION_METRICS_MISSING`；布局/输出code至少为`LAYOUT_CONSTRAINT_INVALID/LAYOUT_CYCLE/LAYOUT_NUMERIC_ERROR/LAYOUT_BUDGET_EXCEEDED/TEXT_OVERFLOW/CODE_CONTENT_TOO_LARGE/MODULE_TOO_SMALL/TRANSFORM_UNSUPPORTED/RASTER_BUDGET_EXCEEDED/OUTPUT_BUDGET_EXCEEDED/RENDER_LAYOUT_TRACE_LIMIT_EXCEEDED`。
- malformed sealed document、digest/manifest/descriptor/Profile/encoder/seal不变量违约使用更细内部code和脱敏告警，但公共Rendering一律折叠为`RENDER_INTERNAL_ERROR`。普通legal-unrenderable错误保留具体code，不因同为Engine stage而折叠。
- HTTP status只映射transport类别，不改变code语义：成功200、malformed Command 400、request conflict/cancel 409、合法但不可渲染422、upstream resource 502、busy 503、deadline 504、internal 500。Rendering只对`RENDER_ENGINE_BUSY`或unknown transport outcome重发同Command；timeout/cancel/4xx/expiry/integrity/decode/layout/raster/encode均不重试，资源内部5xx重试仍由Engine负责。
- 普通overflow、signed overlap、low contrast、1–3px module或其他仍合法结果不形成warning；获准trace可给固定flag。无warning数组、自动修复或“成功但有错误”状态。

### 12. Profile authority、READY 与 conformance

- `renderweave-renderer/1.0`由机器可读算法/dependency/feature manifest、固定reference implementation commit与golden corpus共同定义；prose解释边界，不能替代可执行向量。Engine startup读取只读certified manifest，只有完整exact compatibility组合均受支持且当前平台通过证据时才进入READY；内部可暴露health/capability，公共调用方不可见也不可选。
- Profile ownership固定为：layout、shaping、break变更发布Layout Profile；decoder、orientation、color、font parser、raster、AA、sampling与QR/Barcode算法变更发布Renderer Profile；仅encoded bitstream/metadata且pixels不变的变更发布Output Profile；wire变更发布对应contract version；可接纳Asset特性范围变更发布AssetAcceptanceProfile。不存在以engine build version、库patch或环境默认热修复旧Profile语义。
- Renderer Profile包含的语义型per-request限制由票据19填入exact数值，部署只有能完整兑现这些上限才能READY，不能悄悄使用更低内存/CPU限制。部署级concurrency slot与queue容量可在接受执行前返回BUSY，但已接受请求只能依合同预算完成或返回合同错误。
- READY证据必须覆盖strict wire/canonical/digest、Layout/shaping、exact pixels、exact PNG/JPEG bytes、QR/Barcode matrix与decode、malformed IMAGE/FONT及descriptor、SSRF/DNS/redirect/proxy/fetch fault、deadline/cancel/retry/registry/shard-loss和output/multipart校验；每个获准OS/CPU/SIMD组合独立重放。截图、单侧unit test、旧Haibo测试或仅说明“Skia默认”均不足以认证。
- 旧 `E:\rust-app\busbox-render-engine` 可复用部分Rust、Skia、PNG/JPEG、fetch与测试基础，但其 `haibo.render/1.0`/`haibo.dsl/1.0`、required DPI、并发resource fetch、built-in font fallback、无safe CPU cancel/active registry、缺少descriptor/trace/opaque locator及环境/codec默认都不兼容。后续必须实现新parser、profiles、resource/control路径并经上述corpus认证，不能只写adapter或改version宣称等价。
- `D:\Yiwer\code\hbads-design-v2` 的“Render Artifact”是sealed scene JSON，权威preview是浏览器SVG；其中PNG/JPEG/WebP只描述输入Asset，PDF也不是Renderer输出。它可说明旧MaterializedScene seam，不能证明本合同的图片Renderer或Output Profile。

### 13. 票据 19 继续冻结的数值与明确排除

- 本票据冻结预算维度但不猜测最终负载数值。票据19必须填写：最大DPI、surface edge/pixel/decoded/encoded bytes；queue/slot/deadline/checkpoint；resource attempts/backoff/fetch bytes与总时长；manifest与request-local cache；font table/glyph、shape/paint/clip；QR/Barcode module/content；PNG/JPEG encode；trace item/bytes与multipart；registry/temp retention，并用峰值内存、CPU和故障注入证据证明。
- v1明确排除：multi-output/batch/page/node export、crop/resize/target-size/background override、WebP/SVG/PDF/HTML输出、GPU、cross-request output/resource cache、Artifact/Workspace/history保存、公共RenderDSL/Command/Engine API、partial output/warning image/placeholder、default/replacement font、arbitrary ICC/HDR/CMYK、variable/color/bitmap font、caller profile选择、旧Haibo fallback、环境默认、lease renewal、替换URL或Asset current、失败后局部继续与Engine触发重新Evaluation。
- 本票据只冻结探索规格、领域语言及后续实施/验收约束，不创建Renderer、client、API、queue、registry、cache、codec、Artifact、表、路由、部署配置或任何占位实现。
