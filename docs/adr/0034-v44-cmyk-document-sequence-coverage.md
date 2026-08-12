# ADR-0034：v44 CMYK 解码与文档序列覆盖门

- 状态：Accepted
- 日期：2026-08-12
- 决策来源：用户要求诊断并修复指定 live run 中嵌套重复结构整体丢失的问题
- 关联：ADR-0026、ADR-0028、ADR-0031、ADR-0033、P6/T6-5/N7

## 问题

run `aafca06e-fc65-42c3-9253-1bd48c4daf69` 产生了合法但不完整的 ROOT-only Candidate：中部有序重复列表
没有 child Schema 或 array reference。回放显示 OBSERVE 仅产生 SLOT/ONE，HIERARCHY 与 materializer 从未获得
MANY relationship。输入是 CMYK JPEG；RapidOCR 的 bytes 路径经 PIL 通用四通道处理时把 K 当作 alpha，
本地观察为 0 行。直接使用三通道 BGR 解码可恢复 35 行有界观察，证明丢失发生在 OCR 边界而非
Schema DSL 或 Candidate materializer。

## 决策

1. `rapidocr_adapter.py` 先用 OpenCV `IMREAD_COLOR` 把所有已接受图片解码为显式 BGR，并校验三通道与
   申报尺寸。不更改 artifact 持久化、capability ID、网络禁用或 payload 日志边界。
2. 新增 immutable product-v44 Profile 和 Prompt 12；v43 保留为历史 snapshot。v44 的模型、价格、调用、
   费用、时限、输出、tools/remote-media 与数据分类边界全部继承 v43。
3. 在 OBSERVE 严格解码后增加 payload-free 覆盖检查。只当单图至少 8 个非 LOW OCR box 同时满足竖长、
   中心纵向离散有界、水平跨度至少画布的 40%，且 inventory 缺失有效
   `GROUP/MANY → REPEATED_GROUP` 时，返回
   `VISUAL_SEMANTIC_OBSERVE_DOCUMENT_SEQUENCE_GROUP_MISSING` 并仅重试 OBSERVE。
4. 覆盖检查不读取 OCR 文本、不匹配业务词、不自动创建区域/字段/数组；若模型不能产生符合
   现有 grounding 合同的重复结构，run 应当 fail-closed，不再静默接受虚假扁平 Candidate。

## 权衡与回退

这个门可能让少数密集竖排文字但非重复数据的图片多消耗一次 OBSERVE；阈值因此要求数量、方向、
纵向对齐与水平跨度同时成立，且已有合法重复结构时立即通过。回退时停止 live 并把新建产品目录切回
v43；已创建 v44 run 仍必须按不可变 snapshot 恢复。

## 验证状态

- adapter 回归：先证明原实现把 encoded bytes 直接交给 OCR，修复后证明交给 OCR 的是已解码 BGR 矩阵。
- semantic verifier 回归：密集强序列 + ROOT-only 必须拒绝；稀疏文字或已有合法重复结构必须通过。
- 发布门、固定 revision/Profile snapshot/evaluation identity 和指定用户图的 live 结构验证记录在
  `plans/logs/P6-T6-5-N7.md`。v44 继续为 `EXPERIMENTAL`，本修复不完成 final 60 或全局 N7。
