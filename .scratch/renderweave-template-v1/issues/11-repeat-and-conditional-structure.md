# 定义循环与条件结构语义

Type: grilling
Status: open
Blocked by: 06, 07, 09, 10

## Question

Repeat/Loop 如何绑定集合、建立 item/index/key 作用域、复制直接子树、进行实例/模板两级布局并限制数量；空集、缺失、单项、嵌套循环、条件显示与条件不渲染如何区分和失败？

## Inherited constraints

- 每个实际 item 创建由单 Template 内唯一稳定 loopId 定位的不可变 Loop frame，公开 typed item 与零基 index，并只能读取同 Template 的 invocation/词法祖先 frame。
- scalar item 必须形成对应精确 `system-basic-*@v1` typed context；只有已验证 StaticSchema `array(items: reference)` 的对象 item 才携带精确引用 Schema context。任意动态 JSON object 不能冒充 context。
- iterable 必须拥有静态封闭 item 类型；StaticSchema field path 不允许用数字下标或 wildcard 穿越数组，访问元素必须通过 Loop domain。
- nested loop 对当前或祖先项的引用必须显式使用 loopId；不存在 `$current/$parent/$root` 漫游，子 Template invocation 也不继承父 loop frame。
- loop-scoped Computed Definition 的 evaluation domain 在 DesignDSL 中固定；它可以读取本域和词法祖先，不能读取 sibling/descendant 或按消费者动态改变求值域。
- 合法 iterable source 的 ABSENT、空集合、运行时错误以及 iteration/output 上限仍由本票据分别冻结，不能把不存在的 Schema path 当作 ABSENT。
- `loopIndex` 是唯一 loop metadata ValueSource，返回零基非负整数 decimal；没有通用 loop object 或 v1 loop key，item 字段只能经显式 loopId context domain 读取。
- v1 scalar list 只允许五种 StaticSchema scalar item；reference array 必须携带精确 item StaticSchemaRef，任意 object/list nesting 或动态 JSON collection 禁止。
- 普通 Binding 要求 CONCRETE；本票据若让 Loop/Conditional 结构目标接受 MAY_BE_ABSENT，必须在全局 Node 属性合同中声明封闭结构类型与明确的 ABSENT policy，不能改变普通 visual property 规则。
- Binding 永远是可选 overlay 且必须保留合法静态 baseline；因此 iterable/condition 若使用统一 Binding 模型，也必须定义未绑定时可执行的 authored 静态值。存在 Binding 但 ABSENT/ERROR 时不得隐式回退该 baseline。
- Loop subtree 中 node-local Binding 的词法 domain 由节点位置与显式 loopId 静态确定；移动节点造成越界是 hard error，definition 或 Binding 值不得逃逸到 parent/sibling/child Template。
- 每个 Loop 同时携带独立 client-generated canonical UUID v4 loopId；它与 nodeId 分属 namespace，服务端只校验唯一性/引用，不生成或修复。copy subtree 时客户端必须成组 remap loopId 与全部 domain refs。
- 输入 scalar/reference collection 与实际 iteration order 是语义顺序并保持 authored/runtime order；definitions/bindings 的 canonical sorting 不能重排 Loop items、instances 或 conditional branch order。
- Loop/Conditional wire、missing policy、结构 type 或既有 property identity 的变化需要新 dslVersion；既有 identity 不能在新版本中复用为不同语义。
