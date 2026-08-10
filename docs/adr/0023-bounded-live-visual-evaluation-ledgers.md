# ADR-0023：跨账本有界的真实视觉评测

- 状态：Accepted
- 日期：2026-08-10
- 关联：AC-VR-001、AC-VR-002、AC-VR-008、AC-VR-010、ADR-0008、ADR-0012、ADR-0022

## 背景与约束

图片识别 vNext 需要在同一 60-case stage-gold 上比较三个真实 DashScope 模型。用户给出的每模型
500,000 total tokens 是整个 Goal 的累计上限，不能因 baseline、ablation、canary 或 final 更换授权文件而
清零。模型调用不可逆；进程可能在 Provider 已收到请求但 Candidate/指标尚未持久化时崩溃；普通 Maven
和项目 gate 必须保持零网络。

旧 live certification journal 只按单一授权统计费用，且指标只覆盖最终 Candidate，不能直接证明
element → hierarchy → binding → Candidate 的阶段质量。复用其安全原则，但不复用旧 CLOSED ledger 或把
历史结果混进新 corpus。

## 决策

1. **三份固定 tracked ledger。** Max、Plus、Flash 各使用一个固定仓库路径；selector 只接受三个枚举值，
   不接受任意路径。ledger 的 PROPOSED/OPEN/CLOSED 生命周期提交可变，但 profile、model、corpus、case slice、
   identity、tokens、attempts、CNY、batch 和时限全部严格加载。
2. **代码身份排除且仅排除三份 ledger。** `VisualEvaluationIdentity` 对全部其他 tracked regular files 做
   path-length/content-length framed SHA-256，要求 clean tree、零 untracked，并双次捕获稳定；三份 ledger
   必须真实 tracked。这样任一 ledger 的生命周期提交不会使其他模型失去同一实验身份，其他任何输入漂移
   都 fail-closed。
3. **Profile 单独绑定。** ledger 保存 Registry canonical snapshot SHA-256；preflight 同时检查 profileId、
   model 与 snapshot。仓库 tree identity 绑定 Profile 资源，snapshot hash 绑定实际运行时解析结果。
4. **Goal 级不可释放预算。** `.sdlc/evidence/renderweave-visual-recognition-vnext-20260810/goal-budget.json`
   跨授权、跨进程保存三个模型的 attempts、total tokens 和 CNY。Provider decorator 在 delegate 前按图片与
   output 上界预留；正常响应后只把未使用部分结算为 actual。可能已发生调用的 RESERVED 不释放；估值不足
   写入 BREACHED 并永久停止该 Goal 后续调用。
5. **单授权 journal 与 OS batch lease。** 每个 authorization journal 保存 assignment/execution/run、有限
   attempt taxonomy、token/cost/latency 与 `VisualStageEvaluationResult` 充分统计量。每批最多 5 case；无 Goal
   reservation 的中断 execution 可删除后重试，有任何 reservation 的 execution 标记
   `ABANDONED_AFTER_RESERVATION`，永久禁止重复调用并使完整报告失败。
6. **证据 payload-free。** journal/report/budget 不保存图片、OCR 原文、prompt、Candidate JSON、Provider
   request id、业务值、路径级 diff 或 chain-of-thought；写前和读回均扫描禁止字段。state/guard 使用严格
   duplicate/unknown/trailing/coercion 拒绝、OS file lock、fsync 和 atomic-move-only。
7. **独立语言重算。** `tools/verify_visual_eval_evidence.py` 不加载 Java 类；它从 corpus 原始字节重建 60
   case/slice，按 Java framing 独立重算 clean tree identity，按 record component 顺序重算 Profile snapshot，
   交叉校验 journal ↔ Goal reservation，并重新微聚合全部 stage/global/slice/calibration 指标。duplicate、
   float/coercion、unknown、预算越界、BREACHED、身份漂移、缺失 reservation 和 payload 泄漏均失败。
8. **默认零调用。** 真实 runner 仅在 `RENDERWEAVE_RUN_VISUAL_EVALUATION=true` 且固定 selector 对应 ledger
   为 OPEN 时加载；普通 `fast/server/eval/full` 子进程显式清空该 gate 和 selector。preflight 完成之前不创建
   Goal/journal 状态，不调用 Provider。

## 备选方案

| 方案 | 优点 | 未选择原因 |
|---|---|---|
| 每个实验单独重置 500k | 实现最简单 | 可通过换 ledger 绕过用户 Goal 总上限 |
| 只使用 PostgreSQL product budget | 已有实现 | Testcontainers 每批重建，不能跨批/授权保留总 token；只约束费用 |
| 失败后重跑同 case | 报告更完整 | 无法证明崩溃前请求未到 Provider，会产生重复费用与重复数据外传 |
| journal 保存完整响应用于恢复 | 可重新解析 | 违反 payload-free/最小留存边界，并扩大 prompt injection 证据面 |
| 只信 Java report | 实现较少 | evaluator、reporter、serializer 同源错误会共同产生伪 PASS |

## 后果与验证

- 正向：三模型跨多个节点仍共享不可绕过的 tokens/attempts/CNY 总账；中断语义不靠猜测；阶段指标能被
  独立实现从原始充分统计量重算。
- 代价：live 操作需要 ledger 生命周期提交、clean identity、每批 verifier 和 CLOSED 负探针；跨语言
  canonical contract 增加维护成本。
- 证据等级：项目 gate 和 runner 产物自身为 A1；Python 从 raw evidence 的独立算法重算可形成局部 A2，
  但仍是同机本地工具，不是外部不可绕过的 A3。用户本轮授权为 J1。
- 回退：代码可 revert 当前节点；ledger 只能前进到 CLOSED，已发生调用、tokens 和费用永不删除或回退；
  Goal budget guard/state 任一缺失或不一致时永久 fail-closed，不能以新目录“恢复额度”。

## N2 运行结论与身份版本债务

2026-08-10 的 product-v4 baseline 按同一 12-case sentinel 执行完毕，三个模型的初始与 continuation
ledger 均已进入 `CLOSED`。continuation 使用新的 authorization，而不是重新打开既有 CLOSED ledger；已发生
的 attempt、Token 和费用仍由同一 Goal budget 累计。运行暴露的 evaluator 空绑定 NPE 与调用前预算终止
checkpoint 兼容问题，均先关闭当时 ledger，再以零 Provider 回归和新 authorization 继续。

- `qwen3.8-max` 的精确别名通过了单 case canary，Provider 返回的 model 与请求值完全一致，运行中没有
  静默替换成 preview 或其他别名。由于供应商公开目录当时没有提供同等明确的长期兼容承诺，该能力仍只
  视为本次运行事实，Profile 保持 `EXPERIMENTAL`。
- Max 与 Flash 的 live journal 均通过独立 Python 重算；Plus continuation 通过独立重算，但 Plus 初始
  末态只保留 A1。原因是 identity `/1` 对工作区文件字节做摘要，在 Windows `autocrlf` 的另一 checkout
  中无法重建同一摘要；不能用降低校验强度换取“通过”。因此 Plus 聚合结果是混合 A1/A2，不能宣称完整 A2。
- identity `/1` 对本次 exact clean checkout 仍是 fail-closed 且可定位的；历史 evidence 不重写。下一次
  live phase 必须引入新的 Git-blob canonical identity `/2`，按 Git tracked blob 字节而不是 checkout
  换行表示计算，并为 `/1` 保留只读兼容，不得就地改变旧算法。
- 三模型最终 Candidate pass 均为 `0/12`，没有任何 Profile 晋级。Max 的 hierarchy 更强但 binding 与
  生成式 STRUCTURE 丢失大量语义；Plus/Flash 更早受 hierarchy/contract 稳定性限制。该证据支持下一节点
  将 validated visual plan 通过确定性 Java materializer 编译为 Candidate，而不是继续让模型重写拓扑。
