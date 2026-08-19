# 补齐 canonical sRGB ICC 等值接受原子

Type: task
Status: open
Blocked by: 10

## Question

如何把冻结 IMAGE 颜色合同中「只接纳无 profile、标准 sRGB 声明或该 Profile 固定 canonical sRGB ICC」补齐为真实
接受路径：确定 canonical sRGB ICC 的固定字节来源与 provenance（候选为 color.org sRGB IEC 61966-2.1 标准
profile，字节需入仓库并冻结 sha256），在 PNG `iCCP`、JPEG APP2 `ICC_PROFILE` 与 WebP `ICCP` 三处实现「与
canonical 字节完全相等才接受，冲突/损坏/其他 ICC 拒绝」的等值检查，并让 Java primary 与独立 Python verifier
（Pillow 侧同样做字节等值）在冻结 manifest 中新增 canonical-sRGB-ICC 准入与拒绝 vectors？本票不改变其他
admission 语义，不登记 acceptance Profile available，也不接触 DB/网络/route；T11 create 纵切以其为 blocker。
