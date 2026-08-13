package cn.hbads.renderweave.inference;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadFreeLiveEvidenceGuardTest {

    @Test
    void acceptsScalarMetricsAndStableProblemCodes() {
        var safe = """
                {"caseId":"live-image-01","problemCodeCounts":{"AI_EVIDENCE_MISSING":2}}
                """;

        assertThat(PayloadFreeLiveEvidenceGuard.requirePayloadFree(safe)).isEqualTo(safe);
    }

    @Test
    void rejectsPayloadBearingEvidenceFieldsAndValuesWithoutEchoingThem() {
        var forbidden = List.of(
                "{\"providerRequestId\":\"request-123\"}",
                "{\"candidateJson\":\"{}\"}",
                "{\"missingFields\":[\"customerName\"]}",
                "{\"missingRootFields\":[\"customerName\"]}",
                "{\"shapeMismatches\":[\"/customerName\"]}",
                "{\"jsonPointer\":\"/customerName\"}",
                "{\"value\":\"data:image;base64,AAAA\"}",
                "{\"detail\":\"DASHSCOPE_TOKEN_API_KEY\"}",
                "{\"authorization\":\"Bearer example\"}"
        );

        for (var value : forbidden) {
            assertThatThrownBy(() -> PayloadFreeLiveEvidenceGuard.requirePayloadFree(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("LIVE_EVIDENCE_CONTAINS_FORBIDDEN_PAYLOAD")
                    .hasMessageNotContaining("customerName")
                    .hasMessageNotContaining("request-123");
        }
    }
}
