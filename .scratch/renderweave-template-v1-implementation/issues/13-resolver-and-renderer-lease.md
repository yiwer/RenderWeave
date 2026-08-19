# 实现 AssetResolver 与 Renderer-only fetch lease 纵切

Type: task
Status: open
Blocked by: 05, 07, 08, 11

## Question

如何按 ADR-0043 与冻结规格物化 `AssetResolver`：closed 输入 `renderRequestId/ownerScope/resourceId/assetId/
expectedKind/rendererAudience/renderDeadline`，在单次线性化事务内检查 ownerScope/存在/ACTIVE/immutable kind、
读取 current contentVersion 与不可变 descriptor，提交 `(renderRequestId, resourceId)` 短期幂等记录（同 key 同
fingerprint 重放、异输入冲突、提交后绝不重选 current）并签发 Renderer-only fetch lease；fetch URL 由
Asset-owned consumer `AssetFetchEndpoint` Port 物化（app Adapter 校验 claims、签发短时签名 URL、流式供给并
验证 sha256/length），ResolvedAsset 请求内携带，Rendering 原样投影 RenderResource，URL 不入 digest/日志/
持久化，Engine 只对 app origin 拉取？本票与 T07（Evaluator/RenderDocument seam）和 T08（Rust Renderer
protocol）同批接线，被 T07/T08/T11 阻塞；不实现 Editor preview 接口或任何公开下载 route。
