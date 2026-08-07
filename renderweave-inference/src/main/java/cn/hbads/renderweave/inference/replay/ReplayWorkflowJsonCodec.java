package cn.hbads.renderweave.inference.replay;

import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.InvalidCandidateContractException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class ReplayWorkflowJsonCodec {
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    String writeCheckpoint(ReplayWorkflowCheckpoint checkpoint) {
        return writeBounded(checkpoint, "REPLAY_CHECKPOINT_WRITE_FAILED");
    }

    ReplayWorkflowCheckpoint parseCheckpoint(String json) {
        try {
            requireBounded(json);
            return JSON.readValue(json, ReplayWorkflowCheckpoint.class);
        } catch (InvalidCandidateContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidCandidateContractException(
                    "REPLAY_CHECKPOINT_INVALID", "Replay checkpoint is invalid", exception
            );
        }
    }

    String writeProblems(List<CandidateProblem> problems) {
        return writeBounded(List.copyOf(problems), "REPLAY_PROBLEMS_WRITE_FAILED");
    }

    private static String writeBounded(Object value, String code) {
        try {
            var json = JSON.writeValueAsString(value);
            requireBounded(json);
            return json;
        } catch (InvalidCandidateContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidCandidateContractException(code, "Replay workflow JSON could not be written", exception);
        }
    }

    private static void requireBounded(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidCandidateContractException(
                    "REPLAY_CHECKPOINT_INVALID", "Replay workflow JSON is required", null
            );
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > CandidateJsonCodec.MAX_CANDIDATE_BYTES) {
            throw new InvalidCandidateContractException(
                    "REPLAY_CHECKPOINT_TOO_LARGE", "Replay workflow JSON exceeds 2 MiB", null
            );
        }
    }
}
