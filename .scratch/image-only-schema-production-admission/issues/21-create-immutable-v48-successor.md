# 21 — IOPA-P1-R06：创建 immutable v48 successor

**What to build:** 创建一个可被精确授权、但默认不可 live 的 immutable v48 successor，把票 18–20 批准并验证的 region recovery 行为绑定到新的 Profile/Prompt/pipeline/normalization identities。任何人都能从 canonical bytes 重算 exact identity，并确认它没有继承或改写 v47 的失败状态。

**Blocked by:** 20 — IOPA-P1-R05：实现受限 region correction vertical slice。

**Status:** resolved

- [x] 创建 dashscope-qwen38-max-product-v48-hybrid-generic 的 canonical immutable resource；Profile SHA-256 必须从最终实际 bytes 计算，不得预写或从 v47 推导。
- [x] 仅绑定票 18 批准的新 Prompt/pipeline/normalization identities；每项 identity 都可由 canonical bytes 独立重算并在 registry、runtime snapshot 和证据中逐字符一致。
- [x] exact provider/model/base URL、8192 maximum output tokens、12-call/¥6 run aggregate hard cap、OCR capability、Candidate contract、pricing snapshot 与 evaluator threshold 保持 v47 的已冻结值。
- [x] 生成 v47→v48 的 payload-free byte/semantic diff，证明变化仅限获批 successor recovery contract；v47 resource、hash、cycle、authorization、ledger 和 terminal 均未修改。
- [x] v48 保持 hidden、ungranted、uncertified，不成为 ACTIVE_EXPERIMENTAL 基线，不签发 ProfileCertificationRecord，也不解锁后续生产阶段。
- [x] identity mismatch、registry drift、缺失 manifest/evaluator 绑定或无 J1 时 runtime 必须在 Provider permit 前拒绝。
- [x] fake-provider、identity、registry、contract、Testcontainers ledger 及受影响 fast/server/runtime provider-zero gates 全绿，provider attempts 为 0。
- [x] 形成 payload-free A1 实现证据并更新实施状态；不创建 OPEN authorization、不调用 Provider、不 apply Candidate、不发布 StaticSchema。

## Evidence

- 2026-08-18：创建 `dashscope-qwen38-max-product-v48-hybrid-generic`；canonical SHA-256=`22f40ef4c865e11778eef4558c20c383e6611e068d8d08be0d080650074d4470`。
- 独立 byte/semantic replay 证明 v47→v48 只改 `profileId`、`pipelineVersion=renderweave-inference-pipeline/4.30`、`elementPromptVersion=renderweave-visual-elements-prompt/14.0`；v47 CLOSED authorization/terminal digests 未变。
- Registry/Profile/Prompt/预算/认证测试全绿；v48 hidden、EXPERIMENTAL、ungranted、非 product-live。A1：`.sdlc/evidence/20260818-015529-image-only-v48-successor/`，Provider usage=0。
