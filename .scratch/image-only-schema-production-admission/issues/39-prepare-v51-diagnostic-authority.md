# 39 — IOPA-P1-R24：准备 fresh v51 diagnostic authority

**What to build:** 在 v51 parent-containment provenance successor 的 Provider-zero gates 全绿后，为同一
ordinary-design regression probe 生成 fresh、非计分、严格绑定 v51 implementation 与 normalization 的
diagnostic preparation、one-shot live seam 与 exact short-lived J1。

**Blocked by:** 38 — IOPA-P1-R23：创建 immutable v51 successor。

**Status:** resolved

- [x] 冻结 fresh cycle、Profile/Prompt/pipeline、静态 PNG metadata 与相同 case SHA；不保存文件名、路径或 payload。
- [x] normalization material 绑定 local-ID canonicalizer 与 v51 parent-containment implementation identity（ADR-0046）。
- [x] manifest 复用既有 non-scoring evaluator，certification credit=0。
- [x] proposed caps 固定为 1 run / 5 calls / 100,000 model tokens / ¥3 / per-run 5 calls + ¥3 / ≤2h。
- [x] strict schema/preflight、negative tests、scripted PostgreSQL closure contract 全绿。
- [x] independent preparation verifier、payload scan 与 dedicated gate 全绿，Provider usage=0、OPEN=0。
- [x] 按 ADR-0042 standing approval 实例化 exact OPEN J1；不得扩到其他 Profile/input/stage。

## Frozen identities

- cycle=`7d929b74-47ca-40a7-bfd5-061e070c2bd2`
- profile=`dashscope-qwen38-max-product-v51-hybrid-generic`
- profile SHA=`972001414977a7cc788def6e8e106b2c7f146a306d1fa328d48ff053d472d3bd`
- successor implementation=`renderweave-image-only-v51-implementation/1.0:45f3e9d6b25a0fbe9788fd7714adecaabc800a9279c2e37d73e76f850df887f1`
- normalization=`renderweave-image-only-fresh-normalization/1.0:632c601ccdcbd561fcb9502777a888712a564a81352f9f19b163b4a0e9a6b4cc`
- manifest=`renderweave-image-only-profile-successor-diagnostic/1.0:e2a01f1d788c52bcf87838a242201a32d1b28dec741640abd6b6a2be8d690925`
- evaluator=`renderweave-image-only-profile-successor-diagnostic-evaluator/1.0:b2167261ae9d1e3775c91d06d90c57c47c16284d11b685e81aa5073de655f37e`
- proposed authorization=`20260818-iopa-v51-diagnostic-7d929b74`

本票在 exact J1 OPEN 前保持 Provider-zero；不计入 5/20/60，不解锁 grant/apply/publish/deploy。

## Resolution evidence

- A1 gate=`.sdlc/evidence/20260818-143544-image-only-v51-diagnostic-preparation/`
- summary SHA-256=`cdfbdac300e454f0091313c4a3ba195baccf405a0aa061cff6d0adf1603965fe`
- authority implementation=`renderweave-image-only-v51-diagnostic-authority/1.0:c40cbc4a662c44ccd1b1af3627960e1b748ac3e0cbac16fd9827e3b7cbe82443`
- tests=Python 3 + inference 106 + Testcontainers PostgreSQL 7，全部 PASS
- preparation verification provider usage=0；J1 在所有 Provider-zero checks 完成后才实例化。
