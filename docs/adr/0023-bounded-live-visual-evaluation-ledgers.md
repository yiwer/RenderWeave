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
