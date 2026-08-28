# T128 — 物化 CapabilityCallPosition 完整 OccurrencePath

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T125, T127 (resolved)

## 目标

移除 T21 明确留下的简化 `source wire + frameKey` position，实现冻结
`renderweave-capability-call-position/1.0`：每次 capability demand 必须绑定 Definition 的
declaration frame，而不是 consumer Node，并以 exact root / TemplateUse / Repeat path、definitionId、
inputAlias、capabilityContractId 与 operation 形成 canonical JSON bytes。

## Interface / seam

- 在 Rendering internal 建立一个 deep `CapabilityCallPosition` module；其 Interface 只负责从 root path
  进入 Repeat/TemplateUse、按 Definition domain 截断 declaration frame，并产生 canonical bytes/memo identity。
- `Materializer.InvocationScope` 只携带该 module 的 immutable runtime path；不让 path 规则散落在
  Materializer、DefinitionEngine、CapabilityValues 或 app Adapter。
- `DefinitionEngine` 不再接收 caller-crafted `frameKey`。invocation-domain Definition 在同一 invocation 的
  下游多个 loop consumer 间 memoize；loop-domain Definition 仅按声明 loop 的原 inputIndex 分帧；不同
  TemplateUse occurrence 隔离。
- 现有 `CapabilityProvider` / `RenderingCapabilityRuntime` 的 `byte[] callPosition` seam 保持不变；app 不需要
  知道路径类型。`capabilityResultDigest` 中的 `callPosition` 改为 canonical object，不再编码为 Base64 string。

## TDD 与边界

- exact bytes 覆盖 ROOT、nested Repeat、TemplateUse-inside-Repeat、child Repeat，以及 member UTF-8 排序与
  最短非负整数；Materializer demand 覆盖 definitionId/alias/contract、invocation truncation、loop item
  independence、child invocation isolation与同 frame memo。
- capability 仍只能作为 Expression input；直接 Binding/Mapping/structure source 防御性失败封闭。
- 本票不新增公开 route/OpenAPI/migration/Profile，不注册 Renderer，不改变 Clock/Random HMAC 公式；position
  per-demand/total 容量计数、正式 Ticket 19 corpus/physical certification、公开诊断投影另票推进。

## 验证

focused Rendering RED→GREEN、`render`、`fast`、顺序 `server`、Goal `full`、resolution `fast`；最高
`automated_verified`。不运行 provider、不读取 API Key、不发送真实数据，不 push/tag/PR，不推进 A3/J1/READY。

## Resolution evidence

- focused TDD 先因 `CapabilityCallPosition` 尚不存在得到编译 RED；实现后
  `CapabilityCallPositionTest` 3、`CapabilityValuesTest` 10、`MaterializerTest` 14，共 27/27 GREEN。
- Rendering internal 的单一 deep module 现拥有 immutable ROOT/TEMPLATE_USE/REPEAT runtime path、按
  invocation/loop declaration frame 截断、exact canonical bytes 与 memo identity。`DefinitionEngine` 不再接收
  caller-crafted `frameKey`；invocation 定义跨下游 Repeat consumer memoize，loop 定义按原 inputIndex 分帧，
  不同 TemplateUse occurrence 保持隔离。
- capability result digest 现直接嵌入 closed canonical `callPosition` object，不再嵌入 Base64 string；冻结测试
  digest 为 `sha256:8b0960a385085e2a4d03cada5347867ea1193eec09e0128ff0c149501179d30a`。
  `CapabilityProvider` / `RenderingCapabilityRuntime` 的 `byte[]` seam 保持不变。
- A1 gates：`render` `.sdlc/evidence/20260828-181110-render/`、affected `fast`
  `.sdlc/evidence/20260828-181203-fast/`、顺序 `server`
  `.sdlc/evidence/20260828-181232-server/` 与 17-step Goal `full`
  `.sdlc/evidence/20260828-182907-full/` 均为 passed；`full` 每个 step 均 exit 0。
- `server` 为 App 366 tests/0 failures/0 errors/15 skipped；`full` 覆盖 Rendering 134 tests、Node 24 Web
  28 files/217 tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft/browser journeys 与最终
  inference replay E2E 1/1。R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0。
- A2 未签发 ticket-specific independent replay；A3 未外部强制；J0。Profile registration、J1、READY、
  paid/live provider、真实数据、push/tag/PR 均未推进。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260828-185451-fast/` 3 steps 均 exit 0。
