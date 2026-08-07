package cn.hbads.renderweave.inference.candidate;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class CandidateJsonCodec {
    public static final int MAX_CANDIDATE_BYTES = 2 * 1024 * 1024;

    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public String write(CandidateBundle candidate) {
        try {
            var value = JSON.writeValueAsString(candidate);
            if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CANDIDATE_BYTES) {
                throw new InvalidCandidateContractException(
                        "CANDIDATE_TOO_LARGE", "Candidate exceeds 2 MiB", null
                );
            }
            return value;
        } catch (InvalidCandidateContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidCandidateContractException(
                    "CANDIDATE_WRITE_FAILED", "Candidate could not be serialized", exception
            );
        }
    }

    public CandidateBundle parse(String value) {
        if (value == null) {
            throw new InvalidCandidateContractException("CANDIDATE_JSON_INVALID", "Candidate JSON is required", null);
        }
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CANDIDATE_BYTES) {
            throw new InvalidCandidateContractException("CANDIDATE_TOO_LARGE", "Candidate exceeds 2 MiB", null);
        }
        if (value.isBlank()) {
            throw new InvalidCandidateContractException("CANDIDATE_JSON_INVALID", "Candidate JSON is required", null);
        }
        try {
            return JSON.readValue(value, CandidateBundle.class);
        } catch (Exception exception) {
            throw new InvalidCandidateContractException(
                    "CANDIDATE_JSON_INVALID", "Candidate JSON does not match the strict contract", exception
            );
        }
    }
}
