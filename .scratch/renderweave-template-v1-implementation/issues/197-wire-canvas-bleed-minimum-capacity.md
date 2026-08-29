# 接线 Canvas bleed 每边 minimum 产品容量

Type: task
Status: resolved / automated_verified
Claimed by: none（Codex /root 于 2026-08-29 释放；single writer）
Blocked by: 196（已 resolved）

## Question

T196 已把 Canvas trim 两轴的 lower/upper bound 接入唯一 Template-owned capacity authority，并冻结
component target v9（wired 57/65）。冻结 cap-058 要求可选 Canvas `bleed` 的每个 side 均 `>= 0`，但产品
admission 仍以本地 `BigDecimal.signum()` 返回泛化 `DESIGN_VALUE_INVALID`，没有观察
`geometry.bleedMmPerSideMin`。如何在不提前接线 max 或其他 geometry 轴的前提下补齐该 reservation？

## Answer（本票冻结的实施决定）

1. **唯一轴**：本票只接线 `geometry.bleedMmPerSideMin`（cap-058），`MIN_INCLUSIVE=0`、
   `CANONICAL_DECIMAL`；成功 target 为 wired 58/65、remaining geometry 7。cap-059–065 留给后继票。
2. **既有公共 seam**：产品行为只通过 `DesignDslAuthority.admit(rawUtf8)` 观察；继续注入同一
   `DesignInputExpressionCapacityAuthority`，不新增 Interface、HTTP/OpenAPI、migration、配置或
   Rendering-local guard。
3. **精确作用域与顺序**：`bleed` 缺失时不观察；存在时四个 required side 按 frozen wire
   `topMm -> rightMm -> bottomMm -> leftMm` 各观察一次，位于 Canvas trim/background validation 之后、bindings/
   children 与 persisted write/Evaluation 之前。任一失败立即停止。
4. **canonical observation 与失败 identity**：每个 side 复用 cap-056/057 的 bounded exact decimal
   canonicalization，zero 统一为 `0`，不量化/截断/饱和。authority reject、invalid 或 throw 均 fail closed 为
   `DESIGN_PROPERTY_CONSTRAINT_INVALID / DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION`，保留 exact side pointer 与
   `geometry.bleedMmPerSideMin` limitId；scale-64 below 拒绝，at/above 接纳。
5. **TDD seam**：先以真实 Canvas raw JSON 在公共 admission seam 证明四 side observation 缺失，再最小接线；
   覆盖 absent bleed、side 顺序、canonical zero、below/at/above、exact pointer 与 authority reject/invalid/throw。
6. **目标与门控**：GREEN 后冻结不可变 component target v10，保持 v1–v9 bytes 不变；执行 focused Template、
   component Java/TypeScript replay、canonical vectors、`template` 与 `fast`。本票无 app wiring，不重复 server/full。

## Boundary

- 不接线 cap-059–065，不把全局 non-negative decimal property 误算为 bleed，不发行 195 formal records，不创建
  两个 class required executor manifests，不升级 execution-class executable、Profile、J1/A3/READY。
- 不运行独立 native Renderer、provider、API Key、真实数据或生产；不改用户 Image/Inference dirty work 与 stash，
  不 push/tag/PR。

## Resolution

- 实现基线 `21ff1c45440c25c8d8e7f0edebd2d8b29d6d8902` 已让可选 Canvas `bleed` 在存在时按
  `topMm -> rightMm -> bottomMm -> leftMm` 经公共 `DesignDslAuthority.admit(rawUtf8)` 进入唯一
  Template-owned capacity authority；缺失时零 observation，任一 side 失败立即停止。
- 每个 side 使用 bounded exact canonical decimal，`-0.00` 观察为 `0`；scale-64 below、at、above 以及
  authority reject/invalid/throw 均保留 exact side pointer、property code/stage 与
  `geometry.bleedMmPerSideMin` limitId。oversized exponent 仍先由 canonical byte limit fail closed。
- immutable component target v10：
  `.scratch/renderweave-template-v1/design-input-expression/capacity-component-target-v10.json`，SHA-256
  `3d12b38b84b00c3f1961a31f337f5ea0e1d729d9dfa8d3af98c93291ddd39609`，21354 bytes；predecessor v9
  SHA-256/length 精确绑定且 bytes 未改，product wiring 58/65、remaining geometry 7、formal records 0。
- RED 已证明 public seam 缺少四 side observation；focused 28/28、Template module 151/151。component evidence
  `.sdlc/evidence/20260829-1934-template-t197-component/` 为 Java primary 195/195（A1）与独立 TypeScript
  195/195、2692 checks（A2）。`template` `.sdlc/evidence/20260829-193558-template/metadata.json` 与 `fast`
  `.sdlc/evidence/20260829-193637-fast/metadata.json` 均 passed/A1；Template Java/Python 211/211 及
  static/registry replay 通过。
- 本票无 app wiring，按 boundary 未重复 server/full；A3 无，J0 pending、J1 未批准。未运行 Renderer/Profile/
  provider/API Key/真实数据/生产，未发行 formal records/class manifests，未改用户 dirty work/stash，未
  push/tag/PR。
