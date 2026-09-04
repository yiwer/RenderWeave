# 34 — IOPA-P1-R19：准备 fresh v50 diagnostic authority

**What to build:** 在 v50 Provider-zero gates 全绿后，为同一 ordinary-design regression probe 生成 fresh、非计分、
严格绑定 canonicalizer implementation 的 diagnostic preparation、one-shot live seam 与 exact short-lived J1。

**Blocked by:** 33 — IOPA-P1-R18：创建 immutable v50 successor。

**Status:** in_progress

- [x] 冻结 fresh cycle、Profile/Prompt/pipeline、静态 PNG metadata 与相同 case SHA；不保存文件名、路径或 payload。
- [x] normalization material 绑定 canonicalizer version 与 v50 implementation identity（ADR-0045）。
- [x] manifest 复用既有 non-scoring evaluator，certification credit=0。
- [x] proposed caps 固定为 1 run / 5 calls / 100,000 model tokens / ¥3 / per-run 5 calls + ¥3 / ≤2h。
- [ ] strict schema/preflight、negative tests、scripted PostgreSQL closure contract 全绿。
- [ ] independent preparation verifier、payload scan、fast/server gates 全绿，Provider usage=0、OPEN=0。
- [ ] 按 ADR-0042 standing approval 实例化 exact OPEN J1；不得扩到其他 Profile/input/stage。

## Frozen identities

- cycle=`82f1d86b-065b-4357-924e-19945daf1077`
- profile=`dashscope-qwen38-max-product-v50-hybrid-generic`
- profile SHA=`62f333aee7096f09d6d04dea004641e8b0a9c425ee133d09a563594d81200691`
- normalization=`renderweave-image-only-fresh-normalization/1.0:146c27620edad71fd40618772c3c1fc8613684d83b91bf20edc5d944b7a4b8b4`
- manifest=`renderweave-image-only-profile-successor-diagnostic/1.0:4715941eb4cfe8ae6d44e8943a8ec2592ad290f044f3b05ea362ec5afb6ac76e`
- evaluator=`renderweave-image-only-profile-successor-diagnostic-evaluator/1.0:b2167261ae9d1e3775c91d06d90c57c47c16284d11b685e81aa5073de655f37e`
- proposed authorization=`20260818-iopa-v50-diagnostic-82f1d86b`

本票在 exact J1 OPEN 前保持 Provider-zero；不计入 5/20/60，不解锁 grant/apply/publish/deploy。
