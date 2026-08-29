# 接线 Canvas trim 每轴 inclusive-max 产品容量

Type: task
Status: resolved / automated_verified
Claimed by: none（Codex /root 于 2026-08-29 释放；single writer）
Blocked by: 195（已 resolved）

## Question

T195 已把 root Canvas `widthMm`、`heightMm` 的 cap-056 exclusive-min 接入唯一 Template-owned
capacity authority，并冻结 immutable component target v8（wired 56/65）。冻结 cap-057 同时要求每轴最多
`1,000 mm`，但产品 admission 尚未观察 `geometry.canvasTrimMmPerAxisMax`。如何保持相同公共 seam、canonical
decimal 与 fail-fast 顺序，接线该上界而不扩张到其余 geometry 轴？

## Answer（本票冻结的实施决定）

1. **唯一轴**：本票只接线 `geometry.canvasTrimMmPerAxisMax`（cap-057），`MAX_INCLUSIVE=1000`、
   `CANONICAL_DECIMAL`；成功 target 为 wired 57/65、remaining geometry 8。cap-058–065 留给后继票。
2. **既有公共 seam**：产品行为只通过 `DesignDslAuthority.admit(rawUtf8)` 观察；继续注入同一
   `DesignInputExpressionCapacityAuthority`，不新增 Interface、HTTP/OpenAPI、migration、配置或
   Rendering-local guard。
3. **精确顺序**：每个真实 Canvas 轴只 canonicalize 一次，按 `width min -> width max -> height min -> height max`
   观察；任一失败立即停止，发生在 persisted write、Evaluation 与任何 downstream work 前。
4. **失败 identity**：authority reject、invalid 或 throw 均 fail closed 为
   `DESIGN_PROPERTY_CONSTRAINT_INVALID / DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION`，保留 exact property pointer 与
   `geometry.canvasTrimMmPerAxisMax` limitId。below 与 at 接纳，scale-64 above 拒绝。
5. **TDD seam**：先以真实 Canvas raw JSON 在公共 admission seam 证明 max observation 缺失，再最小接线；覆盖
   observation 顺序、两个轴、below/at/above、canonical decimal 与 authority reject/invalid/throw。
6. **目标与门控**：GREEN 后冻结不可变 component target v9，保持 v1–v8 bytes 不变；执行 focused Template、
   component Java/TypeScript replay、canonical vectors、`template` 与 `fast`。本票无 app wiring，不重复 server/full。

## Boundary

- 不接线 cap-058–065，不发行 195 formal records，不创建两个 class required executor manifests，不升级
  execution-class executable、Profile、J1/A3/READY 或 `BUILD_NOT_AUTHORIZED`。
- 不运行独立 native Renderer、provider、API Key、真实数据或生产；不改用户 Image/Inference dirty work 与 stash，
  不 push/tag/PR。

## Resolution

- 实现基线 `3b662149d4647239f96acf309ee2e39ed4b41b4d` 已让 root Canvas 每轴共享一次 canonical decimal，
  并按 `width min -> width max -> height min -> height max` 进入唯一 Template-owned authority；below/at 成功，
  scale-64 above、authority reject/invalid/throw 均保留 exact property pointer、code/stage 与
  `geometry.canvasTrimMmPerAxisMax` limitId。
- 冻结 canonical kernel v1/8 的 256-byte number-token fixture 原本独立验证 parser/canonical 边界；测试现以显式
  frozen-kernel capacity context 隔离后继 cap-057 product rule，不改其 211 份原 bytes/digest，而产品 admission
  仍完整执行 max reservation。
- immutable component target v9：
  `.scratch/renderweave-template-v1/design-input-expression/capacity-component-target-v9.json`，SHA-256
  `ad06c0415cfaad9e636a536b57c0d32c2ad4ad73a72d103c36bbdf458ec70abf`，21317 bytes；predecessor v8
  SHA-256/length 精确绑定且 bytes 未改，product wiring 57/65、remaining geometry 8、formal records 0。
- RED 已证明 public seam 缺少 max observation；focused 23/23、Template module 146/146。component evidence
  `.sdlc/evidence/20260829-1923-template-t196-component/` 为 Java primary 195/195（A1）与独立 TypeScript
  195/195、2692 checks（A2）。`template` `.sdlc/evidence/20260829-192419-template/metadata.json` 与 `fast`
  `.sdlc/evidence/20260829-192451-fast/metadata.json` 均 passed/A1；Template Java/Python 211/211 及
  static/registry replay 通过。
- 本票无 app wiring，按 boundary 未重复 server/full；A3 无，J0 pending、J1 未批准。未运行 Renderer/Profile/
  provider/API Key/真实数据/生产，未发行 formal records/class manifests，未改用户 dirty work/stash，未
  push/tag/PR。
