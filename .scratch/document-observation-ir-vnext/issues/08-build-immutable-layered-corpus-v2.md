# 08 — 生成不可变的 60-case 分层 corpus v2

**Parent:** N9 / R1

**Anchor:** approved successor spec、ADR-0036、commit c12f23d76a6fc76a6a38042ff89bbd166e6012b5

**What to build:** 建立完整的 renderweave-visual-stage-corpus/2.0，保持 45 DEV + 15 HOLDOUT，并为每个相关 case 提供完整感知层到 Candidate 层的分层 gold。

**Blocked by:** 06 — 完成 R0 全矩阵行为等价与 durable recovery 门；07 — 冻结 corpus v2 标注、评测记录与手算 golden 合同。

**Status:** planned

## Acceptance criteria

- [ ] corpus 恰好包含 60 cases、45 DEV、15 HOLDOUT；domain、difficulty、render identity 和 partition 稳定。
- [ ] 所有要求的 OCR、layout、order、repeat、evidence-owner、semantic 和 Candidate 标注满足 closure。
- [ ] evidence owner、precedence、repeat membership、entity graph、relationship 和 binding 不可悬空、重复或成环。
- [ ] deterministic render 与 annotation identity byte-stable。
- [ ] 只使用仓库自制、确定性 synthetic 或许可已审查的 CC0 素材。
- [ ] 不吸收用户图片、真实业务数据或历史 live payload。
- [ ] corpus 1.0、旧 report 和旧 EvaluationIdentity bytes 保持不变。
- [ ] HOLDOUT 针对性修复必须迁入 DEV 并补新 HOLDOUT，不能在原 identity 下调参。

## Required gate/evidence

- [ ] corpus count、partition、closure、duplicate 和 tamper verifier 通过。
- [ ] deterministic render/hash replay 通过。
- [ ] license/source inventory review 完成。
- [ ] 形成 A1；最终独立 A2 留到 ticket 12；Provider attempts、reservations、cost 为 0。

## Guardrails

- 不修改 corpus 1.0，不借用旧报告或 live 结论。
- 普通 evidence 只保存 case ID、identity、计数和固定 code，不嵌入图片或 OCR text。
- 不使用用户、客户或真实业务数据。
- corpus v2 完成不构成产品质量晋级。
