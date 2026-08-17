# 冻结 Asset admission 与 resolution 首个增量

Type: grilling
Status: open
Blocked by: 01, 02

## Question

Asset、immutable contentVersion、scope-local content addressing、IMAGE/FONT admission、logical current、soft delete/restore 与 `AssetRef → ResolvedAsset → RenderResource` 应如何收成最小 deep interface，使普通产品读取、Evaluator occurrence resolution 和 Renderer-only lease 各自只获得所需能力，并且不复用 inference-only `BlobStore` 语义、不泄露路径/token/hash、不引入外部 URL 或占位 persistence？
