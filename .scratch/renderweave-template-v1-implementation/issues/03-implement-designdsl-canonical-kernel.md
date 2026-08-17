# 实现 DesignDSL canonical kernel 与独立 replay

Type: task
Status: resolved
Claimed by: Codex /root
Blocked by: 01, 02

## Question

如何以测试驱动方式实现首个真实产品 kernel：通过单一 `DesignDslAuthority.admit(rawUtf8)` closed union，对 `system-empty@v1` 与单 Canvas 输入执行 strict parse、预算、metadata normalization、set ordering、canonical encoding 和 domain-separated hash，同时让 Java primary 与独立 Python verifier 重放 exact vectors，并把新 `template` gate 纳入 `full`？本票不得访问 DB、网络、StaticSchema、Asset 或 Template，不创建 API/UI/Renderer，也不得把部分语义注册成可用的 `renderweave-design/1.0` Profile。

## Answer

新增真实 `renderweave-template` Maven artifact，但当前不声明任何仓库内部 compile edge；其唯一 public top-level
Interface 是 `DesignDslAuthority`，只暴露 `admit(byte[] rawUtf8)`，返回 `Admitted` 或 `Rejected` sealed
outcome。Implementation、strict JSON model/parser、canonical writer 与 domain hash 全部隐藏在 `.internal`；没有
Spring、JDBC、HTTP、文件、process、StaticSchema、Asset、Template aggregate 或外部 dependency I/O。

当前 kernel 只承认冻结的最小原子：exact `renderweave-design/1.0` / `renderweave-expression/1.0` envelope、合法
root metadata、空 `definitions`、唯一 Canvas、可选 Canvas displayName/uppercase RGBA/完整 bleed，以及空
`bindings`/`children`。它不填充业务 default；non-empty set-like `definitions`/`bindings` 和 semantic
`children` 全部以 `DESIGN_KERNEL_SCOPE_UNSUPPORTED` fail closed，因此没有以未知 Definition/Binding/Node wire
猜测排序或建立 partial Profile。`system-empty@v1` 只冻结 vector 的外部 authority context，并未传给或被产品
kernel 读取。

strict parser 在建模前拒绝 BOM、非法 UTF-8、duplicate、malformed JSON，并逐项实施 16 MiB raw、depth 64、
object 1,024、array 100,000、total 1,000,000、string 1 MiB、member name 256 bytes、number token 256 bytes
预算。metadata 只 trim 且不做 Unicode normalization；decimal 按任意精度数值规范化，`-0` 写为 `0`；object
member 按 unsigned UTF-8 排序，string 不转义 slash；canonical writer 先经 16 MiB capped counting sink，再
分配 exact output。内容 hash 固定为 `SHA-256("renderweave-design-content/1\\0" || canonicalUtf8)`。

冻结 manifest 共有 33 cases：success bytes/hash、member order、Unicode、metadata、decimal、`-0`、颜色、
duplicate/UTF-8/BOM/malformed/unknown/null/version、non-empty set/semantic array fail-closed、deterministic first
pointer，以及九个 parser/canonical limits 的 at/above 向量。Java primary 只经正式 `admit` Seam 执行；独立
Python scanner/model/canonical writer 重放同一 manifest 并逐 case 对照 Java report。`template` gate 现按
repository diff → kernel Java/Python → 原 Editor/SPEC_REGISTRY static replay 执行，`full` 含相同步骤。

ADR-0041 要求的 non-empty ArchUnit anchors 与 exact public-surface reflection 同票加入：锁定 API/internal
ownership、单一 top-level Interface、closed enums/record shape，并禁止 domain 依赖 app/Spring/JDBC/native/
process。没有 outbound SPI，故本票不存在可合法创建的 scripted Adapter；测试直接驱动真实 public Interface，
不创建 test-only authority 或 bypass。

本票只形成自动证据，不注册 DesignDSL Profile available，不新增 app wiring/API/migration/table/route/page，
不运行 DB、网络、浏览器、Web 服务、provider 或 J1。Ticket 19 继续 open；Template、Editor、Renderer 仍未
READY。
