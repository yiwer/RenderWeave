# ADR-0041：以 provider-owned contracts 和 app adapters 隔离 Template v1 模块

- 状态：accepted
- 日期：2026-08-17
- 决策来源：Template v1 implementation Wayfinder Ticket 02，经用户逐项采用推荐方案
- 关联：ADR-0003、TV1-T02、冻结 checkpoint `0b485f4a13de9d754a81d07f464730776e13c14b`

## 背景与约束

Template v1 新增 Template Design、Asset Management 与 Rendering 三个 bounded context。它们在 Java modular
monolith 中分别落入 `renderweave-template`、`renderweave-asset` 与 `renderweave-rendering` deep Maven
module；Spring、HTTP、JDBC、文件、宿主授权和进程启动仍属于 `renderweave-app` Adapter。Renderer 是独立
Rust executable，不进入 Maven 图，也不得通过 JNI、JNA、JNR 或 Java FFM 与领域 Module 耦合。

冻结语义同时形成两条方向相反的运行时协作：Template 保存 Asset-owned `AssetRef`，而 Asset 删除前又要查询
Template-owned `AssetReferenceAuthority`。若两个 artifact 直接互相依赖就会形成 Maven cycle；若把身份、错误
或授权塞进 `common`，则会丢失 bounded-context ownership。Rendering 又必须消费 Template closure、精确
Validation proof 与 Asset resolution，但不能取得这些上下文的 aggregate、repository 或 persistence record。

当前 reactor 只有 Schema、Validation、Inference 与 app。Ticket 03 才会加入首个真实 Template kernel；本票
不得以空 module、marker class、placeholder Interface 或未使用 dependency 假装未来拓扑已经存在。

## 决策

### 1. 编译依赖只有一个允许的方向

箭头表示左侧 artifact 可以直接 compile-depend on 右侧 artifact：

```text
renderweave-schema      → ∅
renderweave-validation  → renderweave-schema
renderweave-inference   → renderweave-schema, renderweave-validation
renderweave-asset       → ∅
renderweave-template    → renderweave-schema, renderweave-asset
renderweave-rendering   → renderweave-schema, renderweave-validation,
                          renderweave-asset, renderweave-template
renderweave-app         → every Module for which it owns a real Adapter or assembly
```

这是允许边的完整上界，不是提前声明未使用 dependency 的要求。一个新 artifact 只有在同一变更已有真实
Implementation 和 executable tests 时才进入 reactor；一条边只有在源码真实命名所依赖的 owning contract 时才
进入 POM。到完整纵切形成时，app 将依赖全部三个新 artifact；kernel-only 阶段不得为了最终图预建 app wiring。
任何已声明边必须是 non-optional compile edge，不能借 test-jar、runtime、provided 或传递依赖绕过。reactor 顺序
必须是实际图的拓扑序，app 保持最后的叶 artifact。

当源码直接使用 `StaticSchemaRef` 等 foreign-owned type 时必须声明直接 dependency，不能依赖传递可见性。既有
Inference→Validation 边保持不变；本 ADR 不重写 Schema/Inference v1 历史。

### 2. 正向语义由 provider-owned Interface 发布

同方向的不可变语义合同由提供事实的 Module 拥有，消费方直接依赖其 public Interface：

- Schema 继续独占 `StaticSchemaRef` 与不可变 Schema identity。
- Asset 独占 `AssetRef`、`AssetResolver`、`ResolvedAsset` 及其 closed resolution outcome。
- Template 独占 `DesignDslAuthority`、Template/closure snapshot 与 `AssetReferenceAuthority`。
- Validation 独占 exact-Schema RootDocument validation Interface、closed typed view/proof 与 validation problem；
  Rendering 独占在 Validation 和 Custom/Asset admission 全部完成后形成的 `AdmittedRenderInput`。
- Rendering 独占 Evaluator、`RenderResource`、RenderDocument、Renderer Command、RenderOutput 及最终
  Rendering problem。

跨 artifact 只能 import provider 的 `cn.hbads.renderweave.<context>.api` package。不得复制 provider identity、
返回 aggregate、store、JDBC row、Spring type 或 import foreign `.internal`/`.spi`。Java package 不添加
`v1` 后缀；Maven artifact version 管理 in-process compatibility，DesignDSL、RenderDSL、Command 与 process
wire 继续使用各自 exact Profile identity。需要两个 Java Interface generation 并存时必须另立 ADR。

每个新 artifact 使用以下 ownership：

```text
cn.hbads.renderweave.<context>.api       provider-facing Interface + immutable public types
cn.hbads.renderweave.<context>.spi       consumer-owned outbound Interface + its closed query/result
cn.hbads.renderweave.<context>.internal  module-owned Implementation
cn.hbads.renderweave.app.<context>       production Adapter, HTTP/JDBC/process wiring and assembly
```

`.internal` 永不跨 artifact import；`.spi` 只由所属 Module 的 Implementation 消费、由 app Adapter 实现。
不创建 `common`、`shared`、泛型全局 ID、泛型 capability registry 或跨上下文 persistence model。一个 Maven
artifact 可以容纳多个 codebase-design Module，但每个 Module 对调用者只呈现一个 coherent Interface。

### 3. 反向协作与外部能力使用 consumer-owned Seam

只有依赖方向与语义提供方向相反，或 provider 位于 Java 图外时，使用 consumer-owned outbound Interface：

- Asset 拥有窄 `AssetReferencePort`；app Adapter 穷尽式调用 Template-owned
  `AssetReferenceAuthority` 并转换 proof/result。Asset 不依赖 Template artifact。
- Template、Asset、Rendering 各自拥有 operation-specific Host authority facet；app 的三个 Adapter 共同委托
  唯一可信宿主授权后端。请求 DTO 不得携带 `ownerScope`、capability set、role、raw token 或“已授权”布尔值。
- Rendering 拥有 `RenderEngine` outbound Interface；app 提供 production process Adapter，测试提供 scripted
  Adapter。process framing、codec、deadline 与 exact wire 由 Ticket 08 冻结，本 ADR 不发明它们。

Host facet 只接收 app 从可信 Gateway context 建立的 opaque invocation reference 和上下文本地 operation，返回
context-local owner scope、closed visibility decision 与 opaque recheck identity。领域 Implementation 在 mutation、
confirmation、queue admission、execution/output seal 等自己的线性化点重新询问，而不是信任 Controller 预检。
各 capability 互不蕴含；capability 名只在 app Adapter 映射，永不进入 DesignDSL、RenderInput、RenderDocument
或 Renderer Command。production 缺少真实 Host Adapter 时 assembly 必须 fail closed，并继续阻止 READY。

### 4. 结果和错误由 Interface owner 封闭

每个 behavioral Interface 返回由 owning Module 定义的 method-specific sealed success/failure union。expected
domain failure 不以异常穿越 Interface；`SQLException`、`IOException`、Spring exception、raw stderr 或 provider
problem 也不得泄漏。禁止全局 `Result<T,E>`、generic `Problem`、`Map<String,Object>` 扩展袋和可供客户端解析的
自由文本语义。

跨上下文消费方必须用无 `default` 的穷尽映射把 provider outcome 转成自己拥有的 failure。Rendering 对外不返回
Template/Validation/Asset problem。HTTP status、RFC problem envelope、redaction 和 safe message 属于 app
Adapter；每个 domain code 必须恰有一个穷尽映射，hidden/cross-scope 与 visible-forbidden 分别保持冻结的
NOT_FOUND/FORBIDDEN 语义。

### 5. 测试 Adapter 不能形成旁路

每个 outbound Interface 至少有 app-owned production Adapter 和 consumer Module test scope 的 scripted Adapter。
script 是 ordered exact expectation queue：unexpected/mismatched call 立即失败，结束必须证明 exhausted。测试只
通过正式 public Interface 驱动 Implementation；scripted Adapter 不得构造仅生产 authority 才能签发的 admitted/
sealed value，也不得跳过 canonical codec、admission、digest、recheck 或 terminal validation。测试类不得进入 main
artifact，不建立共享 test-fixture Module。

### 6. Architecture gate 分阶段但不空转

Ticket 02 在现存 app test scope 加入 `TemplateV1ArchitectureTest`：

1. 解析真实 root/child POM，锁定既有六条内部 compile edge、允许边上界、non-optional compile scope、拓扑序、
   app leaf、无环和 staged Module 的 production/test source 非空。
2. 扫描全部 production Java package，要求 exact package 只有一个 artifact owner，并禁止 `common/shared`。
3. 扫描全部现存 domain source，禁止 app/Spring/Servlet/JDBC/JPA/process/JNI/JNA/JNR/FFM capability。
4. 对未来三个 package root 预装 foreign `.api`-only 与 app 不占 domain package 的规则。
5. 使用 synthetic cycle、split package 和 native method fixtures 证明三个 guard 会真实失败。

为使 package ownership 零例外，本票把 app 的 Validation HTTP/JDBC/assembly Adapter 从历史 split package
`cn.hbads.renderweave.validation` 移到 `cn.hbads.renderweave.app.validation`，不改变 Validation domain Interface
或 HTTP 合同。

Ticket 03 在 `renderweave-template` 首个真实 Interface、Implementation 和 scripted Adapter 同票出现时，必须追加
`allowEmptyShould(false)` 的 ArchUnit anchors 和 exact public-surface reflection tests；后续 Asset/Rendering 首个真实
纵切依同一规则加入。不得创建空 artifact、marker class 或 placeholder Interface 让未来规则假绿。

## 备选方案

| 方案 | 优点 | 未选择原因 |
| --- | --- | --- |
| 所有行为关系都使用 consumer-owned port + app bridge | artifact 可独立替换，未来拆进程较容易 | 同 JVM 内为 Template/Validation/Asset 的稳定语义复制 DTO、problem 与 Adapter，映射量大且易漂移 |
| Rendering 只依赖 Schema，其他全部经自己的 outbound ports | Rendering compile fan-in 最小 | 隐藏真实语义依赖，也使 app 聚集三套 total translation，降低 locality |
| Asset 与 Template 双向直接依赖 | 调用最直接 | 形成 Maven cycle，无法保持 artifact 独立构建 |
| 新建 Host/common contract artifact | ownerScope Java type 可共享 | 把宿主 identity/capability 变成第四个泛化共享中心，诱发领域对象与权限集合泄漏 |
| Controller 预授权后把 boolean/scope 传给领域 | 接口数量少 | 无法在领域线性化点重检，易受授权漂移和调用方自报污染 |
| 现在创建三个空 module 再运行 ArchUnit | 目标 package 立即存在 | 测试只会对空集合通过，并违反 no-placeholder 与真实纵切纪律 |
| Java 直接绑定 Rust/JNI/FFI | 单进程调用开销较小 | native ABI、内存安全和平台构建细节会污染领域 Interface，破坏独立认证 seam |

## 后果与验证

- 正向后果：最终 Maven 图严格无环；`AssetRef`、closure、validation proof、RenderDocument 各由正确上下文拥有；
  app 只持有可替换 Adapter/assembly，Rust build 与 Java domain 没有 ABI 耦合。
- 代价：Asset→Template、三个 Host facet 和 RenderEngine process 均需要显式 outbound Interface 与 app Adapter；
  每个上下文也需自己的 closed problem 与 HTTP mapper。
- 当前自动证据只能证明现存源码和 POM 遵守 policy、package split 已消除且负向 guard 有效；它不证明未来
  Template/Asset/Rendering Interface 已实现，也不证明产品行为、浏览器体验或 Renderer process。
- Ticket 19 保持 open；Template、Editor、Renderer 不因本 ADR 或 architecture test 晋级 READY。
