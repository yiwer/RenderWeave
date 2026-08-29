package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FontDescriptor;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ImageDescriptor;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.TechnicalDescriptor;

import java.math.BigDecimal;
import java.util.TreeMap;

/** Single canonical projection authority for the closed RenderResource wire value. */
final class RenderResourceCanonicalizer {

    private RenderResourceCanonicalizer() {
    }

    static CanonicalJson.CanonicalValue canonicalValue(Materializer.ResourceEntry resource) {
        var entry = new TreeMap<String, CanonicalJson.CanonicalValue>();
        entry.put("acceptanceProfileId",
                CanonicalJson.stringValue(resource.acceptanceProfileId()));
        entry.put("byteLength", CanonicalJson.decimalValue(
                BigDecimal.valueOf(resource.byteLength())));
        entry.put("expiresAt", CanonicalJson.decimalValue(
                BigDecimal.valueOf(resource.leaseExpiresAtEpochSecond())));
        entry.put("fetchUrl", CanonicalJson.stringValue(resource.fetchUrl()));
        entry.put("kind", CanonicalJson.stringValue(resource.kind().toLowerCase()));
        entry.put("mediaType", CanonicalJson.stringValue(resource.mediaType()));
        entry.put("resourceId", CanonicalJson.stringValue(resource.resourceId()));
        entry.put("sha256", CanonicalJson.stringValue(resource.sha256()));
        entry.put("technicalDescriptor",
                technicalDescriptorValue(resource.technicalDescriptor()));
        return CanonicalJson.objectValue(entry);
    }

    static long canonicalUtf8Length(Materializer.ResourceEntry resource) {
        return CanonicalJson.canonicalUtf8Length(canonicalValue(resource));
    }

    static String technicalDescriptorWire(TechnicalDescriptor descriptor) {
        return CanonicalJson.encode(technicalDescriptorValue(descriptor));
    }

    private static CanonicalJson.CanonicalValue technicalDescriptorValue(
            TechnicalDescriptor descriptor
    ) {
        var members = new TreeMap<String, CanonicalJson.CanonicalValue>();
        if (descriptor instanceof ImageDescriptor image) {
            members.put("colorEncoding", CanonicalJson.stringValue(image.colorEncoding().name()));
            members.put("encodedHeightPx", CanonicalJson.decimalValue(
                    BigDecimal.valueOf(image.encodedHeightPx())));
            members.put("encodedWidthPx", CanonicalJson.decimalValue(
                    BigDecimal.valueOf(image.encodedWidthPx())));
            members.put("frameCount", CanonicalJson.decimalValue(
                    BigDecimal.valueOf(image.frameCount())));
            members.put("kind", CanonicalJson.stringValue("image"));
            members.put("logicalHeightPx", CanonicalJson.decimalValue(
                    BigDecimal.valueOf(image.logicalHeightPx())));
            members.put("logicalWidthPx", CanonicalJson.decimalValue(
                    BigDecimal.valueOf(image.logicalWidthPx())));
            members.put("orientation", CanonicalJson.stringValue(image.orientation().name()));
        } else if (descriptor instanceof FontDescriptor font) {
            members.put("faceIndex", CanonicalJson.decimalValue(
                    BigDecimal.valueOf(font.faceIndex())));
            members.put("flavor", CanonicalJson.stringValue(font.flavor().name()));
            members.put("kind", CanonicalJson.stringValue("font"));
            members.put("unitsPerEm", CanonicalJson.decimalValue(
                    BigDecimal.valueOf(font.unitsPerEm())));
        } else {
            throw new IllegalStateException("unknown technical descriptor");
        }
        return CanonicalJson.objectValue(members);
    }
}
