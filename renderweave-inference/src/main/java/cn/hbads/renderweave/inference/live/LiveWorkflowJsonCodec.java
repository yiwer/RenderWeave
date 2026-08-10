package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.InvalidCandidateContractException;
import cn.hbads.renderweave.inference.run.InferenceStage;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

final class LiveWorkflowJsonCodec {
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
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
            var tree = JSON.readTree(value);
            var version = tree.path("checkpointVersion").isString()
                    ? tree.path("checkpointVersion").asText() : null;
            if (LiveWorkflowCheckpoint.VERSION.equals(version)) {
                return JSON.treeToValue(tree, LiveWorkflowCheckpoint.class);
            }
            if (LegacyCheckpoint.VERSION.equals(version)) {
                var legacy = JSON.treeToValue(tree, LegacyCheckpoint.class);
                return new LiveWorkflowCheckpoint(
                        LiveWorkflowCheckpoint.VERSION, legacy.completedStage(), legacy.structureCalls(),
                        legacy.repairRounds(), null, null, null,
                        legacy.outputValid(), legacy.candidate(), legacy.validationProblems()
                );
            }
            if (version == null && runStoreNormalizeEnvelope(tree)) {
                return LiveWorkflowCheckpoint.started();
            }
            throw new IllegalArgumentException("Unsupported live checkpoint version");
        } catch (InvalidCandidateContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid(exception);
        }
    }

    private static boolean runStoreNormalizeEnvelope(tools.jackson.databind.JsonNode tree) {
        if (!tree.isObject() || tree.size() != 2
                || !"NORMALIZE".equals(tree.path("completedStage").asText())) {
            return false;
        }
        var fingerprint = tree.path("inputFingerprint");
        if (fingerprint.isString() && fingerprint.asText().matches("[a-f0-9]{64}")) {
            return true;
        }
        var retry = tree.path("retryOfRunId");
        if (!retry.isString()) return false;
        try {
            UUID.fromString(retry.asText());
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
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

    private record LegacyCheckpoint(
            String checkpointVersion,
            InferenceStage completedStage,
            int structureCalls,
            int repairRounds,
            boolean outputValid,
            CandidateBundle candidate,
            List<CandidateProblem> validationProblems
    ) {
        private static final String VERSION = "renderweave-live-checkpoint/1.0";
    }
}
