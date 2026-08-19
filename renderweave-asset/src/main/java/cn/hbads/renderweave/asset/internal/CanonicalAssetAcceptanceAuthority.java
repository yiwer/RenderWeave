package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

final class CanonicalAssetAcceptanceAuthority implements AssetAcceptanceAuthority {

    static final int IMAGE_RAW_BYTE_LIMIT = 64 * 1024 * 1024;
    static final int FONT_RAW_BYTE_LIMIT = 32 * 1024 * 1024;

    @Override
    public Acceptance admit(byte[] rawBytes, AssetKind kind) {
        Objects.requireNonNull(rawBytes, "rawBytes");
        Objects.requireNonNull(kind, "kind");
        if (rawBytes.length == 0) {
            return new Rejected(
                    FailureCode.ASSET_CONTENT_INVALID,
                    FailureStage.ASSET_STRUCTURE,
                    "/",
                    Optional.empty()
            );
        }
        int rawByteLimit = kind == AssetKind.IMAGE ? IMAGE_RAW_BYTE_LIMIT : FONT_RAW_BYTE_LIMIT;
        if (rawBytes.length > rawByteLimit) {
            return new Rejected(
                    FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED,
                    FailureStage.ASSET_STRUCTURE,
                    "/",
                    Optional.of(Limit.RAW_BYTES)
            );
        }
        if (kind == AssetKind.IMAGE) {
            if (PngAdmission.looksLikePng(rawBytes)) {
                return PngAdmission.admit(rawBytes);
            }
            if (JpegAdmission.looksLikeJpeg(rawBytes)) {
                return JpegAdmission.admit(rawBytes);
            }
            if (WebpAdmission.looksLikeWebp(rawBytes)) {
                return WebpAdmission.admit(rawBytes);
            }
            return new Rejected(
                    FailureCode.ASSET_CONTENT_INVALID,
                    FailureStage.ASSET_STRUCTURE,
                    "/",
                    Optional.empty()
            );
        }
        // FONT admission lands in FontAdmission; OTTO/CFF flavor fails closed until its
        // analyzer increment lands.
        return FontAdmission.admit(rawBytes);
    }

    static Admitted admitted(AssetKind kind, byte[] rawBytes, TechnicalDescriptor descriptor) {
        try {
            var sha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(rawBytes)
            );
            return new Admitted(kind, rawBytes.length, sha256, descriptor, ACCEPTANCE_PROFILE_ID);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
