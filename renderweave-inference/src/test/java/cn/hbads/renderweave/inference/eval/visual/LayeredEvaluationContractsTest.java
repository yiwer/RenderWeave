package cn.hbads.renderweave.inference.eval.visual;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredEvaluationContractsTest {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @Test
    void strictAnnotationEnvelopeCoversEveryLayerAndRejectsTamper() {
        var annotation = annotation();
        var codec = new LayeredEvaluationJsonCodec();

        var encoded = codec.writeAnnotation(annotation);
        var identity = codec.annotationIdentity(annotation);
        var replay = codec.readAnnotation(encoded, identity);

        assertEquals(annotation, replay);
        assertTrue(identity.matches("renderweave-layered-annotation/1\\.0:[0-9a-f]{64}"));
        assertEquals(List.of("line-title"), replay.ocrLines().stream()
                .map(LayeredVisualAnnotation.OcrLine::lineId).toList());
        assertEquals(List.of(LayeredVisualAnnotation.RegionKind.TITLE,
                        LayeredVisualAnnotation.RegionKind.REPEATED_GROUP,
                        LayeredVisualAnnotation.RegionKind.ITEM,
                        LayeredVisualAnnotation.RegionKind.SLOT),
                replay.regions().stream().map(LayeredVisualAnnotation.Region::kind).toList());
        assertEquals(2, replay.evidence().size());
        assertEquals(1, replay.precedenceEdges().size());
        assertEquals(1, replay.repeatGroups().size());
        assertEquals(2, replay.entities().size());
        assertEquals(1, replay.relationships().size());
        assertEquals(1, replay.bindings().size());
        assertEquals(1, replay.candidate().fields().size());
        assertEquals(List.of("field-label"), replay.abstention().expectedUnresolvedOwnerIds());

        var json = new String(encoded, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> codec.readAnnotation(
                json.replace("Controlled title", "Changed title").getBytes(StandardCharsets.UTF_8), identity));
        assertThrows(IllegalArgumentException.class, () -> codec.readAnnotation(encoded,
                "renderweave-layered-annotation/1.0:" + SHA_B));
        assertThrows(IllegalArgumentException.class, () -> codec.readAnnotation(
                json.replaceFirst("\\{", "{\"unknown\":1,").getBytes(StandardCharsets.UTF_8), identity));
        assertThrows(IllegalArgumentException.class, () -> codec.readAnnotation(
                (json + " true").getBytes(StandardCharsets.UTF_8), identity));
    }

    @Test
    void annotationGraphIsClosedAcyclicAndCanonical() {
        var valid = annotation();

        assertThrows(IllegalArgumentException.class, () -> new LayeredVisualAnnotation(
                valid.annotationVersion(), valid.caseId(), valid.renderIdentity(), valid.sourceLicense(),
                valid.ocrLines(), valid.ocrTokens(), valid.regions(), valid.evidence(),
                List.of(new LayeredVisualAnnotation.PrecedenceEdge("slot-label", "missing")),
                valid.repeatGroups(), valid.entities(), valid.relationships(), valid.bindings(),
                valid.candidate(), valid.abstention()));
        assertThrows(IllegalArgumentException.class, () -> new LayeredVisualAnnotation(
                valid.annotationVersion(), valid.caseId(), valid.renderIdentity(), valid.sourceLicense(),
                valid.ocrLines(), valid.ocrTokens(), valid.regions(), valid.evidence(),
                List.of(new LayeredVisualAnnotation.PrecedenceEdge("slot-label", "title"),
                        new LayeredVisualAnnotation.PrecedenceEdge("title", "slot-label")),
                valid.repeatGroups(), valid.entities(), valid.relationships(), valid.bindings(),
                valid.candidate(), valid.abstention()));
        assertThrows(IllegalArgumentException.class, () -> new LayeredVisualAnnotation(
                valid.annotationVersion(), valid.caseId(), valid.renderIdentity(), valid.sourceLicense(),
                valid.ocrLines(), valid.ocrTokens(),
                List.of(valid.regions().getFirst(), valid.regions().getFirst()), valid.evidence(),
                valid.precedenceEdges(), valid.repeatGroups(), valid.entities(), valid.relationships(),
                valid.bindings(), valid.candidate(), valid.abstention()));
    }

    @Test
    void predictionIsEphemeralAndPersistentRecordIsPayloadSafeAndTamperEvident() {
        var prediction = new LayeredVisualPrediction(
                "case-a-v1",
                List.of(new LayeredVisualPrediction.OcrLine("line-title", "RUNTIME_OCR_SENTINEL")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(), LayeredVisualPrediction.Runtime.empty());
        assertFalse(prediction.toString().contains("RUNTIME_OCR_SENTINEL"));

        var record = LayeredEvaluationRecord.empty(
                "case-a-v1", "renderweave-layered-case/2.0:" + SHA_A,
                LayeredEvaluationRecord.Partition.DEV, "generic",
                LayeredEvaluationRecord.Difficulty.BASELINE);
        var codec = new LayeredEvaluationJsonCodec();
        var encoded = codec.writeRecord(record);
        var json = new String(encoded, StandardCharsets.UTF_8);
        var identity = codec.recordIdentity(record);

        assertEquals(record, codec.readRecord(encoded, identity));
        assertEquals(json, new String(codec.writeRecord(record), StandardCharsets.UTF_8));
        assertTrue(identity.matches("renderweave-layered-evaluation-record/1\\.0:[0-9a-f]{64}"));
        for (var forbidden : List.of("RUNTIME_OCR_SENTINEL", "ocrText", "prompt", "providerRequest",
                "providerResponse", "candidateJson", "boundingBox", "rootDocument", "base64")) {
            assertFalse(json.toLowerCase().contains(forbidden.toLowerCase()), forbidden);
        }
        assertThrows(IllegalArgumentException.class, () -> codec.readRecord(
                json.replace("\"providerAttempts\":0", "\"providerAttempts\":1")
                        .getBytes(StandardCharsets.UTF_8), identity));
        assertThrows(IllegalArgumentException.class, () -> codec.readRecord(
                json.replaceFirst("\\{", "{\"unknown\":1,").getBytes(StandardCharsets.UTF_8), identity));
        assertThrows(IllegalArgumentException.class, () -> codec.readRecord(
                json.replace("\"providerAttempts\":0", "\"providerAttempts\":null")
                        .getBytes(StandardCharsets.UTF_8), identity));
        assertThrows(IllegalArgumentException.class, () -> codec.readRecord(
                json.replace("\"providerAttempts\":0", "\"providerAttempts\":0.0")
                        .getBytes(StandardCharsets.UTF_8), identity));
    }

    @Test
    void evaluationIdentityBindsEveryR1InputAndDriftsOnAnyChangedComponent() {
        var baseline = identity("input-set-a");
        var replay = identity("input-set-a");
        var changedInput = identity("input-set-b");

        assertEquals(baseline.identity(), replay.identity());
        assertNotEquals(baseline.identity(), changedInput.identity());
        assertTrue(baseline.identity().matches("renderweave-layered-evaluation/1\\.0:[0-9a-f]{64}"));
        assertEquals(DocumentObservationSuccessorIdentity.VERSION + ":" + SHA_A,
                baseline.observationSuccessorIdentity());
        assertFalse(baseline.toString().contains("Controlled title"));

        var components = baseline.components();
        for (var key : components.keySet()) {
            var changed = new java.util.LinkedHashMap<>(components);
            changed.put(key, changed.get(key) + "-drift");
            assertThrows(IllegalArgumentException.class,
                    () -> LayeredEvaluationIdentity.fromComponents(changed, baseline.identity()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> LayeredEvaluationIdentity.fromComponents(Map.of("inputSetIdentity", "only-one"),
                        baseline.identity()));
    }

    private static LayeredEvaluationIdentity identity(String inputSet) {
        return new LayeredEvaluationIdentity(
                inputSet,
                LayeredVisualAnnotation.VERSION,
                "annotation-set:" + SHA_A,
                "deterministic-render/2.0:" + SHA_A,
                DocumentObservationSuccessorIdentity.VERSION + ":" + SHA_A,
                "document-observation-ir/1.0",
                "acquisition-policy/1.0:" + SHA_A,
                "rapidocr-local-process/1.0",
                "weight-sha256:" + SHA_A,
                "source-pixel-projection/1.0",
                "top-left-order/1.0",
                "stage-shape-catalog/1.0:" + SHA_A,
                "scripted-replay/1.0:" + SHA_A,
                "prompt-set-v45:" + SHA_A,
                "visual-validator/1.0:" + SHA_A,
                "candidate-materializer/1.0:" + SHA_A,
                "layered-evaluator/1.0:" + SHA_A,
                "budget-zero-provider/1.0",
                "deterministic-json-object/1.0");
    }

    static LayeredVisualAnnotation annotation() {
        var titleBox = LayeredVisualAnnotation.Geometry.box(500, 200, 9500, 1200);
        var groupBox = LayeredVisualAnnotation.Geometry.box(500, 1500, 9500, 8500);
        var itemBox = LayeredVisualAnnotation.Geometry.polygon(List.of(
                new LayeredVisualAnnotation.Point(600, 1700),
                new LayeredVisualAnnotation.Point(9400, 1700),
                new LayeredVisualAnnotation.Point(9400, 4500),
                new LayeredVisualAnnotation.Point(600, 4500)));
        var slotBox = LayeredVisualAnnotation.Geometry.box(900, 2100, 5000, 3000);
        return new LayeredVisualAnnotation(
                LayeredVisualAnnotation.VERSION, "case-a-v1", "render-sha256:" + SHA_A,
                LayeredVisualAnnotation.SourceLicense.SYNTHETIC,
                List.of(new LayeredVisualAnnotation.OcrLine(
                        "line-title", "Controlled title", List.of("token-title"), titleBox)),
                List.of(new LayeredVisualAnnotation.OcrToken(
                        "token-title", "line-title", "Controlled", titleBox)),
                List.of(
                        new LayeredVisualAnnotation.Region("title", LayeredVisualAnnotation.RegionKind.TITLE,
                                titleBox),
                        new LayeredVisualAnnotation.Region("repeated", LayeredVisualAnnotation.RegionKind.REPEATED_GROUP,
                                groupBox),
                        new LayeredVisualAnnotation.Region("item-one", LayeredVisualAnnotation.RegionKind.ITEM,
                                itemBox),
                        new LayeredVisualAnnotation.Region("slot-label", LayeredVisualAnnotation.RegionKind.SLOT,
                                slotBox)),
                List.of(
                        new LayeredVisualAnnotation.Evidence("evidence-title",
                                LayeredVisualAnnotation.OwnerKind.OCR_LINE, "line-title", titleBox),
                        new LayeredVisualAnnotation.Evidence("evidence-slot",
                                LayeredVisualAnnotation.OwnerKind.REGION, "slot-label", slotBox)),
                List.of(new LayeredVisualAnnotation.PrecedenceEdge("title", "slot-label")),
                List.of(new LayeredVisualAnnotation.RepeatGroup(
                        "repeated", 1,
                        List.of(new LayeredVisualAnnotation.RepeatItem("item-one", List.of("slot-label"))))),
                List.of(
                        new LayeredVisualAnnotation.Entity("root", "document", List.of("title")),
                        new LayeredVisualAnnotation.Entity("item", "item", List.of("repeated"))),
                List.of(new LayeredVisualAnnotation.Relationship(
                        "rel-items", "root", "item", "items",
                        LayeredVisualAnnotation.Multiplicity.MANY, List.of("repeated"))),
                List.of(new LayeredVisualAnnotation.Binding(
                        "binding-label", "slot-label", "item", "label")),
                new LayeredVisualAnnotation.CandidateGold(
                        "root",
                        List.of(new LayeredVisualAnnotation.CandidateField(
                                "field-label", "item", "label", LayeredVisualAnnotation.ValueKind.UNRESOLVED,
                                "binding-label")),
                        List.of("rel-items"), true),
                new LayeredVisualAnnotation.Abstention(List.of("field-label")));
    }
}
