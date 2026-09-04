# 28 — IOPA-P1-R13：创建 immutable v49 successor

**What to build:** 用一份新的 hidden experimental Inference Profile 承载票 24 批准、票 27 已验证的 mixed-region recovery semantics，让注册表、Prompt/pipeline 选择、预算、认证禁令和独立 verifier 对 exact v49 identity 达成一致，同时保持 v48 及全部历史失败对象不可变。

**Blocked by:** 27 — IOPA-P1-R12：实现 mixed-field bounded correction 与 breaker。

**Status:** resolved

- [x] 创建票 24 批准的 exact v49 Profile、pipeline 和 element Prompt identities；canonical SHA-256 必须从最终资源 bytes 重算，不手填猜测。
- [x] v49 相对 v48 只允许改变 `profileId`、`pipelineVersion`、`elementPromptVersion`；provider/model/base URL、8192 output tokens、12 calls/¥6、360s、OCR、其他 Prompts、Candidate/evaluator/pricing 均逐字段相同。
- [x] v49 只对批准的 mixed/unclassified policy opt in；v48 及更早 Profile 对相同 synthetic 输入保持历史 fixed code、retry 与 breaker 行为。
- [x] 新 Prompt 明确覆盖 approved primary/detail codes、bounded correction 和禁止项，且不包含特定业务域、样例 payload 或动态异常文本。
- [x] v49 保持 hidden、experimental、ungranted、uncertified、非 product-live；不得加入 Product Profile catalog 或产生 certification record。
- [x] 独立 verifier 锁定 v46/v47/v48 Profile bytes/hash、所有 CLOSED J1/ledger/terminal/evidence digests，并重算 v48→v49 three-field diff 与新 identity。
- [x] registry、Prompt、codec/policy、worker、authorization/profile prohibition 和兼容性回归全绿；任何 identity drift fail closed。
- [x] provider-zero A1 evidence 只记录 identities、diff、fixed codes、计数和状态；Provider usage=0、OPEN authorization=0，未 apply/publish。

## Evidence

- exact v49 Profile=`dashscope-qwen38-max-product-v49-hybrid-generic`；canonical SHA-256=
  `acffdd4dd56ca2f1f7260fc5d37aa48ca3da488a0ae2718f2095bf1530e86eaf`。Prompt 15 raw SHA-256=
  `107edf6a5a2abf31e718fdc8245b640ec251a5dab9a496f502e38bbf396ceacf`。
- A1 dedicated gate：`.sdlc/evidence/20260818-111558-image-only-v49-successor/`，result=`PASS`；独立
  verifier 重放 v46/v47/v48 hashes、CLOSED J1/terminal/live-summary digests，并证明 v48→v49 仅三字段变化。
- 专用测试：inference 87/87、Testcontainers PostgreSQL 10/10；fast
  `.sdlc/evidence/20260818-111730-fast/` PASS。首轮 server
  `.sdlc/evidence/20260818-111759-server/` 如实保留为 stale migration-count assertion 的 negative evidence；
  修正 V020 count 后 focused 1/1 与 server `.sdlc/evidence/20260818-113122-server/` PASS（20/13/388/285，
  0 failures/errors）。
- v49 仍 hidden、EXPERIMENTAL、ungranted、非 product-live；OPEN=0、Provider/key usage=0，未 apply/publish/
  deploy，未 commit/push。
