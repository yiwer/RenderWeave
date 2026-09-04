# 43 — IOPA-P1-R28：创建 immutable v52 successor

**What to build:** 创建 hidden/EXPERIMENTAL v52 Profile，只让 pipeline 4.34 opt in ticket 42 normalization；Prompt 16、
route/model、caps 与 v51 其他字段保持 exact。

**Blocked by:** 42 — IOPA-P1-R27。

**Status:** resolved

- [x] Profile 相对 v51 仅 `profileId`、`pipelineVersion` 不同；canonical SHA/Prompt SHA 冻结。
- [x] registry/capability/budget lifecycle 允许认证候选但禁止 product-live/grant/apply/publish/deploy。
- [x] exact diff/hash、compatibility 与 provider-zero successor gate 全绿。

## Frozen identity — 2026-08-18

- Profile：`dashscope-qwen38-max-product-v52-hybrid-generic`
- Profile SHA-256：`d8014b605dfa01a5aa1e6062696c61eb896da9e146b2a6ab3c5dae3ca9957332`
- Pipeline：`renderweave-inference-pipeline/4.34`
- Prompt 16 SHA-256：`c01f11fb68d846b0fe6a71d42615dfb410d7d9945458f57b2f50748ef61451b5`
- 相对 v51 exact diff：`pipelineVersion`,`profileId`；`EXPERIMENTAL`、hidden candidate、productLive=false。
- A1 summary：`.sdlc/evidence/20260818-151531-image-only-v52-successor/image-only-v52-successor-summary.json`。
