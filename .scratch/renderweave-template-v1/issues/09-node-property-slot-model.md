# 定义封闭的节点、属性与 BindingPolicy 模型

Type: grilling
Status: open
Blocked by: 07, 08

## Question

首版叶元素与结构容器的 kind 集合是什么；每个 kind 的静态属性、可绑定目标、类型、默认值、子节点能力和验证规则由哪个全局单一合同权威定义；如何以只追加 BindingPolicy 扩展可绑定属性而不重复旧系统中属性语义分散的问题？

## Inherited constraints

- Node kind 与属性模型对所有 Template 全局固定；Template 只能实例化和填写，不能定义属性 shape、ValueType、validation、bindability 或 enum catalog，也不开放运行时插件注册。
- `BaseValueType` 精确为 text/decimal/boolean/date/time/color/imageRef/fontRef；enum 与受限 scalar list 是派生类型。对象/复杂数组是 authored property tree 容器，不是任意 JSON ValueType。
- 全局 `BindingPolicyCatalog` 由 Node 定义者维护，前端/客户端消费、服务端保存与 Evaluation 权威执行；单条 Policy 以 nodeKind + propertyPathPattern 唯一定位 targetType 与 propertyValidation。
- Catalog 只追加允许项：已有 Policy 不可修改/删除，新 Policy 不得与已有 target set 重叠且不得使既有 Template 失效；Policy 不绑定 DesignDSL version，Template 不保存 policyId/Catalog revision。
- 没有匹配 Policy 的属性不可绑定；不存在 STATIC_ONLY/STATIC_OR_BINDING/BINDING_REQUIRED 模式。每个可绑定属性仍必须拥有合法 authored static baseline，Binding 只是可选 overlay。
- Binding 位于宿主 Node 的 `bindings[]`，targetPropertyRef 不含 nodeId/slotId；支持 property、property.member、property[index]、property[index].member、property.member[index]，最多一次 member 与一次 fixed nonnegative index。
- target 的容器与叶子必须已经存在并匹配唯一 Policy；Binding 不创建 member 或扩展数组。重复 target 与 ancestor/descendant overlap 是 hard error，bindings 顺序无语义。
- overlay 后的 concrete value 必须精确匹配 targetType 并重新通过同一 propertyValidation；ABSENT/ERROR/类型或约束失败中止 Evaluation，不回退 static baseline。
- 本票据必须给出每个 Node kind/property/member/array-item 的唯一全局合同，并明确哪些 property path pattern 进入首批 BindingPolicyCatalog；不能把合同分散在 Web 控件、Evaluator switch 与 Renderer payload 中。
- `renderweave-design/1.0` 的顶层只通过必填 `designRoot` 承载全部 authored Node/结构内容；本票据必须冻结 DesignRoot exact shape、root kind/children 规则与其和画板的关系，不能增加平行顶层 elements/bindings/editor state。
- Global Node Property Identity 是永久 `nodeKind + propertyPathPattern`；一经引入，其 ValueType、结构角色、default 与 validation 跨所有 DSL version 不可改变。破坏性演进必须使用新 node kind/propertyId 与新 dslVersion，不能靠新版本复用旧 identity。
- Node 自身使用 client-generated canonical lowercase UUID v4 `nodeId`；所有 Node kind/property/member 对 unknown field 与 null 失败封闭，服务端不生成/修复 nodeId 或 opaque-preserve 未知属性后保存。
- canonical writer 不展开/删除 Node defaults，也不创建 bindable baseline；可绑定 target leaf 必须 authored 存在。票据须声明哪些 child/member arrays 有 z-order/layout 语义并保序；完全 set-like 的 flat node collection 才可按 nodeId canonical sort。
- Node/property wire 或已有 default/validation 的任何语义变化都需要新 dslVersion；只追加 BindingPolicy 不改变 wire/version，但只能授权已存在、永久同义的 property identity。
