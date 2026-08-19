# 实现 Asset acceptance kernel 与独立 replay

Type: task
Status: resolved
Claimed by: Codex `/root`
Blocked by: 05

## Question

如何以测试驱动方式实现 renderweave-asset 的首个真实产品 kernel：通过唯一 `AssetAcceptanceAuthority` 的
closed admission，对静态 PNG/JPEG/WebP（64 MiB、单边 20,000 px、总像素 100,000,000、`SRGB_8BIT` 颜色合同、
唯一 EXIF orientation、拒绝 16-bit/APNG/冲突或非 canonical sRGB ICC/CMYK/YCCK/HDR/wide-gamut）与单 face、
non-variable、monochrome outline TTF/OTF（32 MiB、TrueType `glyf`/CFF、`cmap/GDEF/GSUB/GPOS/kern` 白名单、
拒绝 collection/COLR/CPAL/CBDT/CBLC/sbix/SVG/bitmap strike/Graphite/AAT-only/malformed table）执行 strict
magic/完整解码/表解析检查，输出 TechnicalDescriptor、sha256 与 byteLength，并让 Java primary 与独立 Python
verifier（Pillow/fontTools）重放 exact vectors？本票不得访问 DB、网络、S3/MinIO、Template/Asset 聚合或产品
route，不创建 API/UI，不登记 `renderweave-asset-acceptance/1.0` available，也不得创建占位 adapter 或 gate。

## Answer

T10 创建 renderweave-asset（reactor 第 6 个 artifact；唯一运行时依赖 webp-imageio 0.2.0 —— 经调研不存在成熟
纯 Java WebP 解码器，libwebp 原生绑定是唯一务实选项，Pillow/fontTools 独立重放不受影响）。唯一 public
top-level Interface 是 `AssetAcceptanceAuthority.admit(rawBytes, kind)` closed union：`Admitted` 携
byteLength/sha256/acceptanceProfileId/TechnicalDescriptor，`Rejected` 携
`ASSET_CONTENT_INVALID|UNSUPPORTED|LIMIT_EXCEEDED`、`ASSET_STRUCTURE|DECODE|DESCRIPTOR` 与 pointer/limit。

- PNG：chunk 走查（逐 chunk CRC、IHDR 位深/颜色组合、acTL/APNG 拒绝、PLTE/tRNS 规则、未知关键 chunk 拒绝、
  2 万 px/1 亿像素上限、sRGB/iCCP 政策）+ ImageIO 全解码；eXIf→orientation。
- JPEG：marker 走查（SOF0/2、precision=8、1/3 组件、Adobe transform 0/2 拒绝、DAC/DNL/DHP/EXP 拒绝、
  DHT/DQT 结构、ICC 分段组装、APP1 EXIF）+ ImageIO 全解码（APP/COM 剥离后的同一字节流）。
- WebP：RIFF/chunk 走查（VP8X 保留位/ANIM 标志、ANIM/ANMF 拒绝、VP8/VP8L 帧头、ICCP、EXIF、未知 fourCC
  fail-closed）+ webp-imageio（libwebp）全解码。
- FONT：sfnt 目录/逐表 checksum（head 按 checkSumAdjustment 清零）、整文件 0xB1B0AFBA、head/maxp/loca/glyf
  逐 glyph 结构走查与 composite 环检测、CFF INDEX/DICT/Type 2 CharString 结构解析（callsubr/callgsubr 有界
  解析与防环）；cmap 至少一个合法 Unicode 子表。

冻结 manifest `renderweave-asset-acceptance-kernel-v1/1` 38 vectors 由 Java primary 经正式 Interface 重放并由
独立 Python verifier（Pillow/fontTools + 独立结构实现）逐 case 重算；新 `asset` gate（repository-diff +
asset-kernel-replay）已接线并纳入 `full`。asset `20260819-134638-asset`（Java=38/38 Python=38/38，vector
sha256:2b690ccea02703ed21f4db3391a6c9e45a222f14fabe19ad7bbcbc07abf7bb7d，A2）与 fast
`20260819-134704-fast` 通过。

诚实边界：① 嵌入 ICC 一律 UNSUPPORTED（canonical sRGB ICC 字节等值原子登记为 T10b，T11 create 前补齐）；
② WebP 像素解码依赖 libwebp 原生绑定（无纯 Java 替代）；③ acceptance/1.0 未登记 available（T11 为界）。
本票无 DB/网络/UI/route/OpenAPI/S3/MinIO，无 Asset 聚合或 Adapter。T10b 与 T11 解锁。
