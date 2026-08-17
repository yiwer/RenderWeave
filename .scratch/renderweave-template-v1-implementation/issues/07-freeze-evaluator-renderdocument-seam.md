# 冻结 Evaluator 与 RenderDocument 产品 seam

Type: grilling
Status: open
Blocked by: 03, 04, 05

## Question

在 Template 与 Asset deep interfaces 稳定后，Evaluator 应如何拥有 closure snapshot、admitted input、Expression/capability materialization、Asset occurrence resolution、lowering、atomic seal 和诊断 sidecar，并通过一个 closed、跨语言可 canonicalize 的 RenderDocument/Command interface 交给 Renderer，确保动态语义不泄漏到 Renderer、RenderDocument 不被公开或跨请求复用、失败无 partial output，且不会误用现有 inference evaluator 或 synthetic rasterizer？
