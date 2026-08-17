# 冻结 Blueprint 归一化结构与 `$to-spec` handoff 包

Type: grilling
Status: resolved
Claimed by: Kimi（2026-08-17 会话）
Blocked by: 13 — 冻结 staged rollout、rollback 与 ProductionLiveAuthority（已 resolved，本票即为 frontier）

## Question

13 张决策票全部 resolved 后，最终《IMAGE_ONLY Schema Recognition Production Admission Blueprint》应以什么归一化结构承载全部冻结决策（章节划分、决策引用方式、exact identity 清单格式）；跨票冲突检查应覆盖哪些维度（数值一致性、术语一致性、identity 引用一致性、红线一致性）以及发现冲突时的处置规则；`$to-spec` handoff 包应包含哪些内容（Blueprint 正文、evidence 索引、票链接、开放残余风险清单），以什么形态落盘（单文档/文档集、存放路径、版本标记），由谁验收后才算地图闭环、可以移交实施。

## Answer

2026-08-17 经 grilling 一轮冻结（全部按所有者确认的推荐）：

### Blueprint 归一化结构（Q1）

1. **单文档** `plans/image-only-production-admission-blueprint-v1.md`，章节按决策域划分：①头部（版本/日期/map 链接/16 票清单）②术语与权威状态（01/05）③Provider 合同与路线（02/06/07/14/15）④信任边界与数据政策（04）⑤OCR 拓扑（03/08）⑥Profile 认证（05/07）⑦SLO/容量/成本（09）⑧持久化/备份/恢复（10）⑨遥测/告警/值守（11）⑩API 合同与 release gate（12）⑪Rollout 与 ProductionLiveAuthority（13）⑫Exact identity 总表（v46 profileId+bytes SHA、route endpoint、capability id、image digest、门槛数值汇总；表格列=名称/值/来源票）⑬残余风险与接受记录（06 风险接受、07 漂移不检测、15 不引入 DeepSeek-OCR 等）⑭证据索引（A1/A2/J1 逐条指向落盘路径）⑮Out of scope（map 原样）。
2. **引用方式**沿用 map 原则：每条决策一行 gist + 票文件锚点链接；细节只存在票里，Blueprint 不复述全文。

### 跨票冲突检查（Q2）

3. **四个维度**：数值一致性（7 天 payload、90 天 audit、¥500 月 hard、12 calls/¥6、P90 15min、RPO/RTO 等在 04/07/09/10/11/12/13 间逐值比对）；术语一致性（CONTEXT.md 词汇 vs 各票用语：ProductionLiveAuthority、ProfileCertificationRecord、ExternalTransferConfirmation、六值 reason code 等）；identity 引用一致性（v46 profileId、capability id、endpoint、digest 在所有引用处逐字符一致）；红线一致性（AGENTS.md 禁区与各票无冲突）。
4. **处置规则**：冲突不静默修——数值/实质冲突以源票为准、Blueprint 标注 drift note，实质冲突开新票；编辑性差异直接以源票为准。

### handoff 包形态与落盘（Q3）

5. 包 = Blueprint 单文档（内含 evidence 索引与残余风险清单，不另开文件）+ 头部 map/票链接。落盘 `plans/image-only-production-admission-blueprint-v1.md`，版本标记 `v1` + 日期；后续修订 = 新版本号新文件（plans 目录 append-only 惯例）。汇编时顺带核对 CONTEXT.md 词汇覆盖（如 ProductionLiveAuthority 未入则补）。

### 闭环验收规则（Q4）

6. 所有者对照 **13 票决策 checklist** 逐条确认 Blueprint 覆盖无遗漏 + 冲突检查结果零未决 → J1 验收；agent 汇编不算验收。
7. 验收后 map.md 顶部标记 closed、NOTES 记录，地图闭环；`$to-spec` 凭 handoff 包启动实施规划，本地图不再持有执行工作。

### 下游

- 全部 16 票 resolved，fog 清空；地图仅剩 Blueprint 汇编（机械执行，非决策）+ 所有者 checklist 验收两个闭环动作。
