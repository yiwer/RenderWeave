# 接线 RenderInput 九轴产品容量

Type: task
Status: automated_verified
Claimed by: Codex `/root`
Blocked by: 131（已 resolved）

## Question

T130/T131 已由 Template-owned `DesignInputExpressionCapacityAuthority` 解释 65 个容量轴，并把 DesignDSL parser、
canonical 与 semantics 共 24 轴接入真实产品路径；`renderInput` 九轴仍由 Rendering 内部常量与 HTTP 特判重复决定。
如何让 HTTP identity encoding、严格 JSON admission 与 custom-value collection 在其真实 reservation point 共享同一
authority，同时保持 strict UTF-8/JSON、first-error、零 evaluation/document/output、现有模块依赖方向和单一深 seam？

## Answer（本票冻结的实施决定）

1. **一个 Rendering-owned reservation adapter**：Rendering 只派生 exact `limitId + observedValue`，并调用 Template
   authority；不复制九轴阈值、比较器或 terminal。authority 的 rejected terminal 映射为现有 public Rendering problem，
   `Invalid` 一律 fail closed 为 `RENDER_INTERNAL_ERROR`。
2. **parser 仍是单一 strict kernel**：`RenderJsonParser` 使用可注入 capacity budget。DesignDSL contract 等内部消费者继续
   使用固定 budget；RenderInput 使用 authority-backed budget，在分配集合/继续递归/构造 number token 前检查 candidate
   observation，保持 request-local、first-error 与 bounded allocation。
3. **九个真实 reservation point**：HTTP header 派生 `renderInput.contentEncoding`；body、JSON depth、object member、array
   item、total value/container、decoded string UTF-8、number token 与 custom-value entry 派生其余八轴。HTTP token 按协议
   case-insensitive 归一化为 lowercase 后交给 exact enum authority；多 header fail closed 为非 identity observation。
4. **唯一装配链**：Template Spring configuration 暴露 canonical authority bean；App controller 与 Rendering evaluator 注入
   同一 bean；`RenderingModule`、`CanonicalEvaluator`、`InputAdmission`、`RenderInputEnvelope` 显式传递，禁止 hidden fallback
   或另一套 product constant。
5. **TDD 与兼容**：先用 recording/rejecting authority 证明九轴 observation 与 exact rejection locator 的 RED，再实现最小
   GREEN；既有 strict JSON、typed admission、controller、architecture 与 evaluator regression 必须全绿。
6. **版本化 component target**：保留 v1/v2 不可变；实现提交后冻结 v3，绑定全部新增/变更 source、test、assembly 与 tool
   hashes。报告只推进 wired 33/65、remaining 32；problems 5、expression 17、geometry 10、两个 required executor
   manifests、独立产品 replay 与 195 条 formal issuance 继续 pending。
7. **门控与边界**：focused Rendering/App → component → `template` → `fast` → 顺序 `server` → Goal `full` → resolution
   `fast`；Maven 串行、精确 staging。formal registry 保持 58/58；不触碰用户 360 项 dirty work、stash、独立 native
   build、provider/API Key/真实数据/生产/J1/A3/READY，`BUILD_NOT_AUTHORIZED` 保持。

## Results

- 实现 revision `9d316fc419314e017812ca08b4548584723a75b2` 已把九个 RenderInput reservation point 接到
  `DesignInputExpressionCapacityAuthority`：HTTP `contentEncoding`、strict JSON 的七项结构/标量计数、body UTF-8 与
  `customValueEntries` 均由共享 authority 决策；authority `Invalid` 或运行时异常 fail closed，结构/语法错误与既有
  transport physical boundary 保持原语义。新增 12 项 reservation proof，Rendering 131 tests 与 App focused 19 tests 全绿。
- target revision `4d4d091adcd39dc9a3280d12024858b54cfae24d` 冻结 v3（13923 bytes，SHA-256
  `7f582b7b3efa340b87a2e5e36f3d944d861d362a20f87cca0c5eb6f156aa6d92`）；v2/v1 bytes 与 SHA-256 保持不可变。
  component `.sdlc/evidence/20260826-175619-design-input-expression-capacity/` 为 Java/TypeScript 195/195、2646
  independent checks，wired 33/65、remaining 32；formal registry 保持 58/58，class preissuance/issuance/executable 均 false。
- 受影响门控 `template` `.sdlc/evidence/20260826-175817-template/`、`fast`
  `.sdlc/evidence/20260826-175914-fast/`、顺序 `server` `.sdlc/evidence/20260826-180004-server/` 全绿；server 为
  Schema 20、Validation 13、Inference 439（9 controlled skips）、Template 91、Asset 97、Rendering 131、App 447
  （21 controlled skips），零 failure/error。
- 发布级 `full` `.sdlc/evidence/20260826-181806-full/` 在 target revision 上 17/17 steps、1744.369 秒全绿；覆盖
  完整 Maven reactor、Node 24 Web 32 files / 251 tests、typecheck/lint/build、runtime/R0/R1/P0 与正式 Template 产品
  浏览器旅程（25 passed + 1 controlled skip；另有 inference replay 1/1）。provider attempts/API Key reads/reservations/cost 均为 0。
- tracker 收口后的 resolution `fast` `.sdlc/evidence/20260826-184921-fast/` 通过（8/8 package reactor + Node 24
  typecheck），作为最终提交前局部复核。
- 用户 360 项 dirty work 指纹 `4bddd1c955a4b4f55d984f3febd551742dd6f63c` 与备份 stash
  `f3c29199ec510ec3f809b3f8263f5d2806cb0740` 保持不变；未接线 problems/expression/geometry 32 轴，未签发两个
  required executor manifests/195 formal records，未升级 executable，未运行独立 native build/provider/真实数据/生产或
  取得 J1/A3/READY，`BUILD_NOT_AUTHORIZED` 保持。
