# 补齐 canonical sRGB ICC 等值接受原子

Type: task
Status: resolved
Claimed by: Codex `/root`
Blocked by: 10

## Question

如何把冻结 IMAGE 颜色合同中「只接纳无 profile、标准 sRGB 声明或该 Profile 固定 canonical sRGB ICC」补齐为真实
接受路径：确定 canonical sRGB ICC 的固定字节来源与 provenance（候选为 color.org sRGB IEC 61966-2.1 标准
profile，字节需入仓库并冻结 sha256），在 PNG `iCCP`、JPEG APP2 `ICC_PROFILE` 与 WebP `ICCP` 三处实现「与
canonical 字节完全相等才接受，冲突/损坏/其他 ICC 拒绝」的等值检查，并让 Java primary 与独立 Python verifier
（Pillow 侧同样做字节等值）在冻结 manifest 中新增 canonical-sRGB-ICC 准入与拒绝 vectors？本票不改变其他
admission 语义，不登记 acceptance Profile available，也不接触 DB/网络/route；T11 create 纵切以其为 blocker。

## Answer

Canonical 字节定为 sRGB IEC 61966-2.1 标准 profile（来源：Windows 系统自带「sRGB Color Space Profile.icm」，
3144 字节，ICC 头 acsp/mntr/RGB/XYZ，sha256 `2b3aa1645779a9e634744faf9b01e9102b0c9b88fd6deced7934df86b949af7e`），
冻结为 `renderweave-asset` 主资源 `cn/hbads/renderweave/asset/acceptance/sRGB-IEC61966-2.1.icc`，加载时以
sha256 自校验。

新增 internal `IccPolicy.isCanonicalSrgb(bytes)` 字节等值并接线三处：PNG `iCCP`（name≤79、compression=0、
zlib 解压后等值；sRGB+iCCP 并存仍拒绝）、JPEG APP2 `ICC_PROFILE`（分段组装后等值）、WebP `ICCP`（chunk
payload 等值，且仅扩展格式 VP8X+ICCP 路径）。垃圾/其他/损坏 ICC 继续 UNSUPPORTED/DESCRIPTOR，损坏组装仍
INVALID/STRUCTURE。

冻结 manifest 新增 3 vectors（png/jpeg/webp canonical-ICC 准入），总数 41；独立 Python verifier 以同一
canonical 文件做等值（`--canonical-icc`）；asset gate 边界 41。asset `20260819-135503-asset` Java=41/41
Python=41/41（A2，vector sha256:74638228ba0910079feb86412314361f09d1761583a58176e6449d90435e9da2）通过。

本票不改其他 admission 语义，未登记 acceptance Profile available，无 DB/网络/route。T11 解锁。
