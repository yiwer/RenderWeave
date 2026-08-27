# 补齐正式 Template Editor 首个节点创作纵切

Type: task
Status: implementation_complete / user_smoke_and_fast_pending
Claimed by: Codex `/root`（single-writer）
Blocked by: 09, 14, 14b, 16, 28, 29, 126（均已 resolved）；136 已 implementation_complete 并释放 claim

## Question

TV1-T126 已把最终 `/templates/:templateId` 产品路由挂载并验收，但 2026-08-27 的人工 smoke 发现正式 Editor 的“节点”
入口仍只有 closed-kind 计数，E2 也明确只允许修改 Template 名称；作者无法从最终产品页面创建任何 Design Node。如何在
不复用 throwaway prototype 状态模型、不复制历史参考仓库旧 wire、也不绕过 DesignDSL authority 的前提下，交付首个可
撤销、可保存、可重开的真实节点创作闭环？

## Answer（本票冻结的实施决定）

1. **真实纵切而非原型**：正式 `TemplateEditorShell` 的节点库新增可执行“添加矩形”动作。Rect 是首个 TDD tracer；
   创建后立即成为 canonical working copy 的一部分，可由既有 Template save API 保存并在重开后恢复，不创建
   `/prototype`、fixture fallback、占位按钮或第二份文档状态。
2. **深 authoring seam**：新增纯前端模块，以
   `StructuredEditorSession + {kind, selectedNodeId} + injectable UUID factory → applied | rejected` 为公开接口。
   模块从当前 DesignDSL 树选择“选中容器，否则最近可承载祖先，否则 Canvas”作为父节点，按父 ContentModel 写入
   `ABSOLUTE | STACK | GRID | PACK` placement，生成 canonical lowercase UUID v4 与合法 Rect static baseline。
3. **同一 EditorSession 与 history**：扩展 closed command union 增加 `insert-node`，记录 parent nodeId、authored child
   index 与完整 node value；forward/backward 都重放结构值并重新 canonicalize。成功创建清空 redo、受 100 条 history
   上限约束、递增 preview generation，并继续只以 canonical bytes 判定 dirty；无效 identity、父树或 16 MiB 超限均零变化。
4. **同步且可访问的产品交互**：节点库按钮使用原生 button，键盘与指针走同一动作；成功后自动选中新 Rect 并切到结构
   面板，使结构树、非权威 Canvas projection 与右侧 inspector 同时读取同一 working copy。保存按钮随 canonical dirty
   真实启用；失败以可见 alert 与 polite announcement 呈现，不依赖颜色表达状态。
5. **现有服务端 admission 闭环**：不新增 HTTP/OpenAPI/migration。浏览器 journey 必须观察 PUT 的完整 canonical
   DesignDSL，断言新增 Rect 拥有 UUID v4、空 bindings、父级合法 placement 与 fill，并让响应/reopen 返回同一节点；
   服务端仍是最终 DesignDSL admission/readiness 权威。
6. **TDD 与门控**：先让纯 authoring/history、正式 DOM 与 formal product save/reopen 三个 seam RED，再最小 GREEN；
   依次执行 focused Vitest → Web test/typecheck/lint/build → `web` → `fast`。当前人工 smoke runtime 在重新打包前保持
   不变；代码绿色后先告知并协调重建，再由用户复验，随后恢复 T136 的顺序 `server/full/resolution fast`。

## Results（2026-08-27）

- 三个冻结 seam 均已 RED→GREEN：新增纯 `template-node-authoring` deep module；`insert-node` 进入同一
  `StructuredEditorSession` canonical history；正式产品 DOM 的“添加矩形”同步 tree/canvas/inspector，并沿既有 PUT
  保存、刷新重开。
- focused Vitest 3 files/40 tests、完整 Web 33 files/262 tests、typecheck、lint 与 2160-module production build 全绿；
  正式 Node 24 `web` gate exit 0，A1 证据为 `.sdlc/evidence/20260827-110418-web/`。
- formal Template Playwright 完整文件 3/3 通过；随后直接复用现有 `localhost:5173` 静态 smoke，首个 Rect
  create/save/reopen journey 1/1 通过。测试 HTTP contract 只在浏览器内拦截，不写入真实 Template；真实
  `18bbf31c-bdfd-42fb-b2c3-50d4725e3ec3` 已只读确认是 `READABLE/READY/revision 0/canvas/0 children`。
- Web smoke 容器只读挂载 `web/dist`，production build 已即时发布，无需重启。`fast` 会重新打包 smoke API 当前挂载的
  JAR，故在用户复验结束前按运行期稳定性边界延后；本票不提前标记 `automated_verified`，人工 smoke/J1 仍 pending。
- 内置 Browser 控制层因 runtime kernel asset 路径错误未能连接；已使用仓库 Playwright 对实际 5173 服务复验，连接层
  故障不记为产品失败，也不冒充 visible/J1 验收。

## Boundary

- 本票只完成首个 Rect 创建纵切，不冒充全节点/全属性设计器；后继纠偏票继续扩展其余无外部选择器依赖的节点、删除/
  重排与基础属性。Text/Image 必须等待真实 FONT/IMAGE Asset picker，不能生成伪引用或默认字体。
- 不修改 NodeContractCatalog、BindingPolicyCatalog、Java/OpenAPI/migration、Renderer/Profile 或正式 runtime 语义；不引入
  通用 properties bag、Slot、zIndex、浏览器派生坐标持久化或历史参考仓库的 flat prototype document。
- 不运行 provider、读取 API Key、发送真实数据、执行生产操作或推进 J1/A3/READY。用户既有 360 项 dirty work、
  `tools/run-gate.ps1`、Rendering resource dirt 与备份 stash 不在本票写入/暂存范围。
