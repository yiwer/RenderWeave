package cn.hbads.renderweave.inference.eval.visual;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredVisualCorpusTest {
    private static final String MANIFEST = "visual-eval/v2/manifest.json";
    private static final String IDENTITY_LOCK = "visual-eval/v2/identity-lock.json";
    private static final String V1_SOURCE = "visual-eval/v1/scenes.json";
    private static final String V1_SOURCE_SHA =
            "ca53d88763af161a1b1b22fa50774c56eae929affe5316157ae355fdb005b8b3";
    private static final String V1_FONT_SHA =
            "13640fa00ef05d468983c9d680fbd291c1fcce6093f99d4bb0072ecd01251514";

    @Test
    void loadsExactlySixtyClosedLayeredCasesWithoutChangingCorpusV1() throws Exception {
        var corpus = new LayeredVisualCorpus();

        assertEquals(LayeredVisualCorpus.VERSION, corpus.version());
        assertEquals(60, corpus.cases().size());
        assertEquals(45, corpus.cases().stream()
                .filter(item -> item.partition() == LayeredEvaluationRecord.Partition.DEV).count());
        assertEquals(15, corpus.cases().stream()
                .filter(item -> item.partition() == LayeredEvaluationRecord.Partition.HOLDOUT).count());
        assertEquals(V1_SOURCE_SHA, corpus.sourceScenesSha256());
        assertEquals("07958d0e968a9f1b7b64dea509d4b8398a112bf1d6e222aa20f35be59d97fe13",
                corpus.manifestSha256());
        assertEquals("renderweave-visual-stage-corpus/2.0:"
                        + "c596621eb680e7e10d42d2e1d1f926995cec9716cc6ef83a96a50ad53adc285c",
                corpus.corpusIdentity());
        assertEquals("renderweave-layered-annotation-set/2.0:"
                        + "a6f7796d0433bb59779a3e1b99fa3c20b3e49148d24eb69dfe17682414fa746a",
                corpus.annotationSetIdentity());

        assertEquals(V1_SOURCE_SHA, sha256(resource(V1_SOURCE)));
        assertEquals(V1_FONT_SHA, sha256(resource("visual-eval/v1/RenderWeaveVisualEval.ttf")));
        assertEquals("renderweave-visual-stage-corpus/1.0", VisualStageCorpus.VERSION);
    }

    @Test
    void everyRelevantLayerIsClosedAndEveryCaseIdentityIsUnique() {
        var corpus = new LayeredVisualCorpus();
        var caseIdentities = new HashSet<String>();
        var annotationIdentities = new HashSet<String>();
        var renderIdentities = new HashSet<String>();
        var ownerKinds = java.util.EnumSet.noneOf(LayeredVisualAnnotation.OwnerKind.class);
        var regionKinds = java.util.EnumSet.noneOf(LayeredVisualAnnotation.RegionKind.class);
        var repeatCases = 0;

        for (var item : corpus.cases()) {
            var annotation = item.annotation();
            assertEquals(item.caseId(), annotation.caseId());
            assertEquals(item.renderIdentity(), annotation.renderIdentity());
            assertEquals(LayeredVisualAnnotation.SourceLicense.SYNTHETIC, annotation.sourceLicense());
            assertFalse(annotation.ocrLines().isEmpty(), item.caseId());
            assertFalse(annotation.ocrTokens().isEmpty(), item.caseId());
            assertFalse(annotation.regions().isEmpty(), item.caseId());
            assertFalse(annotation.evidence().isEmpty(), item.caseId());
            assertFalse(annotation.entities().isEmpty(), item.caseId());
            assertFalse(annotation.bindings().isEmpty(), item.caseId());
            assertTrue(caseIdentities.add(item.caseIdentity()), item.caseId());
            assertTrue(annotationIdentities.add(item.annotationIdentity()), item.caseId());
            assertTrue(renderIdentities.add(item.renderIdentity()), item.caseId());
            ownerKinds.addAll(annotation.evidence().stream().map(LayeredVisualAnnotation.Evidence::ownerKind)
                    .toList());
            regionKinds.addAll(annotation.regions().stream().map(LayeredVisualAnnotation.Region::kind).toList());
            if (!annotation.repeatGroups().isEmpty()) repeatCases++;

            var bindingIds = annotation.bindings().stream().map(LayeredVisualAnnotation.Binding::bindingId)
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(annotation.candidate().fields().stream()
                    .allMatch(field -> bindingIds.contains(field.bindingId())), item.caseId());
        }
        assertEquals(60, caseIdentities.size());
        assertEquals(60, annotationIdentities.size());
        assertEquals(60, renderIdentities.size());
        assertEquals(Set.of(LayeredVisualAnnotation.OwnerKind.values()), ownerKinds);
        assertEquals(Set.of(LayeredVisualAnnotation.RegionKind.values()), regionKinds);
        assertTrue(repeatCases >= 40);
    }

    @Test
    void annotationAndRenderReplayAreByteStable() {
        var corpus = new LayeredVisualCorpus();
        var codec = new LayeredEvaluationJsonCodec();
        var rasterizer = new VisualStageRasterizer();
        for (var item : corpus.cases()) {
            assertArrayEquals(codec.writeAnnotation(item.annotation()),
                    codec.writeAnnotation(item.annotation()), item.caseId());
            assertEquals(item.annotationIdentity(), codec.annotationIdentity(item.annotation()), item.caseId());
            var rendered = rasterizer.render(item.renderCase());
            assertEquals("render-sha256:" + rendered.sha256(), item.renderIdentity(), item.caseId());
            assertArrayEquals(rendered.bytes(), rasterizer.render(item.renderCase()).bytes(), item.caseId());
        }
    }

    @Test
    void licenseInventoryAndHoldoutMutationPolicyAreClosed() {
        var corpus = new LayeredVisualCorpus();

        assertEquals(Set.of("repository-synthetic-scenes", "ofl-font-subset"),
                corpus.sourceInventory().stream().map(LayeredVisualCorpus.SourceAsset::assetId)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(LayeredVisualCorpus.AssetLicense.REPOSITORY_SYNTHETIC,
                        LayeredVisualCorpus.AssetLicense.OFL_1_1),
                corpus.sourceInventory().stream().map(LayeredVisualCorpus.SourceAsset::license)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals("MOVE_TO_DEV_AND_REPLACE_HOLDOUT/1.0", corpus.holdoutMutationPolicy());
        assertTrue(corpus.sourceInventory().stream().allMatch(item -> item.sha256().matches("[0-9a-f]{64}")));
    }

    @Test
    void manifestUnknownDuplicateAndIdentityTamperFailClosed() throws Exception {
        var manifest = new String(resource(MANIFEST), StandardCharsets.UTF_8);
        var identityLock = new String(resource(IDENTITY_LOCK), StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> new LayeredVisualCorpus(overriding(
                manifest.replaceFirst("\\{", "{\"unknown\":1,"))));
        assertThrows(IllegalStateException.class, () -> new LayeredVisualCorpus(overriding(
                manifest.replace("\"corpusVersion\":", "\"corpusVersion\":\"duplicate\",\"corpusVersion\":"))));
        assertThrows(IllegalStateException.class, () -> new LayeredVisualCorpus(overriding(
                manifest.replace(V1_SOURCE_SHA, "f".repeat(64)))));
        assertThrows(IllegalStateException.class, () -> new LayeredVisualCorpus(overriding(
                manifest.replace("OFL_1_1", "UNKNOWN_LICENSE"))));
        assertThrows(IllegalStateException.class, () -> new LayeredVisualCorpus(overriding(
                manifest, identityLock.replaceFirst("render-sha256:[0-9a-f]{64}",
                        "render-sha256:" + "0".repeat(64)))));
    }

    private static byte[] resource(String name) throws Exception {
        try (var input = LayeredVisualCorpusTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("missing " + name);
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static ClassLoader overriding(String manifest) {
        try {
            return overriding(manifest, new String(resource(IDENTITY_LOCK), StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static ClassLoader overriding(String manifest, String identityLock) {
        var parent = LayeredVisualCorpusTest.class.getClassLoader();
        return new ClassLoader(parent) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (MANIFEST.equals(name)) {
                    return new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8));
                }
                if (IDENTITY_LOCK.equals(name)) {
                    return new ByteArrayInputStream(identityLock.getBytes(StandardCharsets.UTF_8));
                }
                return super.getResourceAsStream(name);
            }
        };
    }
}
