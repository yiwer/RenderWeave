# 接线 Expression Definition 静态结构八轴产品容量

Type: task
Status: automated_verified
Claimed by: Codex `/root`
Blocked by: 133（已 resolved）

## Question

T130–T133 已让唯一 Template-owned `DesignInputExpressionCapacityAuthority` 接管 38/65 个真实产品轴；
Expression 仍有 17 轴只存在于 scalar profile。如何先把每份 DesignDSL 在 presence analysis 与 lazy execution 前即可
完整决定的 source/input/Mapping case/Definition graph 八轴接到保存与重检的真实 admission 路径，同时保持单份 DSL
计数、未使用/未选择/静态不可达内容仍计数、Definition authored order 无求值含义、零 Template 写入和既有 canonical
bytes 不漂移？

## Answer（本票冻结的实施决定）

1. **最小八轴纵切**：本票只接线 `expression.sourceUtf8BytesPerExpression`、
   `expression.sourceUtf8BytesTotal`、`expression.inputsPerExpression`、`expression.inputsTotal`、
   `expression.mappingCasesPerDefinition`、`expression.mappingCasesTotal`、
   `expression.definitionGraphEdges` 与 `expression.definitionChainDepth`。AST 两轴和 decimal 七轴继续后票，
   不把 partial expression group 冒充完成。
2. **一个 request-local 深预算器**：在 `CanonicalDesignDslAuthority` 的单次 `admit` 内建立
   `ExpressionDefinitionCapacityBudget`；它只从已验证的 Definition wire 派生 observation 并调用共享 authority，
   不复制 limit/comparator/terminal 数值，也不让计数状态进入 singleton、Template revision 或日志。
3. **先 reserve、后遍历/加入**：Expression source 按 exact UTF-8 bytes、inputs 与 Mapping cases 按 candidate array size
   在扫描/遍历前同时验证 per-owner 与全 DSL total；Definition source edge 在加入 graph 前验证 candidate total。
   未使用 input、未选择 case 与未消费 Definition 均在 DesignDSL admission 时计数。
4. **DAG 后计算 chain**：沿 `CanonicalDesignDslAuthority` 已验证的 Definition DAG，以引用 edge 数定义 dependency
   chain depth（无 edge 为 0），在任何 lazy definition evaluation 前对最长链形成唯一 observation；cycle/dangling 仍保持
   原有 `DESIGN_VALUE_INVALID` first-error，不用容量 rejection 掩盖结构错误。
5. **closed terminal 与 public surface**：共享 authority 继续唯一拥有 Expression terminal；DesignDSL admission 沿用
   T131 的既有 closed envelope，把任一非 `Accepted`/异常判断映射为
   `DESIGN_DSL_LIMIT_EXCEEDED / DESIGN_SEMANTIC_VALIDATION` 与 exact public `Limit`，不新增 HTTP/OpenAPI 方法或第二套
   comparator。Create/save/recheck 超限时不 canonicalize、不访问依赖、不追加 revision。
6. **TDD 与兼容**：先以 recording/rejecting authority 从真实 `CanonicalDesignDslAuthority.admit` 建 RED，覆盖八个
   observation、每轴 exact rejection、per-vs-total、edge-before-admission、longest-chain 与 authority fail-closed；随后最小
   实现并重放既有 211 canonical vectors，证明合法 canonical bytes/contentHash byte-identical。
7. **版本化 target 与门控**：实现提交后保留 v1–v4 不可变并冻结 v5，只声明 wired 46/65、remaining 19
   （expression 9、geometry 10）。component → `template` → `fast` → sequential `server` → Goal `full` → resolution
   `fast`；两个 required executor manifests、独立产品 replay 与 195 formal records继续 pending，
   `BUILD_NOT_AUTHORIZED`、provider/API Key/真实数据/生产/J1/A3/READY 边界不变。

## Results

- 产品实现 revision `f068e69592f6420781e2b6c95288dc29ff582c4b` 已建立 request-local
  `ExpressionDefinitionCapacityBudget`，并把 source UTF-8 bytes、input、Mapping case、Definition graph edge 与最长
  chain depth 八轴接到真实 `CanonicalDesignDslAuthority.admit`。候选总量均在遍历/加入前 reserve，chain 在结构 DAG
  验证后、lazy evaluation 前计算；authority reject/invalid 均沿既有 closed envelope 零写退出。
- TDD 的 recording/rejecting seam 先得到 5 个预期 RED，随后 focused 11/11、Template 108/108 与 canonical
  Java/Python 211/211 全绿；未使用 input、未选择 Mapping case、未消费 Definition、edge-before-admission、最长链、
  per-vs-total first-error 与 fail-closed 均由真实产品入口证明，既有 canonical bytes/contentHash 未漂移。
- target revision `8b92d961986ae326d647f2aba6ee4f7b2aee3a12` 冻结不可变 v5（25610 bytes，SHA-256
  `3de4e110b54ebede43d137e4c39a130a1474e0a1fc803574178112b7a323e7a9`），v1–v4 bytes 保持不变。component
  `.sdlc/evidence/20260826-203600-design-input-expression-capacity/` 为 Java/TypeScript 195/195、2666-check 独立
  replay、45 个 bound artifacts 零 mismatch，wired 46/65、remaining 19；class
  preissuance/issuance/executable 均 false，formal registry 保持 58/58。
- 分级门控 `template` `.sdlc/evidence/20260826-204015-template/` 与 `fast`
  `.sdlc/evidence/20260826-204050-fast/` 全绿。首次 `server`
  `.sdlc/evidence/20260826-204127-server/` 只命中用户既有 UDS fixture 的跨测试清理竞态；独立复现确认与本票无关后，
  顺序重放 `.sdlc/evidence/20260826-210351-server/` 以 Application 447/447、Inference 439/439 通过。
- 首次发布级 `full` `.sdlc/evidence/20260826-211608-full/` 只暴露正式 Template 搜索 placeholder 的 Axe
  对比度失败；revision `0192af74464532e5d06a233d943fbed643189155` 在 Template-owned CSS 内修复，focused
  Chromium 2/2 `.sdlc/evidence/20260826-213931-template-search-contrast-repeat/` 与 Node 24 `web`
  `.sdlc/evidence/20260826-214010-web/` 全绿。最终 `full` `.sdlc/evidence/20260826-214129-full/` 为 17/17
  steps、1282.425 秒，Node 24 Web 32 files / 251 tests、Chromium 25 passed + 1 controlled skip、Draft 与
  inference replay E2E 全绿；正式 Template catalog 与 create/open/preview 两条浏览器路径均通过。
- tracker 收口后的 resolution `fast` `.sdlc/evidence/20260826-220648-fast/` 通过（8/8 package reactor + Web
  typecheck）。
- Expression 尚余 AST 2 轴与 decimal 7 轴，geometry 尚余 10 轴；两个 required executor manifests、独立产品
  replay 与 195 formal records 仍 pending。未升级 lifecycle，未运行独立 native build/真实数据/生产或取得
  J1/A3/READY，provider attempts/API Key reads/reservations/cost 为 0，`BUILD_NOT_AUTHORIZED` 保持。
