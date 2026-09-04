# 31 — IOPA-P1-R16：冻结 v50 lossless local-ID canonicalization recovery

**What to build:** 把 v49 immutable mixed-set negative terminal 转成一份 successor-only、Provider-zero 的恢复合同：
在严格 visual-grounding validator 前只对模型内部 local identifier 做可逆关系保持的确定性 canonicalization，避免把
纯词法 ID 失败反复交给模型，同时不修补坐标、层级、multiplicity、evidence、semantic name 或 Candidate 内容。

**Blocked by:** None — ticket 30 immutable terminal 是输入，不是可修改 blocker。

**Status:** resolved

- [x] 冻结 v49 Profile、CLOSED authorization、terminal/live summary 与 post-close evidence digests；确认 OPEN=0。
- [x] 只依据 payload-free fixed-code sets 与 classifier 实现确定事实：主导重复 set 来自 regionId、parentRegionId、
  repeatGroupId 的 local-ID lexical validation；不得猜测原始 token、字段值、坐标或业务语义。
- [x] canonicalizer 只允许映射 regionId、elementId、parentRegionId、repeatGroupId、element.regionIds；所有其他 JSON
  bytes/values 在 semantic tree 上保持相同，随后仍由现有 RenderWeave validator 全量验证。
- [x] region/element declaration token 必须非空且各自唯一；parent/element region reference 必须指向唯一已声明
  region；未知、重复、歧义、类型错误或结构错误 fail closed，不做猜测。
- [x] 按数组顺序生成 bounded `r1..r32`、`e1..e32`；repeat-group equality classes 按首次出现生成 `g1..g32`；
  只保持 equality/reference graph，不依据 token 文本排序、hash、记录或命名。
- [x] v50 才 opt in；v49 及更早 pipeline/profile/prompt bytes 与失败语义不变。v50 相对 v49 只改 profileId、
  pipelineVersion=`renderweave-inference-pipeline/4.32`、elementPromptVersion=`renderweave-visual-elements-prompt/16.0`。
- [x] Prompt 16 将模型 token 定义为 stage-local opaque correlation labels，要求引用一致，并明确 adapter 会 canonicalize；
  不包含失败 artifact 的 token、坐标、值、领域词或 response 片段。
- [x] canonicalizer 不进入日志、attempt/evidence 或 review payload 的 raw-ID 投影；只允许固定 outcome code、计数与
  implementation identity。
- [x] fresh v50 diagnostic 继续使用同一 regression probe，但必须新 normalization/manifest/cycle/exact J1/ledger；caps
  不宽于 1 run/5 calls/100k tokens/¥3/per-run 5+¥3/≤2h，non-scoring、0 credit、无自动 rerun。
- [x] 本 source decision Provider usage=0，不创建 OPEN authorization、不 apply/publish/deploy/commit/push。

## Decision frontier

### Q1 — deterministic lexical seam

是否把重复的 local-ID lexical failure 从 LLM correction loop 移到 successor-only deterministic canonicalizer，且只保持
声明顺序、equality 与 references，不修复任何结构/几何/语义错误？

**推荐：是。** v49 的相同三字段 set 已第三次失败；这三个 fixed code 在 classifier 中只由 `localId` 词法验证产生。
把内部标签标准化是比继续提示模型更窄、可证明且不泄漏 payload 的恢复。

### Q2 — ambiguity policy

是否对 duplicate/null/blank declaration、dangling/ambiguous parent 或 element region reference、非字符串 shape 一律
fail closed，而不是按位置猜 parent、删除 reference 或创造 region？

**推荐：是。** canonicalization 只能重命名，不能改变图；无法证明关系等价时必须退出。

### Q3 — exact successor boundary

是否创建 hidden v50，pipeline 4.32 + Prompt 16，相对 v49 Profile 只改三 identity fields，其余 route/model/output/
calls/cost/time/OCR/Candidate/evaluator/pricing 全部相同？

**推荐：是。** pre-validation semantics 已改变，不能 patch/rerun v49。

### Q4 — fresh diagnostic boundary

是否继续单案 non-scoring regression probe 与相同窄 caps，但使用全新 cycle/identities/exact J1；只有
`REVIEW_REQUIRED` 才允许另拆 scoring 票，失败继续形成新 immutable terminal？

**推荐：是。** 保持因果可比性，同时不复用 CLOSED v49 authority。

## Answer

所有者于 `2026-08-18T10:23:09+08:00` 启动持续 Goal，并指示“所有授权都批准，决策都按你的推荐[落 ADR]”；
ADR-0042 将该 standing approval 限定为 Wayfinder 内推荐决策与逐 stage exact authority 的来源。ticket 30 产生新的
不可变失败事实后，执行者按 Blueprint 附录 A 开本票并采用 Q1–Q4 推荐；ADR-0044 记录边界。该接受不构成 paid
wildcard J1：本票只批准 Provider-zero v50 recovery contract，live 仍须在 exact identities 形成后实例化短时 JSON。
