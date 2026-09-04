# ADR-0051：先分类 parent containment，再决定 recovery

- 状态：accepted
- 日期：2026-08-18
- 决策来源：v50 immutable negative terminal、ticket 36、ADR-0047 standing approval
- 关联：ADR-0028、ADR-0033、ADR-0049、IOPA-P1-R21

## 背景

v50 已运行最强既有 bounded parent normalization，仍三次得到同一 containment fixed code。该 code 只证明至少一个
直接 parent box 不包含 child；它不说明 region kind、候选数量、坐标差值或 repetition。历史 ADR 已禁止从该 code
推断 alias、box 或任意 parent repair。

## 决策

1. 不直接扩大 parent、裁剪 child、删除 repeated branch 或强制 ROOT projection。
2. v51 只在内存中把 failure 分成 allowlisted、payload-free structural categories；输出固定 code/count，不输出
   region/local ID、坐标、图片、OCR 或模型 response。
3. classified primary 首次即 terminal，不把 detail 回显给模型，不进行 correction call；目的是获取一个因果事实，而非
   用诊断 Profile 偷渡 repair。
4. v51 相对 v50 只改 profileId/pipelineVersion，Prompt 16 保持 exact bytes；任何后续 repair 必须依据 v51 terminal
   另开 source ticket、新 Profile/identity/cycle/J1。

## 后果

多一次窄 live probe，但最多实际一 call；它可避免用无法证明的几何/结构修改换取表面通过。v50 CLOSED bytes 与
认证状态保持不可变，5/20/60 仍未解锁。
