package cn.hbads.renderweave.inference.certification;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

public final class ImageOnlyCertificationP0ReportJsonCodec {
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public ImageOnlyCertificationP0ReportEnvelope envelope(ImageOnlyCertificationP0Report report) {
        return new ImageOnlyCertificationP0ReportEnvelope(
                ImageOnlyCertificationP0Report.VERSION + ":" + CertificationIdentity.sha256Text(
                        new String(encodeReport(report), StandardCharsets.UTF_8)), report);
    }

    public byte[] write(ImageOnlyCertificationP0ReportEnvelope envelope) {
        try {
            return JSON.writeValueAsBytes(envelope);
        } catch (Exception failure) {
            throw new IllegalArgumentException("IMAGE_ONLY_P0_REPORT_INVALID", failure);
        }
    }

    private static byte[] encodeReport(ImageOnlyCertificationP0Report report) {
        try {
            return JSON.writeValueAsBytes(report);
        } catch (Exception failure) {
            throw new IllegalArgumentException("IMAGE_ONLY_P0_REPORT_INVALID", failure);
        }
    }
}
