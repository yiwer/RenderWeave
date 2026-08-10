package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateEvidenceKind;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies narrow, deterministic formatting repairs to IMAGE_ONLY provider output and visual inventory.
 * Business field identity, inferred kinds, evidence meaning, confidence and graph topology are never changed.
 * A multi-box family that demonstrably uses artifact pixels is converted to the Candidate 0..10000 contract.
 */
final class ImageOnlyCandidateCanonicalizer {

    InventoryResult canonicalize(
            VisualElementInventory inventory,
            Map<String, ImageDimensions> imageDimensions
    ) {
        var boxesByArtifact = new HashMap<String, List<CandidateBoundingBox>>();
        for (var element : inventory.elements()) {
            collectBoxes(element.evidence(), boxesByArtifact);
        }
        var pixelCoordinateArtifacts = pixelCoordinateArtifacts(boxesByArtifact, imageDimensions);
        if (pixelCoordinateArtifacts.isEmpty()) return new InventoryResult(inventory, 0);

        var normalizedElements = 0;
        var elements = new ArrayList<VisualElement>(inventory.elements().size());
        for (var element : inventory.elements()) {
            var normalized = false;
            var evidence = new ArrayList<CandidateEvidence>(element.evidence().size());
            for (var item : element.evidence()) {
                if (pixelCoordinateArtifacts.contains(item.artifactId())) {
                    evidence.add(CandidateEvidence.image(
                            item.artifactId(), normalizePixelBox(
                                    item.boundingBox(), imageDimensions.get(item.artifactId())
                            )
                    ));
                    normalized = true;
                } else {
                    evidence.add(item);
                }
            }
            if (normalized) normalizedElements++;
            elements.add(new VisualElement(
                    element.elementId(), element.kind(), element.proposedKey(), element.displayName(),
                    element.multiplicity(), element.valueHint(), evidence
            ));
        }
        return new InventoryResult(
                new VisualElementInventory(inventory.contractVersion(), elements), normalizedElements
        );
    }

    Result canonicalize(CandidateBundle candidate, Map<String, ImageDimensions> imageDimensions) {
        var pixelCoordinateArtifacts = pixelCoordinateArtifacts(candidate, imageDimensions);
        var usedSchemaKeys = new HashSet<String>();
        var problems = new ArrayList<CandidateProblem>();
        var schemas = new ArrayList<CandidateSchema>(candidate.schemas().size());
        for (var schemaIndex = 0; schemaIndex < candidate.schemas().size(); schemaIndex++) {
            var schema = candidate.schemas().get(schemaIndex);
            var schemaPointer = "/schemas/" + schemaIndex;
            var schemaKey = schema.proposedSchemaKey();
            if (!validUniqueSchemaKey(schemaKey, usedSchemaKeys)) {
                schemaKey = generatedSchemaKey(schema.candidateSchemaId(), usedSchemaKeys);
                problems.add(warning(
                        "CANDIDATE_SCHEMA_KEY_NORMALIZED", schema.candidateSchemaId(),
                        schemaPointer + "/proposedSchemaKey"
                ));
            }
            usedSchemaKeys.add(schemaKey);

            var fields = new ArrayList<CandidateField>(schema.fields().size());
            for (var fieldIndex = 0; fieldIndex < schema.fields().size(); fieldIndex++) {
                var field = schema.fields().get(fieldIndex);
                var fieldPointer = schemaPointer + "/fields/" + fieldIndex;
                var value = canonicalValue(
                        field.value(), field.candidateFieldId(),
                        fieldPointer + "/value", problems
                );
                var assessment = canonicalAssessment(
                        field.assessment(), field.candidateFieldId(), fieldPointer + "/assessment",
                        pixelCoordinateArtifacts, imageDimensions, problems
                );
                fields.add(new CandidateField(
                        field.candidateFieldId(), field.proposedFieldKey(), field.displayName(),
                        field.required(), value, field.source(), assessment
                ));
            }
            var assessment = canonicalAssessment(
                    schema.assessment(), schema.candidateSchemaId(), schemaPointer + "/assessment",
                    pixelCoordinateArtifacts, imageDimensions, problems
            );
            schemas.add(new CandidateSchema(
                    schema.candidateSchemaId(), schemaKey, schema.displayName(),
                    schema.source(), assessment, fields
            ));
        }
        return new Result(
                new CandidateBundle(candidate.contractVersion(), candidate.rootCandidateSchemaId(), schemas),
                problems
        );
    }

    Result canonicalize(CandidateBundle candidate) {
        return canonicalize(candidate, Map.of());
    }

    private static CandidateAssessment canonicalAssessment(
            CandidateAssessment assessment,
            UUID itemId,
            String pointer,
            Set<String> pixelCoordinateArtifacts,
            Map<String, ImageDimensions> imageDimensions,
            List<CandidateProblem> problems
    ) {
        var normalized = false;
        var evidence = new ArrayList<CandidateEvidence>(assessment.evidence().size());
        for (var index = 0; index < assessment.evidence().size(); index++) {
            var item = assessment.evidence().get(index);
            if (item.kind() == CandidateEvidenceKind.IMAGE
                    && item.artifactId() != null
                    && item.boundingBox() != null
                    && pixelCoordinateArtifacts.contains(item.artifactId())) {
                var dimensions = imageDimensions.get(item.artifactId());
                evidence.add(CandidateEvidence.image(
                        item.artifactId(), normalizePixelBox(item.boundingBox(), dimensions)
                ));
                normalized = true;
            } else {
                evidence.add(item);
            }
        }
        if (normalized) {
            problems.add(warning(
                    "IMAGE_EVIDENCE_PIXEL_COORDINATES_NORMALIZED", itemId,
                    pointer + "/evidence"
            ));
            return new CandidateAssessment(
                    assessment.confidenceBps(), assessment.inferred(), assessment.resolution(), evidence
            );
        }
        return assessment;
    }

    private static Set<String> pixelCoordinateArtifacts(
            CandidateBundle candidate,
            Map<String, ImageDimensions> imageDimensions
    ) {
        var boxesByArtifact = new HashMap<String, List<CandidateBoundingBox>>();
        for (var schema : candidate.schemas()) {
            collectBoxes(schema.assessment(), boxesByArtifact);
            for (var field : schema.fields()) collectBoxes(field.assessment(), boxesByArtifact);
        }
        return pixelCoordinateArtifacts(boxesByArtifact, imageDimensions);
    }

    private static Set<String> pixelCoordinateArtifacts(
            Map<String, List<CandidateBoundingBox>> boxesByArtifact,
            Map<String, ImageDimensions> imageDimensions
    ) {
        var result = new HashSet<String>();
        boxesByArtifact.forEach((artifactId, boxes) -> {
            var dimensions = imageDimensions.get(artifactId);
            if (dimensions != null && looksLikePixelCoordinateFamily(boxes, dimensions)) {
                result.add(artifactId);
            }
        });
        return Set.copyOf(result);
    }

    private static void collectBoxes(
            CandidateAssessment assessment,
            Map<String, List<CandidateBoundingBox>> boxesByArtifact
    ) {
        collectBoxes(assessment.evidence(), boxesByArtifact);
    }

    private static void collectBoxes(
            List<CandidateEvidence> evidenceItems,
            Map<String, List<CandidateBoundingBox>> boxesByArtifact
    ) {
        for (var evidence : evidenceItems) {
            if (evidence.kind() != CandidateEvidenceKind.IMAGE
                    || evidence.artifactId() == null
                    || evidence.boundingBox() == null) continue;
            boxesByArtifact.computeIfAbsent(evidence.artifactId(), ignored -> new ArrayList<>())
                    .add(evidence.boundingBox());
        }
    }

    private static boolean looksLikePixelCoordinateFamily(
            List<CandidateBoundingBox> boxes,
            ImageDimensions dimensions
    ) {
        if (boxes.size() < 2 || dimensions.width() < 1 || dimensions.height() < 1
                || dimensions.width() > 10_000 || dimensions.height() > 10_000) return false;
        var minLeft = Integer.MAX_VALUE;
        var minTop = Integer.MAX_VALUE;
        var maxRight = Integer.MIN_VALUE;
        var maxBottom = Integer.MIN_VALUE;
        for (var box : boxes) {
            if (box.left() < 0 || box.top() < 0
                    || box.right() > dimensions.width() || box.bottom() > dimensions.height()
                    || box.left() >= box.right() || box.top() >= box.bottom()) return false;
            minLeft = Math.min(minLeft, box.left());
            minTop = Math.min(minTop, box.top());
            maxRight = Math.max(maxRight, box.right());
            maxBottom = Math.max(maxBottom, box.bottom());
        }
        var spansCanvas = (long) (maxRight - minLeft) * 2 >= dimensions.width()
                && (long) (maxBottom - minTop) * 2 >= dimensions.height()
                && (long) minLeft * 10 <= (long) dimensions.width() * 3
                && (long) minTop * 10 <= (long) dimensions.height() * 3
                && (long) maxRight * 10 >= (long) dimensions.width() * 7
                && (long) maxBottom * 10 >= (long) dimensions.height() * 7;
        var reachesPixelBoundary = (long) maxRight * 100 >= (long) dimensions.width() * 98
                || (long) maxBottom * 100 >= (long) dimensions.height() * 98;
        return spansCanvas && reachesPixelBoundary;
    }

    private static CandidateBoundingBox normalizePixelBox(
            CandidateBoundingBox box,
            ImageDimensions dimensions
    ) {
        return new CandidateBoundingBox(
                scaleFloor(box.left(), dimensions.width()),
                scaleFloor(box.top(), dimensions.height()),
                scaleCeil(box.right(), dimensions.width()),
                scaleCeil(box.bottom(), dimensions.height())
        );
    }

    private static int scaleFloor(int coordinate, int dimension) {
        return (int) Math.min(10_000, (long) coordinate * 10_000 / dimension);
    }

    private static int scaleCeil(int coordinate, int dimension) {
        return (int) Math.min(10_000, ((long) coordinate * 10_000 + dimension - 1) / dimension);
    }

    private static CandidateValue canonicalValue(
            CandidateValue value,
            UUID itemId,
            String pointer,
            List<CandidateProblem> problems
    ) {
        if (value.kind() == CandidateValueKind.ARRAY && value.items() != null) {
            var items = canonicalValue(value.items(), itemId, pointer + "/items", problems);
            if (items != value.items()) {
                return new CandidateValue(
                        value.kind(), items, value.reference(), value.observedKinds(), value.constraints()
                );
            }
            return value;
        }
        if (supportedScalar(value.kind())
                && value.items() == null
                && value.reference() == null
                && !value.observedKinds().isEmpty()) {
            problems.add(warning(
                    "CANDIDATE_SCALAR_OBSERVED_KINDS_NORMALIZED", itemId,
                    pointer + "/observedKinds"
            ));
            return new CandidateValue(
                    value.kind(), null, null, List.of(), value.constraints()
            );
        }
        return value;
    }

    private static boolean supportedScalar(CandidateValueKind kind) {
        return kind == CandidateValueKind.TEXT
                || kind == CandidateValueKind.DECIMAL
                || kind == CandidateValueKind.DATE
                || kind == CandidateValueKind.TIME
                || kind == CandidateValueKind.BOOLEAN;
    }

    private static boolean validUniqueSchemaKey(String value, Set<String> used) {
        if (value == null || used.contains(value)) return false;
        try {
            SchemaKey.userProvided(value);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String generatedSchemaKey(UUID candidateSchemaId, Set<String> used) {
        var stem = "inferred-" + candidateSchemaId.toString().replace("-", "");
        var candidate = stem;
        for (var suffix = 2; used.contains(candidate); suffix++) {
            candidate = stem + "-" + suffix;
        }
        return candidate;
    }

    private static CandidateProblem warning(String code, UUID itemId, String pointer) {
        return new CandidateProblem(
                code, CandidateProblemSeverity.WARNING, itemId, pointer, Map.of()
        );
    }

    record Result(CandidateBundle candidate, List<CandidateProblem> problems) {
        Result {
            problems = List.copyOf(problems);
        }
    }

    record InventoryResult(VisualElementInventory inventory, int normalizedElements) {
        InventoryResult {
            if (normalizedElements < 0) throw new IllegalArgumentException("normalizedElements is invalid");
        }
    }

    record ImageDimensions(int width, int height) { }
}
