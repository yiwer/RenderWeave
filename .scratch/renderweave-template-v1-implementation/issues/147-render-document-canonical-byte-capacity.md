# T147 — RenderDocument canonical-byte seal 预算

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T146 (resolved)

## 目标

物化 Ticket 19 / `RW-T19-S7-089` 与 cap-016 的真实纵切：RenderDocument canonical UTF-8 bytes 使用
MAX_INCLUSIVE `67108864`，observed `67108863/67108864/67108865`，contract stage
`DOCUMENT_SEAL_COUNTING`、public stage `DOCUMENT_SEAL`、code `RENDER_DOCUMENT_LIMIT_EXCEEDED`、zero boundary
`ZERO_DOCUMENT_OUTPUT`。每一段 canonical bytes 必须在写入前经唯一
`renderweave-rendering-pipeline-capacity-guard/1.0` 原子预留；只有完整 writer 成功后才 commit immutable bytes 与 digest。

## 现状缺口与 seam

- 当前 `Sealer` 先递归拼出整份 canonical `String`，再创建完整 UTF-8 `byte[]`，最后以手写 `64L * 1024 * 1024`
  比较。它虽然拒绝 above，但分配发生在 guard 前，而且重复拥有 limit，不能满足 capped counting writer 合同。
- 扩展现有 Rendering-internal guard，使每个 limit 自带 frozen public stage/code；既有 materialization limits 保持
  `MATERIALIZATION/EVALUATION_BUDGET_EXCEEDED`，新增 canonical-byte limit 使用
  `DOCUMENT_SEAL/RENDER_DOCUMENT_LIMIT_EXCEEDED`。不创建第二个 capacity catalog。
- `Sealer.seal` 仍是调用/测试 interface。内部把 canonical object/array/scalar 表示写入容量感知的 chunked UTF-8
  accumulator；每个 chunk 先 reserve 再保留，失败 outcome 不含 partial bytes/digest。package-internal tracker overload
  只让测试预留前序 bytes，默认 production 路径创建新的 document-seal request tracker；无外部 Interface/config。
- frozen 最小 RenderDocument 是 305 UTF-8 bytes。预留 `67108864-305` 后必须 byte-identical seal 成功；再多预留 1
  必须返回 exact problem。cap-016 自身明确不执行 Sealer，因此 `67108863/64/65` 仍通过同一 production guard
  隔离重放，不冒充 full pipeline A2。

## TDD、验证与边界

- 先在 production guard test 加 cap-016 三点，missing enum/limit-specific stage-code 形成 RED；最小扩展 catalog 后 GREEN。
- 再从 `Sealer.seal` outcome 加 frozen 305-byte at/above test：先捕获 missing tracker seam compile RED，再只接 tracker
  但保留 post-hoc writer，捕获错误 `Sealed` behavioral RED；随后替换为 pre-write capped writer。
- 保持全部现有 RenderDocument canonical/digest vectors byte-identical；focused guard/Sealer/RenderDocument/Evaluator、
  完整受影响 reactor、`render` 与 `fast`。无 app wiring/API/Web/migration/Profile 变化，不重复 server/full。
- 本票不实现 `renderDocument.jsonDepth/staticNodes/childEdges/runs/textScalars/vectorEntries`、diagnostics、正式 Ticket 19
  records/product executor，也不改变 Engine/Profile。provider/API Key/真实数据/费用/Profile registration/push/tag/PR
  均不推进；最高 `automated_verified`。

## Resolution

- guard tracer 首先产生 3 个 compile RED，全部为 production enum 缺少 `RENDER_DOCUMENT_CANONICAL_BYTES`。唯一 catalog
  现让 limit 自带 frozen code/stage；cap-016 `67108863/67108864` admit、`67108865` 返回 exact
  DOCUMENT_SEAL / RENDER_DOCUMENT_LIMIT_EXCEEDED / `renderDocument.canonicalBytes`，guard 9/9 GREEN。
- Sealer tracer 随后产生 2 个 missing-overload compile RED；只接 tracker seam、保留 post-hoc 检查时，Sealer 6 tests
  精确 1 个 behavioral RED：above 期望 `SealRejected`、实际 `Sealed`。
- `CanonicalJson.CanonicalValue` 现在保留 canonical member/array 语义但不拼完整文档 String；
  `RenderDocumentCanonicalWriter` 用 64 KiB chunks，先无分配计算每段 exact UTF-8 length、reserve 成功后才编码/保留，
  string escaping 也分块写入。全部 canonical value 成功后才复制成完整 immutable bytes，随后计算 document/result digest；
  overflow partial chunks 不可见。Sealer problem 直接投影至 Evaluator，删除重复 problem 映射与手写 64 MiB authority。
- frozen 305-byte minimal document 在预留 `67108864-305` 后 byte-identical seal；多预留 1 后无 sealed bytes/digest，
  返回 exact problem。focused Evaluator 68 + RenderDocument 4 + guard 9 + Sealer 6 = 87/87；受影响 reactor Schema 20、
  Validation 13、Template 84、Asset 92、Rendering 211 全绿，`git diff --check` 通过。
- A1 `render` `.sdlc/evidence/20260829-052149-render/`（2/2，含现有 RenderDocument independent replay 83/83）与
  `fast` `.sdlc/evidence/20260829-052240-fast/`（3/3）metadata 均 `passed`。cap-016 明确不执行 Sealer 且无正式
  product executor，故无 T147-specific A2/A3；J0 pending、J1 未批准。未重复 server/full，provider attempts/API
  Key reads/真实数据/Profile registration/push/tag/PR 均为 0。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260829-052401-fast/` metadata 为 `passed`，3/3 steps 全绿。
