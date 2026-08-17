# 11 — 生成 allowlisted synthetic/CC0 本地 visual diff

**Parent:** N9 / R1

**Anchor:** approved successor spec、ADR-0036、commit 19e22854e0be236d0068336a32969356a6befaf8

**What to build:** 建立仅供受控本地评测使用的 visual diff，叠加 gold/predicted box、region、order edge 和 evidence owner，帮助人工定位漏检、错检与顺序错误。

**Blocked by:** 06 — 完成 R0 全矩阵行为等价与 durable recovery 门；07 — 冻结 corpus v2 标注、评测记录与手算 golden 合同；08 — 生成不可变的 60-case 分层 corpus v2。

**Status:** planned

## Acceptance criteria

- [ ] 工具只接受 exact allowlisted corpus identity 和 synthetic/CC0 case。
- [ ] 用户 artifact、真实 live run ID、绝对外部路径、远程 URL 和未知许可素材全部 fail-closed。
- [ ] visual diff 不上传、不联网、不进入普通 gate evidence，也不成为 Web/API 产品表面。
- [ ] 输出和 manifest 明确标记为 local diagnostic，不包含 Prompt、Provider I/O 或 RootDocument。
- [ ] 普通 report/evidence 只能记录生成数量、case ID、identity 和固定 code，不能嵌入图片。
- [ ] 自动生成成功只算 A1；视觉正确性保持 human_review_pending / J0。

## Required gate/evidence

- [ ] allowlist、路径、URL、live-run 和许可 negative tests 通过。
- [ ] synthetic sample render smoke 通过。
- [ ] ordinary-evidence payload scan 通过。
- [ ] 形成 A1 + J0；Provider attempts、reservations、cost 为 0。

## Guardrails

- 不生成用户/live 图片 diff。
- 不写入普通 evidence、监控页、审核页或产品 BlobStore。
- 不新增 Web route、API、数据库表或导航占位。
- visual diff 不能作为 A2 或 J1 的替代物。
