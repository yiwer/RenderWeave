package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.InvalidCandidateContractException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

final class LiveWorkflowJsonCodec {
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    String write(LiveWorkflowCheckpoint checkpoint) {
        try {
            var value = JSON.writeValueAsString(checkpoint);
            requireBounded(value);
            return value;
        } catch (InvalidCandidateContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid(exception);
        }
    }

    LiveWorkflowCheckpoint parse(String value) {
        try {
            requireBounded(value);
            return JSON.readValue(value, LiveWorkflowCheckpoint.class);
        } catch (InvalidCandidateContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid(exception);
        }
    }

    private static void requireBounded(String value) {
        if (value == null || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > CandidateJsonCodec.MAX_CANDIDATE_BYTES) {
            throw invalid(null);
        }
    }

    private static InvalidCandidateContractException invalid(Throwable cause) {
        return new InvalidCandidateContractException(
                "LIVE_CHECKPOINT_INVALID", "Live workflow checkpoint is invalid", cause
        );
    }
}
