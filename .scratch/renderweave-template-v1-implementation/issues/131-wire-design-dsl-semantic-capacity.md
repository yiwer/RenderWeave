# 接线 DesignDSL semantics 十五轴产品容量

Type: task
Status: automated_verified
Claimed by: Codex `/root`
Blocked by: 130（已 resolved）

## Question

T130 已让唯一 `DesignInputExpressionCapacityAuthority` 完整解释 65 轴，并把 parser/canonical 九轴接到真实产品路径；
`designDslSemantics` 的 authored node/tree、definitions、Binding、Text run、Grid、vector、TemplateUse fill 与 literal list
十五轴仍只是 scalar rule。如何让这些计数在真正 DesignDSL admission 中共享同一 authority，同时保持 first-error、
zero-write、现有 canonical bytes 与单一深 module，而不把巨大语义 validator 拆成十五个浅 guard？

## Answer（本票冻结的实施决定）

1. **一个 request-local 预算器**：在 `CanonicalDesignDslAuthority` 内建立单一 request-local semantic capacity budget，
   只负责从真实 validator reservation point 派生十五轴 observation，并调用 T130 的共享 Interface；数值、比较器、
   terminal 与 downstream effects 继续只由 canonical capacity authority 拥有，不复制阈值。
2. **先 reservation、后语义实体**：authored node/depth/children、definition、Binding、run、Grid track、vector entry、fill 与
   literal list item 均在加入规范化集合或继续递归前检查 candidate count；任一拒绝映射到既有
   `DESIGN_DSL_LIMIT_EXCEEDED / DESIGN_SEMANTIC_VALIDATION`，保持零 Template/Asset write、零 evaluation/render output。
3. **closed public locator**：只对 `DesignDslAuthority.Limit` 追加十五个精确 `designDslSemantics.*` locator，使真实产品
   rejection 可携带冻结 limitId；不新增方法、SPI、配置、HTTP/OpenAPI/DB/Web surface。
4. **精确计数语义**：Canvas 与 authored child node 计入 node/depth；每个 container 的直接 children 单独计数；definitions、
   bindings/runs/fills/track/entry/list 分别同时维护 per-owner 与 request total；run text 按 Unicode scalar 计数。计数器使用
   checked long，溢出失败封闭，不分配 boundary-size fixture。
5. **TDD 与 replay**：先让 recording/rejecting authority 对十五个缺失 reservation source 捕获 RED，再实现最小 GREEN；
   产品 proof 必须展示真实小型 DesignDSL 派生观察与 exact rejection locator。既有 211-vector Java/Python canonical replay
   必须 byte-identical；65-axis/195-case Java+TypeScript scalar replay继续通过。
6. **版本化 component target**：保留 T130 frozen v1，不原地改写；实现提交后生成 v2 component target，绑定全部变更
   source/test/tool hashes，报告必须写明 wired 24/65、remaining 41、preissuance/issuance/executable false。仍不签发 class
   required executor manifests 或 formal records。
7. **门控与边界**：focused Template → component → `template`/`template-static` → `fast` → 顺序 `server` → Goal `full` →
   resolution `fast`；Maven 串行、精确 staging。formal registry 保持 58/58；不触碰用户 360 项 dirty work、独立 native
   build、provider/API Key/真实数据/生产/J1/A3/READY，`BUILD_NOT_AUTHORIZED` 保持。

## Results

- 产品实现 revision `42260b99a7315ac2fceff3e9b6831bb2ee34aa5a` 新增 request-local
  `DesignSemanticCapacityPreflight`，并由 `CanonicalDesignDslAuthority` 在 root/version 检查之后、语义规范化与任何依赖
  I/O 之前调用。authored nodes/depth/children、definitions、bindings、Text runs/scalars、Grid tracks、vector entries、
  TemplateUse fills 与 literal-list items 十五轴均从真实 parsed `JsonValue` 派生 observation，并调用唯一
  `DesignInputExpressionCapacityAuthority`；拒绝继续精确映射到既有
  `DESIGN_DSL_LIMIT_EXCEEDED / DESIGN_SEMANTIC_VALIDATION`。
- `DesignDslSemanticCapacityReservationTest` 对十五轴产品 observation 与 exact locator rejection 完成 proof；Template
  module 回归为 91 tests 全绿，DesignDSL Java/Python canonical kernel 保持 211/211 byte-identical。未新增方法/SPI/
  配置/HTTP/OpenAPI/DB/Web surface，也没有把容量阈值或 comparator 复制进调用者。
- target revision `31ea01718a8a2e9aca2c82c39c88d95357aab724` 冻结 component target v2；target 9967 bytes、
  SHA-256 `3f0a923d982da15b64ef999720947550da374889c41491a0bbc645b53dc4f45c`，target ID 为
  `DESIGN_INPUT_EXPRESSION_TARGET::CAPACITY_AUTHORITY_PARTIAL_WIRING::2.0`，绑定 20 个 source/test/tool artifact。
  predecessor v1 继续保持 7762 bytes、SHA-256
  `049e78fb920d8317d95579b96cddca18de424811859b388e55cba1afbbe0432f`，未被原地改写。
- component gate `.sdlc/evidence/20260826-162032-design-input-expression-capacity/` 中 Java primary 与独立
  TypeScript replayer 均为 65 axes、195/195 cases（125 accepted、70 rejected）；primary 报告 SHA-256
  `10afe83d8370cfcba00ef8dc89a5547439c00d06bbd80f4701cbd1c72e741155`，independent 报告 SHA-256
  `bd2f5d8980791dc76c1cd94cbe3e091d6d67267cf4101ed3fd4fe27302794670`，独立 replay 为 2608 checks，
  observation digest `e760c63ae7a2cf364adc33dc366150e31c5e03eb70c0348e4c2d82c8de304796`。两份报告均诚实声明
  wired 24/65、remaining 41、preissuance/issuance/executable false。
- 受影响 `template` `.sdlc/evidence/20260826-162133-template/` 与 `fast`
  `.sdlc/evidence/20260826-162230-fast/` 全绿。首次 `server`
  `.sdlc/evidence/20260826-162321-server/` 揭示 `MaterializerTest` 的旧容量探针使用单个 10000-item literal list，已被
  新接线的 4096 per-list 产品上限正确提前拒绝；这不是产品行为回归。兼容 revision
  `98974377315a3868ecfe77ab3e7daf74a6915bc2` 将同一 materializer 压力场景改为 4000 + 4000 + 2000 三个合法
  list，仍生成超过 20000 个 static nodes；focused 1/1、完整 `MaterializerTest` 11/11 与顺序 `server`
  `.sdlc/evidence/20260826-164117-server/`（Application 445 tests、0 failure/error、21 controlled skips）均通过。
- 发布级 `full` `.sdlc/evidence/20260826-165919-full/` 在 exact revision `98974377` 上 17/17 steps、
  1500.69 秒通过，覆盖完整 Maven reactor、Node 24 Web 251 tests、runtime/R0/R1/P0 与浏览器旅程；首轮 Chromium
  为 25 passed + 1 controlled skip，随后真实 inference replay 为 1/1。R0/R1/P0 的 external provider attempts、
  API Key reads、reservations 与 cost 均为 0；formal registry 保持 58 Case / 58 Oracle。
- 用户既有 360 项 dirty work 继续保持指纹
  `4bddd1c955a4b4f55d984f3febd551742dd6f63c`，备份 stash
  `f3c29199ec510ec3f809b3f8263f5d2806cb0740` 未变。本票未签发两个 class required executor manifests，未发行
  195 条 Design/Input/Expression records，未升级中央 executable 状态，也未运行独立 native build/provider/真实数据/
  生产或取得 J1/A3/READY；仓库 `full` 内既有 Rust checks 不改变 `BUILD_NOT_AUTHORIZED`。
