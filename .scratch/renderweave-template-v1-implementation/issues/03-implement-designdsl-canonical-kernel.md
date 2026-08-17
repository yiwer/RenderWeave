# 实现 DesignDSL canonical kernel 与独立 replay

Type: task
Status: open
Blocked by: 01, 02

## Question

如何以测试驱动方式实现首个真实产品 kernel：通过单一 `DesignDslAuthority.admit(rawUtf8)` closed union，对 `system-empty@v1` 与单 Canvas 输入执行 strict parse、预算、metadata normalization、set ordering、canonical encoding 和 domain-separated hash，同时让 Java primary 与独立 Python verifier 重放 exact vectors，并把新 `template` gate 纳入 `full`？本票不得访问 DB、网络、StaticSchema、Asset 或 Template，不创建 API/UI/Renderer，也不得把部分语义注册成可用的 `renderweave-design/1.0` Profile。
