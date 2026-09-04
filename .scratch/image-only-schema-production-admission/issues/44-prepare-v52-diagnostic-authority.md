# 44 — IOPA-P1-R29：准备 fresh v52 diagnostic authority

**What to build:** Provider-zero 创建 fresh normalization/manifest/evaluator/cycle、exact one-shot harness 与 J1 proposal。

**Blocked by:** 43 — IOPA-P1-R28。

**Status:** resolved

- [x] 同一 case SHA / `USER_PROVIDED + ORDINARY_DESIGN`，不保存路径、文件名或 payload。
- [x] caps≤1 run/5 calls/100k tokens/¥3/per-run 5+¥3/2h，Goal aggregate fail closed。
- [x] schema/preflight/negative/PostgreSQL/payload gates 全绿且 OPEN=0 后才实例化 exact J1。

## Frozen identities

- cycle=`981d7262-d802-45bb-96ce-d34b4468f9f9`
- profile=`dashscope-qwen38-max-product-v52-hybrid-generic`
- profile SHA=`d8014b605dfa01a5aa1e6062696c61eb896da9e146b2a6ab3c5dae3ca9957332`
- successor implementation=`renderweave-image-only-v52-implementation/1.0:293fec9792df98131d72acffdc22ed4b4d65e0d8edaea1b46743e9e7da2b7405`
- normalization=`renderweave-image-only-fresh-normalization/1.0:e0e505c515ff3c7c7bac57e0ddc19e714721e301fd2216830bc6ac82f98cae35`
- manifest=`renderweave-image-only-profile-successor-diagnostic/1.0:4a81d0718abb9b8db3e95052a4c268767c9ac01ce9ed90f117894dc1aed63d20`
- evaluator=`renderweave-image-only-profile-successor-diagnostic-evaluator/1.0:b2167261ae9d1e3775c91d06d90c57c47c16284d11b685e81aa5073de655f37e`
- authorization=`20260818-iopa-v52-diagnostic-981d7262`

## Resolution evidence

- A1 gate=`.sdlc/evidence/20260818-152744-image-only-v52-diagnostic-preparation/`
- summary SHA-256=`f670651c0d0662dcd00349566d26fe6c07dc9cd74d726a24a50ec0a6ef4b3e6d`
- authority implementation=`renderweave-image-only-v52-diagnostic-authority/1.0:d46775b3998d910e9ad524a36753b5f93e8707c768bdb655fc1e69abd6f87473`
- tests=Python、inference 78、Testcontainers PostgreSQL 6，全部 PASS
- preparation verification Provider usage=0；J1 仅在 Provider-zero checks 与 OPEN=0 后实例化。
