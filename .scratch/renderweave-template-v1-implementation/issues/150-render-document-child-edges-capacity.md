# T150 — RenderDocument child edges 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T149 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-092` 与 cap-019：`renderDocument.childEdges` 使用 MAX_INCLUSIVE `19999`，
observed `19998/19999/20000`，contract stage `DOCUMENT_SEAL_COUNTING`、public stage `DOCUMENT_SEAL`、code
`RENDER_DOCUMENT_LIMIT_EXCEEDED`、zero boundary `ZERO_DOCUMENT_OUTPUT`。每条 final static tree parent-child edge 必须在
child Node、canonical object、canonical bytes/digest 与任何 Engine Command 分配前，经现有 request-local production
guard 原子预留。

## seam 与计数语义

- root Canvas 无 parent edge，计 `0`；每个 surviving `children[]` item 与其 parent 之间计 `1`，空 children array
  计 `0`。pruned content 不进入 final tree，因而不产生 edge。
- `compositionViewport.sourceCanvas` 是最终静态树中的专用 parent-child link，虽不编码在 `children[]`，仍计 `1`；
  sourceCanvas 的每个 children item 再各计 `1`。
- 普通 edge 在 `sealChildren()` 调用 `sealNode()` 前 reserve；viewport→sourceCanvas edge 在 source occurrenceId/
  object 分配前 reserve。这样 20,000th edge 会先于第 20,001 个 static Node 失败，并保持稳定 first-fail。
- 本轴与 T149 `renderDocument.staticNodes` 共享同一 tracker/catalog，但必须使用独立 limitId/counter；不得用
  `nodes - 1` 后验推导、canonical JSON array item 计数或第二套 guard 替代真实生产 reservation。

## TDD、验证与边界

- guard test 先引用缺失的 `RENDER_DOCUMENT_CHILD_EDGES` 捕获 compile RED，再加入唯一 catalog 项重放
  `19998/19999/20000`。
- Sealer product tests 以 prefix + empty root/one-child tree 证明 root/empty zero-charge 与普通 child edge；再以
  root→viewport→sourceCanvas 两条 edge 证明专用 sourceCanvas link 的 exact at/above 边界。
- focused guard/Sealer/RenderDocument/Evaluator、完整受影响 reactor、`render` 与 `fast`；无 app wiring/API/Web/
  migration/Profile 变化，不重复 server/full。
- 本票不实现 Runs/textScalars/vectorEntries、diagnostics、正式 Ticket 19 records/product executor 或 Engine。
  provider/API Key/真实数据/费用/Profile registration/push/tag/PR 均不推进；最高 `automated_verified`、J0。

## Resolution

- guard test 先精确产生 3 个缺失 `RENDER_DOCUMENT_CHILD_EDGES` 的 compile RED；唯一 catalog 加入
  `renderDocument.childEdges=19999` 后 guard 12/12 GREEN，stage/code/limitId 与 cap-019 完全一致。
- Sealer 10 tests 在 production 接线前精确出现 2 个 behavioral RED：普通 one-child above 与 sourceCanvas above
  均错误 Sealed；在 `sealNode`/source occurrenceId 前 reserve 后 10/10 GREEN，其余 8 个既有测试保持绿色。
- empty root 在 child/static prefix 均为 19999 后仍 seal；one-child tree 在双轴 prefix 19998 后同时到达
  19999 edges/20000 Nodes，双轴 prefix 19999 后 exact child-edge reject；root→viewport→sourceCanvas 在双轴 prefix
  19997 后同时到达两轴上限、prefix 19998 后于 source edge exact reject。
- `sealChildren` 不再按未准入的 `children.size()` 预分配 backing storage；empty list 的每次 growth 均发生在对应 edge
  reservation 成功后。20,000th edge 因此先于 child Node/object 与第 20,001 个 static Node 分配失败。
- focused Evaluator 68 + canonical writer 3 + RenderDocument contract 4 + guard 12 + Sealer 10 = 97/97；受影响
  reactor Schema 20、Validation 13、Template 84、Asset 92、Rendering 221 全绿，零 failure/error。
- A1 `render` `.sdlc/evidence/20260829-055908-render/`（2/2）与 `fast`
  `.sdlc/evidence/20260829-055957-fast/`（3/3）metadata 均 `passed`；既有 RenderDocument independent replay
  83/83 仍 byte-identical。
- cap-019 不执行 Evaluator/Sealer，故无 T150-specific A2/A3；J0 pending、J1 未批准。无 app wiring/API/Web/
  migration/Profile 变化，未重复 server/full；provider attempts/API Key reads/真实数据/Profile registration/
  push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-060138-fast/` metadata 为 `passed`，3/3 steps 全绿。
