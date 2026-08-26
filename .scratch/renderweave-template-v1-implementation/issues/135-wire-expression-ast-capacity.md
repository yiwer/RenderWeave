# 接线 Expression AST 两轴产品容量

Type: task
Status: automated_verified
Claimed by: Codex `/root`
Blocked by: 134（已 resolved）

## Question

T134 已把 source/input/Mapping case/Definition graph 八轴接入唯一 Template-owned
`DesignInputExpressionCapacityAuthority`，但 `expression.astNodesPerExpression` 与
`expression.astNodesTotal` 仍只存在于 scalar profile；Rendering 内部 parser 还持有一份硬编码 per-expression
阈值，并且只在 Definition 被惰性 demand 时解析。如何在保存、重检与 closure integrity admission 的真实产品路径上，
对每份 DesignDSL 的全部 Expression 派生精确 AST node 计数，同时消除重复 grammar/guard ownership、保持未使用和
静态不可达 Expression 仍计数，并在任何 AST allocation/lazy execution 前 fail closed？

## Answer（本票冻结的实施决定）

1. **最小两轴纵切**：本票只接线 `expression.astNodesPerExpression`（4,096）与
   `expression.astNodesTotal`（65,536）。decimal 七轴与 geometry 十轴继续后票；不把 partial Expression group
   冒充完成。
2. **复用已确认产品 seam**：TDD 继续从 `DesignDslAuthority.admit(rawUtf8)` 观察保存/重检共用行为；
   `CanonicalDesignDslAuthority` 的同一 request-local `ExpressionDefinitionCapacityBudget` 负责 per-expression/全 DSL
   状态。该 seam 已由 canonical kernel、Template save 与 closure integrity re-admission 冻结，不新增第二条 admission
   Interface。
3. **一个 Template-owned parser**：把既有 exact `renderweave-expression/1.0` grammar parser 与 immutable AST 派生值
   收进 Template-owned `DesignSemanticAuthority` deep module；admission 与 Rendering 消费同一实现。Rendering 不再保留
   第二份 parser、第二份 AST 或硬编码 AST limit，`Interpreted` 一次交付 canonical semantic tree 与按 definitionId
   解析的 AST map。
4. **逐 node 原子 reserve**：parser 在构造每个 AST node 前先形成 per-expression candidate 与全 DSL candidate，依次
   调用唯一 capacity authority；两者均 Accepted 后才提交 request-local total 并分配 node。checked-long overflow、
   authority Invalid/throw 与 reject 均在 exact `/definitions/<index>/source` fail closed。
5. **完整 authored 计数**：admission 按 definitions authored order 解析每个 ExpressionDefinition；未消费 Definition、
   静态不可达 branch 与惰性不命中的 Expression 仍计数。语法非法继续是不可确认 hard error；AST capacity rejection
   沿既有 closed DesignDSL envelope 返回 `DESIGN_DSL_LIMIT_EXCEEDED / DESIGN_SEMANTIC_VALIDATION` 与 exact Limit，
   不 canonicalize、不读取依赖、不写 Template。
6. **TDD 与兼容**：先用 recording/rejecting authority 在真实 `admit` seam 建立 per-expression、total、构造前停止、
   未使用 Expression 和 authority fail-closed RED；随后最小实现，重放 Expression engine 与 211 canonical vectors，
   证明 grammar/evaluation 与合法 canonical bytes/contentHash 不漂移。
7. **版本化 target 与门控**：实现提交后保留 v1–v5 不可变并冻结 v6，只声明 wired 48/65、remaining 17
   （decimal 7、geometry 10）。component → `template` → `fast` → sequential `server` → Goal `full` → resolution
   `fast`；两个 required executor manifests、独立产品 replay 与 195 formal records继续 pending，
   `BUILD_NOT_AUTHORIZED`、provider/API Key/真实数据/生产/J1/A3/READY 边界不变。

## Results

- 产品实现 revision `91da8d56be811139835a845f5d5313682cf4a6d9` 已把 exact Expression grammar parser 与
  immutable `ExpressionAst` 收进 Template-owned `DesignSemanticAuthority`；`CanonicalDesignDslAuthority.admit`
  现在按 authored order 解析全部 Expression，并在每个 node allocation 前对 per-expression 4,096 与全 DSL 65,536
  两轴逐项 reserve。Rendering-owned 重复 parser/AST 与硬编码阈值已删除，Rendering 直接消费同一 semantic authority
  交付的 definitionId→AST map。
- TDD recording/rejecting seam 先得到预期 RED，随后 focused public-surface/reservation 17/17、Expression engine 28/28、
  Template 114/114、Rendering 131/131 与 canonical Java/Python 211/211 全绿。未使用 Definition、静态不可达 branch、
  per-vs-total first error、node allocation 前停止、authority invalid/throw、checked overflow 与 exact source pointer
  均由真实 admission seam 证明；合法 canonical bytes/contentHash 未漂移。
- target revision `93ba7a30dfd1000dafc6a600a30f816d2c27a0d0` 冻结不可变 v6（29,157 bytes，SHA-256
  `5927679899d4c0b2c12159a7c61734b7cd6882190aa2812d00ef1dd21c8d878c`），v1–v5 bytes 保持不变。
  component `.sdlc/evidence/20260826-225802-design-input-expression-capacity/` 为 Java/TypeScript 195/195
  （accepted 125 / rejected 70）、2,684 independent checks、53 个 bound artifacts 零 mismatch，wired 48/65、
  remaining 17；formal registry 保持 58/58，recordsIssued=0，preissuance/issuance/executable 均 false。
- 分级门控 `template` `.sdlc/evidence/20260826-225949-template/`、`fast`
  `.sdlc/evidence/20260826-230044-fast/`、顺序 `server` `.sdlc/evidence/20260826-230121-server/` 全绿。
  发布级 `full` `.sdlc/evidence/20260826-231827-full/` 为 17/17 steps、1,575.495 秒；clean Maven reactor
  Schema 20、Validation 13、Inference 439（9 skip）、Template 114、Asset 97、Rendering 131、Application 447
  （21 skip）均零失败，Node 24 Web 32 files / 251 tests、typecheck/lint/build、runtime canary、R0/R1/P0、
  Chromium 25 passed + 1 controlled skip、Draft 与 inference replay E2E 全绿。
- Resolution：收口文件写入后的 `fast` `.sdlc/evidence/20260826-234734-fast/` 通过（8/8 package reactor +
  Web typecheck）。
- Boundary：decimal 7、geometry 10、两个 required executor manifests、独立产品 replay 与 195 formal records 仍
  pending；未升级 lifecycle，未运行独立 native build/真实数据/生产或取得 J1/A3/READY。provider attempts、API Key
  reads、reservations 与 cost 均为 0，`BUILD_NOT_AUTHORIZED` 保持；用户 360 项 dirty work 与备份 stash 不在本票写入范围。
