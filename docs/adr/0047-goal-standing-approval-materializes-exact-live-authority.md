# ADR-0047：Goal standing approval 只通过 exact live authority 实例化

- 状态：accepted
- 日期：2026-08-18
- 决策来源：所有者于 `2026-08-18T10:23:09+08:00` 要求 `/goal` 持续推进、批准推荐决策与授权，并授予
  1,500,000 aggregate model-token 上限
- 关联：IMAGE_ONLY Production Admission Goal、IOPA-P1-R14..P5、ADR-0040

## 背景与约束

长程 Goal 会依次经过 diagnostic、5/20/60 certification、restore acceptance 与 guarded pilot。所有者要求在已批准
Wayfinder 范围内按推荐方案连续推进，不因重复权限问答停顿，并允许真实 LLM 调用总计不超过 1,500,000 model
tokens。与此同时，ADR-0040 与 production-admission delta 要求每个 paid stage 在 Provider boundary 前绑定 exact
Profile/evaluation/input/data/calls/cost/time，历史 CLOSED authority 永不复用；宽泛 token/time 声明不能单独成为
可执行 permit。

## 决策

1. 所有者回复是本 Goal 内“采用执行者推荐的更窄 exact scope”的 standing human approval source，不是一个可直接
   外传的 wildcard authorization。执行者无需为同一批准范围重复停下询问，但每次 live 前仍必须生成 schema-valid、
   可定位到该批准来源的 exact J1 JSON，并由 preflight 逐字段验证。
2. 1,500,000 model tokens 是整个 Goal 从本回复之后开始计算的 aggregate hard ceiling，不是每 stage、每 run 或每模型
   可重置额度。历史已关闭调用不从该新 epoch 扣减；新 authorization 的 token cap 总和与实际 settled/reserved usage
   都不得使剩余 Goal authority 为负。
3. 每个 exact authorization 只能缩窄至当时已批准 ticket/spec 的 route/model、Profile SHA、evaluation/normalization/
   manifest/case identities、`USER_PROVIDED + ORDINARY_DESIGN`、calls、费用与最短实用时间窗。费用采用对应 approved
   delta 的推荐 hard cap；未知字段、identity drift 或 scope 扩大仍 fail closed。
4. standing approval 不替代 Candidate 人工逐项审核、StaticSchema 发布禁令、restore 真实性验证或 A3 生产部署硬门；
   不把 Agent recommendation 冒充已经发生的人类视觉/业务/ops verdict。需要观察尚未产生的结果时，只继续其他安全
   工作，待结果存在后按事实形成 recommendation 与记录。
5. 每次 live 无论成功、失败、异常或中止都关闭 exact authorization/ledger；不自动续期、不复用 CLOSED JSON，未知
   Provider 结果不盲重放。Agent 不读取、打印或复制 API Key。

## 备选方案

| 方案 | 优点 | 代价/风险 | 未选择原因 |
|---|---|---|---|
| 把本回复直接当 wildcard J1 | 不需生成阶段文件 | 缺少 exact identity/cost/time，无法审计或执法 | 与 ADR-0040 和 source decision 冲突 |
| 每个 stage 再次等待聊天批准 | 人工确认最显式 | 重复阻塞已由所有者明确批准的窄范围 | 不符合本次持续执行指令 |
| 一次创建覆盖所有未来 identity 的 OPEN JSON | 表面上连续 | identity 尚不存在，授权不可精确且长期暴露 | 无法 fail closed，也会复用 authority |
| 取消 live，全部 Provider-zero | 风险最低 | 无法完成 certification 与 guarded pilot Goal | 不满足用户明确目标 |

## 后果与验证

- 正向后果：授权问答与实际 permit 分离；Goal 可连续推进，同时每次外传仍有 exact、短期、可关闭的执法对象和
  统一 1.5M token 上限。
- 负向后果/债务：需要 Goal-level append-only usage projection，把各 stage reservation/settlement 汇总；本地文本
  仍不是 A3 权限系统，真实生产发布必须如实标注保证等级。
- 验证：Provider-zero preflight 必须证明 wildcard/过期/identity drift/超 aggregate cap 全部拒绝；Testcontainers
  PostgreSQL 证明 concurrent reservation 不超额；每次 post-close verifier 证明 OPEN=0、unsettled=0。
- 回退/替代条件：可停止创建新的 exact authority并保留所有 CLOSED 记录；源码回退不撤销已发生调用、费用或外部
  Provider 副作用。任何超出当前 Wayfinder、数据分类、route/model 或 1.5M cap 的需求必须另取人类批准。
