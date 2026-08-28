# T129 — 物化 Capability 声明目录与按需 CapabilityState 组件

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T125, T128 (resolved)

## 目标

移除 Evaluator 对 canonical DesignDSL 的字符串搜索，并修复 CapabilityState 在 Asset 预准入之前、且总是
同时读取 Clock 与 entropy 的两项同边界违约。完整 closure 中每个合法 capability source 都必须由
Template-owned semantic value 精确识别；只有 authored + PUBLIC override AssetRef 全部通过 stage 5 后，
才可按声明组件进入 stage 6。只声明 CLOCK 时只建立 Clock，只声明 RANDOM 时只建立 nonce，两者均未声明时
不建立或持久化 CapabilityState。静态不可达、未使用 Definition 与 child snapshot 中的声明仍必须参加目录。

## Interface / seam

- 在 Rendering internal 建立 deep `CapabilityDeclarations` module：唯一输入是 frozen closure 与
  `DesignSemanticAuthority`，输出 closed、稳定排序的 exact contract 集合与 source count；interpret fault
  作为内部不变量违约，不把 raw JSON 或 Template AST 暴露给 app。
- 深化既有 `RenderingCapabilityRuntime` seam，以 closed required-contract set 建立/恢复运行时；时间与熵仍
  只存在于 app Adapter，Evaluator 不直接读取二者。
- app `InMemoryRenderingCapabilityRuntime` 使用版本化、closed sealed-state wire，只写入声明组件；恢复必须
  与 required-contract set 精确一致，并兼容当前短 TTL 内已提交的 legacy both-components state。
- evaluation fingerprint 使用同一声明目录的 canonical contract identity；不再由逗号字符串拆分或
  canonical bytes 文本匹配派生。
- 把 authored/PUBLIC override AssetRef 预准入从 Materializer 内部提升为 Evaluator 明确的 stage 5 module；
  precheck 完成后 Materializer 不重复调用外部 Asset seam，确保任何 ASSET_ADMISSION failure 的 state/store/
  Clock/entropy 下游效果为零。

## TDD 与边界

- `Evaluator.evaluate` seam 覆盖：无声明零 state work、unused CLOCK-only、unused RANDOM-only、跨 child 的
  both-components、semantic interpret fault、unsupported exact contract，以及 Asset admission failure-before-state。
- `RenderingCapabilityRuntime` Adapter seam 覆盖：CLOCK-only 不触碰 entropy、RANDOM-only 不读取 Clock、
  selective sealed-state round trip、required-set mismatch 与 malformed state fail-closed、legacy both restore。
- 本票不实现 capability demand/position/digest 字节预算、初始化重试预算或 Ticket 19 正式 records；这些在
  声明目录与选择性 state 正确后独立登记。不新增 route/OpenAPI/migration/Profile，不运行 provider。

## 验证

focused Rendering/app RED→GREEN、`render`、`fast`、顺序 `server`；按 RULE-VAL-001 复用未受影响且输入未变的
最近绿色 full，若 gate identity 或跨模块输入变化则重跑 `full`。最高 `automated_verified`。不读取 API Key、
不发送真实数据，不 push/tag/PR，不推进 A3/J1/READY。

## Resolution

- 新增 Rendering-internal `CapabilityDeclarations` deep module，以完整 frozen closure 的
  `DesignSemanticAuthority` 结果形成稳定排序的 exact CLOCK/RANDOM contract set 与 source count；
  Evaluator 不再搜索 canonical JSON 文本，semantic interpret fault 与 unsupported contract 均 fail closed。
- 新增显式 stage 5 `AssetAdmission`，按 authored closure atoms 后 external PUBLIC override winners 的顺序完成
  exact IMAGE/FONT kind 预准入，并把 opaque admitted token 交给 Materializer；任何预准入失败均发生在
  CapabilityState/store/Clock/entropy 之前，Materializer 不再重复调用 Asset seam。
- `RenderingCapabilityRuntime` 现以 closed `CapabilityRequirements` 建立/恢复组件；app sealed-state wire 升级为
  selective v2，CLOCK-only 不触碰 entropy、RANDOM-only 不读取 Clock、无声明不建 state，并保留短 TTL 内 legacy
  both-components v1 的精确兼容。evaluation fingerprint 使用同一 exact declaration identity。
- focused Rendering 143/143、app 17/17 通过；A1 `render` `20260828-192928-render`、`asset`
  `20260828-193022-asset`、`fast` `20260828-193042-fast`、`web` `20260828-193111-web`、`server`
  `20260828-193158-server` 与最终 `full` `20260828-204250-full` 均通过。full metadata 为 17/17 steps、
  provider attempts/API Key reads/reservations/cost=0，runtime canary 为 PostgreSQL ready / contract 0.16.0。
- required browser gate 在 Windows 默认 7 workers 下稳定复现 socket `ERR_NO_BUFFER_SPACE`；门控改为单 worker 后
  独立 prototype audit `20260828-201544-prototype-audit` 为 23 passed + 1 controlled skip，最终 full 同样通过且
  A/B/C 视觉变体 console/page errors 均为 0。失败 metadata 保留为事实，未停止用户进程或修改系统网络。
- A2 仅来自 full 中未变 Template/Asset/Renderer/R0/R1/P0 轴的独立重放；本票 selective-state Java 行为没有
  ticket-specific issued replay。A3 未外部强制，J0 pending、J1 未批准；未新增 migration/OpenAPI/Profile，未
  push/tag/PR、未调用 paid/live provider、未发送真实数据。
- 状态回填后的 resolution `fast` `20260828-210925-fast` 3/3 steps 均通过。
- 后续边界：external PUBLIC override 的 caller `asset.read` 授权尚未由当前 Asset seam 表达；capability
  demand/position/digest 容量、初始化重试预算与 Ticket 19 正式 records 也继续作为独立 frontier，不在本票冒充完成。
