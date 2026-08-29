# 接线 Canvas bleed 每边 maximum 产品容量

Type: task
Status: resolved / automated_verified
Claimed by: none（Codex /root 于 2026-08-29 释放；single writer）
Blocked by: 197（已 resolved）

## Question

T197 已把可选 Canvas `bleed` 四边的 inclusive minimum 接入唯一 Template-owned capacity authority，并冻结
component target v10（wired 58/65）。冻结 cap-059 还要求每个 side 均 `<= 100`，但产品 admission 目前只观察
`geometry.bleedMmPerSideMin`，因此大于 100 的 bleed 仍可被接纳。如何在不提前接线其他 geometry 轴的前提下
补齐 maximum reservation？

## Answer（本票冻结的实施决定）

1. **唯一轴**：本票只接线 `geometry.bleedMmPerSideMax`（cap-059），`MAX_INCLUSIVE=100`、
   `CANONICAL_DECIMAL`；成功 target 为 wired 59/65、remaining geometry 6。cap-060–065 留给后继票。
2. **既有公共 seam**：产品行为仍只通过 `DesignDslAuthority.admit(rawUtf8)` 观察；继续注入同一
   `DesignInputExpressionCapacityAuthority`，不新增 Interface、HTTP/OpenAPI、migration、配置或
   Rendering-local guard。
3. **精确作用域与顺序**：`bleed` 缺失时不观察；存在时按 frozen wire
   `topMm -> rightMm -> bottomMm -> leftMm`，每个 side 共享一次 bounded canonical decimal，并依次执行
   minimum→maximum reservation；任一失败立即停止，位于 bindings/children 与 persisted write/Evaluation 前。
4. **失败 identity**：below/at 接纳，scale-64 above 拒绝；authority reject、invalid 或 throw 均 fail closed 为
   `DESIGN_PROPERTY_CONSTRAINT_INVALID / DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION`，保留 exact side pointer 与
   `geometry.bleedMmPerSideMax` limitId。minimum 失败时不得观察该 side 的 maximum。
5. **TDD seam**：先把现有真实 Canvas raw JSON 的公共 admission 断言提升为每边 min→max，取得 maximum
   observation 缺失 RED；再最小接线，并补齐四边 below/at/above、exact pointer、fail-fast 与 authority
   reject/invalid/throw。
6. **目标与门控**：GREEN 后冻结不可变 component target v11，保持 v1–v10 bytes 不变；执行 focused Template、
   component Java/TypeScript replay、canonical vectors、`template` 与 `fast`。本票无 app wiring，不重复 server/full。

## Boundary

- 不接线 cap-060–065，不把其他 non-negative decimal property 误算为 bleed，不发行 195 formal records，不创建
  两个 class required executor manifests，不升级 execution-class executable、Profile、J1/A3/READY。
- 不运行独立 native Renderer、provider、API Key、真实数据或生产；不改用户 Image/Inference dirty work 与 stash，
  不 push/tag/PR。

## Resolution

- 实现基线 `ca1a0b55fc008a55c5dfbc94437ba28aabcfe141` 已让可选 Canvas `bleed` 在存在时按
  `topMm -> rightMm -> bottomMm -> leftMm` 处理；每个 side 共享一次 bounded exact canonical decimal，并经公共
  `DesignDslAuthority.admit(rawUtf8)` 依次进入 minimum→maximum。minimum 失败不会观察 maximum，任一失败停止。
- 四个 side 的 scale-64 below/at 成功，scale-64 above 以 exact side pointer、property code/stage 与
  `geometry.bleedMmPerSideMax` limitId 拒绝；authority reject/invalid/throw 均 fail closed。缺失 bleed 仍为零
  observation，oversized exponent 仍先由 canonical byte limit 收口。
- immutable component target v11：
  `.scratch/renderweave-template-v1/design-input-expression/capacity-component-target-v11.json`，SHA-256
  `957abb68737d22ccbb9dfa8d4007135ee018b08c3e920e6df04c7653a1c94d90`，21391 bytes；predecessor v10
  SHA-256/length 精确绑定且 bytes 未改，product wiring 59/65、remaining geometry 6、formal records 0。
- RED 精确证明 public seam 只有四个 minimum observation、缺少四个 maximum；focused 30/30、Template module
  153/153。component evidence `.sdlc/evidence/20260829-1948-template-t198-component/` 为 Java primary
  195/195（A1）与独立 TypeScript 195/195、2692 checks（A2）。`template`
  `.sdlc/evidence/20260829-194829-template/metadata.json` 与 `fast`
  `.sdlc/evidence/20260829-194900-fast/metadata.json` 均 passed/A1；Template Java/Python 211/211 及
  static/registry replay 通过。
- 本票无 app wiring，按 boundary 未重复 server/full；A3 无，J0 pending、J1 未批准。未运行 Renderer/Profile/
  provider/API Key/真实数据/生产，未发行 formal records/class manifests，未改用户 dirty work/stash，未
  push/tag/PR。
