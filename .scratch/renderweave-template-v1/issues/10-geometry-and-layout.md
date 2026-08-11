# 定义几何与布局容器语义

Type: grilling
Status: open
Blocked by: 09

## Question

画板、自由布局、Group、Stack、Grid 等容器如何拥有和计算子节点几何；fixed/hug/fill、min/max、padding/margin、gap、writing mode、transform、overflow/clip、排序和派生坐标持久化规则应是什么？

## Inherited constraints

- 所有 authored geometry/layout property 必须先按全局 Node 属性合同形成合法静态 baseline；哪些具体路径允许 Binding 只能由只追加 BindingPolicyCatalog 显式列出，不由 Template 自定义。
- Binding 只覆盖已存在 property leaf，最多一次 member 与一次 fixed array index；overlay 结果必须在进入 layout 前成为 concrete exact ValueType 并重新通过该 geometry property 的全局约束，失败不得回退 baseline。
- RenderEngine/布局阶段只接收已求值的静态 RenderDocument，不得重新解释 Binding、Expression、ABSENT 或 DesignDSL property policy。
- DesignRoot/Node children、画板与输出相关数组若以 authored order 表达 z-order/layout/output，就必须在 Canonical DesignDSL 中保序；若顺序完全由显式 order/placement 字段决定，票据须声明其 set-like canonical sort key，不能同时存在两个 authority。
- geometry decimal 在 Canonical DesignDSL 中按 arbitrary-precision plain token 写出，等价 lexeme 不构成作者事实；每项范围/default/单位仍由永久 Node Property Identity 定义，canonical writer 不自动补 default 或裁剪非法数值。
