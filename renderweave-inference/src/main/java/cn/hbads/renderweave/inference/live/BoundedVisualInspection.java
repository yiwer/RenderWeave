package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.provider.ProviderImage;
import cn.hbads.renderweave.inference.vision.ArtifactSet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Offline-only code-owned inspection action. It accepts product-normalized artifacts and an
 * exact complete static plan; it has no provider, persistence, prompt, profile, or route seam.
 */
public final class BoundedVisualInspection {
    public static final String VERSION = "renderweave-bounded-visual-inspection/1.0";
    public static final String REQUEST_VERSION = "InspectionRequest/1.0";
    public static final String PLAN_VERSION = "renderweave-visual-view-plan/2.0";
    public static final String POLICY_VERSION = "AdaptiveInspectionPolicy/1.0";
    public static final String ESTIMATOR_VERSION =
            "renderweave-visual-patch-token-estimator/1.0";

    private static final int MAXIMUM_ROUNDS = 1;
    private static final int MAXIMUM_INSPECTED_VIEWS = 2;
    private static final int MAXIMUM_VIEWS = 10;
    private static final long MAXIMUM_TOTAL_BYTES = 30L * 1024L * 1024L;
    private static final long MAXIMUM_INSPECTED_PIXELS = 11_520_000L;
    private static final long MAXIMUM_ADDITIONAL_VISUAL_TOKENS = 12_000L;
    private static final long MAXIMUM_TRANSFORM_MILLIS = 10_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long VISUAL_PATCH_PIXELS = 32L * 32L;
    private static final long VISUAL_TOKEN_OVERHEAD = 2L;
    private static final String POLICY_IDENTITY = POLICY_VERSION + ":" + framedSha256(List.of(
            "maximum-rounds=" + MAXIMUM_ROUNDS,
            "maximum-inspected-views=" + MAXIMUM_INSPECTED_VIEWS,
            "maximum-views=" + MAXIMUM_VIEWS,
            "maximum-total-bytes=" + MAXIMUM_TOTAL_BYTES,
            "maximum-inspected-pixels=" + MAXIMUM_INSPECTED_PIXELS,
            "maximum-additional-visual-tokens=" + MAXIMUM_ADDITIONAL_VISUAL_TOKENS,
            "maximum-transform-millis=" + MAXIMUM_TRANSFORM_MILLIS,
            "estimator=" + ESTIMATOR_VERSION,
            "transform=" + R5ProductRasterTransform.VERSION));

    private final RasterTransform transform;
    private final LongSupplier nanoTime;

    public BoundedVisualInspection() {
        this(new R5ProductRasterTransform()::render, System::nanoTime);
    }

    BoundedVisualInspection(RasterTransform transform, LongSupplier nanoTime) {
        this.transform = Objects.requireNonNull(transform, "transform");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public InspectionOutcome inspect(
            ArtifactSet artifacts,
            VisualViewPlan staticPlan,
            InspectionRequest request,
            AdaptiveInspectionPolicy policy
    ) {
        if (policy == null || !POLICY_IDENTITY.equals(policy.identity())) {
            return rejected("R5P_INSPECTION_POLICY_INVALID", null, policy);
        }
        if (policy.roundsConsumed() >= MAXIMUM_ROUNDS) {
            return exhausted("R5P_INSPECTION_ROUND_EXHAUSTED", null, policy);
        }
        if (artifacts == null || staticPlan == null) {
            return rejected("R5P_INSPECTION_PLAN_LINEAGE_INVALID", null, policy);
        }

        var validation = validateRequest(staticPlan, request);
        if (validation.failureCode() != null) {
            return rejected(validation.failureCode(), null, policy);
        }
        try {
            var canonicalPlan = validateCompleteStaticPlan(artifacts, staticPlan);
            if (canonicalPlan == null) {
                return rejected("R5P_INSPECTION_PLAN_LINEAGE_INVALID", null, policy);
            }
            var basePlanIdentity = basePlanIdentity(canonicalPlan);
            var requestIdentity = requestIdentity(
                    basePlanIdentity, artifacts, validation.regions(), policy);
            return execute(
                    artifacts, canonicalPlan, validation.regions(),
                    requestIdentity, basePlanIdentity, policy);
        } catch (ArithmeticException failure) {
            return exhausted("R5P_INSPECTION_ARITHMETIC_EXHAUSTED", null, policy);
        } catch (IllegalArgumentException failure) {
            return rejected("R5P_INSPECTION_PLAN_LINEAGE_INVALID", null, policy);
        }
    }

    private InspectionOutcome execute(
            ArtifactSet artifacts,
            VisualViewPlan staticPlan,
            List<ValidatedRegion> regions,
            String requestIdentity,
            String basePlanIdentity,
            AdaptiveInspectionPolicy policy
    ) {
        var staticViews = staticPlan.descriptors().stream()
                .map(descriptor -> staticPlan.require(descriptor.viewId()))
                .toList();
        var requiredOverviews = staticViews.stream()
                .filter(view -> view.descriptor().kind() == VisualViewKind.OVERVIEW)
                .toList();
        if (Math.addExact(requiredOverviews.size(), regions.size()) > MAXIMUM_VIEWS) {
            return exhausted("R5P_INSPECTION_VIEW_LIMIT_EXHAUSTED", requestIdentity, policy);
        }

        try {
            var maximumPixels = 0L;
            var maximumTokens = 0L;
            for (var region : regions) {
                var edge = region.region().resolutionPreset().longEdge();
                var pixels = Math.multiplyExact((long) edge, edge);
                maximumPixels = Math.addExact(maximumPixels, pixels);
                maximumTokens = Math.addExact(
                        maximumTokens, estimateVisualTokens(pixels));
            }
            if (maximumPixels > MAXIMUM_INSPECTED_PIXELS) {
                return exhausted("R5P_INSPECTION_PIXEL_LIMIT_EXHAUSTED", requestIdentity, policy);
            }
            if (maximumTokens > MAXIMUM_ADDITIONAL_VISUAL_TOKENS) {
                return exhausted("R5P_INSPECTION_TOKEN_LIMIT_EXHAUSTED", requestIdentity, policy);
            }
        } catch (ArithmeticException failure) {
            return exhausted("R5P_INSPECTION_ARITHMETIC_EXHAUSTED", requestIdentity, policy);
        }

        var sources = artifacts.artifacts().stream().map(item -> new VisualSourceImage(
                item.artifactId(), item.bytes(), item.width(), item.height())).toList();
        var requiredBytes = requiredOverviews.stream().mapToLong(
                view -> view.providerImage().bytes().length).reduce(0L, Math::addExact);
        var inspected = new ArrayList<VisualView>();
        var inspectedPixels = 0L;
        var additionalTokens = 0L;
        var requiredTotalBytes = requiredBytes;
        var sourceOrdinals = new HashMap<Integer, Integer>();
        final long start;
        try {
            start = nanoTime.getAsLong();
        } catch (RuntimeException failure) {
            return exhausted("R5P_INSPECTION_ARITHMETIC_EXHAUSTED", requestIdentity, policy);
        }

        for (var validated : regions) {
            var region = validated.region();
            final R5ProductRasterTransform.RasterView raster;
            try {
                raster = Objects.requireNonNull(transform.render(
                        sources.get(validated.baseView().descriptor().sourceOrdinal()),
                        validated.baseView(),
                        region.boundingBox(),
                        region.marginPreset().marginBps(),
                        region.resolutionPreset().longEdge()), "transform result");
            } catch (ArithmeticException failure) {
                return exhausted("R5P_INSPECTION_ARITHMETIC_EXHAUSTED", requestIdentity, policy);
            } catch (IllegalArgumentException | NullPointerException failure) {
                return rejected("R5P_INSPECTION_TRANSFORM_INVALID", requestIdentity, policy);
            }

            final long elapsedNanos;
            try {
                elapsedNanos = Math.subtractExact(nanoTime.getAsLong(), start);
            } catch (RuntimeException failure) {
                return exhausted("R5P_INSPECTION_ARITHMETIC_EXHAUSTED", requestIdentity, policy);
            }
            if (elapsedNanos < 0L) {
                return exhausted("R5P_INSPECTION_ARITHMETIC_EXHAUSTED", requestIdentity, policy);
            }
            if (elapsedNanos > Math.multiplyExact(MAXIMUM_TRANSFORM_MILLIS, NANOS_PER_MILLI)) {
                return exhausted("R5P_INSPECTION_TRANSFORM_TIMEOUT", requestIdentity, policy);
            }

            var bytes = raster.bytes();
            try {
                requiredTotalBytes = Math.addExact(requiredTotalBytes, bytes.length);
                if (requiredTotalBytes > MAXIMUM_TOTAL_BYTES) {
                    return exhausted("R5P_INSPECTION_BYTE_LIMIT_EXHAUSTED", requestIdentity, policy);
                }
                var pixels = Math.multiplyExact((long) raster.width(), raster.height());
                inspectedPixels = Math.addExact(inspectedPixels, pixels);
                additionalTokens = Math.addExact(
                        additionalTokens, estimateVisualTokens(pixels));
            } catch (ArithmeticException failure) {
                return exhausted("R5P_INSPECTION_ARITHMETIC_EXHAUSTED", requestIdentity, policy);
            }
            if (inspectedPixels > MAXIMUM_INSPECTED_PIXELS) {
                return exhausted("R5P_INSPECTION_PIXEL_LIMIT_EXHAUSTED", requestIdentity, policy);
            }
            if (additionalTokens > MAXIMUM_ADDITIONAL_VISUAL_TOKENS) {
                return exhausted("R5P_INSPECTION_TOKEN_LIMIT_EXHAUSTED", requestIdentity, policy);
            }
            if (!validRaster(raster, validated.baseView(), region, bytes)) {
                return rejected("R5P_INSPECTION_TRANSFORM_INVALID", requestIdentity, policy);
            }

            var sourceOrdinal = validated.baseView().descriptor().sourceOrdinal();
            var inspectedOrdinal = sourceOrdinals.merge(sourceOrdinal, 1, Integer::sum) - 1;
            var descriptor = new VisualViewDescriptor(
                    "view-%02d-inspected-%02d".formatted(sourceOrdinal, inspectedOrdinal),
                    raster.sourceArtifactId(), sourceOrdinal, VisualViewKind.TARGETED_CROP,
                    raster.sourceBoundingBox(), raster.width(), raster.height());
            var providerImage = new ProviderImage(
                    raster.artifactId(), raster.mediaType(), bytes, raster.width(), raster.height());
            inspected.add(new VisualView(
                    descriptor, providerImage,
                    sources.get(sourceOrdinal).width(), sources.get(sourceOrdinal).height(),
                    raster.sourceCrop()));
        }

        final long elapsedNanos;
        try {
            elapsedNanos = Math.subtractExact(nanoTime.getAsLong(), start);
        } catch (RuntimeException failure) {
            return exhausted("R5P_INSPECTION_ARITHMETIC_EXHAUSTED", requestIdentity, policy);
        }
        if (elapsedNanos < 0L) {
            return exhausted("R5P_INSPECTION_ARITHMETIC_EXHAUSTED", requestIdentity, policy);
        }
        if (elapsedNanos > Math.multiplyExact(MAXIMUM_TRANSFORM_MILLIS, NANOS_PER_MILLI)) {
            return exhausted("R5P_INSPECTION_TRANSFORM_TIMEOUT", requestIdentity, policy);
        }

        var selected = new ArrayList<VisualView>();
        selected.addAll(requiredOverviews);
        selected.addAll(inspected);
        var totalBytes = requiredTotalBytes;
        for (var view : staticViews) {
            if (view.descriptor().kind() == VisualViewKind.OVERVIEW
                    || selected.size() >= MAXIMUM_VIEWS) {
                continue;
            }
            var next = Math.addExact(totalBytes, view.providerImage().bytes().length);
            if (next <= MAXIMUM_TOTAL_BYTES) {
                selected.add(view);
                totalBytes = next;
            }
        }
        var executionViews = List.copyOf(selected);
        var planIdentity = planIdentity(
                basePlanIdentity, requestIdentity, policy.identity(), executionViews);
        var resources = new ResourceSummary(
                executionViews.size(), inspected.size(), totalBytes,
                inspectedPixels, additionalTokens,
                Math.floorDiv(elapsedNanos, NANOS_PER_MILLI));
        return new InspectionOutcome(
                Disposition.EXECUTED, "R5P_INSPECTION_EXECUTED", requestIdentity,
                policy.identity(), PLAN_VERSION, planIdentity, resources,
                new ExternalProviderUsage(0, 0, 0), 0, executionViews);
    }

    private static RequestValidation validateRequest(
            VisualViewPlan staticPlan, InspectionRequest request
    ) {
        if (request == null || !REQUEST_VERSION.equals(request.contractVersion())) {
            return RequestValidation.failure("R5P_INSPECTION_CONTRACT_UNSUPPORTED");
        }
        var requested = request.regions();
        if (requested == null || requested.isEmpty()
                || requested.size() > MAXIMUM_INSPECTED_VIEWS) {
            return RequestValidation.failure("R5P_INSPECTION_REGION_COUNT_INVALID");
        }
        var descriptors = staticPlan.descriptors();
        var order = new HashMap<String, Integer>();
        for (var index = 0; index < descriptors.size(); index++) {
            order.put(descriptors.get(index).viewId(), index);
        }
        var seen = new HashSet<RegionKey>();
        var validated = new ArrayList<ValidatedRegion>();
        for (var region : requested) {
            if (region == null || region.baseViewId() == null
                    || region.boundingBox() == null
                    || region.marginPreset() == null
                    || region.resolutionPreset() == null
                    || !validBox(region.boundingBox())) {
                return RequestValidation.failure("R5P_INSPECTION_REGION_INVALID");
            }
            var baseOrder = order.get(region.baseViewId());
            if (baseOrder == null) {
                return RequestValidation.failure("R5P_INSPECTION_BASE_VIEW_UNKNOWN");
            }
            var key = RegionKey.from(region);
            if (!seen.add(key)) {
                return RequestValidation.failure("R5P_INSPECTION_REGION_DUPLICATED");
            }
            validated.add(new ValidatedRegion(
                    region, staticPlan.require(region.baseViewId()), baseOrder));
        }
        validated.sort(Comparator
                .comparingInt((ValidatedRegion value) ->
                        value.baseView().descriptor().sourceOrdinal())
                .thenComparingInt(ValidatedRegion::baseOrder)
                .thenComparingInt(value -> value.region().boundingBox().top())
                .thenComparingInt(value -> value.region().boundingBox().left())
                .thenComparingInt(value -> value.region().boundingBox().bottom())
                .thenComparingInt(value -> value.region().boundingBox().right())
                .thenComparing(value -> value.region().marginPreset())
                .thenComparing(value -> value.region().resolutionPreset()));
        return new RequestValidation(List.copyOf(validated), null);
    }

    private static VisualViewPlan validateCompleteStaticPlan(
            ArtifactSet artifacts, VisualViewPlan actual
    ) {
        for (var artifact : artifacts.artifacts()) {
            if (!artifact.artifactId().equals(sha256(artifact.bytes()))) {
                return null;
            }
        }
        var sources = artifacts.artifacts().stream().map(item -> new VisualSourceImage(
                item.artifactId(), item.bytes(), item.width(), item.height())).toList();
        var expected = new MultiScaleVisualViewPlanner().plan(sources, List.of());
        if (!expected.planVersion().equals(actual.planVersion())
                || !expected.descriptors().equals(actual.descriptors())) {
            return null;
        }
        for (var descriptor : expected.descriptors()) {
            var expectedView = expected.require(descriptor.viewId());
            var actualView = actual.require(descriptor.viewId());
            if (!sameView(expectedView, actualView)) return null;
        }
        return actual;
    }

    private static boolean sameView(VisualView left, VisualView right) {
        return left.descriptor().equals(right.descriptor())
                && left.sourceWidth() == right.sourceWidth()
                && left.sourceHeight() == right.sourceHeight()
                && left.crop().equals(right.crop())
                && left.providerImage().artifactId().equals(right.providerImage().artifactId())
                && left.providerImage().mediaType().equals(right.providerImage().mediaType())
                && Objects.equals(left.providerImage().width(), right.providerImage().width())
                && Objects.equals(left.providerImage().height(), right.providerImage().height())
                && Arrays.equals(left.providerImage().bytes(), right.providerImage().bytes());
    }

    private static boolean validRaster(
            R5ProductRasterTransform.RasterView raster,
            VisualView baseView,
            InspectionRegion region,
            byte[] bytes
    ) {
        var sourceBox = raster.sourceBoundingBox();
        var sourceCrop = raster.sourceCrop();
        return raster.sourceArtifactId().equals(baseView.descriptor().sourceArtifactId())
                && "image/png".equals(raster.mediaType())
                && raster.artifactId().equals(sha256(bytes))
                && Math.max(raster.width(), raster.height())
                == region.resolutionPreset().longEdge()
                && raster.width() > 0 && raster.height() > 0
                && raster.width() <= region.resolutionPreset().longEdge()
                && raster.height() <= region.resolutionPreset().longEdge()
                && validBox(sourceBox)
                && sourceCrop.left() >= 0 && sourceCrop.top() >= 0
                && sourceCrop.right() <= baseView.sourceWidth()
                && sourceCrop.bottom() <= baseView.sourceHeight();
    }

    private static boolean validBox(CandidateBoundingBox box) {
        return box.left() >= 0 && box.top() >= 0
                && box.left() < box.right() && box.top() < box.bottom()
                && box.right() <= 10_000 && box.bottom() <= 10_000;
    }

    private static long estimateVisualTokens(long pixels) {
        return Math.addExact(Math.ceilDiv(pixels, VISUAL_PATCH_PIXELS), VISUAL_TOKEN_OVERHEAD);
    }

    private static String basePlanIdentity(VisualViewPlan plan) {
        var frames = new ArrayList<String>();
        frames.add("plan-version=" + plan.planVersion());
        frames.add("view-count=" + plan.descriptors().size());
        for (var index = 0; index < plan.descriptors().size(); index++) {
            frames.add(viewFrame(index, plan.require(plan.descriptors().get(index).viewId())));
        }
        return "renderweave-r5p-action-base-plan/1.0:" + framedSha256(frames);
    }

    private static String requestIdentity(
            String basePlanIdentity,
            ArtifactSet artifacts,
            List<ValidatedRegion> regions,
            AdaptiveInspectionPolicy policy
    ) {
        var frames = new ArrayList<String>();
        frames.add("contract=" + REQUEST_VERSION);
        frames.add("base-plan=" + basePlanIdentity);
        frames.add("policy=" + policy.identity());
        for (var artifact : artifacts.artifacts()) {
            frames.add("source=" + artifact.sourceOrdinal() + ":" + artifact.artifactId()
                    + ":" + artifact.width() + "x" + artifact.height());
        }
        for (var validated : regions) {
            var region = validated.region();
            frames.add("region=" + region.baseViewId() + ":"
                    + coordinates(region.boundingBox()) + ":" + region.marginPreset()
                    + ":" + region.resolutionPreset());
        }
        return "renderweave-inspection-request/1.0:" + framedSha256(frames);
    }

    private static String planIdentity(
            String basePlanIdentity,
            String requestIdentity,
            String policyIdentity,
            List<VisualView> views
    ) {
        var frames = new ArrayList<String>();
        frames.add("plan-version=" + PLAN_VERSION);
        frames.add("base-plan=" + basePlanIdentity);
        frames.add("request=" + requestIdentity);
        frames.add("policy=" + policyIdentity);
        frames.add("view-count=" + views.size());
        for (var index = 0; index < views.size(); index++) {
            frames.add(viewFrame(index, views.get(index)));
        }
        return "renderweave-visual-view-plan/2.0:" + framedSha256(frames);
    }

    private static String viewFrame(int ordinal, VisualView view) {
        var descriptor = view.descriptor();
        var image = view.providerImage();
        return ordinal + ":" + descriptor.viewId() + ":" + descriptor.sourceArtifactId()
                + ":" + descriptor.sourceOrdinal() + ":" + descriptor.kind() + ":"
                + coordinates(descriptor.sourceBoundingBox()) + ":" + descriptor.width()
                + "x" + descriptor.height() + ":" + image.artifactId() + ":"
                + image.bytes().length;
    }

    private static String coordinates(CandidateBoundingBox box) {
        return box.left() + "," + box.top() + "," + box.right() + "," + box.bottom();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static String framedSha256(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                var bytes = value.getBytes(StandardCharsets.UTF_8);
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

    private static InspectionOutcome rejected(
            String reasonCode, String requestIdentity, AdaptiveInspectionPolicy policy
    ) {
        return failure(Disposition.REJECTED, reasonCode, requestIdentity, policy);
    }

    private static InspectionOutcome exhausted(
            String reasonCode, String requestIdentity, AdaptiveInspectionPolicy policy
    ) {
        return failure(Disposition.EXHAUSTED, reasonCode, requestIdentity, policy);
    }

    private static InspectionOutcome failure(
            Disposition disposition,
            String reasonCode,
            String requestIdentity,
            AdaptiveInspectionPolicy policy
    ) {
        return new InspectionOutcome(
                disposition, reasonCode, requestIdentity,
                policy == null ? null : policy.identity(), null, null,
                ResourceSummary.empty(), new ExternalProviderUsage(0, 0, 0), 0, List.of());
    }

    @FunctionalInterface
    interface RasterTransform {
        R5ProductRasterTransform.RasterView render(
                VisualSourceImage source,
                VisualView baseView,
                CandidateBoundingBox viewRelativeBox,
                int marginBps,
                int requestedLongEdge);
    }

    public enum Disposition { EXECUTED, REJECTED, EXHAUSTED }

    public enum MarginPreset {
        TIGHT_0000_BPS(0),
        CONTEXT_0500_BPS(500);

        private final int marginBps;

        MarginPreset(int marginBps) {
            this.marginBps = marginBps;
        }

        int marginBps() {
            return marginBps;
        }
    }

    public enum ResolutionPreset {
        DETAIL_LONG_EDGE_1400(1_400),
        INSPECT_LONG_EDGE_2400(2_400);

        private final int longEdge;

        ResolutionPreset(int longEdge) {
            this.longEdge = longEdge;
        }

        int longEdge() {
            return longEdge;
        }
    }

    public record InspectionRegion(
            String baseViewId,
            CandidateBoundingBox boundingBox,
            MarginPreset marginPreset,
            ResolutionPreset resolutionPreset
    ) { }

    public record InspectionRequest(String contractVersion, List<InspectionRegion> regions) {
        public InspectionRequest {
            if (regions != null) {
                regions = Collections.unmodifiableList(new ArrayList<>(regions));
            }
        }
    }

    public static final class AdaptiveInspectionPolicy {
        private final int roundsConsumed;

        private AdaptiveInspectionPolicy(int roundsConsumed) {
            this.roundsConsumed = roundsConsumed;
        }

        public static AdaptiveInspectionPolicy initial() {
            return new AdaptiveInspectionPolicy(0);
        }

        public static AdaptiveInspectionPolicy consumed() {
            return new AdaptiveInspectionPolicy(1);
        }

        public String identity() {
            return POLICY_IDENTITY;
        }

        int roundsConsumed() {
            return roundsConsumed;
        }

        @Override
        public String toString() {
            return "AdaptiveInspectionPolicy[identity=" + identity()
                    + ", roundsConsumed=" + roundsConsumed + "]";
        }
    }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) {
        public ExternalProviderUsage {
            if (attempts != 0L || reservations != 0L || costMicrosCny != 0L) {
                throw new IllegalArgumentException("R5P_INSPECTION_PROVIDER_USAGE_NONZERO");
            }
        }
    }

    public record ResourceSummary(
            int totalViews,
            int inspectedViews,
            long totalEncodedBytes,
            long inspectedPixels,
            long additionalVisualTokens,
            long localTransformMillis
    ) {
        public ResourceSummary {
            if (totalViews < 0 || inspectedViews < 0 || totalEncodedBytes < 0L
                    || inspectedPixels < 0L || additionalVisualTokens < 0L
                    || localTransformMillis < 0L) {
                throw new IllegalArgumentException("R5P_INSPECTION_RESOURCE_SUMMARY_INVALID");
            }
        }

        static ResourceSummary empty() {
            return new ResourceSummary(0, 0, 0L, 0L, 0L, 0L);
        }
    }

    public static final class InspectionOutcome {
        private final Disposition disposition;
        private final String reasonCode;
        private final String requestIdentity;
        private final String policyIdentity;
        private final String planVersion;
        private final String planIdentity;
        private final ResourceSummary resourceSummary;
        private final ExternalProviderUsage externalProviderUsage;
        private final int apiKeyReads;
        private final List<VisualView> executionViews;

        private InspectionOutcome(
                Disposition disposition,
                String reasonCode,
                String requestIdentity,
                String policyIdentity,
                String planVersion,
                String planIdentity,
                ResourceSummary resourceSummary,
                ExternalProviderUsage externalProviderUsage,
                int apiKeyReads,
                List<VisualView> executionViews
        ) {
            this.disposition = Objects.requireNonNull(disposition, "disposition");
            this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
            this.requestIdentity = requestIdentity;
            this.policyIdentity = policyIdentity;
            this.planVersion = planVersion;
            this.planIdentity = planIdentity;
            this.resourceSummary = Objects.requireNonNull(resourceSummary, "resourceSummary");
            this.externalProviderUsage = Objects.requireNonNull(
                    externalProviderUsage, "externalProviderUsage");
            if (apiKeyReads != 0) {
                throw new IllegalArgumentException("R5P_INSPECTION_API_KEY_READ_NONZERO");
            }
            this.apiKeyReads = apiKeyReads;
            this.executionViews = List.copyOf(executionViews);
            if (disposition == Disposition.EXECUTED) {
                if (!PLAN_VERSION.equals(planVersion)
                        || planIdentity == null || requestIdentity == null
                        || this.executionViews.isEmpty()) {
                    throw new IllegalArgumentException("R5P_INSPECTION_EXECUTED_OUTCOME_INVALID");
                }
            } else if (!this.executionViews.isEmpty() || planVersion != null || planIdentity != null) {
                throw new IllegalArgumentException("R5P_INSPECTION_PARTIAL_OUTCOME_INVALID");
            }
        }

        public Disposition disposition() {
            return disposition;
        }

        public String reasonCode() {
            return reasonCode;
        }

        public String requestIdentity() {
            return requestIdentity;
        }

        public String policyIdentity() {
            return policyIdentity;
        }

        public String planVersion() {
            return planVersion;
        }

        public String planIdentity() {
            return planIdentity;
        }

        public ResourceSummary resourceSummary() {
            return resourceSummary;
        }

        public ExternalProviderUsage externalProviderUsage() {
            return externalProviderUsage;
        }

        public int apiKeyReads() {
            return apiKeyReads;
        }

        List<VisualView> executionViews() {
            return executionViews;
        }

        @Override
        public String toString() {
            return "InspectionOutcome[disposition=" + disposition + ", reasonCode=" + reasonCode
                    + ", requestIdentity=" + requestIdentity + ", policyIdentity=" + policyIdentity
                    + ", planVersion=" + planVersion + ", planIdentity=" + planIdentity
                    + ", resourceSummary=" + resourceSummary + ", externalProviderUsage="
                    + externalProviderUsage + ", apiKeyReads=" + apiKeyReads
                    + ", payload=<redacted>]";
        }
    }

    private record ValidatedRegion(
            InspectionRegion region, VisualView baseView, int baseOrder
    ) { }

    private record RequestValidation(List<ValidatedRegion> regions, String failureCode) {
        static RequestValidation failure(String code) {
            return new RequestValidation(List.of(), code);
        }
    }

    private record RegionKey(
            String baseViewId,
            int left,
            int top,
            int right,
            int bottom,
            MarginPreset marginPreset,
            ResolutionPreset resolutionPreset
    ) {
        static RegionKey from(InspectionRegion region) {
            return new RegionKey(
                    region.baseViewId(), region.boundingBox().left(), region.boundingBox().top(),
                    region.boundingBox().right(), region.boundingBox().bottom(),
                    region.marginPreset(), region.resolutionPreset());
        }
    }
}
