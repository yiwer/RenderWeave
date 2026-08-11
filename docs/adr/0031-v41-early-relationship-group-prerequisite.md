# ADR-0031：v41 层级支持解析前的 GROUP 前置诊断

- 状态：Accepted
- 日期：2026-08-12
- 关联：ADR-0028、ADR-0030、AC-020、AC-021、AC-VR-009、AC-VR-010

## 背景

product-v40 的一次 Plus live 运行在 OBSERVE 接受了 7 个 SLOT、0 个 GROUP，随后四次 HIERARCHY 都以
`VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN` 拒绝，最终未生成 Candidate。现有 bounded verifier 已定义
“HIERARCHY 提出 relationship、但 OBSERVE inventory 没有任何 GROUP”应以
`VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING` 回到 OBSERVE；但 pipeline 4.27 在构造 hierarchy 时先从
support element 派生 relationship cardinality。未知 support 因此在前置语义检查之前抛错，使既有跨阶段回退
不可达，并把剩余调用全部消耗在无法补回上游 GROUP 的 HIERARCHY 重试上。

pipeline 4.27、Prompt 10 与 product-v40 Profile 已冻结且有历史 live 证据，不能原地修改。

## 决策

1. 新增 `renderweave-inference-pipeline/4.28`。只有该版本在 strict JSON 解码和 contract version 校验之后、
   relationship support ID 与 evidence-derived cardinality 解析之前执行零 GROUP 前置检查。
2. 前置检查只在 hierarchy response 至少包含一个 relationship 且已验证 OBSERVE inventory 的 GROUP 数量为零时
   命中；它返回既有固定码 `VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING`，并把 `earliestStage` 固定为
   `OBSERVE`。空 relationship 的合法扁平文档继续通过；有 GROUP 的路径继续执行原有全部严格验证与诊断顺序。
3. 该检查只读取 relationship 是否为空与 element kind 计数，不读取或记录文本、动态 ID、坐标、OCR、图片、
   Prompt 或 Provider 原文；不创建 GROUP、不改 support、不推断 cardinality，也不放宽任何 JSON、region、
   ownership、semantic verifier 或 Candidate 合同。
4. 事务性 rewind 复用 ADR-0028 的白名单路径：保留 provider call 计数，清空 OBSERVE 之后的 checkpoint，下一次
   请求只重做 OBSERVE 并携带固定问题码，不携带旧 plan 或 crop。五次总调用边界不变，因此一次上游修复后仍可
   用剩余三次调用完成 OBSERVE、HIERARCHY、ELEMENT_BINDING。
5. 新增三份 immutable product-v41 Profile，复用 Prompt 10/7/3、Document Vision capability、模型、价格、
   8192 output tokens、240 秒 stage timeout、五次调用与 ¥2 单次预留边界。新建产品目录切换到 v41；所有 v40
   资源、run snapshot 与失败证据保持不可变、可读和可恢复。
6. product-v41 继续标记 `EXPERIMENTAL`，不实现跨模型自动 fallback。本离线修复不构成真实模型质量证明；任何
   v41 live 必须重新绑定 exact revision、evaluation identity、Profile snapshot、数据分类、次数、费用与时限，
   并获得新的当次 J1。

## 备选方案

| 方案 | 未采用原因 |
|---|---|
| 修改 pipeline 4.27 或 v40 Profile | 改变已冻结 snapshot 与 live evidence 的历史含义 |
| HIERARCHY 继续原地重试 | 下游调用不能补回 OBSERVE 遗漏的 GROUP |
| 从 GROUP region 自动生成 GROUP element | 伪造模型语义证据，并把布局容器误当成数据结构 |
| 任何未知 support 都回到 OBSERVE | 会掩盖 inventory 已有 GROUP 时真正属于 HIERARCHY 的引用错误 |
| 在 OBSERVE 看到任意 GROUP region 就强制 GROUP element | 装饰性 GROUP region 不一定对应 child Schema，条件过宽 |

## 后果与验证

- 合同回归精确覆盖“support ID 未知 + evidence-derived cardinality + inventory 0 GROUP”，修复前因新 policy 不存在
  而红，修复后返回 OBSERVE fixed code；已有有 GROUP 的 unknown-support normalization 测试保持不变。
- 真实 PostgreSQL 合成回归在五次调用内得到
  `OBSERVE accepted → HIERARCHY rejected/rewind → OBSERVE accepted → HIERARCHY accepted → BINDING accepted → REVIEW_REQUIRED`，
  并证明 rewind checkpoint 不含旧 inventory/grounding、OCR sentinel 或 crop。
- Registry、OpenAPI、generated client、Web 默认值与 capability-aware admission 只暴露三份 v41 Profile；v40 不再
  接纳新产品 run，但历史 run 仍按保存的 snapshot 恢复。
- 实施与自动验证期间显式清除 live/key 环境，Provider attempts/reservations 为 0。状态只能报告
  `automated_verified`，不能报告 live accepted 或生产可靠。
