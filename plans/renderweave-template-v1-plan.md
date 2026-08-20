# RenderWeave Template v1 Implementation Plan

- 状态：`in_progress`；TV1-T01/T02/T03/T04/T05/T06/T07/T08/T09/T10/T10b/T11/T12a/T12b/T13/T14/T14b/T15/T16/T17/T18/T19/T20/T22=`automated_verified`
  （T09 另含人工 J1），TV1-T21=`automated_verified`（首个 Rendering 纵切——renderweave-rendering
  首个 artifact、TemplateClosureAuthority/Evaluator stage 1–8/seal、CapabilityState 加密落盘、
  RenderNodeContract 向量语料 Java primary、端到端 assembly 证明）；TV1-T13 已完成 AssetResolver、加密
  selection recovery record、Renderer-only signed fetch lease、内部 exact-byte endpoint 与 Rendering bridge。
  TV1-T22 已完成首个 Rust daemon/process protocol/manifest/replay 与 `render` gate 纵切；Profile 保持
  NOT_REGISTERED，raster 仍 ABSENT，物理 Linux/J1/A3 不在本票。TV1-T23=`resolved/automated_verified`：exact
  RenderDocument/default/lowering 与 Java/Rust/Python validator 已完成；TV1-T24=`resolved/automated_verified`：exact
  surface preflight + PNG encoder kernel 已完成，daemon 仍未接线、Profile NOT_REGISTERED、raster ABSENT；
  TV1-T25=`resolved/automated_verified`：Layout Profile 静态可判定预检与 daemon defensive admission 已完成；
  TV1-T26=`resolved/automated_verified`：资源无关 definite ABSOLUTE binary64 local-box kernel 已完成，不接
  daemon output，未产生 world scene 或注册 Profile
- 日期：2026-08-20
- Approved delta：[`specs/changes/20260817-template-v1-implementation-authority.md`](../specs/changes/20260817-template-v1-implementation-authority.md)
- Frozen checkpoint：`0b485f4a13de9d754a81d07f464730776e13c14b`
- Code base anchor：`main@7848c821aa9b809dd8cadb2b5e28f40f6947a90e`
- Tracker：`.scratch/renderweave-template-v1-implementation/map.md`
- Branch/worktree：`feature/template-v1` / adjacent isolated worktree；worktree-local `core.autocrlf=false`
- Lifecycle：整个 Template v1 仍为 `planned/in_progress`；Editor、Renderer 与 release 均未 READY

## 1. 四维执行配置

```text
规模：project
自主：copilot
风险：standard（外部授权、生产、真实数据、Renderer 认证等局部 guarded）
协作：single-writer
```

该 effort 跨 Java/Web/Rust、合同、数据库与浏览器验收，需要可恢复的长期 DAG。当前没有原子 claim、CI 分支
保护或 A3，因此不启用并发写入；Wayfinder 子票的 `Status`/`Claimed by` 是调度事实源。确定性且可逆的票据可在
已批准语义内连续推进；产品语义必变、新外部授权、真实数据、付费、生产或难恢复动作仍由人决定。

## 2. Harness 能力与保证上限

```yaml
capabilities:
  evidence_capture: tool
  atomic_claim: none
  blocking_permission: human
  independent_verify: limited
  isolated_workspace: available
binding: generic + project-local tools/run-gate.ps1 + Wayfinder markdown tracker
```

| 能力 | 当前事实 | 调度影响 |
|---|---|---|
| evidence capture | gate 捕获 revision、diff hash、输入 manifest、原始输出与 exit | 普通结果上限 A1 |
| independent verify | Editor/SPEC_REGISTRY 与部分既有视觉证据有独立 Python replay | 只在 exact 输入范围内记 A2，不外推产品 |
| isolated workspace | Template 使用相邻 worktree，dirty main 不参与写入 | 允许 `feature/template-v1` 单 writer |
| atomic claim | 无；Markdown claim 非原子 | 同时只 claim 一个 frontier ticket |
| external hard gate | 无 CI/branch protection | 不存在 A3；release 另需外部门控 |

## 3. Phase、增量与退出门

| Phase | Tickets | 可观察增量 | 退出门 |
|---|---|---|---|
| TV1-P0 Authority | 01 | 隔离分支、additive spec、LF authority、离线 static gate 与恢复边界 | G-TV1-AUTHORITY：`template` + `fast` + Git/product-surface audit；A1，registry 范围 A2 |
| TV1-P1 Kernel | 02 → 03 | deep interface/依赖 ADR 后，最小 DesignDSL canonical kernel 和独立 vectors | G-TV1-KERNEL：focused TDD + Java primary/Python replay + `template`；不得登记 partial Profile available |
| TV1-P2 Aggregates | 04、05、06 | Template/Asset interface 决策；随后仅实现 Template create/read/save PostgreSQL 纵切 | G-TV1-TEMPLATE：server/Testcontainers/OpenAPI + contract/full；无占位 Asset 产品 |
| TV1-P2b Asset slices | 10 → 11 → 12a → 12b、13 | Asset acceptance kernel、S3/PostgreSQL 持久化纵切、replace/delete/restore 与 Resolver/lease 纵切 | 逐票 gate：T10 kernel replay；T11 Testcontainers PostgreSQL+MinIO/OpenAPI；12b 依赖 Template 投影票；13 随 P3；gate 组成由各票冻结，不预建 |
| TV1-P2c DesignDSL full-Profile atoms | 14 → 15/16 → 17/18 → 19 → 20 | NodeContract/Property Identity、Definition、Binding、Repeat、Conditional、TemplateUse 的静态 admission/canonical 原子与 Template 依赖投影 | 逐票 exact vectors + template gate 扩展；全部语义原子通过前 Profile 不登记 available |
| TV1-P3 Rendering seam | 07 → 08 | Evaluator/RenderDocument ownership 与独立 Rust process/certification protocol | G-TV1-RENDER-SEAM：closed protocol vectors、failure boundaries、supply-chain plan；不等于 Renderer READY |
| TV1-P4 Editor validation | 09 | 基于真实 Template/Rendering seam 的 throwaway Product Editor 状态原型结论 | G-TV1-EDITOR-ARCH：自动观察 A1/A2 + 人工 J1；不开放产品 route |
| TV1-P5a Renderer process | 22 | Rust UDS daemon、Java process Adapter、machine manifest、跨语言 replay 与 `render` gate | 协议纵切 A1/A2；Profile NOT_REGISTERED；不等于 raster/物理 Linux certification |
| TV1-P5b Document handoff | 23 | exact RenderDocument/default/lowering + Java/Rust/Python validator | 文档合同 A1/A2；Profile 仍 NOT_REGISTERED；无 layout/raster |
| TV1-P5c Exact PNG kernel | 24 | Canvas/bleed/DPI surface preflight + byte-exact PNG encoder | Rust/Python exact-vector A1/A2；不接线 daemon、不注册 Profile、raster ABSENT |
| TV1-P5d Layout static preflight | 25 | exact RenderDocument 上的 size/cycle/Stack/Grid/Text/QR 静态约束 deep kernel | Rust/Python exact-vector A1/A2 + daemon defensive admission；无 measure/arrange/Profile/RESULT |
| TV1-P5e Definite local boxes | 26 | Canvas→Frame/无资源叶子的 FIXED/FILL + ABSOLUTE binary64 local LayoutBox/ContentBox | Rust/Python exact-bit A1/A2；unsupported 不外泄；无 HUG/Stack/Grid/resource/world scene/RESULT |
| TV1-P5+ Product completion | 26 后按真实依赖另切票 | 完整 Renderer layout/resource/raster/JPEG/Engine output、公开产品面、Product Editor 与 formal registry records | 逐纵切 gate + product target/executor + J1/A3/物理 Linux 认证；不预建未知下游票 |

## 4. 当前 ticket DAG

```mermaid
flowchart LR
  T01[01 Authority] --> T02[02 Module interfaces]
  T01 --> T05[05 Asset interface]
  T02 --> T03[03 Canonical kernel]
  T02 --> T04[04 Template aggregate]
  T02 --> T05
  T03 --> T04
  T03 --> T06[06 Template CRUD]
  T04 --> T06
  T03 --> T07[07 Evaluator seam]
  T04 --> T07
  T05 --> T07
  T02 --> T08[08 Rust protocol]
  T07 --> T08
  T06 --> T09[09 Editor architecture]
  T07 --> T09
  T08 --> T09
  T05 --> T10[10 Asset kernel]
  T05 --> T11[11 Asset persistence]
  T10 --> T10b[10b canonical sRGB ICC]
  T10 --> T11
  T10b --> T11
  T11 --> T12a[12a replace/restore]
  T11 --> T12b[12b delete/restore]
  T11 --> T13[13 Resolver/lease]
  T07 --> T13
  T08 --> T13
  T03 --> T14[14 NodeContract/Property Identity]
  T14 --> T15[15 Definition/ValueSource]
  T14 --> T16[16 Binding/Policy]
  T14 --> T17[17 Repeat]
  T14 --> T18[18 Conditional]
  T15 --> T17
  T15 --> T18
  T14 --> T19[19 TemplateUse]
  T15 --> T19
  T16 --> T19
  T14 --> T20[20 依赖投影]
  T19 --> T20
  T20 --> T12b
  T07 --> T21[21 Rendering seam 物化]
  T08 --> T21
  T20 --> T21
  T21 --> T13
  T08 --> T22[22 Renderer process vertical]
  T13 --> T22
  T21 --> T22
  T13 --> T23[23 RenderDocument contract/defaults]
  T21 --> T23
  T22 --> T23
  T22 --> T24[24 Surface + exact PNG kernel]
  T23 --> T24
  T22 --> T25[25 Layout static preflight]
  T23 --> T25
  T23 --> T26[26 Definite ABSOLUTE boxes]
  T25 --> T26
```

| Ticket | 类型 | 状态 | Blocked by | 本票退出事实 |
|---|---|---|---|---|
| 01 | task | `resolved` / `automated_verified` | none | 实施权威与反馈闭环；无产品代码 |
| 02 | grilling | `resolved` / `automated_verified` | 01 | ADR-0041、零 split package 与非空转 architecture test |
| 03 | task | `resolved` / `automated_verified` | 01, 02 | 最小 canonical kernel；TDD + independent replay |
| 04 | grilling | `resolved` / `automated_verified` | 02, 03 | ADR-0042 与 Template aggregate/persistence contract；无 migration |
| 05 | grilling | `resolved` / `automated_verified` | 01, 02 | ADR-0043 Asset admission/resolution deep interface 与 S3 Blob seam；无 Java/migration/route |
| 06 | task | `resolved` / `automated_verified` | 03, 04 | Template create/read/save PostgreSQL 纵切；V018 + OpenAPI 0.10.0 + Web SDK + `full` 15/15 |
| 07 | grilling | `resolved` / `automated_verified` | 03, 04, 05 | ADR-0044：closure authority/Evaluator/RenderOutput/CapabilityStateStore/RenderEngine port/语料纪律与 T08 边界 |
| 08 | grilling | `resolved` / `automated_verified` | 02, 07 | ADR-0045：常驻 daemon/UDS 帧协议/registry/fetch/cancel/构建与四级认证计划 |
| 09 | prototype | `resolved` / `automated_verified`（J1） | 06, 07, 08 | throwaway 状态机原型 10 场景 37/37 断言 + 人工 J1；Verdict 与 E1–E9 纵切分解 |
| 10 | task | `resolved` / `automated_verified` | 05 | Asset acceptance kernel；38 vectors Java/Python 38/38（A2）；`asset` gate 入 full |
| 10b | task | `resolved` / `automated_verified` | 10 | canonical sRGB ICC 字节等值接受原子；41 vectors Java/Python 41/41 |
| 11 | task | `resolved` / `automated_verified` | 05, 10, 10b | Asset create/current/catalog PostgreSQL+S3 纵切；V019 + OpenAPI 0.11.0/Web SDK + MinIO + `full` 15/15 |
| 12a | task | `resolved` / `automated_verified` | 05, 11 | content replace/旧内容恢复；V020 审计事件 + OpenAPI 0.12.0 + `full` 16/16 |
| 12b | task | `resolved` / `automated_verified` | 05, 11, 20 | delete/restore + AssetReferencePort/确认 token 编排 |
| 13 | task | `resolved` / `automated_verified` | 05, 07, 08, 11, 21 | AssetResolver + 加密 selection recovery + Renderer-only signed fetch lease + Rendering bridge；V024 |
| 14 | task | `resolved` | 03 | NodeContractCatalog 与 Node/Property Identity 原子（容器增量：canvas/group/frame/stack/grid 递归 admission/canonical，manifest v2 57 vectors） |
| 14b | task | `resolved` | 03, 14 | visual leaf Node kinds（text/image/rect/ellipse/line/polygon/polyline/path/qrCode/barcode）与 BindingPolicyCatalog 基础登记，manifest v5 152 vectors |
| 15 | task | `resolved` | 03, 14 | Definition/ValueSource 原子（custom/mapping/expression + lexical domains，manifest v3 94 vectors） |
| 16 | task | `resolved` | 03, 14 | Binding 与 BindingPolicyCatalog 原子（manifest v6 176 vectors） |
| 17 | task | `resolved` | 03, 14, 15 | Repeat 原子（items/PACK/packing/loopId，manifest v4 116 vectors） |
| 18 | task | `resolved` | 03, 14, 15 | Conditional 原子（condition/absent policy/剪枝，manifest v8 211 vectors） |
| 19 | task | `resolved` | 03, 14, 15, 16 | TemplateUse 原子（ContextSelector/fills/closure 边，manifest v7 197 vectors） |
| 20 | task | `resolved` / `automated_verified` | 04, 05, 14, 19 | Template 依赖投影（AssetRef/反向索引/STALE 消费）；T12b 的 blocker |
| 21 | task | `resolved` / `automated_verified` | 07, 08, 20 | 首个 Rendering 纵切：TemplateClosureAuthority/Evaluator stage 1–8/seal + RenderNodeContractCatalog 与向量语料（Java primary）；V023 + app Adapter；无公开 route |
| 22 | task | `resolved` / `automated_verified` | 08, 13, 21 | Rust UDS daemon + strict frame/manifest/registry + Java process Adapter + Java/Rust/Python/Linux replay；`render` gate 入 full；Profile NOT_REGISTERED |
| 23 | task | `resolved` / `automated_verified` | 13, 21, 22 | exact RenderDocument catalog/default/lowering + Java/Rust/Python validator；无 layout/raster/Profile registration |
| 24 | task | `resolved` / `automated_verified` | 22, 23 | exact surface preflight + PNG encoder kernel；daemon 未接线、Profile NOT_REGISTERED、raster ABSENT |
| 25 | task | `resolved` / `automated_verified` | 22, 23 | static layout constraints deep kernel + daemon defensive admission；无 measure/arrange/Profile/RESULT |
| 26 | task | `resolved` / `automated_verified` | 23, 25 | resource-free definite ABSOLUTE binary64 local boxes；无 daemon success/Profile |

每次只 claim 一个 unblocked ticket；一票 resolved 后才由其 `Blocked by` 关系产生下一 frontier。未知实现切片留在
map 的 `Not yet specified`，不为排满计划提前发明接口、migration 或 Profile identity。

TV1-T07/T08/T09 已 resolve（ADR-0044/0045 与 Editor 状态原型，T09 含人工 J1）；TV1-T14 容器增量已
resolve（NodeContractCatalog + 递归容器 admission/canonical，manifest v2 57 vectors）；TV1-T14b visual
leaf kinds 与 BindingPolicyCatalog 基础登记已 resolve（manifest v5 152 vectors）；TV1-T15
Definition/ValueSource 原子已 resolve（manifest v3 94 vectors）；TV1-T17 Repeat 原子已 resolve（PACK
placement + loopId namespace + RepeatPackingSpec，manifest v4 116 vectors）；TV1-T16 Binding 与
BindingPolicyCatalog 消费已 resolve（manifest v6 176 vectors）；TV1-T19 TemplateUse 原子已 resolve
（manifest v7 197 vectors）；TV1-T18 Conditional 原子已 resolve（manifest v8 211 vectors——v1 全部
kind 已 admission，Java/Python 211/211，Profile 仍 NOT_REGISTERED）；TV1-T20（Template 依赖投影，
T12b 的 blocker）已 resolve（AssetRef/TemplateUse 原子提取 + current-only 投影物化 + 反向 proof +
STALE 消费 + readiness recheck，template gate 含 A2 提取重放，V021）；TV1-T12b（被引用 Asset 删除
确认与恢复编排）已 resolve（AssetReferencePort 桥接 + 5 分钟单次确认 token + 独占 reservation 零写
重验 + 软删除/恢复 + assetId 排序读 reservation，V022 + OpenAPI 0.13.0 + Web SDK）；首个 Rendering
实现票 TV1-T21 已 resolve（renderweave-rendering 首个 artifact：TemplateClosureAuthority/
Evaluator stage 1–8/seal + RenderNodeContract 向量语料 Java primary + CapabilityState 加密落盘 +
端到端 assembly 证明）；TV1-T13（AssetResolver/Renderer-only lease 纵切）已 resolve（单事务 selection
幂等 + AES-GCM recovery record + signed internal exact-byte fetch + Rendering bridge，V024）；TV1-T22 已
resolve（offline Cargo workspace + Linux UDS daemon + strict frame/manifest/registry + Java process Adapter/
Supervisor + Java/Rust/Python/Linux replay，`render` 已入 17-step `full`）。TV1-T23 已 resolve：同一 catalog
  驱动 Java default/lowering，Rust daemon 在 Profile lookup 前独立验证 exact document，Python 再以标准库重放
  共同语料，Rust Engine 不再需要猜测 DesignDSL default。TV1-T24 也已 resolve（exact surface/PNG kernel）；
  T25 static layout preflight 与 T26 definite ABSOLUTE local-box kernel 现均已 resolve；仍未预建公开 render
  route、tolerance-dependent HUG/Stack/Grid、resource/raster/JPEG/daemon output Profile 或
  Editor 产品 route，Editor E1–E9 仍按各自前置拆票。

## 5. TV1-T01 执行卡

- AC：AC-TV1-001..006。
- 允许影响：specs、governance、plans、tracker、frozen registry LF repair、gate scripts、evidence。
- 禁止影响：Java/Web/Rust 产品源码、OpenAPI、migration、产品 route/page/table、Profile available registration。
- 局部验证：PowerShell parse、frozen file hash、registry path/hash walker、product-surface inventory。
- 受影响验证：`tools/run-gate.ps1 -Gate template`、`-Gate fast`、`git diff --check`。
- 本票不运行：browser/E2E/runtime/Web service/J1/provider；`full` 已静态包含 template step，但不以本票越权启动。
- 保证等级：gate A1；SPEC_REGISTRY independent replay 对其 strict inputs 为 A2；无 A3/J1。
- 完成信号：Ticket 01 resolved、map 仅登记其上下文指针、Ticket 02 成为唯一 unblocked frontier、形成一个
  `agent-commit`，worktree clean；不 push/tag/PR。

## 6. TV1-T02 执行卡

- 决策：ADR-0041；provider-owned public contracts、consumer-owned reverse/Host/process Seams、单向 staged
  Maven graph、context-local closed outcomes 与 `api/spi/internal` ownership。
- 允许影响：ADR、CONTEXT/tracker/plan/log、architecture tests、app Adapter package 迁移及保证冻结 legacy
  resource checkout bytes 的 `.gitattributes`。
- 禁止影响：新 Template/Asset/Rendering artifact 或占位 Interface、DesignDSL/product API、migration、Web route、
  Rust wire、Profile registration、产品语义变化。
- 局部验证：Architecture 4/4、Validation PostgreSQL/API 3/3、legacy corpus identity 5/5。
- 受影响验证：完整 `server`、`template`、`fast` 与 `git diff --check`；不运行 browser/E2E/runtime/J1/provider。
- 保证等级：自动 gate A1；本票没有产品 execution-class A2、A3 或 J1。
- 完成信号：Ticket 02 resolved、ADR/CONTEXT/architecture gate 一致、零 split package、形成一个 verified
  `agent-commit` 且 worktree clean；不 push/tag/PR。

## 7. TV1-T03 执行卡

- 决策：新增首个真实 `renderweave-template` artifact；唯一 public top-level Interface 为
  `DesignDslAuthority.admit(rawUtf8)` closed outcome，Implementation 与 JSON model/canonical writer 全部 internal。
- 允许影响：root reactor、新 Template module、TDD/ArchUnit/public-surface tests、exact vector fixture、独立 Python
  replay、template/full gate composition、CONTEXT/tracker/plan/log/NOTES。
- 禁止影响：app wiring、OpenAPI、migration/table、Template/Asset aggregate、产品 route/page、Renderer/Rust、
  Profile available registration、DB/网络/浏览器/Web 服务/provider/J1。
- admission 原子：exact versions + root metadata + empty definitions + single Canvas + empty bindings/children；
  non-empty set/semantic arrays 以 `DESIGN_KERNEL_SCOPE_UNSUPPORTED` fail closed，不推断未知 wire。
- 局部验证：TDD red/green、Template module 17 tests、app architecture 4/4、Java primary/Python independent 33/33。
- 受影响验证：完整 `server`、`template`、`fast`、`git diff --check` 与 product-surface/Git audit；不运行
  browser/E2E/runtime/full/provider。
- 保证等级：Java/module/gate 为 A1；Python 对 exact manifest/Java report 的独立重放为 A2；无 A3/J1。
- 完成信号：Ticket 03 resolved/`automated_verified`、Profile 仍 `NOT_REGISTERED`、形成一个 verified
  `agent-commit` 且 implementation worktree clean；不 push/tag/PR。

## 8. TV1-T04 执行卡

- 决策：ADR-0042；一个 authoring `TemplateApplication`、专用 snapshot/reference provider Interfaces、
  Template-owned `OwnerScopeAuthority` 与 transaction-sized `TemplatePersistence` outbound seams。
- 允许影响：ADR、CONTEXT、tracker/plan/log/NOTES；只冻结 closed command/outcome、authority disclosure、
  aggregate/revision state、trusted read、confirmation 和 forward-only persistence/test invariants。
- 禁止影响：Java Interface/Implementation、POM dependency、OpenAPI、migration/table、route/page、Asset/
  Rendering product seam、Profile registration、DB/网络/浏览器/Provider/J1。
- T06 首个真实 surface 限于 create/current-read/save；copy/restore/delete/history/export/confirmation/snapshot/
  reference proof 只能与真实 consumer/behavior 同票进入，不能用 unsupported placeholder 预建。
- 局部验证：authority/ADR/CONTEXT cross-check、`git diff --check`、product-surface inventory。
- 受影响验证：`template` composite 与 `fast`；DesignDSL kernel/static authority 输入未改时必须 byte-identical。
- 保证等级：文档/静态 gate A1；既有 kernel/registry exact inputs 的 independent replay 仍只在原边界为 A2；
  本票没有 Template aggregate product execution、A3 或 J1。
- 完成信号：Ticket 04 resolved/`automated_verified`、ADR/CONTEXT/plan 一致、零新增 product surface，形成一个
  verified `agent-commit` 且 worktree clean；不 push/tag/PR。

## 9. TV1-T06 执行卡

- 决策：物化 ADR-0042 已冻结且真实接线的 Template create/current-read/save 纵切；`TemplateModule` 是 app 可
  import 的唯一 Template `.internal` assembly seam（ADR-0041 窄例外）。
- 允许影响：renderweave-template api/internal/spi、renderweave-schema `StaticSchemaAuthority`、
  renderweave-app Adapter/Controller/Config、V018 migration、OpenAPI/Web SDK、contract/public-surface/
  architecture/API/persistence 测试、E2E 清理加固、tracker/plan/log/NOTES/evidence。
- 禁止影响：Editor/Asset/Evaluator/Renderer 产品 surface、placeholder 表/接口/route、DesignDSL Profile
  available 注册、Workspace/org auth、付费 provider/真实数据。
- 局部验证：Template module contract/public-surface/architecture；app API permission/媒体类型/冲突、
  persistence same-hash 追加/并发只一胜/零部分写入/terminal DELETED；Testcontainers PostgreSQL。
- 受影响验证：`template`、`server`、`web` 与完整 `full` 15/15；draft-browser-e2e 与 inference-browser-e2e
  旅程和进程清理均通过（清理已加固为 CIM 瞬断可重试且不吞旅程结果）。
- 保证等级：自动 gate A1；kernel/registry exact replay 在原边界为 A2；无 A3/J1。
- 完成信号：Ticket 06 resolved/`automated_verified`、full gate 绿、形成 verified `agent-commit` 且 worktree
  clean；push 已获用户明确授权，在收口提交后执行（不建 tag/PR）。

## 10. TV1-T05 执行卡

- 决策：ADR-0043；renderweave-asset 三个 provider-owned Interface + `AssetPersistence`/`AssetBlobPersistence`
  SPI + Asset-owned Host facet；acceptance kernel 先行（独立 Python replay）；S3 协议 Blob seam（生产 OSS、
  本地与测试 MinIO、AWS SDK v2）；事务容量计数器；`AssetFetchEndpoint` Port；T10–T13 切片顺序。
- 允许影响：ADR、tracker/plan/log/NOTES、新票 T10–T13 登记、CONTEXT 术语核对。
- 禁止影响：Java/Web/Rust 产品源码、OpenAPI、migration、route/page、gate 组成、S3/MinIO/compose、AWS SDK
  依赖、Profile available 注册。
- 局部验证：ADR/tracker/plan/NOTES 交叉一致、`git diff --check`、product-surface inventory（零新增）。
- 受影响验证：`template` composite 与 `fast`（docs-only，输入未变复用已有绿）。
- 保证等级：文档/静态 gate A1；本票没有产品 execution-class A2、A3 或 J1。
- 完成信号：Ticket 05 resolved/`automated_verified`、T10 成为唯一 unblocked frontier、形成一个 verified
  `agent-commit` 且 worktree clean；push 待用户另行授权。

## 11. TV1-T10 执行卡

- 决策：ADR-0043 的 admission kernel 物化为 renderweave-asset 首个 artifact；唯一 public Interface
  `AssetAcceptanceAuthority.admit(rawBytes, kind)` closed union；PNG/JPEG/WebP/TTF/OTF 全切片；WebP 像素解码
  用 webp-imageio 0.2.0（libwebp 绑定，无纯 Java 替代）；38 冻结 vectors + `asset` gate（Java primary +
  Pillow/fontTools 独立重放 A2）纳入 `full`。
- 允许影响：root reactor、renderweave-asset 源码/测试/vectors、tools（ensure/generate/verify/run-asset-kernel-
  gate、run-gate 组成）、CONTEXT/tracker/plan/log/NOTES/evidence。
- 禁止影响：DB、网络、S3/MinIO、Template/Asset 聚合、产品 route/OpenAPI、acceptance Profile available
  注册、T11 的 Adapter/迁移。
- 局部验证：TDD red/green、module 53 tests、public-surface/ArchUnit 锁、冻结 vectors Java primary 重放。
- 受影响验证：`asset` composite（Java=38/38 Python=38/38，A2）与 `fast`；`full` 已含 asset-kernel-replay。
- 保证等级：gate A1；exact vectors 的独立 Python 重放为 A2；无 A3/J1。
- 完成信号：Ticket 10 resolved/`automated_verified`、T10b/T11 解锁、形成一个 verified `agent-commit` 且
  worktree clean；push 待用户另行授权。T10b（canonical sRGB ICC 等值原子）已并入本 kernel（41 vectors，
  Java/Python 41/41）。

## 12. Gate 与证据策略

`template` gate 顺序固定为 repository diff → DesignDSL kernel Java primary/Python independent exact-vector replay
（T20 起追加 AssetRef/TemplateUse 依赖投影提取的 Java primary/Python independent exact-fixture replay）
→ 临时副本 Editor generator/independent/A2 → registry target refresh/Node primary/Python independent/A2 → 全树
byte comparison → frozen counts/readiness assertions。任何命令失败或相同输入生成 diff 都失败；仓库 authority
不被重写。kernel report 必须保持 211/211（vectorVersion `renderweave-template-canonical-kernel-v1/8`，
v1 全部 DesignDSL kind 已 admission——容器、visual leaves、Definition/ValueSource、Repeat/PACK、
Binding、TemplateUse 与 Conditional）、Profile=`NOT_REGISTERED`；static replay 的冻结 counts 不变。

后续票据遵守 focused → affected → Phase → Goal。新增 Maven module、root POM、OpenAPI、lockfile、migration、
process protocol 或 `full` 组成变化属于共享面，必须提前扩大回归。自动 green 只把对应任务推进到
`automated_verified`；体验/业务选择是 J0/J1，独立机器 replay 是 A2，物理 Linux/CI policy 才可能形成外部门。

## 13. Version、恢复与熔断

- 每个已验证 Template ticket 在 `feature/template-v1` 独立提交；不 rewrite/cherry-pick 到 dirty main，不 push/tag/PR。
- Ticket 01/02 无数据或外部副作用，恢复为回退对应单一提交或删除新 worktree；不得用 `reset --hard` 清理用户 worktree。
- 后续 migration 一律 forward-only，只有真实纵切接线时选择当时下一个版本，并用 PostgreSQL/Testcontainers 验证。
- provider/API key/真实数据/生产权限默认关闭。Renderer 的 hermetic build、ELF closure、portable tricky-font 与
  双物理 Linux CPU-family pixel replay未完成前持续 fail-closed。
- 触发熔断：authority replay diff、产品语义冲突、依赖循环、test-only bypass、partial Profile availability、
  migration/route placeholder、越界外部副作用或 readiness 夸大。停止受影响票，保留证据；其他安全 frontier 继续。

## 14. 当前边界

- Ticket 19 保持 open；Capacity formal records=0。
- Formal Editor Case/Oracle=0/0；47 个 EditorContentSource slots 仅 1 exact、46 UNBOUND。
- Template/Editor/Renderer 未 READY；静态 replay 不等于产品执行、浏览器观察、人工验收或外部认证。

## 15. TV1-T11 执行卡

- 决策：物化 ADR-0043 冻结的 Asset create/current/catalog 纵切；`AssetModule` 是 app 可 import 的唯一
  Asset `.internal` assembly seam（与 `TemplateModule` 同为 ADR-0041 窄例外）。
- 允许影响：renderweave-asset api/internal/spi、renderweave-app Adapter/Controller/Config、V019 migration、
  OpenAPI/Web SDK（0.11.0）、compose.yaml（minio + minio-init + api env）、multipart transport 上限与
  inference app 层权威预算、contract/public-surface/architecture/API/persistence 测试、
  tracker/plan/log/NOTES/evidence。
- 禁止影响：replace/delete/restore、AssetResolver/lease、Asset UI、placeholder 表/接口/route、
  acceptance Profile available 注册、Template/Evaluator/Renderer surface、付费 provider/真实数据。
- 局部验证：Asset module contract/public-surface/architecture；app HTTP API（multipart 幂等 create、
  catalog 过滤与稳定游标、metadata 409、versions/download/preview、problem 面、无效请求零写入）、
  persistence（幂等 replay/conflict、容量水位、零部分写入）、fail-closed 不装配；Testcontainers
  PostgreSQL+MinIO。
- 受影响验证：`asset`、`server`、`web` 与完整 `full` 16/16；draft/inference browser E2E 旅程与进程清理
  均通过。
- 保证等级：自动 gate A1；kernel/registry exact replay 在原边界为 A2；无 A3/J1。
- 完成信号：Ticket 11 resolved/`automated_verified`、T12a 成为唯一 unblocked frontier、形成一个 verified
  `agent-commit` 且 worktree clean；push 待用户另行授权。

## 16. TV1-T12a 执行卡

- 决策：物化 ADR-0043 §7 的 content replace/旧内容恢复切片；`appendContent` 事务内零部分写入并逐事务追加
  有界审计事件（`asset_audit_event` 即可靠可重放 STALE 事实流，Template 依赖投影票消费）；相同内容
  no-op 不增加 revision/事件/STALE，且 no-op 判定先于 revision 校验。
- 允许影响：renderweave-asset api/internal/spi、renderweave-app Adapter/Controller/Config、V020 migration、
  OpenAPI/Web SDK（0.12.0）、contract/public-surface/architecture/API/persistence 测试、
  tracker/plan/log/NOTES/evidence。
- 禁止影响：delete/restore、确认 token、AssetReferencePort、AssetResolver/lease、Asset UI、
  placeholder 表/接口/route、acceptance Profile available 注册、Template/Evaluator/Renderer surface、
  付费 provider/真实数据。
- 局部验证：Asset module contract（replace/restore closed outcomes、no-op、audit 事件）、app HTTP API
  （PUT content/POST restore、409/404/422/507、无效请求零写入）、persistence（事务内追加/切换/审计、
  容量只计新建 Blob、恢复复用不计数）；Testcontainers PostgreSQL+MinIO。
- 受影响验证：`asset`、`server`、`web` 与完整 `full` 16/16；draft/inference browser E2E 旅程与进程清理
  均通过。
- 保证等级：自动 gate A1；kernel/registry exact replay 在原边界为 A2；无 A3/J1。
- 完成信号：Ticket 12a resolved/`automated_verified`、形成一个 verified `agent-commit` 且 worktree clean；
  T12b 仍以 Template 依赖投影票为 blocker；push 待用户另行授权。

## 17. TV1-T07 执行卡

- 决策：ADR-0044（两轮 HITL 对答 Q1–Q12 逐项按推荐采纳）：Template-owned `TemplateClosureAuthority`
  （render 专用 `freezeClosure`；AssetRef-atom 提取依赖 DesignDSL full-Profile，不预建方法）、单一窄
  `Evaluator.evaluate` → closed outcome（input admission 在 Rendering 内部）、caller 只选 bounded output
  而 rendererProfile 服务端冻结、`RenderOutput` 携带最终图片 bytes+描述、`CapabilityStateStore` spi
  closed 三操作 + app 加密落盘、`RenderEngine.execute` 单次五态 outcome、诊断 sidecar 内部持有 + 有权限
  投影、problem 基础形态 + 九值 stage enum（容量 oracle 归 Ticket 19）、跨语言 contract/向量语料纪律
  （首 task 票落向量，T08 后 Rust independent + `render` gate）、T07/T08 边界与 READY 纪律。
- 允许影响：ADR、CONTEXT 术语核对、tracker/plan/log/NOTES。
- 禁止影响：Java/Web/Rust 产品源码、OpenAPI、migration、route/page、gate 组成、Profile available
  注册、DB/网络/浏览器/Provider/J1。
- 局部验证：ADR/tracker/plan/NOTES 交叉一致、`git diff --check`、product-surface inventory（零新增）。
- 受影响验证：`template` composite 与 `fast`（docs-only，输入未变可复用既有绿）。
- 保证等级：文档/静态 gate A1；既有 kernel/registry exact inputs 的独立 replay 仍只在原边界为 A2；
  本票没有 Rendering 产品执行、A3 或 J1。
- 完成信号：Ticket 07 resolved/`automated_verified`、T08 成为唯一 unblocked frontier、形成一个 verified
  `agent-commit` 且 worktree clean；不 push/tag/PR。

## 18. TV1-T08 执行卡

- 决策：ADR-0045（两轮 HITL 对答 Q1–Q12 逐项按推荐采纳）：常驻 Rust daemon + UDS（单连接 requestId
  多路复用）、握手（协议版本 + certified manifest + capability）+ 类型化 4 字节大端 length 前缀帧
  （COMMAND/CANCEL strict JSON、RESULT closed JSON + 分帧 raw image bytes、PROBLEM closed JSON；
  stdout/stderr 仅日志、exit code 固定）、registry 全在 daemon 内存（Java 只映射 ADR-0044 五态，崩溃=
  Unknown→原 deadline 重发→terminal）、FIFO queue/slot 全在 daemon（数值归 Ticket 19）、daemon 侧
  manifest order HTTPS fetch app origin（rustls/allowlist/复验/5xx backoff，app 只经 AssetFetchEndpoint）、
  CANCEL 帧 + 固定 cooperative checkpoint + 原子 seal 后单一 RESULT 帧、adapter 监督生命周期 + 握手
  manifest 校验 + 固定 backoff 重启、仓库内 `renderer/` cargo workspace（不进 Maven reactor）钉死
  工具链/lockfile/vendor/manifest/唯一 CPU 路径、Linux-only 认证、四级认证阶梯（仓库内 replay →
  双物理 Linux CPU-family → J1/A3 → Ticket 19 数值）、Windows/WSL/scripted 永不升级 READY、帧编码
  向量格式冻结（实际向量随首个实现票）。
- 允许影响：ADR、CONTEXT 术语核对、tracker/plan/log/NOTES。
- 禁止影响：Rust/Java/Web 产品源码、OpenAPI、migration、route/page、gate 组成、Profile available
  注册、DB/网络/浏览器/Provider/J1/物理机认证执行。
- 局部验证：ADR/tracker/plan/NOTES 交叉一致、`git diff --check`、product-surface inventory（零新增）。
- 受影响验证：`template` composite 与 `fast`（docs-only，输入未变可复用既有绿）。
- 保证等级：文档/静态 gate A1；既有 kernel/registry exact inputs 的独立 replay 仍只在原边界为 A2；
  本票没有 Rendering/Rust 产品执行、A3 或 J1。
- 完成信号：Ticket 08 resolved/`automated_verified`、Rendering 侧无 unblocked grilling、形成一个
  verified `agent-commit` 且 worktree clean；不 push/tag/PR。

## 19. TV1-T09 执行卡

- 决策：throwaway 逻辑原型（`web/src/prototype/editor-state-model/`，路由 `/prototype/editor-state-model`，
  明确非产品代码）把冻结编辑器规则编码为确定性 fixture 状态机；验证 revision-aware baseline、
  三模式、dirty guard、Local recovery、conflict/unknown reconciliation、current-only 权威预览与
  generation guard、失败撤下与可访问性流；结论复用 T17 Canvas Focus IA/视觉决定、丢弃无契约内存状态
  模型、给出 E1–E9 占位-free 实施纵切分解。
- 允许影响：`web/src/prototype/editor-state-model/`、`web/src/app/App.tsx`（仅 throwaway route）、
  `tools/editor_state_model_audit.py`、tracker/plan/log/NOTES/evidence。
- 禁止影响：产品 route/page/组件、Template/Asset/Rendering API、migration、OpenAPI、gate 组成、
  Editor 产品代码或 READY 声明。
- 局部验证：web typecheck/lint/unit tests；Playwright 驱动 10 场景 37/37 断言 + 自由操作冒烟 + 键盘
  焦点检查（A1，evidence `.sdlc/evidence/t09-prototype-observation/`）；人工 J1。
- 受影响验证：`fast` 与 `web`（web-node24：npm ci + api:generate + typecheck + lint + test + build）。
- 保证等级：浏览器自动观察 A1；叠加人工 J1；无独立 A2 重放、无 A3；不开放产品 route。
- 完成信号：Ticket 09 resolved/`automated_verified`（J1）、E1–E9 作为后续 Editor 实施票的前置分解登记、
  形成一个 verified `agent-commit` 且 worktree clean；不 push/tag/PR。

## 20. TV1-T21 执行卡

- 决策：物化 ADR-0044 冻结 seam 的首个 Rendering 纵切（`renderweave-rendering` 首个 artifact）：
  Template-owned `TemplateClosureAuthority.freezeClosure`（integrity 复核/递归闭包/逐 snapshot 权威
  重检/漂移有界重试）、单一窄 `Evaluator.evaluate` stage 1–8 first-fail 串行（REQUEST_ADMISSION →
  DOCUMENT_SEAL，含 expression 完整求值、Repeat/Conditional/TemplateUse 展开、Asset 预准入与串行
  resolve、CapabilityState Clock/Random exact 派生）、RenderNodeContractCatalog 驱动的 lowering 与
  原子 seal（occurrenceId 先序/c14n canonical/四个 domain digest/Renderer Command 构造）、
  `CapabilityStateStore` 三操作 + app AES-GCM 加密落盘（派生 nonce，V023）、`RenderEngine` 五态 port
  合同（scripted adapter）、Rendering-owned `AssetResolutionPort` consumer seam（生产 bridge 随
  T13）、problem 基础形态 + 九值 stage enum + closed limitId 结构容量、请求级诊断 sidecar；
  RenderDSL canonical/digest/Command/capability 向量语料镜像 T03/T10 manifest 格式，Java primary
  重放（A1；Rust independent 与 `render` gate 入 `full` 随 T08 实现票）。
- 允许影响：root reactor 与 app POM compile edges、renderweave-rendering 源码/测试/vectors、
  renderweave-template api/internal（closure authority + TemplateModule factory）、renderweave-app
  Adapter/Configuration/V023 migration、contract/public-surface/architecture/canary 测试、
  CONTEXT/tracker/plan/log/NOTES/evidence。
- 禁止影响：公开 render/preview/diagnostic route 与 OpenAPI/Web SDK 变更（contractVersion 保持
  0.13.0）、AssetResolver/lease/fetch endpoint 实现（T13）、Rust 工程与 process adapter（T08 实现
  票）、`render` gate 入 `full`、Profile available 注册、容量数值 oracle/registry（Ticket 19）、
  Editor 产品代码、真实 Engine 执行或 synthetic raster、付费 provider/真实数据。
- 局部验证：TDD red/green；rendering 模块 contract/public-surface/architecture；closure integrity/
  漂移重试/`TEMPLATE_CLOSURE_UNSTABLE`；input admission typed-view 与 Custom 消解；expression exact
  语义（typing/ERROR/lazy/memoize）；Repeat/Conditional/TemplateUse 展开与剪枝；capability HMAC
  exact 派生/demand/fingerprint/conflict；seal occurrenceId/c14n/digests/Command；scripted engine
  Unknown 重发；Asset port 缺席 fail-closed 与 scripted 在场全链路；Testcontainers PostgreSQL。
- 受影响验证：`template`、`asset`、`server`、`web` 与完整 `full` 16/16（canary 迁移数 22→23）。
- 保证等级：gate A1；向量 Java primary 重放（独立重放缺位，A2 随 Rust independent）；无 A3/J1。
- 完成信号：Ticket 21 resolved/`automated_verified`、T13 成为唯一 unblocked frontier、形成 verified
  commit 且 worktree clean；push 待用户另行授权。

## 21. TV1-T22 执行卡

- 决策：按 ADR-0045 首个实现票约束，一票原子物化 `renderer/` Cargo workspace、常驻 Linux UDS daemon、
  exact manifest handshake、typed frame codec、内存 request registry、Java `RenderEngine` process Adapter/
  Supervisor、Java/Rust/Python exact replay 与 Linux 容器 UDS round-trip；`render` gate 同票纳入 `full`。
- 允许影响：`renderer/**`、Rendering request/engine contract hardening、app process Adapter/配置/测试、共享
  vectors、render gate/独立 verifier、architecture/public-surface 测试与 tracker/plan/log/NOTES/evidence。
- 禁止影响：layout/shaping/decode/raster/PNG/JPEG 或 synthetic image；公开 render/preview route 与
  OpenAPI/Web SDK；Editor；Profile registration、Ticket 19 数值、双物理 Linux/J1/A3/READY、外部 provider。
- TDD：先用共同 vector manifest 让 Java/Rust/Python replay RED；再实现 codec/manifest/registry/daemon；随后
  Adapter persistent UDS/multiplex/result/problem/Unknown 与 supervisor；最后 Linux `--network none` UDS replay。
- 受影响验证：focused rendering/app → `render` → `server`/`fast` → 完整 `full`（17 steps）。
- 保证上限：tool-captured A1；三实现 exact-vector + Linux Docker UDS 输入边界 A2；Docker/Windows/WSL 不构成
  物理 Linux certification，无 A3/J1。Profile 始终 NOT_REGISTERED、certification NOT_CERTIFIED。
- 完成信号：Ticket 22 `resolved / automated_verified`、`render` 已入 full、合法 Command 在空 Profile manifest
  下稳定 terminal fail-closed、verified local commit 且 worktree clean；不 push/tag/PR。
- 结果：已按上述边界完成；分级证据与不可自指的最终 full 记录策略见 `plans/logs/TV1-T22.md`。

## 22. TV1-T23 执行卡

- 决策：以同一 machine-readable RenderNodeContract 驱动 Java default/lowering 与 Rust specialized validator，
  补齐 Ticket 15 已冻结但 T21 明确保留的 exact RenderDocument handoff；daemon path 仍无成功图片。
- 允许影响：Rendering catalog/sealer/materializer、共享 vectors、Rust document crate/daemon、render gate/独立
  verifier、tracker/plan/log/NOTES/evidence。
- 禁止影响：layout/shaping/fetch/decode/raster/PNG/JPEG、公开 route/OpenAPI/Editor、Profile registration、
  Ticket 19 records、物理 Linux/J1/A3/READY、provider/真实数据/API Key。
- TDD：共同语料 Java/Rust/Python 先 RED；随后 catalog/default materialization、完整 lowering、Rust closed
  validator 与 daemon 接线；最后 Linux `--network none` replay。
- 受影响验证：focused Rendering/app + Cargo → `render` → `server`/`fast` → 完整 `full`。
- 保证上限：文档合同工具 A1、三实现 exact replay + Linux daemon path A2；无 A3/J1，Profile 始终
  NOT_REGISTERED、certification NOT_CERTIFIED、raster ABSENT。
- 完成信号：Ticket 23 `resolved / automated_verified`、document 无 default omission/authored residue、validator
  接入真实 daemon path、verified local commit 且 worktree clean；不 push/tag/PR。
- 结果：已按上述边界完成；catalog/default/lowering、三实现共同语料、daemon admission、分级 gate 与 exact
  identity 见 `plans/logs/TV1-T23.md`。最终 full 目录按不可自指策略只在提交交接中报告。

## 23. TV1-T24 执行卡

- 决策：新增 workspace-internal Rust output-PNG deep kernel，以 checked decimal6/i128 对 T23 Canvas trim、
  bleed 与 effective DPI 做整体 `ROUND_HALF_UP` surface preflight；随后只对已完成 raster/unpremultiply 的
  canonical straight RGBA8 surface执行冻结 `renderweave-output-png/1.0` stored-DEFLATE/IDAT/CRC/Adler 编码。
- 允许影响：`renderer/` workspace/output crate与共同 vectors、Python independent verifier、`render` gate、
  tracker/plan/log/NOTES/evidence；必要时只收紧与该 kernel 边界直接相关的测试/manifest断言。
- 禁止影响：daemon RESULT/success path与process HELLO capability、Profile registration、layout/shaping/fetch/
  decode/premultiply/paint/raster/JPEG/QR/Barcode、公开 route/OpenAPI/Web/Editor、Ticket 19 formal records、
  physical Linux/J1/A3/READY、provider/真实数据/API Key。
- TDD：先让缺位 crate/API 的共享 vector test RED；再实现 exact surface/capacity与流式 PNG；最后以 Python
  标准库独立重算全部 bytes、chunk boundaries、CRC/Adler、SHA 与尺寸。
- 受影响验证：focused Cargo/Python → `render` → `server`/`fast` → 完整 `full`。
- 保证上限：Rust/kernel/gate A1；Rust+Python exact-vector A2；生成 RGBA fixture只证明encoder输入边界，
  不构成 raster/pixel/物理 Linux certification，无 A3/J1。
- 完成信号：Ticket 24 `resolved / automated_verified`、process manifest仍为rendererProfiles空集合/
  NOT_REGISTERED/NOT_CERTIFIED/raster ABSENT、verified local commit且worktree clean；不 push/tag/PR。
- 结果：已按上述边界完成；10 surface + 6 PNG exact vectors、Rust/Python independent replay、容量/字节合同、
  分级 gate 与 exact identity 见 `plans/logs/TV1-T24.md`。最终 full 目录按不可自指策略只在提交交接中报告。

## 24. TV1-T25 执行卡

- 决策：新增 workspace-internal Rust layout deep kernel，只消费 T23 `AdmittedRenderDocument`，在任何 resource、
  measure 或 allocation 前按 frozen DFS 对 statically-decidable layout constraints 做 defensive preflight；产物仅为
  immutable counts summary 或 closed stable problem，不是 `LaidOutScene`。
- 允许影响：`renderer/` workspace/layout crate与共同 vectors、daemon document-admission 接线、Python independent
  verifier、`render` gate、tracker/plan/log/NOTES/evidence；必要时只收紧与该 preflight identity 直接相关的
  process manifest/HELLO assertions。
- 禁止影响：binary64 tolerance、measure/arrange/boxes、resource fetch/decode、shaping、paint/raster/codec、daemon
  RESULT/success、Renderer/Profile registration、公开 route/OpenAPI/Web/Editor、formal Case/Oracle、physical Linux/
  J1/A3/READY、provider/真实数据/API Key。
- TDD：先让缺位 crate/API 与 independent verifier RED；再实现 decimal6、kind/mode/min-max、HUG/FILL cycle、
  Stack/Grid/Text/QR 静态规则；最后证明每个 negative document 通过 T23 admission 后才由 T25 精确拒绝。
- 受影响验证：focused Cargo/Python → `render` → `server`/`fast` → 完整 `full`。
- 保证上限：Rust/kernel/daemon/gate A1；Rust+Python exact-vector A2；无真实 layout/pixel/物理 Linux certification，
  无 A3/J1。
- 完成信号：Ticket 25 `resolved / automated_verified`、process manifest仍为rendererProfiles空集合/
  NOT_REGISTERED/NOT_CERTIFIED/raster ABSENT、verified local commit且worktree clean；不 push/tag/PR。
- 结果：已按上述边界完成；7 positive + 25 negative 的共同语料、Rust/Python 32/32 与 77 checks、daemon
  document-admission 折叠、分级 gate 与 exact identity 见 `plans/logs/TV1-T25.md`。最终 full 目录按不可自指
  策略只在提交交接中报告。

## 25. TV1-T26 执行卡

- 决策：深化现有 Rust layout crate，新增只消费 `AdmittedRenderDocument` 的 definite ABSOLUTE box seam；入口先
  通过 T25 preflight，再以 binary64 固定顺序计算 Canvas、Frame 与无资源视觉叶子的 pre-transform local
  LayoutBox/ContentBox。
- 允许影响：T26 tracker/plan/NOTES/log、layout crate/tests、shared fixture/vector、Python independent verifier、
  `render` gate与必要 evidence；process manifest/capability/daemon不因本票变化。
- 禁止影响：Layout tolerance、HUG intrinsic、Group/Stack/Grid/compositionViewport、Text/Image resource path、
  world transform/bounds、clip/paint/scene、fetch/decode/shaping/raster/PNG/JPEG、daemon RESULT/success、公开
  route/OpenAPI/Web/Editor、Profile/formal record/physical Linux/J1/A3/READY、provider/真实数据/API Key。
- TDD：先冻结 shared vectors并捕获缺位 API/verifier RED；实现 FIXED/FILL、min/max、Frame inward stroke/padding
  与 authored preorder；Rust/Python 对输出 binary64 bits 和 internal unsupported first boundary独立重放。
- 受影响验证：focused Cargo/Python → workspace fmt/clippy/test → `render` → `server`/`fast` → 完整 `full`。
- 完成信号：Ticket 26 `resolved / automated_verified`、Profile/daemon/raster边界不变、verified local commit且
  worktree clean；不 push/tag/PR。
- 结果：已按上述边界完成；6 laid-out + 9 unsupported 的共同语料、Rust/Python exact binary64 replay 15/15、
  50 checks、同解析 preflight/layout 与分级 gate 见 `plans/logs/TV1-T26.md`。最终 full 目录按不可自指策略只在
  提交交接中报告。
