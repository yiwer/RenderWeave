# 接线 Canvas trim 每轴 exclusive-min 产品容量

Type: task
Status: resolved / automated_verified
Claimed by: none（Codex /root 于 2026-08-29 释放；single writer）
Blocked by: 136（已 resolved）

## Question

T136 已把 Design/Input/Expression component target 推进到 v7、wired 55/65，剩余十轴均为 geometry。冻结
cap-056 要求 Canvas trim 的 `widthMm` 与 `heightMm` 每轴严格 `> 0`，但当前
`CanonicalDesignDslAuthority` 仍以本地 `BigDecimal.signum()` 比较并返回泛化 `DESIGN_VALUE_INVALID`，没有经过
Template-owned capacity authority，也没有保留冻结 limitId/stage/code。如何在不增加第二 comparator 或新 API 的
前提下接线首个 geometry 轴？

## Answer（本票冻结的实施决定）

1. **唯一轴**：本票只接线 `geometry.canvasTrimMmPerAxisExclusiveMin`（cap-056）；成功 target 为 wired
   56/65、remaining geometry 9。`canvasTrimMmPerAxisMax` 与其余八个 geometry 轴留给后继票。
2. **既有公共 seam**：产品行为仅通过 `DesignDslAuthority.admit(rawUtf8)` 观察，继续注入同一
   `DesignInputExpressionCapacityAuthority`；不新增 Interface、HTTP/OpenAPI、migration、配置或 Rendering-local
   guard。
3. **精确 domain 与顺序**：只观察 root Canvas authored `widthMm`、`heightMm`，按 wire declaration 顺序
   width→height；两者均在 semantic acceptance/canonical emission/dependency I/O 前各观察一次。其他正数 property
   不借用本轴。
4. **canonical observation**：先完成 Number 类型与有限 BigDecimal 解析，再把所有 zero 规范为 `0`，非零值经
   `stripTrailingZeros().toPlainString()` 形成 `CANONICAL_DECIMAL`；不量化、不截断、不饱和，不把 exponent token
   直接交给 scalar guard。
5. **失败 identity**：authority reject、invalid 或 throw 均 fail closed 为
   `DESIGN_PROPERTY_CONSTRAINT_INVALID / DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION`，保留 exact property pointer 与
   `geometry.canvasTrimMmPerAxisExclusiveMin` limitId；默认 authority 对 below/at 拒绝、最小 scale-64 above 接纳。
6. **TDD seam**：先以真实 Canvas raw JSON 证明 observation 缺失、旧泛化 code/stage/limit 的 RED；再最小接线，
   覆盖 width/height、canonical decimal、below/at/above 与 reject/invalid/throw。测试只调用公共 admission seam，
   不测私有 helper 或 mock 内部 collaborator。
7. **目标与门控**：GREEN 后冻结不可变 component target v8，保持 v1–v7 bytes 不变；执行 focused Template、
   component Java/TypeScript replay、canonical vectors、`template` 与 `fast`。本票无 app wiring，不重复 server/full。

## Boundary

- 不接线 geometry 其余九轴，不发行 195 formal records，不创建两个 class required executor manifests，不升级
  execution-class executable、Profile、J1/A3/READY 或 `BUILD_NOT_AUTHORIZED`。
- 不运行独立 native Renderer、provider、API Key、真实数据或生产；不改用户 Image/Inference dirty work 与 stash，
  不 push/tag/PR。

## Resolution

- 实现基线 `86b4dc1d25f6c3c4af6c960b989417829493069c` 已沿公共
  `DesignDslAuthority.admit(rawUtf8)` 把 root Canvas `widthMm`、`heightMm` 按 width→height 接入唯一
  Template-owned capacity authority；below/at、authority reject/invalid/throw 均以 exact
  `DESIGN_PROPERTY_CONSTRAINT_INVALID` / `DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION` /
  `geometry.canvasTrimMmPerAxisExclusiveMin` fail closed，scale-64 above 成功。
- 为保持既有 16 MiB canonical 边界，单一 authority 的 canonical-decimal comparator 改为线性字符串比较，且在构造
  observation 前拒绝单值已超过整份 canonical 文档上限的指数展开；211 个冻结 canonical vectors（含
  16 MiB exact-at/above）保持原 bytes/digest 与约一秒 replay。
- immutable component target v8：
  `.scratch/renderweave-template-v1/design-input-expression/capacity-component-target-v8.json`，SHA-256
  `7c088a21bae28cefc712f2b7c0d6cbc12b22382577b5bf335412c6150ce5daf6`，21275 bytes；predecessor v7
  bytes 未改，product wiring 56/65、remaining geometry 9、formal records 0。
- focused 18/18、Template module 144/144；component evidence
  `.sdlc/evidence/20260829-1913-template-t195-component/` 为 Java primary 195/195（A1）与独立 TypeScript
  195/195、2692 checks（A2）。`template` `.sdlc/evidence/20260829-190635-template/metadata.json` 与 `fast`
  `.sdlc/evidence/20260829-190708-fast/metadata.json` 均 passed/A1；Template Java/Python 211/211，static/registry
  replay 通过。
- 本票无 app wiring，按冻结 boundary 未重复 server/full；A3 无，J0 pending、J1 未批准。未运行
  Renderer/Profile/provider/API Key/真实数据/生产，未发行 formal records/class manifests，未改用户 dirty work/stash，
  未 push/tag/PR。
