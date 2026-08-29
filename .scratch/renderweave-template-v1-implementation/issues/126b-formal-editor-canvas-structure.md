# 补齐正式 Template Editor 画布视口与结构树交互

Type: task
Status: resolved / automated_verified
Claimed by: —（verified 后已释放）
Blocked by: 09, 27, 28, 37, 126, 126a（均已 resolved 或 implementation_complete）

## Question

TV1-T126a 已让正式 Editor 创建、保存和重开 Rect，但 2026-08-27 人工 smoke 发现新增节点只出现在结构列表，
本地画布仍把节点渲染为名称标签，既没有 authored Rect 几何，也没有设计工具所需的滚轮缩放；现有结构树也只有
静态缩进和基础上下键，没有真实折叠、父子导航、搜索或大树窗口。如何在不把浏览器投影冒充 Renderer、不复制
历史参考仓库旧 document wire 的前提下，按 `D:\Yiwer\code\hbads-design-v2` 已验证的 Canvas/LayerTree 交互语言
补齐正式产品体验？

## Answer（本票冻结的实施决定）

1. **参考交互、保留 RenderWeave 事实源**：复用历史参考的指针锚定滚轮缩放、适合画板/100%/步进缩放、空格或
   中键平移、固定 Canvas 根行、层级折叠、搜索保留祖先、roving tree 与选中联动；继续使用本仓库 Hum/Workbench
   tokens。历史 flat prototype document、Leafer persistence、visibility/lock/DnD/rename 等当前没有真实 command 的
   verb 不进入正式产品，也不出现 placeholder。
2. **session-only viewport deep module**：新增纯 `fit / zoomAtPoint / wheelZoom / pan` seam；viewport 只持有
   `scale + offset`，缩放限制为 `0.25..4`，wheel 使用连续指数步进并把指针下 world point 固定。该状态不进入
   DesignDSL、canonical dirty、history、save、recovery 或 authoritative preview basis。
3. **诚实的本地 authored projection**：Canvas 根按物理毫米比例形成有限画板；只把能够从 authored
   `ABSOLUTE + FIXED` placement 精确读取的节点投影为本地几何，首个闭环为 Rect 的 x/y/width/height/fill，并支持
   同一 selection seam。浏览器不运行 RenderEngine，不猜 STACK/GRID/PACK/HUG/FILL 布局；无法精确投影的节点仍在
   结构树与 inspector 可见，画布持续标记“本地草稿投影 · 非权威”。
4. **结构树 deep module**：从现有先序 Node projection 构建稳定 parent/ancestor/descendant 行；支持真实折叠、搜索命中
   及祖先链、固定高度虚拟窗口、selected 自动揭示、Up/Down/Home/End 与 Right/Left 层级导航。正式组件固定 Canvas
   根行，使用节点类型 icon、层级连线/圆点、折叠计数与明确选中态；tree、canvas、inspector 共用同一 nodeId。
5. **可访问与降级路径**：滚轮缩放同时提供原生 button 的缩小、百分比复位、放大和适合画板；结构树仍是画布选择的
   完整键盘替代路径。交互目标至少 44px、focus 可见、状态不只靠颜色、reduced motion 不引入持续动画。
6. **TDD 与门控**：用户已确认四个 seam：pure viewport、pure structure tree、正式 DOM、正式 5173 browser。先写
   RED 并捕获旧标签画布/无 wheel/无折叠的失败，再最小 GREEN；focused Vitest → Web test/typecheck/lint/build →
   `web` → formal Template Playwright → 实际 5173 smoke。用户 smoke 期间不运行会改写挂载 JAR 的 `fast/server/full`；
   完成复验后再恢复 T126a/T136 延后门控。

## Results（2026-08-29）

- `template-canvas-viewport` 已形成纯 `fit / zoomAtPoint / wheelZoom / pan` 模块，冻结 `0.25..4` clamp、连续指数
  wheel 与指针 world-point 不漂移；正式 Canvas 只持 session transform，不写 DesignDSL/history/save/recovery。
- 正式 Canvas 已以 4 px/mm 投影 Canvas 物理尺寸及可精确读取的 authored `ABSOLUTE + FIXED` Rect
  x/y/width/height/fill，tree/canvas/inspector 共用 selection；画布持续显示“本地草稿 · 非权威”，未运行或冒充
  RenderEngine layout。滚轮、空格/中键平移、缩小/100%/放大/适合画板均有真实行为，控制目标至少 44px。
- `template-structure-tree` 已形成稳定 parent/descendant、collapse/search ancestor、固定 44px row window 与
  Up/Down/Home/End/Right/Left 纯语义；正式树固定 Canvas 根行并完成 disclosure、branch guide、roving focus、
  selected reveal 与 55-node 虚拟窗口焦点重放。
- 正式 Node 24 `web` gate 全绿：35/35 test files、274/274 tests、typecheck、lint、2164-module production build；
  A1 证据为 `.sdlc/evidence/20260829-182815-web/metadata.json`。formal Template product 3/3 + a11y 4/4（合计
  Playwright 7/7）通过，覆盖 Rect DOM 几何、指针锚定 wheel、折叠/展开、祖先保留搜索、canonical save/reload、
  44px targets、零 serious/critical axe、1024@2x 与窄屏诚实降级。已有实际 5173 截图经本轮复核；
  未写真实 Template 或调用 provider。
- T126b-specific A2/A3 无；J0 pending、J1 未批准。未修改 Java/OpenAPI/migration/Renderer/Profile，provider
  attempts、API Key reads、真实数据、生产操作、push/tag/PR 均为 0。

## Boundary

- Web-only；不修改 Java/OpenAPI/migration/Renderer/Profile，不新增依赖，不把 local projection 当 Authoritative Preview。
- 不实现完整 LayoutEngine、拖拽改 geometry、resize/rotate、删除/重排/重命名、visibility/lock 或其他节点 authoring。
- 不运行 provider、读取 API Key、发送真实数据、执行生产操作或推进 J1/A3/READY；用户既有 dirty work 不在本票写入。
