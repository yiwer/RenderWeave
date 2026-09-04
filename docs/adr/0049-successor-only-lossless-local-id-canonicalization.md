# ADR-0049：successor-only lossless local-ID canonicalization

- 状态：accepted
- 日期：2026-08-18
- 决策来源：ticket 31、v49 immutable negative terminal、ADR-0047 standing approval
- 关联：ADR-0046、ADR-0048、IOPA-P1-R16

## 背景

v49 已把 mixed field provenance、bounded correction 与 breaker 做到可验证，但同一 local-ID 三字段集合仍在唯一
diagnostic 中第三次失败。classifier 代码证明这些 fixed codes 来自词法 validator；继续提示模型修复内部标签会消耗
Provider budget，却没有新增产品语义证据。

## 决策

1. v50 在 strict grounding validator 前加入 deterministic canonicalizer，只重命名 region/element declarations、
   parent/repeat-group/element-region references，保持 equality/reference graph。
2. 生成名只由数组顺序和 equality-class 首次出现决定，不读取 token 含义，不 hash/记录 raw token；其他 semantic
   JSON values 不变。
3. 无法证明关系等价时 fail closed：duplicate/null/blank declaration、dangling/ambiguous reference、type/shape error
   都不猜测、不删除、不补结构。
4. canonicalized tree 必须继续通过完整 RenderWeave validator；该 seam 不修复或放宽几何、层级、reading order、
   evidence、multiplicity、semantic name 或 Candidate 规则。
5. 只由 hidden v50 pipeline 4.32 / Prompt 16 opt in；v49 与更早 bytes、hash、terminal semantics 不变。

## 后果与验证

- 正向：把可机械证明的内部标签词法问题从随机 retry 移到确定性边界，减少无效调用，同时保留全部结构验证。
- 风险：错误实现可能重接关系；因此必须用 exhaustive graph fixtures、非 ID 字段 differential、unknown/duplicate
  negative properties、历史 replay 与 payload scan 约束。
- 回退：停止创建 v50 authority；不得关闭 canonicalizer 后复用 v50 identity，也不得回写/重开 v49。
