# ADR-0002：封闭 DSL 为事实源，编译产物完全内联，自有验证器为权威

- 状态：accepted
- 日期：2026-08-07
- 关联：AC-008–AC-012

## 背景与约束

RenderWeave 需要比通用 JSON Schema 更窄的类型系统和时间/引用语义，同时需要导出可被外部工具理解的 JSON Schema。若以通用 JSON Schema 为事实源，任意关键字、format 实现差异和外部 `$ref` 会突破产品边界。

## 决策

- `renderweave-schema/1.0` 是封闭、unknown-member-reject 的事实源。
- 根永远 object；只支持 text/decimal/date/time/boolean/reference/array，禁止 nullable、union、inline object、nested array。
- Static publish 自底向上嵌入子 Static 已保存 artifact；完全 inline，不生成 `$id/$defs/$ref`。
- compiled JSON Schema 2020-12 在发布事务中一次生成并保存 exact compact UTF-8，永不重新计算。
- 不能标准表达的 date/time range 等语义放入 `x-renderweave-*`。
- 产品运行时直接解释 DSL；通用 JSON Schema validator 只做互操作辅助测试。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| JSON Schema 直接作为源 | 生态广 | 语言边界过宽、format/precision 不确定 | 无法保证产品语义 |
| `$defs` + internal refs | artifact 更小 | 诊断/嵌套来源更间接 | 用户要求底向上嵌套解析；2 MiB 上限可控 |
| 每次下载重编译 | 可吸收 compiler 修复 | 破坏 Static 不可变与字节审计 | 发布时一次生成即可 |
| 用通用 validator 运行时判定 | 少写代码 | 欠验证扩展语义、numeric/time 差异 | 不足以成为权威 |

## 后果与验证

- 正向：边界清楚、产物自包含、旧 Static 不受 compiler 升级影响。
- 代价：需要自有 parser/validator/compiler；重复 inline 可能触发 2 MiB blocker；父子可含不同 compilerVersion。
- 验证：byte golden、property tests、generic validator interoperability suite（A1/A2）。
- 回退：compiler 版本升级并发布新 Static；不覆盖旧记录。

