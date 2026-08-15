package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.InputNormalizer;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.vision.ArtifactSet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Evaluation-only proof that static visual acquisition starts at the product normalization seam. */
public final class ProductViewHarness {
    public static final String VERSION = "renderweave-r5p-product-view-harness/1.0";
    private static final String PLAN_IDENTITY_VERSION = "renderweave-r5p-static-plan/1.0";
    private static final String EVIDENCE_IDENTITY_VERSION =
            "renderweave-r5p-harness-evidence/1.0";

    public ConformanceOutcome acquireCompleteStaticPlan(
            List<RawRasterFixture> fixtures,
            StaticPlanAcquisition acquisition
    ) {
        Objects.requireNonNull(acquisition, "acquisition");
        var prepared = prepare(fixtures);
        var planned = plannedViews(prepared.plan());
        var acquired = List.copyOf(Objects.requireNonNull(
                acquisition.acquire(List.copyOf(planned)), "acquisitionTrace"));
        validateTrace(planned, acquired);
        var summaries = planned.stream().map(PlannedView::summary).toList();
        var evidenceIdentity = EVIDENCE_IDENTITY_VERSION + ":" + framedSha256(
                evidenceFrames(prepared, summaries, acquired));
        return new ConformanceOutcome(
                VERSION,
                prepared.normalizationProvenance(),
                staticPlanIdentity(prepared.plan()),
                summaries,
                acquired,
                planned.size(),
                acquired.size(),
                prepared.blobWrites(),
                prepared.blobReads(),
                new ExternalProviderUsage(0, 0, 0),
                0,
                "R5P_HARNESS_CONFORMANT",
                evidenceIdentity);
    }

    PreparedProductView prepare(List<RawRasterFixture> sourceFixtures) {
        var fixtures = List.copyOf(Objects.requireNonNull(sourceFixtures, "fixtures"));
        return prepare(fixtures, "r5p-offline-harness", "r5p-fixture-set:" + framedSha256(
                fixtures.stream().map(item -> item.fixtureId() + ":" + item.rawSha256()).toList()));
    }

    PreparedProductView prepare(
            List<RawRasterFixture> sourceFixtures,
            String profileId,
            String sourceReference
    ) {
        var fixtures = List.copyOf(Objects.requireNonNull(sourceFixtures, "fixtures"));
        if (fixtures.isEmpty() || fixtures.size() > ArtifactSet.MAXIMUM_ARTIFACTS
                || fixtures.stream().anyMatch(Objects::isNull)) {
            throw invalid("R5P_RAW_FIXTURE_SET_INVALID");
        }
        var fixtureIds = new java.util.HashSet<String>();
        if (fixtures.stream().anyMatch(item -> !fixtureIds.add(item.fixtureId()))) {
            throw invalid("R5P_RAW_FIXTURE_ID_DUPLICATED");
        }
        var store = new ScopedBlobStore();
        var input = new InferenceInput(
                InferenceMode.IMAGE_ONLY,
                Objects.requireNonNull(profileId, "profileId"),
                Objects.requireNonNull(sourceReference, "sourceReference"),
                true,
                fixtures.stream().map(item -> new InferenceInput.BinaryInput(
                        item.fileName(), item.mediaType(), item.bytes())).toList(),
                List.of());
        var normalized = new InputNormalizer(store).normalize(input);
        if (normalized.artifacts().size() != fixtures.size()
                || normalized.references().size() != fixtures.size()) {
            throw invalid("R5P_NORMALIZED_ARTIFACT_ALIAS_INVALID");
        }
        var byId = normalized.artifacts().stream().collect(java.util.stream.Collectors.toMap(
                NormalizedArtifact::artifactId, item -> item));
        var artifacts = new ArrayList<ArtifactSet.Artifact>();
        var provenance = new ArrayList<NormalizationProvenance>();
        for (var ordinal = 0; ordinal < normalized.references().size(); ordinal++) {
            var reference = normalized.references().get(ordinal);
            if (reference.kind() != NormalizedArtifact.Kind.IMAGE || reference.ordinal() != ordinal) {
                throw invalid("R5P_NORMALIZED_REFERENCE_ORDER_INVALID");
            }
            var metadata = byId.get(reference.artifactId());
            if (metadata == null || metadata.kind() != NormalizedArtifact.Kind.IMAGE
                    || metadata.width() == null || metadata.height() == null) {
                throw invalid("R5P_NORMALIZED_ARTIFACT_INVALID");
            }
            var bytes = store.read(metadata.locator());
            if (!metadata.artifactId().equals(sha256(bytes))
                    || metadata.byteLength() != bytes.length
                    || !"image/png".equals(metadata.mediaType())) {
                throw invalid("R5P_NORMALIZED_BLOB_IDENTITY_DRIFT");
            }
            artifacts.add(new ArtifactSet.Artifact(
                    metadata.artifactId(), ordinal, metadata.mediaType(), bytes,
                    metadata.width(), metadata.height(), true));
            var fixture = fixtures.get(ordinal);
            provenance.add(new NormalizationProvenance(
                    fixture.fixtureId(), fixture.rawSha256(), normalized.inputFingerprint(),
                    metadata.artifactId(), metadata.mediaType(), metadata.byteLength(),
                    metadata.width(), metadata.height()));
        }
        var artifactSet = ArtifactSet.canonical(artifacts);
        var sources = artifactSet.artifacts().stream().map(item -> new VisualSourceImage(
                item.artifactId(), item.bytes(), item.width(), item.height())).toList();
        var plan = new MultiScaleVisualViewPlanner().plan(sources, List.of());
        return new PreparedProductView(
                artifactSet, plan, List.copyOf(provenance), store.writeCalls(), store.readCalls());
    }

    private static List<PlannedView> plannedViews(VisualViewPlan plan) {
        var descriptors = plan.descriptors();
        var images = plan.providerImages();
        if (descriptors.size() != images.size()) throw invalid("R5P_STATIC_PLAN_INTERNAL_DRIFT");
        var result = new ArrayList<PlannedView>();
        for (var index = 0; index < descriptors.size(); index++) {
            var descriptor = descriptors.get(index);
            var image = images.get(index);
            if (!Objects.equals(descriptor.width(), image.width())
                    || !Objects.equals(descriptor.height(), image.height())
                    || !image.artifactId().equals(sha256(image.bytes()))) {
                throw invalid("R5P_STATIC_PLAN_INTERNAL_DRIFT");
            }
            var descriptorIdentity = "renderweave-r5p-view-descriptor/1.0:" + framedSha256(List.of(
                    "ordinal=" + index,
                    "view-id=" + descriptor.viewId(),
                    "source=" + descriptor.sourceArtifactId(),
                    "source-ordinal=" + descriptor.sourceOrdinal(),
                    "kind=" + descriptor.kind(),
                    "source-box=" + coordinates(descriptor.sourceBoundingBox()),
                    "dimensions=" + descriptor.width() + "x" + descriptor.height(),
                    "provider-artifact=" + image.artifactId(),
                    "encoded-bytes=" + image.bytes().length));
            result.add(new PlannedView(
                    index, descriptor.viewId(), descriptor.sourceArtifactId(),
                    descriptor.sourceOrdinal(), descriptor.kind().name(), descriptorIdentity,
                    image.artifactId(), image.mediaType(), descriptor.width(), descriptor.height(),
                    image.bytes()));
        }
        return List.copyOf(result);
    }

    static String staticPlanIdentity(VisualViewPlan plan) {
        var planned = plannedViews(plan);
        var frames = new ArrayList<String>();
        frames.add("plan-version=" + plan.planVersion());
        frames.add("view-count=" + planned.size());
        planned.forEach(item -> frames.add(item.descriptorIdentity()));
        return PLAN_IDENTITY_VERSION + ":" + framedSha256(frames);
    }

    private static void validateTrace(
            List<PlannedView> planned,
            List<AcquisitionArtifact> acquired
    ) {
        if (acquired.size() != planned.size() || acquired.stream().anyMatch(Objects::isNull)) {
            throw invalid("R5P_PLAN_ACQUISITION_COVERAGE_INVALID");
        }
        var seen = new java.util.HashSet<String>();
        for (var index = 0; index < planned.size(); index++) {
            var expected = planned.get(index);
            var actual = acquired.get(index);
            if (actual.acquisitionOrdinal() != index
                    || !expected.viewId().equals(actual.viewId())) {
                throw invalid("R5P_PLAN_ACQUISITION_ORDER_INVALID");
            }
            if (!seen.add(actual.viewId())) {
                throw invalid("R5P_PLAN_ACQUISITION_COVERAGE_INVALID");
            }
            if (!expected.providerArtifactId().equals(actual.providerArtifactId())
                    || !expected.encodedSha256().equals(actual.encodedSha256())
                    || expected.encodedBytes() != actual.encodedBytes()) {
                throw invalid("R5P_PLAN_ACQUISITION_BYTES_INVALID");
            }
            if (expected.width() != actual.width() || expected.height() != actual.height()) {
                throw invalid("R5P_PLAN_ACQUISITION_DIMENSIONS_INVALID");
            }
        }
    }

    private static List<String> evidenceFrames(
            PreparedProductView prepared,
            List<ViewSummary> summaries,
            List<AcquisitionArtifact> acquired
    ) {
        var frames = new ArrayList<String>();
        frames.add("harness=" + VERSION);
        frames.add("plan=" + staticPlanIdentity(prepared.plan()));
        for (var item : prepared.normalizationProvenance()) {
            frames.add("normalized=" + item.fixtureId() + ":" + item.rawFixtureSha256()
                    + ":" + item.normalizedArtifactId() + ":" + item.width() + "x"
                    + item.height() + ":" + item.encodedBytes());
        }
        summaries.forEach(item -> frames.add("view=" + item.descriptorIdentity()));
        acquired.forEach(item -> frames.add("acquired=" + item.acquisitionOrdinal() + ":"
                + item.viewId() + ":" + item.providerArtifactId() + ":" + item.width() + "x"
                + item.height() + ":" + item.encodedBytes()));
        frames.add("blob-writes=" + prepared.blobWrites());
        frames.add("blob-reads=" + prepared.blobReads());
        frames.add("provider-attempts=0");
        frames.add("api-key-reads=0");
        frames.add("terminal=R5P_HARNESS_CONFORMANT");
        return List.copyOf(frames);
    }

    private static String coordinates(cn.hbads.renderweave.inference.candidate.CandidateBoundingBox box) {
        return box.left() + "," + box.top() + "," + box.right() + "," + box.bottom();
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    static String framedSha256(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                var bytes = Objects.requireNonNull(value, "identity frame").getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    @FunctionalInterface
    public interface StaticPlanAcquisition {
        List<AcquisitionArtifact> acquire(List<PlannedView> completeOrderedPlan);
    }

    public record RawRasterFixture(
            String fixtureId,
            String fileName,
            String mediaType,
            byte[] bytes
    ) {
        public RawRasterFixture {
            if (fixtureId == null || !fixtureId.matches("[a-z][a-z0-9-]{0,127}")) {
                throw invalid("R5P_RAW_FIXTURE_ID_INVALID");
            }
            if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || (!"image/png".equals(mediaType) && !"image/jpeg".equals(mediaType))) {
                throw invalid("R5P_RAW_FIXTURE_METADATA_INVALID");
            }
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            if (bytes.length == 0 || bytes.length > InputNormalizer.MAX_IMAGE_BYTES) {
                throw invalid("R5P_RAW_FIXTURE_BYTES_INVALID");
            }
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        public String rawSha256() {
            return sha256(bytes);
        }

        @Override
        public String toString() {
            return "RawRasterFixture[fixtureId=" + fixtureId + ", mediaType=" + mediaType
                    + ", bytes=<redacted:" + bytes.length + ">]";
        }
    }

    public record PlannedView(
            int planOrdinal,
            String viewId,
            String sourceArtifactId,
            int sourceOrdinal,
            String kind,
            String descriptorIdentity,
            String providerArtifactId,
            String mediaType,
            int width,
            int height,
            byte[] bytes
    ) {
        public PlannedView {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        public int encodedBytes() {
            return bytes.length;
        }

        public String encodedSha256() {
            return sha256(bytes);
        }

        ViewSummary summary() {
            return new ViewSummary(planOrdinal, viewId, sourceArtifactId, sourceOrdinal, kind,
                    descriptorIdentity, providerArtifactId, width, height, encodedBytes(),
                    encodedSha256());
        }

        @Override
        public String toString() {
            return "PlannedView[planOrdinal=" + planOrdinal + ", viewId=" + viewId
                    + ", descriptorIdentity=" + descriptorIdentity + ", dimensions=" + width
                    + "x" + height + ", bytes=<redacted:" + bytes.length + ">]";
        }
    }

    public record AcquisitionArtifact(
            int acquisitionOrdinal,
            String viewId,
            String providerArtifactId,
            int width,
            int height,
            long encodedBytes,
            String encodedSha256
    ) {
        public AcquisitionArtifact {
            if (acquisitionOrdinal < 0 || viewId == null || viewId.isBlank()
                    || providerArtifactId == null || !providerArtifactId.matches("[0-9a-f]{64}")
                    || width < 1 || height < 1 || encodedBytes < 1
                    || encodedSha256 == null || !encodedSha256.matches("[0-9a-f]{64}")) {
                throw invalid("R5P_ACQUISITION_ARTIFACT_INVALID");
            }
        }

        public static AcquisitionArtifact observed(int ordinal, PlannedView view) {
            Objects.requireNonNull(view, "view");
            return new AcquisitionArtifact(ordinal, view.viewId(), view.providerArtifactId(),
                    view.width(), view.height(), view.encodedBytes(), view.encodedSha256());
        }
    }

    public record NormalizationProvenance(
            String fixtureId,
            String rawFixtureSha256,
            String inputFingerprint,
            String normalizedArtifactId,
            String mediaType,
            long encodedBytes,
            int width,
            int height
    ) { }

    public record ViewSummary(
            int planOrdinal,
            String viewId,
            String sourceArtifactId,
            int sourceOrdinal,
            String kind,
            String descriptorIdentity,
            String providerArtifactId,
            int width,
            int height,
            long encodedBytes,
            String encodedSha256
    ) { }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) {
        public ExternalProviderUsage {
            if (attempts < 0 || reservations < 0 || costMicrosCny < 0) {
                throw invalid("R5P_PROVIDER_USAGE_INVALID");
            }
        }
    }

    public record ConformanceOutcome(
            String harnessVersion,
            List<NormalizationProvenance> normalizationProvenance,
            String staticPlanIdentity,
            List<ViewSummary> viewSummaries,
            List<AcquisitionArtifact> acquisitionTrace,
            int plannedViewCount,
            int acquiredViewCount,
            int blobWrites,
            int blobReads,
            ExternalProviderUsage externalProviderUsage,
            int apiKeyReads,
            String terminalCode,
            String evidenceIdentity
    ) {
        public ConformanceOutcome {
            normalizationProvenance = List.copyOf(normalizationProvenance);
            viewSummaries = List.copyOf(viewSummaries);
            acquisitionTrace = List.copyOf(acquisitionTrace);
        }

        @Override
        public String toString() {
            return "ConformanceOutcome[harnessVersion=" + harnessVersion + ", staticPlanIdentity="
                    + staticPlanIdentity + ", plannedViewCount=" + plannedViewCount
                    + ", acquiredViewCount=" + acquiredViewCount + ", blobWrites=" + blobWrites
                    + ", blobReads=" + blobReads + ", externalProviderUsage="
                    + externalProviderUsage + ", apiKeyReads=" + apiKeyReads + ", terminalCode="
                    + terminalCode + ", evidenceIdentity=" + evidenceIdentity
                    + ", payload=<redacted>]";
        }
    }

    record PreparedProductView(
            ArtifactSet artifactSet,
            VisualViewPlan plan,
            List<NormalizationProvenance> normalizationProvenance,
            int blobWrites,
            int blobReads
    ) { }

    private static final class ScopedBlobStore implements BlobStore {
        private final Map<String, byte[]> byLocator = new LinkedHashMap<>();
        private int writeCalls;
        private int readCalls;

        @Override
        public WriteReceipt write(String artifactId, byte[] bytes) {
            writeCalls++;
            var locator = "r5p-blob:" + artifactId;
            var previous = byLocator.putIfAbsent(locator, bytes.clone());
            if (previous != null && !Arrays.equals(previous, bytes)) {
                throw invalid("R5P_SCOPED_BLOB_COLLISION");
            }
            return new WriteReceipt(locator, previous == null);
        }

        @Override
        public byte[] read(String locator) {
            readCalls++;
            var bytes = byLocator.get(locator);
            if (bytes == null) throw invalid("R5P_SCOPED_BLOB_MISSING");
            return bytes.clone();
        }

        @Override
        public void delete(String locator) {
            byLocator.remove(locator);
        }

        int writeCalls() {
            return writeCalls;
        }

        int readCalls() {
            return readCalls;
        }
    }
}
