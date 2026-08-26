# 激活 Template 最终产品路由与浏览器验收

Type: task
Status: in_progress
Claimed by: Codex `/root`（single-writer）
Blocked by: 09, 27, 28, 29, 30, 31, 32, 35, 36, 37, 114, 124, 125（均已 resolved）

## Question

Template 目录、创建页、真实 Editor E1–E9、公共 Authoritative Preview HTTP seam 与完整失败封闭均已物化，
但 `App` 仍未挂载 `/templates`，一级导航仍声称“不展示 Template/Render”，Editor 顶栏也没有返回正式目录的
产品路径。如何一次性激活最终产品页面并在真实浏览器中验证 list → create/open → Editor → authoritative preview，
同时不把 `/prototype`、测试 fixture 或未认证 native Renderer 冒充最终交付？

## Answer（本票冻结的实施决定）

1. **正式路由一次开放**：`App` 以既有 lazy-route deployment recovery 挂载 `TemplateListPage`、
   `TemplateCreatePage` 与 `TemplateEditorPage`，精确开放 `/templates`、`/templates/new`、
   `/templates/:templateId`；opaque route identity 只由 wrapper 原样交给 Editor，wildcard 纪律不变。
2. **稳定一级导航**：`ResourceRail` 新增“模板设计”产品入口，对 list/new/editor 全部保持 active；接口版本与 v1
   范围说明同步已发布的 0.17.0 Template/Rendering seam。Editor 的品牌入口在 structured、safe、loading 与 error
   状态均提供可访问的“返回模板目录”链接，不依赖浏览器 history，也不新增隐式状态。
3. **最终页面而非原型**：只复用 T114/T125 的 `web/src/features/templates` 与 `template-editor` 产品组件；
   不导入 `web/src/prototype`，不创建占位 route、disabled preview、浏览器自渲染或产品内 fixture/fallback。
4. **正式产品浏览器闭环**：新增只访问正式 URL 的 Playwright journey，在 HTTP 边界提供合同精确的 catalog、
   StaticSchema、create、current/recheck 与完整 preview image headers/body；验证目录/导航、创建后的 exact editor URL、
   trusted baseline、权威预览图片与安全 metadata、返回目录、1024/1280/1440 布局和 serious/critical axe 零发现。
   测试响应只属于 E2E，不进入 production bundle，也不证明 native Renderer/Profile physical success。
5. **验证与诚实边界**：先新增 App/nav/editor-link unit RED 与 formal product Playwright RED，再实现最小 GREEN；
   focused Node 24 → 全量 Web typecheck/lint/test/build → `web` → `fast` → Goal `full` → resolution `fast`。
   Profile registration/certification、daemon native success、public cancel、J1/A3/READY、生产/真实数据/provider/API Key
   均不在本票，native stack 继续 `BUILD_NOT_AUTHORIZED`。
