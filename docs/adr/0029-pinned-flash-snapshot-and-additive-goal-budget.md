# ADR-0029：Pinned Flash 快照与追加 Goal 预算槽位

- 状态：accepted
- 日期：2026-08-11
- 关联：AC-VR-002、AC-VR-008、AC-VR-010、P6/T6-5 N7、ADR-0023、ADR-0028

## 背景

N6 的 `qwen3.7-flash` v10/v11/v12 单 case smoke 共 15 attempts，全部停在 OBSERVE。历史 Goal guard 以
精确 model 字符串聚合，token cap 为每模型 500,000；若直接新增日期快照而不改变聚合语义，新 Flash 会被
错误视为一个全新预算，从而绕过旧 Flash 已暴露的 393,034 tokens。

用户于 2026-08-11 指定把当前 Flash 替换为 `qwen3.7-flash-2026-07-15`，其他两个模型保持不变，并给三个
模型各追加 500,000 tokens。用户没有追加 attempts 或费用。官方资料显示新快照为原生视觉语言模型，支持
图像、结构化输出和可关闭 thinking；中国区 ≤32K 输入档目录价为 ¥0.2/M input、¥0.8/M output。

同日用户继续为三个稳定模型槽位各追加 500,000 exposed tokens，并明确允许 Plus。该增量只把每槽位累计
token cap 从 1,000,000 提到 1,500,000；attempts 与 Max/Plus/Flash 的 ¥18/¥4/¥0.40 费用硬门均不增加。

## 决策

1. 不修改任何历史 Flash capability/Profile/ledger。新增日期显式的 immutable capability 与 N7 Profile，
   model 精确绑定 `qwen3.7-flash-2026-07-15`；Plus 与 Max 继续使用既有精确 model 字符串和 v12 contract。
2. Goal guard 当前升级为 v3。预算以三个稳定槽位聚合：Max、Plus、Flash；旧 `qwen3.7-flash` 与新日期快照
   都映射到 Flash 槽位。每槽位 token cap 为 1,500,000，旧 reservations 和实际用量全部继续计入。
3. v1/v2 guard 与历史 evidence 保持可读且可由独立 verifier 重放。首次使用 v3 runner 时，只在持有既有
   原子 lock、先按来源 guard 验证 state 一致后原子替换 guard；reservation state 不重写、不删除、不重算。
4. attempts 仍为每槽位 180；费用仍为 Max ¥18、Plus ¥4、Flash ¥0.40。Authorization 的 ledger 级 token、
   attempts、cost 继续显式收窄，追加 token 不自动变成追加费用或调用次数。
5. N7 先运行 pinned Flash 单 case canary。用户随后明确重新允许 Plus，因此 Plus 可在独立精确授权下执行
   单 case reachability；只有 OBSERVE→HIERARCHY→BINDING 三阶段合同 live 可达、identity/Profile snapshot
   精确、J1 费用/次数/时限仍有效时才考虑 Max。

## 实施与受控实证

- `252dc00` 新增 pinned Flash capability/Profile、Goal guard v2 原子迁移、旧/新 Flash alias 聚合及独立
  verifier v1/v2 兼容；`0d7b73c` 修正 runner 对 pinned Flash 的稳定槽位费用上限查询。v1 的 reservations
  未清零，首次 live preflight 在锁内迁移为 v2。
- `2b23617` 在相同锁与不可变 reservation 语义下新增 exact v2→v3 迁移，并让 Java guard 与独立 Python
  verifier 分别按历史 500k/1M 和当前 1.5M cap 重放；定向迁移、tamper 与 verifier 回归 12/12 PASS。
- pinned Flash v13 与 Plus v12 各执行一次仓库合成 `transit-board-v3` 单 case、最多 5 attempts 的
  PROPOSED→负探针→OPEN→CLOSED smoke。两份独立 verifier 均 PASS、0 abandoned、payload scan PASS；所有
  ledger 在检查 evidence 前已 CLOSED。
- pinned Flash 的 5 attempts 全部停在 OBSERVE；Plus 的第三次 attempt 通过 OBSERVE，第四次在 HIERARCHY
  以 `VISUAL_HIERARCHY_V2_ENTITY_INVALID` 拒绝，第五次收到 `DASHSCOPE_HTTP_400`，未到 BINDING。该 400
  对应 reservation 保留为 RESERVED，不释放、不伪造 usage。
- 三阶段入口门仍未满足，因此 Max 没有调用。Goal 累计为 Flash 76 attempts / 428,373 tokens / ¥0.190286，
  Plus 91 / 530,579 / ¥2.227000，Max 73 / 428,816 / ¥9.204720；guard 中 235 SETTLED、5 RESERVED，均继续
  按保守上界计入。

后续 product-v27 单 case smoke 全部按 v3 guard 执行并独立重放。最终累计为 Flash 100 attempts /
641,256 tokens / ¥0.302686，Plus 153 / 900,566 / ¥3.484570，Max 82 / 491,919 / ¥10.289316；335 个
reservations 中 330 SETTLED、5 个历史 Plus RESERVED、0 BREACHED。三份 ledger 均 `CLOSED`，追加 token
没有被解释为追加 attempts、费用或质量晋级。

## 后果

- 正向：模型快照不随供应商浮动；旧 Flash 用量不会因换 model ID 消失；新增 J1 可被 guard 与 verifier
  机器检查；历史 evidence 仍可复核。
- 代价：模型身份与预算槽位不再是一一对应，Java guard 和独立 Python verifier 必须共享同一显式映射并有
  跨 alias 回归测试。
- 恢复：源码可 revert 独立实现 commit；guard v3 一旦写入不降级回 v2/v1，因为已发生的新 reservation 可能
  依赖 1.5M cap。外部调用费用不可恢复，只能 CLOSED ledger 并阻止后续调用。
- 状态：本 ADR 只提供 N7 Profile/guard 的可执行边界；即使 v27 Plus/Max 已证明三阶段可达，single-case
  质量仍未过门，因此不把任何 Profile 提升出 `EXPERIMENTAL`。

## v32 后续预算结果与费用停止门

product-v32 Plus 单 case 又结算 3 个 attempts / 21,316 exposed tokens / ¥0.067226。Goal guard v3
当前为 372 reservations（367 SETTLED、5 个历史 Plus RESERVED、0 BREACHED）：

| 稳定槽位 | attempts | exposed tokens / 1,500,000 | Goal cost / cap | 剩余 |
|---|---:|---:|---:|---:|
| Flash（旧 alias + pinned） | 120 / 180 | 815,516 | ¥0.392962 / ¥0.40 | 60 attempts；684,484 tokens；¥0.007038 |
| Plus | 170 / 180 | 1,021,208 | ¥3.903838 / ¥4.00 | 10 attempts；478,792 tokens；¥0.096162 |
| Max | 82 / 180 | 491,919 | ¥10.289316 / ¥18.00 | 98 attempts；1,008,081 tokens；¥7.710684 |

追加的 token 授权没有追加费用。按当前 Profile 的标准 OBSERVE reservation，Flash 需要
¥0.009740，Plus 需要 ¥0.097390，分别高于两槽剩余 ¥0.007038 / ¥0.096162。因此在
CNY cap 不变时，两者都必须在新 reservation 前 fail-closed，不得用更小的临时 token 预留规避
完整 stage 合同。Max 虽有费用余量，但 v32 未 accepted HIERARCHY/BINDING，v33 又只有离线
三阶段证据，不满足“同版本 live 三阶段 + 质量/J1”前置，仍保持 CLOSED。

这是本 ADR 的预期 fail-closed 后果：token 余量、attempt 余量或历史三阶段记录都不能替代
当次费用与同版本阶段门。三份 visual ledger 现均为 `CLOSED`。

## 后续决定：Goal guard v4 的 Flash/Plus ¥10 总费用上限

用户于 2026-08-11 继续同一 Goal，明确给予 Flash 与 Plus 各 ¥10 预算、时限 24h。本 ADR 将
“各 ¥10”按不扩大授权的方式解释为每个稳定槽位 **累计总 cap ¥10**，而不是在旧 cap 上追加
¥10；窗口固定为 `2026-08-11T09:51:55Z`（含）到 `2026-08-12T09:51:55Z`（不含）。Max
仍为 ¥18，三槽 1.5M tokens、180 attempts 与单 authorization 500k 不变。

实现使用 `renderweave-visual-evaluation-goal-guard/4.0`：Plus/Flash/Max cost map 为
¥10/¥10/¥18。runner 只接受字节语义精确的 v1/v2/v3 guard 并在同一原子锁内迁移到 v4；历史
reservation 不删除、不释放、不重算。独立 Python verifier 按 guard version 分别验证历史
500k/1M/1.5M + 旧费用 map 与当前 1.5M + 新费用 map，不能用 v4 费用回溯放宽旧 evidence。

本决定只解除 Flash/Plus 的 Goal cost 停止门，不自动 OPEN ledger、不增加 attempts/tokens、不晋级
Profile，也不放宽 Max 的同版本三阶段门。每个 single-case ledger 仍独立收窄并遵循
PROPOSED→负探针→OPEN→CLOSED；wrapper 超时仍先查 lease/进程，禁止并发重跑。

## guard v4 首次迁移与 v33 结算

`15b5d00` 的 clean full/Document Vision 与 fresh identity/Profile preflight 通过后，Flash v33 的首个
OPEN reservation 在 Goal lock 内把精确 v3 guard 原子迁移为 v4；state reservation 未删除、释放或重算。
Flash `f12e5af`→`69e8455`→`f50f591` 结算 4 attempts / 37,181 tokens / ¥0.019870；Plus
`b0bceab`→`36c13db`→`f7a87b9` 结算 5 attempts / 35,407 tokens / ¥0.159584。两者的 PROPOSED
负探针均为 NOT_OPEN 且 Goal/evidence 零写入，两份 CLOSED evidence 均由独立 verifier 重建 PASS。

| 稳定槽位 | attempts | exposed tokens / 1,500,000 | Goal cost / cap | 剩余 |
|---|---:|---:|---:|---:|
| Flash（旧 alias + pinned） | 124 / 180 | 852,697 | ¥0.412832 / ¥10 | 56 attempts；647,303 tokens；¥9.587168 |
| Plus | 175 / 180 | 1,056,615 | ¥4.063422 / ¥10 | 5 attempts；443,385 tokens；¥5.936578 |
| Max | 82 / 180 | 491,919 | ¥10.289316 / ¥18 | 98 attempts；1,008,081 tokens；¥7.710684 |

381 reservations 中 376 SETTLED、5 个历史 Plus RESERVED、0 BREACHED。Plus 第五次才接受 OBSERVE，
随后在 HIERARCHY 前由 ledger call cap 停止；Flash 未接受 OBSERVE。因此 Max 的同版本三阶段门仍未成立，
保持 CLOSED。guard v4 的成功迁移只证明治理闭环，不构成 Profile 晋级或 final eval 许可。

## product-v34 离线节点与下一次预算入口

pipeline 4.21/product-v34 的 codec、Profile、PostgreSQL 恢复、monitor/review 与 E2E 均为零 Provider 的离线
节点，未创建 reservation 或 ledger，也不构成新的费用授权。Goal 因而仍为 381 reservations（376 SETTLED、
5 个历史 Plus RESERVED、0 BREACHED），Flash/Plus/Max 分别保持 124/175/82 attempts、
852,697/1,056,615/491,919 exposed tokens 与 ¥0.412832/¥4.063422/¥10.289316；三份 ledger 均 CLOSED。

若在固定 J1 窗口结束前进入 v34 live，必须在 exact-clean checkpoint 上重新计算 evaluation identity、当次
Profile snapshot、Goal aggregate、剩余 token/attempt/CNY/time、API 配置存在性、进程与 evidence lease。
Flash 仍优先执行单 synthetic case、最多 5 calls 的完整 ledger lifecycle；Plus 只剩 5 个 Goal attempts，
必须在 Flash 结果有直接诊断价值且 fresh preflight 仍通过时使用。Max 仍要求 v34 同版本 live accepted
OBSERVE/HIERARCHY/BINDING、质量门与当次 J1；离线三阶段 tracer 不能替代该前置。

## product-v34 live 结算与 product-v35 预算入口

v34 Flash/Plus 各自完成 PROPOSED→NOT_OPEN 负探针→OPEN→唯一 wrapper→CLOSED，CLOSED 后独立 verifier
重建与 payload scan 均 PASS、0 abandoned。Flash 结算 5 attempts / 43,396 tokens / ¥0.022364；Plus
结算 4 actual attempts / 30,885 tokens / ¥0.096198，第五次在 Provider 前由 authorization cost reservation
拒绝，因此没有虚增 Goal reservation。

Goal guard v4 当前为 390 reservations（385 SETTLED、5 个历史 Plus RESERVED、0 BREACHED）：

| 稳定槽位 | attempts | exposed tokens / 1,500,000 | Goal cost / cap | 剩余 |
|---|---:|---:|---:|---:|
| Flash（旧 alias + pinned） | 129 / 180 | 896,093 | ¥0.435196 / ¥10 | 51 attempts；603,907 tokens；¥9.564804 |
| Plus | 179 / 180 | 1,087,500 | ¥4.159620 / ¥10 | 1 attempt；412,500 tokens；¥5.840380 |
| Max | 82 / 180 | 491,919 | ¥10.289316 / ¥18 | 98 attempts；1,008,081 tokens；¥7.710684 |

三份 ledger 均 CLOSED。product-v35 离线节点未创建 reservation，故上表不变。Plus 的单个剩余 attempt
不足以验证 OBSERVE→HIERARCHY→BINDING，不应为“用完额度”而调用；Max 仍要求 v35 同版本三阶段 live 与
质量门。Flash 如在固定窗口内进入 v35 smoke，仍须 exact-clean full/Document Vision、fresh identity/Profile
snapshot/Goal/time/process/lease preflight、单 case/最多 5 calls 与完整 ledger 生命周期。任何 token 或费用
余量都不能替代这些前置，也不能自动授权 final 20/60。

## product-v35 Flash 结算

Flash v35 完成独立 PROPOSED→NOT_OPEN→OPEN→唯一 wrapper→CLOSED→NOT_OPEN 生命周期，A2 verifier 与
payload scan PASS、0 abandoned。5 个 SETTLED reservations 消耗 41,477 exposed tokens 与 ¥0.020835；
它们全部停在 OBSERVE，未形成三阶段证据。

| 稳定槽位 | attempts | exposed tokens / 1,500,000 | Goal cost / cap | 剩余 |
|---|---:|---:|---:|---:|
| Flash（旧 alias + pinned） | 134 / 180 | 937,570 | ¥0.456031 / ¥10 | 46 attempts；562,430 tokens；¥9.543969 |
| Plus | 179 / 180 | 1,087,500 | ¥4.159620 / ¥10 | 1 attempt；412,500 tokens；¥5.840380 |
| Max | 82 / 180 | 491,919 | ¥10.289316 / ¥18 | 98 attempts；1,008,081 tokens；¥7.710684 |

395 reservations 中 390 SETTLED、5 个历史 Plus RESERVED、0 BREACHED。三 ledger CLOSED；Plus 不为凑数
调用，Max/final eval 继续受同版本 accepted OBSERVE/HIERARCHY/BINDING 与质量门约束。
