# ADR-0050：v50 diagnostic normalization 绑定 canonicalizer implementation

- 状态：accepted
- 日期：2026-08-18
- 决策来源：ticket 34、ADR-0047 standing approval、ADR-0049 successor boundary
- 关联：IOPA-P1-R19、IOPA-P1-R20

## 背景

v50 的因果差异不在输入图片，而在 pipeline 4.32 的 deterministic local-ID canonicalizer。只按图片 SHA、媒体类型、
字节和尺寸生成 fresh normalization，虽然能证明输入不变，却不能证明实际被诊断的 canonicalization seam 未漂移。

## 决策

1. v50 fresh normalization material 除静态 PNG 身份与时间外，还绑定
   `renderweave-image-only-local-id-canonicalizer/1.0` 与 dedicated v50 successor gate 复算出的 exact
   implementation identity。
2. normalization identity 继续作为 manifest 与 exact J1 的必填字段；preflight 在 Provider boundary 前逐字段拒绝缺失或
   漂移。
3. 这些 identity 只描述版本和源码摘要，不包含文件名、路径、图片、OCR、模型 request/response 或 local-ID payload。
4. canonicalizer 或其被绑定实现发生任何变化，都必须新 normalization、manifest、cycle 与 J1；不得复用本 cycle。

## 后果

- diagnostic 能把“同一输入 + exact v50 deterministic seam”作为可重放因果单元。
- dedicated verifier 必须独立重算 normalization、manifest、Profile、Prompt、v49 terminal digests 与 OPEN count。
- 该 ADR 不构成 paid wildcard；实际调用仍只能使用 ticket 34 形成的 exact、短时、单 run J1。
