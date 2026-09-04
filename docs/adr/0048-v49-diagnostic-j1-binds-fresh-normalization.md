# ADR-0048：v49 diagnostic J1 显式绑定 fresh normalization identity

- 状态：accepted
- 日期：2026-08-18
- 决策来源：IOPA-P1-R14 ticket 29 与 ADR-0047 standing approval
- 关联：ADR-0040、ADR-0047、IOPA-P1-R14/R15

## 背景与约束

v47/v48 diagnostic 的 manifest identity 已间接包含 normalization identity，但旧 authorization JSON 没有独立字段。
ticket 29 要求 v49 的 paid boundary 逐字段绑定 Profile、manifest、evaluator、normalization、route、数据、case、caps
与时间。历史 CLOSED JSON 和其 SHA-256 又必须保持 bytes 不变，不能用补字段的方式回写。

## 决策

1. `normalizationIdentity` 成为 authorization codec 的可识别字段；v49 successor diagnostic 在 preflight 与 runner
   中必须显式存在并逐字等于 frozen manifest 的 fresh normalization identity。
2. v47/v48 已关闭 authorization 继续允许按原 bytes 读取，缺失字段表示 legacy envelope；不得重写、重签或借此
   再次 OPEN。若 legacy record 自带该字段，也必须与 manifest 精确一致。
3. v49 preparation 只记录静态 PNG 的 case ID、SHA-256、media type、encoded bytes、width/height 与 normalization
   identity；不记录文件名、路径、图片、OCR 或模型 payload。
4. normalization identity 漂移、缺失、未知 JSON 字段、Profile/manifest/evaluator/route/case/cap/time 漂移都必须在
   Provider reservation 与外传前 fail closed。

## 后果与验证

- 新 v49 exact J1 比历史 envelope 多一个可审计字段；manifest 间接绑定仍保留，形成双重一致性检查。
- schema 使用 successor-only conditional requirement，因此不改变历史 CLOSED 文件 bytes/digest。
- Java codec/preflight 负测、独立 verifier、one-shot runner preflight 与 Testcontainers closure path 共同验证；所有
  Provider-zero gate 必须保持 attempts/reservations/model tokens/cost/key reads 为 0。
- 回退只能停止 v49 live；不能删除字段检查后继续外传，也不能修改历史 authorization 来伪造兼容。
