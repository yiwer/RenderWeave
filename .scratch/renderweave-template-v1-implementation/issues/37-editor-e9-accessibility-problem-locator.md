# 实现 Editor E9 键盘流、live region、有效宽度与问题定位投影

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 09, 27, 28, 30, 31, 32, 35, 36（均已 resolved）

## Question

如何在不开放产品 route、不伪造 E6 权威预览、也不把服务端问题指针猜成任意 UI 位置的前提下，让当前
Template Editor 的 Structured 核心流满足冻结的 E9：问题失败后聚焦摘要、可验证地定位到等价表单路径，
树与 drawer 全键盘可用，动态反馈不过度播报，并在 1024/1280/1440 与 200% 有效视口下保持完整操作、低于
1024 时如实显示不支持状态？

## Answer（本票冻结的实施决定）

1. **严格、有限的问题定位 deep module**：只接受已受限的 RFC 6901 JSON Pointer；严格解码 `~0`/`~1`，
   拒绝 malformed escape、非法数组索引、越界或非 own-property traversal。定位只产生 closed outcome：Template
   display name、exact-walk 已到达的最深 authored node、definitions 面板，或 `unavailable`。属性本身缺失时可定位
   到已经精确走过且具稳定 `nodeId` 的 owning node，但必须在播报中说明粒度；未知根分支、无稳定身份或 malformed
   pointer 不猜测、不改写，原 `code`/`pointer` 始终可见。
2. **失败摘要与定位交互**：invalid-save offer 出现后把程序焦点移到带总数与完整性说明的摘要；每个可定位问题
   提供显式按钮。定位时按目标打开 Structure/Definitions、展开有界树窗口、选择并滚动目标，最后把焦点放到等价
   form/tree target；失败保持面板原位并以 truthful message 宣告。该交互不重试 PUT、不改变 confirmation token、
   baseline、working canonical、history、recovery 或 preview generation。
3. **键盘树与焦点纪律**：Structure tree 使用单一 roving `tabIndex`；ArrowUp/ArrowDown/Home/End 沿当前可见顺序
   移动并选择，Enter/Space 保留原生 button 行为，鼠标点击与程序定位复用同一 selection seam。skip link 进入主区，
   inspector drawer 的开关、关闭和所有既有操作保持顺序可达；不得产生 keyboard trap 或依赖拖拽。
4. **最小 live region**：删除包围整个 entry panel 的 live region，只让短、去重的状态 delta 进入一个 central
   polite/atomic announcer；错误摘要继续使用适当 alert/status 语义。不得把完整 DSL、完整 RootDocument、模型输入输出、
   原始导入字节或重复的大块 DOM 文本送入辅助技术播报或日志。
5. **有效宽度与缩放边界**：浏览器 A1 在 1440、1280、1024 CSS px 验证无页面级横向溢出、核心操作与 1024
   inspector drawer 键盘路径；以 2x device scale/等效 1024 CSS viewport 覆盖 200% reflow 下限。低于 1024 只显示
   明确 unsupported notice，隐藏编辑操作不得进入 Tab 序列。真实浏览器 200% zoom 与完整人工键盘走查仍是 J0，
   自动结果不冒充 J1。
6. **浏览器证据不开放产品面**：新增仅供 Playwright/Vite 测试加载的独立 fixture page，直接装配 production
   component 与内存 transport；不修改 `App.tsx`、不新增 `/templates/:templateId` 或 prototype/product route。
   使用 `@axe-core/playwright` 对默认、invalid/problem 与 drawer 状态断言 serious/critical 为 0，并检查 focus order、
   tree navigation、locator 与 unsupported width。
7. **边界**：Web-only；不修改 Java、OpenAPI、generated SDK、migration、API version、Renderer、Profile 或 formal
   record，不实现 E6 preview action/result，不调用 provider、不读取 API Key、不发送真实数据。最高状态为
   `automated_verified`；AC-014/Editor 人工 J1、A3、physical Linux certification 与 READY 均不在本票自证。

## TDD、验证与完成信号

- Pure RED：pointer escape/array/own-property/budget、display name、nearest authored node、definitions 与全部 fail-closed
  outcome；不得靠 DOM selector 猜指针。
- DOM RED：invalid summary focus、定位成功/降级/不可用、central live delta、roving tree Arrow/Home/End、超过首批
  50 节点的程序展开与 focus、drawer/unsupported 状态的可访问名称。
- Browser RED：Playwright fixture 在 1024/1280/1440、2x effective viewport 与 `<1024` 下覆盖 axe、Tab/skip、tree、
  invalid-save locator、drawer 和页面级 overflow；console/page error 为 0。
- focused Node 24 tests → 完整 Editor tests → typecheck/lint/build → `web`/`fast` → 最终 `full`；全部绿色后才改为
  `resolved / automated_verified` 并形成一个 verified local commit。不 push/tag/PR，不升级 Editor/Renderer/Template v1
  READY；人工 J1 保持 pending。

## Resolution

- 新增 Web-owned `template-problem-locator` deep module：严格、受预算约束地解码 RFC 6901，拒绝 malformed escape、
  非 canonical/越界数组索引与非 own-property traversal；只返回 Template 名称、definitions、exact/owning authored node
  或 unavailable，绝不从不可信 nodeId 拼 selector。
- invalid-save offer 现在聚焦摘要；可定位问题打开所需 navigator/entry、扩展有界树、选择并聚焦目标，中央
  polite/atomic live region 只播报短 delta。Structure tree 使用 roving tab 与 ArrowUp/ArrowDown/Home/End；skip link、
  1024 drawer、44px 目标及低于 1024 的 truthful unsupported/零编辑 Tab 路径均已验证。
- pure locator 14/14；新增 DOM accessibility 3/3，完整 Editor 12 files/136 tests；正式 Node 24 Web 为
  26 files/212 tests、2144-module build，证据 `.sdlc/evidence/20260821-200839-web/`。
- 无产品 route 的 Playwright/Vite fixture 在 1440/1280/1024、1024 CSS px + 2x device scale 与 900px 下验证
  axe serious/critical=0、无横向溢出、键盘树/skip/focus/locator/live/unsupported 行为及 console/page errors=0。
  完整 E2E 23 passed/1 live-provider skip，证据 `.sdlc/evidence/20260821-200647-e2e/`；既有资源列表 axe 审计
  同时改为 reduced-motion 用户偏好并等待卡片动画结束，消除扫描过渡帧的竞态而不放宽规则。
- 审计确认无 Java/OpenAPI/generated SDK/migration/API version/`App.tsx`/产品 route/Renderer 增量，无
  provider/API Key/真实数据/付费调用。T37 最高为 `automated_verified`；真实浏览器 200% zoom、完整人工键盘
  走查、AC-014 J1、A3、physical Linux 与 READY 仍 pending。
