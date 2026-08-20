package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.spi.AssetFetchEndpoint;
import cn.hbads.renderweave.asset.spi.AssetPersistence;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Domain-separated encryption and signing keys for renderer-only Asset leases. */
final class AssetResolutionSecrets {

    private static final int PAYLOAD_VERSION = 1;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private static final byte[] ENCRYPTION_DOMAIN =
            "renderweave.asset-resolution-encryption/1\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SIGNING_KEY_DOMAIN =
            "renderweave.asset-fetch-signing-key/1\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SIGNATURE_DOMAIN =
            "renderweave.asset-fetch-lease/1\0".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec signingKey;
    private final SecureRandom entropy = new SecureRandom();

    AssetResolutionSecrets(byte[] masterKey) {
        if (masterKey == null || masterKey.length != 32) {
            throw new IllegalArgumentException("asset resolution master key must be exactly 32 bytes");
        }
        this.encryptionKey = new SecretKeySpec(derive(masterKey, ENCRYPTION_DOMAIN), "AES");
        this.signingKey = new SecretKeySpec(derive(masterKey, SIGNING_KEY_DOMAIN), "HmacSHA256");
    }

    String newLeaseHandle() {
        byte[] bytes = new byte[32];
        entropy.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    SealedSelection seal(AssetPersistence.RenderSelection selection) throws SecretFailure {
        try {
            byte[] nonce = new byte[GCM_NONCE_BYTES];
            entropy.nextBytes(nonce);
            byte[] payload = encodePayload(selection);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey,
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(headerBytes(RecordHeader.from(selection)));
            return new SealedSelection(nonce, cipher.doFinal(payload));
        } catch (GeneralSecurityException | IOException failure) {
            throw new SecretFailure(failure);
        }
    }

    AssetPersistence.RenderSelection open(
            RecordHeader header,
            byte[] nonce,
            byte[] ciphertext
    ) throws SecretFailure {
        try {
            if (nonce == null || nonce.length != GCM_NONCE_BYTES
                    || ciphertext == null || ciphertext.length <= 16
                    || ciphertext.length > MAX_PAYLOAD_BYTES + 16) {
                throw new SecretFailure(new IllegalArgumentException("invalid sealed selection"));
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey,
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(headerBytes(header));
            byte[] payload = cipher.doFinal(ciphertext);
            return decodePayload(header, payload);
        } catch (GeneralSecurityException | IOException | RuntimeException failure) {
            throw new SecretFailure(failure);
        }
    }

    String sign(AssetFetchEndpoint.IssueRequest request) throws SecretFailure {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            mac.update(signatureBytes(request));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (GeneralSecurityException | IOException failure) {
            throw new SecretFailure(failure);
        }
    }

    boolean verifies(AssetFetchEndpoint.IssueRequest request, String encodedSignature) {
        try {
            byte[] supplied = Base64.getUrlDecoder().decode(encodedSignature);
            byte[] expected = Base64.getUrlDecoder().decode(sign(request));
            return MessageDigest.isEqual(expected, supplied);
        } catch (RuntimeException | SecretFailure invalid) {
            return false;
        }
    }

    private static byte[] encodePayload(AssetPersistence.RenderSelection selection)
            throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(PAYLOAD_VERSION);
            writeString(out, selection.ownerScope().value());
            writeString(out, selection.assetId().value());
            writeString(out, selection.kind().name());
            writeString(out, selection.rendererAudience());
            out.writeLong(selection.content().contentVersion());
            writeString(out, selection.content().sha256());
            writeString(out, selection.content().mediaType());
            out.writeLong(selection.content().byteLength());
            writeDescriptor(out, selection.content().descriptor());
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("selection payload exceeds fixed limit");
        }
        return payload;
    }

    private static AssetPersistence.RenderSelection decodePayload(
            RecordHeader header,
            byte[] payload
    ) throws IOException {
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("selection payload exceeds fixed limit");
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != PAYLOAD_VERSION) {
                throw new IOException("unknown selection payload version");
            }
            var ownerScope = new AssetApplication.OwnerScope(readString(in));
            var assetId = AssetApplication.AssetId.of(readString(in));
            var kind = AssetAcceptanceAuthority.AssetKind.valueOf(readString(in));
            String audience = readString(in);
            var content = new AssetPersistence.ResolutionContent(
                    in.readLong(),
                    readString(in),
                    readString(in),
                    in.readLong(),
                    readDescriptor(in)
            );
            if (in.available() != 0) {
                throw new IOException("trailing selection payload bytes");
            }
            return new AssetPersistence.RenderSelection(
                    header.renderRequestId(),
                    ownerScope,
                    header.resourceId(),
                    assetId,
                    kind,
                    audience,
                    header.requestFingerprint(),
                    header.leaseHandle(),
                    content,
                    header.issuedAtEpochMilli(),
                    header.leaseExpiresAtEpochSecond(),
                    header.recordExpiresAtEpochMilli()
            );
        }
    }

    private static void writeDescriptor(
            DataOutputStream out,
            AssetAcceptanceAuthority.TechnicalDescriptor descriptor
    ) throws IOException {
        if (descriptor instanceof AssetAcceptanceAuthority.ImageDescriptor image) {
            out.writeByte(1);
            out.writeInt(image.encodedWidthPx());
            out.writeInt(image.encodedHeightPx());
            writeString(out, image.orientation().name());
            out.writeInt(image.logicalWidthPx());
            out.writeInt(image.logicalHeightPx());
            out.writeInt(image.frameCount());
            writeString(out, image.colorEncoding().name());
            return;
        }
        var font = (AssetAcceptanceAuthority.FontDescriptor) descriptor;
        out.writeByte(2);
        out.writeInt(font.faceIndex());
        writeString(out, font.flavor().name());
        out.writeInt(font.unitsPerEm());
    }

    private static AssetAcceptanceAuthority.TechnicalDescriptor readDescriptor(DataInputStream in)
            throws IOException {
        int type = in.readUnsignedByte();
        if (type == 1) {
            return new AssetAcceptanceAuthority.ImageDescriptor(
                    in.readInt(),
                    in.readInt(),
                    AssetAcceptanceAuthority.Orientation.valueOf(readString(in)),
                    in.readInt(),
                    in.readInt(),
                    in.readInt(),
                    AssetAcceptanceAuthority.ColorEncoding.valueOf(readString(in))
            );
        }
        if (type == 2) {
            return new AssetAcceptanceAuthority.FontDescriptor(
                    in.readInt(),
                    AssetAcceptanceAuthority.FontFlavor.valueOf(readString(in)),
                    in.readInt()
            );
        }
        throw new IOException("unknown technical descriptor type");
    }

    private static byte[] headerBytes(RecordHeader header) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            writeString(out, header.renderRequestId());
            writeString(out, header.resourceId());
            writeString(out, header.requestFingerprint());
            writeString(out, header.leaseHandle());
            out.writeLong(header.issuedAtEpochMilli());
            out.writeLong(header.leaseExpiresAtEpochSecond());
            out.writeLong(header.recordExpiresAtEpochMilli());
        }
        return bytes.toByteArray();
    }

    private static byte[] signatureBytes(AssetFetchEndpoint.IssueRequest request)
            throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.write(SIGNATURE_DOMAIN);
            writeString(out, request.leaseHandle());
            writeString(out, request.requestFingerprint());
            writeString(out, request.renderRequestId());
            writeString(out, request.resourceId());
            writeString(out, request.assetId().value());
            out.writeLong(request.contentVersion());
            writeString(out, request.sha256());
            out.writeLong(request.byteLength());
            writeString(out, request.rendererAudience());
            out.writeLong(request.expiresAtEpochSecond());
        }
        return bytes.toByteArray();
    }

    private static byte[] derive(byte[] masterKey, byte[] domain) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            return mac.doFinal(domain);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_PAYLOAD_BYTES || length > in.available()) {
            throw new IOException("invalid selection string length");
        }
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    record SealedSelection(byte[] nonce, byte[] ciphertext) {
        SealedSelection {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }

    record RecordHeader(
            String renderRequestId,
            String resourceId,
            String requestFingerprint,
            String leaseHandle,
            long issuedAtEpochMilli,
            long leaseExpiresAtEpochSecond,
            long recordExpiresAtEpochMilli
    ) {
        static RecordHeader from(AssetPersistence.RenderSelection selection) {
            return new RecordHeader(
                    selection.renderRequestId(),
                    selection.resourceId(),
                    selection.requestFingerprint(),
                    selection.leaseHandle(),
                    selection.issuedAtEpochMilli(),
                    selection.leaseExpiresAtEpochSecond(),
                    selection.recordExpiresAtEpochMilli()
            );
        }
    }

    static final class SecretFailure extends Exception {
        SecretFailure(Throwable cause) {
            super(cause);
        }
    }
}
