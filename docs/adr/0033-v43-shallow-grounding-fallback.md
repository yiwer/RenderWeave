# ADR-0033：v43 最浅确定 grounding 回退

- 状态：Accepted
- 日期：2026-08-12
- 决策来源：用户要求 `O:\GptDownload` 两张图片都经正式流程生成 Candidate，并授予后续精确 J1 的持续执行权
- 关联：ADR-0026、ADR-0028、ADR-0031、ADR-0032、AC-020、AC-021、P6/T6-5/N7

## 背景与问题

product-v42 已把单 run 的上限扩展为 7 calls、360 秒/stage 和 Plus/Flash 16384 output tokens。首张用户图片的
v42 Plus run `95abb5c8-469c-4e0e-ab5c-173c6cf170ba` 确实执行完七个调用，证明旧的
`LIVE_WORKFLOW_FAILED` 五调用截断已修复；但输出分别因 region forest、element、parent containment 等严格
grounding 合同失败，另有一次 timeout 和一次 network error，最终没有 Candidate。实际费用为 ¥0.422782。

失败集中在 OBSERVE 对复杂平面版式建立过深 region forest，而不是数据库、凭据、Document Vision、调用上限或
Candidate materializer。继续放宽 validator 会把不确定几何关系写成结构事实；硬编码站牌领域词汇则会破坏通用图片
入口。旧 v42 Profile 与 run snapshot 已冻结，不能原地改写。

## 决策

1. 新增三份 immutable product-v43 Profile，并把新建产品目录切换到 v43；v42 及更早资源继续不可变、可读、
   可恢复。v43 继续绑定 pipeline 4.28、schema/hierarchy/binding Prompt 5/7/3、同一 Document Vision
   capability 和相同模型。
2. OBSERVE element prompt 升为 `renderweave-visual-elements-prompt/11.0`。模型必须优先输出最浅的确定 region
   forest；只有 containment、重复关系和非重叠都能确定时才建立嵌套。不能确定时允许合法 ROOT-only forest，
   所有 SLOT 归属 ROOT，并在提交前显式检查 parent containment。
3. Prompt 11 将通用 `VISUAL_GROUNDING_ELEMENT_INVALID` 纳入同阶段纠错路由，并要求有疑义的 branch 折叠到
   最近确定祖先。它不读取领域词汇，不伪造 GROUP，不改变 box、validator、materializer、Candidate 合同或
   checkpoint 语义。
4. v43 完整继承 v42 的硬边界：最多 7 calls、360 秒/stage、0 repair、262144 output bytes、每调用保守预留
   上界 ¥2；Plus/Flash 最大 16384 output tokens，Max 保持 8192；每个产品 run 仍强制
   `costLimitMicrosCny=1..5,000,000`，且不做跨模型 fallback、tools 或 remote media。
5. 用户的持续 J1 仅免除逐次停等确认。每次真实调用仍必须自动生成并记录精确 revision、evaluation identity、
   Profile snapshot、输入 hash/分类、次数、token、费用和时限；任何边界变化仍需新的明确授权。

## 受控 live 证据

- exact code revision：`2da0af8014462a09f0938d878db92cac3046530c`；clean full
  `20260812-065143-full` 9/9 PASS；Document Vision `20260812-065750-document-vision` 1/1、19 lines PASS。
- Java/Python evaluation identity 一致为
  `renderweave-visual-evaluation-tree-sha256/2:17cb0b63f0245dd8b3cfac3a04a52c52d0ecaefbd1075f640f61ce55daaf90a5`；
  v43 Plus snapshot 为 `77990399d47c5364698eac6e50f7eb5840b2d35b2a7149ec587762472a1021db`。
- `J1-RW-V43-SHALLOW-LIVE-20260812-0705-IMG1` 绑定输入 hash
  `57dd2cc16291eacb85583b68a111a7889bc3cf5924a37c711f1e8e5b5d9628ae`。run
  `aafca06e-fc65-42c3-9253-1bd48c4daf69` 以 4 calls 到达 `REVIEW_REQUIRED`：OBSERVE 首次严格拒绝、
  第二次接受，随后 HIERARCHY/BINDING 接受；47,476 input + 5,756 output tokens，¥0.141000，Candidate
  revision 0、1 schema、1 image。
- `J1-RW-V43-SHALLOW-LIVE-20260812-0708-IMG2` 绑定输入 hash
  `570e0347d357e6c074dcaf31ee9b696bba58cef7314c1daceff243e909010a62`。run
  `898b3e8f-ccf5-49be-84f6-0b2efdb7c13b` 三阶段均首次接受，以 3 calls 到达 `REVIEW_REQUIRED`；
  30,199 input + 1,737 output tokens，¥0.074294，Candidate revision 0、1 schema、1 image。
- 两个 v43 run 合计 ¥0.215294；连同本任务先前 v42 诊断，任务累计 ¥0.638076，距 ¥5 上限尚余
  ¥4.361924。证据只记录 hash、ID、固定码、计数、token、费用和时延，不记录原图、OCR、Prompt、完整模型
  输入输出、Candidate 内容或 chain-of-thought。

## 后果与状态

两张指定图片都已通过正式三阶段 workflow 生成可人工审核 Candidate，当前用户目标完成。该结果证明 v43 对这两个
样本的 reachability，不代表 60 例发布集、任意图片成功率或生产可靠性；三份 v43 Profile 继续为
`EXPERIMENTAL`，N6 可报告 `automated_verified`，全局 N7/final quality gate 仍为 `in_progress`。

回退方式是停止 live 并把新建目录切回 v42；已创建的 v43 run 必须继续按其 immutable snapshot 恢复和审核。
