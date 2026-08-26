# 接线 bounded problem collection 五轴产品容量

Type: task
Status: automated_verified
Claimed by: Codex `/root`
Blocked by: 132（已 resolved）

## Question

T130–T132 已建立 Template-owned `DesignInputExpressionCapacityAuthority` 并把 DesignDSL parser/semantics 与
RenderInput 共 33 轴接入真实产品 reservation point；`problems.*` 五轴仍由 `TemplateProblemBudget` 常量和事后裁剪
决定，依赖 evaluator 与 semantic validator 也各自维护停止条件。如何让完整 Template 依赖问题路径共享同一 authority
与同一 request-local collector，同时严格保持自然 200 项、201 项回退为 199 ordinary + 1 marker、1 KiB marker
预留、canonical item/total bytes、稳定 fingerprint、originating-stage problem 和达到边界后零昂贵下游检查？

## Answer（本票冻结的实施决定）

1. **一个 Template-owned 深收集器**：深化现有 `TemplateProblemBudget` 为 request-local bounded collector；它独占
   ordinary prefix、canonical byte accounting、排序、marker 与 fingerprint。调用方只提交一个 canonical problem 并读取
   `accepted/stopped/report`，不再复制 item/byte 比较器或自行事后裁剪。
2. **五个真实 observation**：collector 在首次使用前验证 exact 1 KiB marker reservation；每个 ordinary append 前按
   canonical bytes 先检查 `canonicalBytesPerItem`、含 marker reserve 的 `canonicalBytesTotal`，再检查 candidate
   `itemsIncludingLimitMarker`。item overflow 时撤回已保留的第 200 个 ordinary、验证
   `ordinaryItemsWhenTruncated=199`，只追加一个 `PROBLEM_LIMIT_REACHED(ITEMS)`；byte first-error 使用 `BYTES`。
3. **共享 request-local 状态**：asset、Template closure 与 semantic dependency validation 共用同一 collector。semantic
   validator 直接写入该 collector，达到 terminal 后立即停止递归/解析/依赖读取；不把已省略 problem、完整 DSL、文本或
   dependency 内容复制到 report、fingerprint、日志或证据。
4. **自然结束与兼容**：自然结束且不超过 200 项时保留全部 ordinary problems；输出继续按 canonical pointer/code 稳定
   排序，marker 永远最后，既有 public `ValidationReport`、problem category/severity/messageArgs 与 confirmability 语义不变。
   authority `Rejected` 与 `Invalid` 均 fail closed，不允许继续下游工作。
5. **单一装配链与 TDD seam**：`TemplateDependencyEvaluator` 增加基于既有 public
   `DesignInputExpressionCapacityAuthority` 的 package-private 注入 seam；正式 `TemplateModule` 的 DesignDSL 与 problem
   collector 使用同一 canonical authority。测试只用 recording/rejecting adapter 经真实 evaluator/semantic product entrance
   证明 observation、first-error、resolution count、final report 和 fingerprint，不创建 test-only interface。
6. **版本化 target**：实现提交后保留 v1/v2/v3 不可变并冻结 v4，绑定新增/变更 source、test 与 assembly hashes；只声明
   wired 38/65、remaining 27。expression 17、geometry 10、两个 required executor manifests、独立产品 replay 与 195 条
   formal issuance 继续 pending。
7. **门控与边界**：逐纵切 RED→GREEN → component replay → `template` → `fast` → sequential `server` → Goal `full` →
   resolution `fast`；Maven 串行、精确 staging。formal registry 保持 58/58；不触碰用户 360 项 dirty work、stash、独立
   native build、provider/API Key/真实数据/生产/J1/A3/READY，`BUILD_NOT_AUTHORIZED` 保持。

## Results

- 产品实现 revision `f629cc48bb07c2c8c45083267eb6740924989256` 已把 `TemplateProblemBudget` 深化为
  request-local incremental collector，并让 asset、Template closure 与 semantic validator 共享同一实例；达到 item、
  per-item bytes、total bytes、marker reserve 或 authority invalid terminal 后均立即停止下游工作。
- TDD 先后捕获缺失 authority 注入、per-item reject 后仍解析下一依赖、semantic validator 未共享 collector 三个 RED；
  新增 9 项纵切 proof 后 Template 100/100 全绿。自然 200 ordinary、第 201 项回退为 199 + marker、byte-first、
  marker-last、稳定 fingerprint 与 fail-closed 语义均由真实产品入口证明。
- target revision `400959d368c9c6f999bc0a474685759d324b072b` 冻结不可变 v4（23987 bytes，SHA-256
  `37ef6e8551c647ad644849172c755621b5a2eaeb06d96170c5469ccdc115fd3c`）；v1/v2/v3 hashes 保持不变。
  component `.sdlc/evidence/20260826-192843-design-input-expression-capacity/` 为 Java/TypeScript 195/195、
  independent 2662 checks、43 个 bound artifacts 零 mismatch，wired 38/65、remaining 27（expression 17、
  geometry 10），class preissuance/issuance/executable 均 false，formal registry 保持 58/58。
- 分级门控 `template` `.sdlc/evidence/20260826-193017-template/`、`fast`
  `.sdlc/evidence/20260826-193109-fast/`、顺序 `server` `.sdlc/evidence/20260826-193208-server/` 与发布级
  `full` `.sdlc/evidence/20260826-194618-full/` 全绿；`full` 为 17/17 steps、1188.665 秒，Node 24 Web
  32 files / 251 tests、Chromium 25 passed + 1 controlled skip、inference replay 1/1。
- tracker 收口后的 resolution `fast` `.sdlc/evidence/20260826-200855-fast/` 通过；正式 `/templates`、
  `/templates/new` 与模板 API 随后均返回 200，独立 smoke Web/API 容器保持运行。
- 未签发 class manifests/195 formal records，未升级 lifecycle；Renderer/Profile、独立 native build、provider/API Key/
  真实数据/生产/J1/A3/READY 均未推进，provider attempts/API Key reads/reservations/cost 为 0，
  `BUILD_NOT_AUTHORIZED` 保持。
