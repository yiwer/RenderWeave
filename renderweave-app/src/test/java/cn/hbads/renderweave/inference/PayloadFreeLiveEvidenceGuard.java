package cn.hbads.renderweave.inference;

import java.util.List;
import java.util.Objects;

/** Final write-boundary guard for live evidence; messages never echo the matched content. */
final class PayloadFreeLiveEvidenceGuard {
    private static final List<String> FORBIDDEN = List.of(
            "\"providerRequestId\"",
            "\"candidateJson\"",
            "\"prompt\"",
            "\"missingEntities\"",
            "\"unexpectedEntities\"",
            "\"missingFields\"",
            "\"unexpectedFields\"",
            "\"missingRootFields\"",
            "\"unexpectedRootFields\"",
            "\"typeMismatches\"",
            "\"edgeMismatches\"",
            "\"shapeMismatches\"",
            "\"jsonPointer\"",
            "\"itemId\"",
            "\"args\"",
            "\"apiKey\"",
            "DASHSCOPE_API_KEY",
            "data:image;base64",
            "Bearer "
    );

    private PayloadFreeLiveEvidenceGuard() { }

    static String requirePayloadFree(String value) {
        Objects.requireNonNull(value, "value");
        for (var forbidden : FORBIDDEN) {
            if (value.contains(forbidden)) {
                throw new IllegalArgumentException("LIVE_EVIDENCE_CONTAINS_FORBIDDEN_PAYLOAD");
            }
        }
        return value;
    }
}
