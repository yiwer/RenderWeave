package cn.hbads.renderweave.inference.candidate;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class CandidateProblemJsonCodec {
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public String write(List<CandidateProblem> problems) {
        try {
            var json = JSON.writeValueAsString(List.copyOf(problems));
            requireBounded(json);
            return json;
        } catch (InvalidCandidateContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidCandidateContractException(
                    "CANDIDATE_PROBLEMS_WRITE_FAILED", "Candidate problems could not be serialized", exception
            );
        }
    }

    public List<CandidateProblem> parse(String json) {
        try {
            requireBounded(json);
            var type = JSON.getTypeFactory().constructCollectionType(List.class, CandidateProblem.class);
            return List.copyOf(JSON.readValue(json, type));
        } catch (InvalidCandidateContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidCandidateContractException(
                    "CANDIDATE_PROBLEMS_INVALID", "Candidate problems do not match the strict contract", exception
            );
        }
    }

    private static void requireBounded(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidCandidateContractException(
                    "CANDIDATE_PROBLEMS_INVALID", "Candidate problems are required", null
            );
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > CandidateJsonCodec.MAX_CANDIDATE_BYTES) {
            throw new InvalidCandidateContractException(
                    "CANDIDATE_PROBLEMS_TOO_LARGE", "Candidate problems exceed 2 MiB", null
            );
        }
    }
}
