# 32 — IOPA-P1-R17：实现 v50 lossless local-ID canonicalization

**What to build:** 按 ticket 31 / ADR-0044 实现 successor-only deterministic local-ID seam：在 OBSERVE JSON
完成 strict shape decode 后、构造 `VisualRegion` / `VisualElement` / grounding forest 前，只把五类 local-ID 字段映射
为 bounded r/e/g labels，并保留 exact declaration order、equality 与 reference graph。

**Blocked by:** None — ticket 31 已 resolved。

**Status:** resolved

- [x] v49 与更早默认 policy 保持 `STRICT`；只有 pipeline 4.32 opt in。
- [x] region / element declarations 按数组顺序分别映射为 `r1..r32` / `e1..e32`；repeat equality classes 按首次
  出现映射为 `g1..g32`。
- [x] parent 与 element ownership references 只按声明表重写；不 trim、排序或解释 raw opaque label。
- [x] null/blank/duplicate declarations、dangling reference、wrong type/shape、超过 bounded classes 一律固定 code
  fail closed；canonicalization failure 不进入 retry allowlist。
- [x] geometry、kind、multiplicity、readingOrder、evidence、proposedKey、displayName、valueHint 等非 ID semantic values
  不变，随后仍运行完整 RenderWeave validator。
- [x] payload-free telemetry 只记录 fixed outcome `VISUAL_GROUNDING_LOCAL_ID_CLASSES_CANONICALIZED` 与 class count；
  raw labels 不进入 attempt/evidence/task projection。
- [x] codec differential 覆盖两套不同 opaque labels 得到同一 canonical plan；strict historical path 仍拒绝旧词法外 label。
- [x] Testcontainers PostgreSQL + scripted Provider 证明单次 OBSERVE 直接前进，无 repair call，后续 task 只见 r/e IDs，
  Candidate 保持 review-only，StaticSchema 未发布。
- [x] 独立 verifier + dedicated Provider-zero gate 通过并落 payload-free evidence；OPEN authorization=0。

## Evidence

- A1 dedicated gate：`.sdlc/evidence/20260818-124217-image-only-v50-successor/` PASS；独立 verifier 2/2、
  inference 79/79、Testcontainers PostgreSQL scripted Provider 3/3，0 failures/errors。
- summary implementation identity=`renderweave-image-only-v50-implementation/1.0:943b289fdaae7d5e7b81f0c339f80119be0dd009c7a0b1320d686977121ee75b`；
  v49 CLOSED artifacts 3/3 digest replay，OPEN=0，Provider attempts/reservations/tokens/cost/key reads 全 0。
- v50 scripted run 只发 OBSERVE/HIERARCHY/ELEMENT_BINDING 三次本地 fake calls；首 attempt 仅记录 9 个 canonical
  local-ID classes，不包含 raw labels；run=`REVIEW_REQUIRED` 但未 apply，StaticSchema count 未变。
