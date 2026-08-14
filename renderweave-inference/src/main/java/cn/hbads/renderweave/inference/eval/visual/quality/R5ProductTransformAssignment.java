package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.eval.visual.LayeredEvaluationRecord;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable pre-result assignment for the exact product raster transform gate. */
public final class R5ProductTransformAssignment {
    public static final String VERSION = "renderweave-r5-product-transform-assignment/1.0";
    private static final String RESOURCE = "visual-eval/r5/product-transform-assignment-v1.json";
    private static final List<String> CASE_IDS = List.of(
            "transit-board-v3", "restaurant-menu-v3", "hospital-schedule-v3", "transit-board-v5");
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();

    private final String identity;
    private final List<CaseAssignment> cases;

    private R5ProductTransformAssignment(String identity, List<CaseAssignment> cases) {
        this.identity = identity;
        this.cases = cases;
    }

    public static R5ProductTransformAssignment load() {
        try (var input = R5ProductTransformAssignment.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalid("R5_PRODUCT_ASSIGNMENT_RESOURCE_MISSING");
            var bytes = input.readAllBytes();
            var document = JSON.readValue(bytes, Document.class);
            var corpus = new LayeredVisualCorpus();
            if (!VERSION.equals(document.assignmentVersion())
                    || !LayeredVisualCorpus.VERSION.equals(document.corpusVersion())
                    || !corpus.corpusIdentity().equals(document.corpusIdentity())
                    || !"OVERVIEW".equals(document.staticViewKind())
                    || document.staticLongEdge() != 768) {
                throw invalid("R5_PRODUCT_ASSIGNMENT_AUTHORITY_DRIFT");
            }
            var source = List.copyOf(Objects.requireNonNull(document.caseAssignments(), "caseAssignments"));
            if (!source.stream().map(CaseDocument::caseId).toList().equals(CASE_IDS)) {
                throw invalid("R5_PRODUCT_ASSIGNMENT_CASE_SET_DRIFT");
            }
            var cases = source.stream().map(item -> validate(item, corpus)).toList();
            return new R5ProductTransformAssignment(VERSION + ":" + sha256(bytes), cases);
        } catch (IOException failure) {
            throw new IllegalStateException("R5_PRODUCT_ASSIGNMENT_INVALID", failure);
        }
    }

    public String identity() { return identity; }

    public List<CaseAssignment> cases() { return cases; }

    private static CaseAssignment validate(CaseDocument source, LayeredVisualCorpus corpus) {
        var expected = corpus.require(source.caseId());
        var partition = LayeredEvaluationRecord.Partition.valueOf(source.partition());
        if (partition != expected.partition()) throw invalid("R5_PRODUCT_ASSIGNMENT_PARTITION_DRIFT");
        var regions = List.copyOf(Objects.requireNonNull(source.regions(), "regions")).stream()
                .map(R5ProductTransformAssignment::validateRegion).toList();
        if (regions.size() != 2 || new HashSet<>(regions).size() != regions.size()) {
            throw invalid("R5_PRODUCT_ASSIGNMENT_REGION_SET_INVALID");
        }
        return new CaseAssignment(source.caseId(), partition, regions);
    }

    private static Region validateRegion(RegionDocument source) {
        if (!"view-00-overview-00".equals(source.baseViewId())
                || !"TIGHT_0000_BPS".equals(source.marginPreset())
                || !"INSPECT_LONG_EDGE_2400".equals(source.resolutionPreset())) {
            throw invalid("R5_PRODUCT_ASSIGNMENT_PRESET_DRIFT");
        }
        var values = List.copyOf(Objects.requireNonNull(source.boundingBox(), "boundingBox"));
        if (values.size() != 4 || values.stream().anyMatch(Objects::isNull)) {
            throw invalid("R5_PRODUCT_ASSIGNMENT_BOX_INVALID");
        }
        var left = values.get(0);
        var top = values.get(1);
        var right = values.get(2);
        var bottom = values.get(3);
        if (left < 0 || top < 0 || right > 10_000 || bottom > 10_000
                || left >= right || top >= bottom) {
            throw invalid("R5_PRODUCT_ASSIGNMENT_BOX_INVALID");
        }
        return new Region(source.baseViewId(),
                new CandidateBoundingBox(left, top, right, bottom),
                source.marginPreset(), source.resolutionPreset());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public record CaseAssignment(
            String caseId,
            LayeredEvaluationRecord.Partition partition,
            List<Region> regions
    ) {
        public CaseAssignment {
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(partition, "partition");
            regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        }
    }

    public record Region(
            String baseViewId,
            CandidateBoundingBox boundingBox,
            String marginPreset,
            String resolutionPreset
    ) {
        public Region {
            Objects.requireNonNull(baseViewId, "baseViewId");
            Objects.requireNonNull(boundingBox, "boundingBox");
            Objects.requireNonNull(marginPreset, "marginPreset");
            Objects.requireNonNull(resolutionPreset, "resolutionPreset");
        }

        @Override
        public String toString() {
            return "Region[baseViewId=" + baseViewId + ", marginPreset=" + marginPreset
                    + ", resolutionPreset=" + resolutionPreset + ", geometry=<redacted>]";
        }
    }

    private record Document(
            String assignmentVersion,
            String corpusVersion,
            String corpusIdentity,
            String staticViewKind,
            int staticLongEdge,
            List<CaseDocument> caseAssignments
    ) { }

    private record CaseDocument(String caseId, String partition, List<RegionDocument> regions) { }

    private record RegionDocument(
            String baseViewId,
            List<Integer> boundingBox,
            String marginPreset,
            String resolutionPreset
    ) { }
}
