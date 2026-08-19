# 实现 Asset acceptance kernel 与独立 replay

Type: task
Status: open
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
