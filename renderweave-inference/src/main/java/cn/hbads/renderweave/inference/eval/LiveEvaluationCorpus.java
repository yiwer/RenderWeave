package cn.hbads.renderweave.inference.eval;

import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.replay.ReplayCorpus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class LiveEvaluationCorpus {
    public static final String VERSION = "renderweave-live-eval/1.0";
    private static final String RESOURCE = "live-eval/v1/gold.json";
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Map<String, LiveEvaluationCase> cases;

    public LiveEvaluationCorpus() {
        this(LiveEvaluationCorpus.class.getClassLoader());
    }

    LiveEvaluationCorpus(ClassLoader classLoader) {
        try (var input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing live evaluation corpus");
            var document = JSON.readValue(input.readAllBytes(), Document.class);
            if (!VERSION.equals(document.corpusVersion()) || document.cases() == null
                    || document.cases().size() != 60) {
                throw new IllegalStateException("Live evaluation corpus version or size is invalid");
            }
            var loaded = new LinkedHashMap<String, LiveEvaluationCase>();
            for (var item : document.cases()) {
                if (loaded.putIfAbsent(item.caseId(), item) != null) {
                    throw new IllegalStateException("Duplicate live evaluation case " + item.caseId());
                }
            }
            validateCoverage(List.copyOf(loaded.values()));
            cases = java.util.Collections.unmodifiableMap(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("Live evaluation corpus cannot be loaded", exception);
        }
    }

    public LiveEvaluationCase require(String caseId) {
        var value = cases.get(caseId);
        if (value == null) throw new IllegalArgumentException("Unknown live evaluation case: " + caseId);
        return value;
    }

    public List<LiveEvaluationCase> cases() {
        return List.copyOf(cases.values());
    }

    private static void validateCoverage(List<LiveEvaluationCase> cases) {
        if (cases.stream().map(LiveEvaluationCase::fixtureId).distinct().count() != 60) {
            throw new IllegalStateException("Live evaluation fixture ids must be unique");
        }
        var replay = new ReplayCorpus();
        var replayIds = replay.cases().stream().map(item -> item.fixtureId()).collect(Collectors.toSet());
        var liveIds = cases.stream().map(LiveEvaluationCase::fixtureId).collect(Collectors.toSet());
        if (!replayIds.equals(liveIds)) {
            throw new IllegalStateException("Live evaluation corpus must cover the repository synthetic corpus");
        }
        for (var mode : InferenceMode.values()) {
            if (cases.stream().filter(item -> item.mode() == mode).count() != 20) {
                throw new IllegalStateException("Live evaluation corpus must contain 20 cases for " + mode);
            }
        }
        var partitions = cases.stream().collect(Collectors.groupingBy(
                LiveEvaluationCase::partition, Collectors.counting()
        ));
        if (partitions.getOrDefault(LiveEvaluationPartition.DEV, 0L) != 45
                || partitions.getOrDefault(LiveEvaluationPartition.HOLDOUT, 0L) != 15) {
            throw new IllegalStateException("Live evaluation corpus must contain 45 dev and 15 holdout cases");
        }
        for (var item : cases) {
            var fixture = replay.require(item.fixtureId());
            if (fixture.mode() != item.mode() || fixture.expectedSchemaCount() != item.expectedSchemaCount()
                    || !Set.copyOf(fixture.expectedRootFields()).equals(item.expectedRootShapes().keySet())) {
                throw new IllegalStateException("Live gold does not match replay fixture envelope " + item.fixtureId());
            }
        }
    }

    private record Document(String corpusVersion, List<LiveEvaluationCase> cases) { }
}
