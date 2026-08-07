package cn.hbads.renderweave.inference.replay;

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

public final class ReplayCorpus {
    public static final String CORPUS_VERSION = "renderweave-replay-corpus/1.0";
    private static final String RESOURCE = "replay-corpus/v1/manifest.json";
    private static final ObjectMapper JSON = JsonMapper.builder(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private final String corpusVersion;
    private final String profileId;
    private final List<ReplayCase> cases;
    private final Map<String, ReplayCase> byId;

    public ReplayCorpus() {
        this(ReplayCorpus.class.getClassLoader());
    }

    ReplayCorpus(ClassLoader classLoader) {
        var manifest = load(classLoader);
        if (!CORPUS_VERSION.equals(manifest.corpusVersion())) {
            throw new IllegalStateException("Unsupported replay corpus version " + manifest.corpusVersion());
        }
        corpusVersion = manifest.corpusVersion();
        profileId = manifest.profileId();
        cases = List.copyOf(manifest.cases());
        validateManifest();
        var index = new LinkedHashMap<String, ReplayCase>();
        for (var replayCase : cases) {
            if (index.putIfAbsent(replayCase.fixtureId(), replayCase) != null) {
                throw new IllegalStateException("Duplicate replay fixture " + replayCase.fixtureId());
            }
        }
        byId = Map.copyOf(index);
    }

    private void validateManifest() {
        if (!"replay-v1".equals(profileId)) {
            throw new IllegalStateException("Replay corpus must bind to replay-v1");
        }
        if (cases.size() != 60) throw new IllegalStateException("Replay corpus must contain exactly 60 cases");
        var modeCounts = cases.stream().collect(Collectors.groupingBy(
                ReplayCase::mode, Collectors.counting()
        ));
        for (var mode : cn.hbads.renderweave.inference.input.InferenceMode.values()) {
            if (modeCounts.getOrDefault(mode, 0L) != 20L) {
                throw new IllegalStateException("Replay corpus must contain 20 cases for " + mode);
            }
        }
        for (var fixture : cases) validateFixture(fixture);
    }

    private static void validateFixture(ReplayCase fixture) {
        if (!fixture.fixtureId().matches("[a-z0-9][a-z0-9-]{0,127}")) {
            throw new IllegalStateException("Replay fixture id is invalid: " + fixture.fixtureId());
        }
        if (fixture.imageCount() < 0 || fixture.imageCount() > 10
                || fixture.jsonSamples().size() > 20) {
            throw new IllegalStateException("Replay fixture input count is invalid: " + fixture.fixtureId());
        }
        var validEnvelope = switch (fixture.mode()) {
            case IMAGE_ONLY -> fixture.imageCount() > 0
                    && fixture.jsonSamples().isEmpty() && !fixture.visualSchemas().isEmpty();
            case JSON_ONLY -> fixture.imageCount() == 0
                    && !fixture.jsonSamples().isEmpty() && fixture.visualSchemas().isEmpty();
            case COMBINED -> fixture.imageCount() > 0
                    && !fixture.jsonSamples().isEmpty() && !fixture.visualSchemas().isEmpty();
        };
        if (!validEnvelope) {
            throw new IllegalStateException("Replay fixture mode/input mismatch: " + fixture.fixtureId());
        }
        if (Set.copyOf(fixture.expectedRootFields()).size() != fixture.expectedRootFields().size()) {
            throw new IllegalStateException("Replay expected root fields must be unique: " + fixture.fixtureId());
        }
        if (fixture.expectedProblemCodes().stream()
                .anyMatch(code -> code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}"))) {
            throw new IllegalStateException("Replay expected problem code is invalid: " + fixture.fixtureId());
        }
        validateVisualEvidence(fixture);
    }

    private static void validateVisualEvidence(ReplayCase fixture) {
        var schemaKeys = new java.util.HashSet<String>();
        for (var schema : fixture.visualSchemas()) {
            if (schema.schemaKey() == null || schema.displayName() == null
                    || !schemaKeys.add(schema.schemaKey())) {
                throw new IllegalStateException("Replay visual schema is invalid: " + fixture.fixtureId());
            }
            validateEvidence(schema.confidenceBps(), schema.imageOrdinal(), schema.boundingBox(), fixture);
            var fieldKeys = new java.util.HashSet<String>();
            for (var field : schema.fields()) {
                if (field.fieldKey() == null || field.displayName() == null || field.type() == null
                        || !fieldKeys.add(field.fieldKey())) {
                    throw new IllegalStateException("Replay visual field is invalid: " + fixture.fixtureId());
                }
                validateEvidence(field.confidenceBps(), field.imageOrdinal(), field.boundingBox(), fixture);
            }
        }
    }

    private static void validateEvidence(
            int confidenceBps,
            int imageOrdinal,
            List<Integer> boundingBox,
            ReplayCase fixture
    ) {
        if (confidenceBps < 0 || confidenceBps > 10_000
                || imageOrdinal < 0 || imageOrdinal >= fixture.imageCount()
                || boundingBox.size() != 4 || boundingBox.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalStateException("Replay visual evidence is invalid: " + fixture.fixtureId());
        }
    }

    public String corpusVersion() {
        return corpusVersion;
    }

    public String profileId() {
        return profileId;
    }

    public List<ReplayCase> cases() {
        return cases;
    }

    public ReplayCase require(String fixtureId) {
        var fixture = byId.get(fixtureId);
        if (fixture == null) throw new IllegalArgumentException("Unknown replay fixture: " + fixtureId);
        return fixture;
    }

    private static Manifest load(ClassLoader classLoader) {
        try (var input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing replay corpus resource " + RESOURCE);
            return JSON.readValue(input, Manifest.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Replay corpus cannot be loaded", exception);
        }
    }

    private record Manifest(String corpusVersion, String profileId, List<ReplayCase> cases) { }
}
