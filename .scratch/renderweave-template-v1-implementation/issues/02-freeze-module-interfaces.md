# 冻结 Template 实施模块 interface 与依赖方向

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: 01

## Question

在已冻结 `renderweave-template`、`renderweave-asset`、`renderweave-rendering` 三个 deep Maven module、`renderweave-app` adapter 边界与独立 Rust process seam 的前提下，精确的编译依赖图、package ownership、跨上下文 closed interface、Host capability、错误类型和测试 seam 应如何划分，才能避免循环依赖、泛化 `common`、领域对象泄漏和未来 JNI/FFI 耦合，并给后续实现提供一份可执行 ADR 与 architecture test？

## Answer

采用 ADR-0041 的 provider-owned contract / consumer-owned external Seam 方案。最终允许的单向图为
Validation→Schema、既有 Inference→Schema+Validation、Template→Schema+Asset、
Rendering→Schema+Validation+Asset+Template、app→已有真实 Adapter/assembly 的全部 Module；Asset 无内部
compile dependency。允许图不是预建未使用依赖的要求：新 artifact 必须携带真实 Implementation 与 executable
tests 才进入 reactor，当前未创建任何空 Template/Asset/Rendering module。

Schema、Validation、Asset、Template 各自拥有向下游发布的 immutable public Interface/type；反向
Asset→Template deletion proof、三个 context-local Host authority facet 与 Java 图外的 RenderEngine 使用
consumer-owned outbound Interface，由 app 提供 production Adapter。新 artifact 的 package ownership 固定为
`<context>.api`、`<context>.spi`、`<context>.internal`，app Adapter 位于 `app.<context>`；禁止 foreign
internal/spi import、split package、`common/shared`、跨上下文 persistence model 和 JNI/JNA/JNR/FFM。

每个 behavioral Interface 拥有 method-specific closed sealed outcome；消费方穷尽映射 provider failure，HTTP/
redaction 只属于 app，不创建 generic Result/Problem。scripted Adapter 必须实现同一正式 SPI、使用 ordered exact
expectations 且证明 exhausted，不得构造 authority-only value 或跳过 admission/canonical/digest/recheck。

本票新增非空转 `TemplateV1ArchitectureTest`，以现存六条真实 Maven edge、全部生产 package/domain source 和
cycle/split/native 三个负向 fixture 验证 graph、ownership 与 capability isolation。唯一历史 split package 已通过把
Validation app Adapter/API test 迁到 `cn.hbads.renderweave.app.validation` 消除，HTTP/Validation domain 合同不变。
Ticket 03 首个真实 Template Module 必须同票追加 non-empty ArchUnit/public-surface anchors。

LF implementation worktree 首次完整 server gate 还揭示旧 visual-eval v1 manifest 绑定 CRLF resource bytes、Git
blob 为 LF 而 checkout policy 未声明；仅以 `.gitattributes` 固定两份 legacy text resource 的 `eol=crlf` 并显式
标记字体 binary，未改 blob、manifest 或 Inference identity。修复后完整 server gate 通过：Schema 20、Validation
13、Inference 361、App 267，见 `.sdlc/evidence/20260817-202707-server/`。详细过程与边界见
`plans/logs/TV1-T02.md`。

本票没有实现 DesignDSL、Template、Asset、Evaluator、Editor 或 Renderer 产品能力，没有新增 API、migration、
route、页面、表、Profile registration 或 native/process wire。Ticket 19 仍 open；Template、Editor、Renderer
仍未 READY。
