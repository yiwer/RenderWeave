package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.asset.api.AssetApplication.AssetId;
import cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope;
import cn.hbads.renderweave.asset.api.AssetResolver;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class AssetResolverToRenderingAdapterTest {

    private static final OwnerScope OWNER = new OwnerScope("owner-a");
    private static final AssetId ASSET_ID =
            new AssetId("00000000-0000-4000-8000-0000000000aa");
    private static final String RESOURCE_ID = "rwres_" + "a".repeat(64);

    @Test
    void projectsTheClosedRequestAndResolvedFactExactly() {
        var descriptor = new AssetAcceptanceAuthority.ImageDescriptor(
                10, 20, AssetAcceptanceAuthority.Orientation.IDENTITY,
                10, 20, 1, AssetAcceptanceAuthority.ColorEncoding.SRGB_8BIT);
        var selected = new AssetResolver.ResolvedAsset(
                RESOURCE_ID,
                ASSET_ID,
                7,
                AssetKind.IMAGE,
                "b".repeat(64),
                "image/png",
                321,
                AssetResolver.ACCEPTANCE_PROFILE_ID,
                descriptor,
                new AssetResolver.FetchLease(
                        "https://assets.internal/internal/render-assets/lease", 12_345));
        var resolver = new ScriptedResolver(
                new AssetResolver.PrecheckPassed(), new AssetResolver.Resolved(selected));
        AssetResolutionPort port = new AssetResolverToRenderingAdapter(resolver);

        var precheck = port.precheckAdmission(OWNER, ASSET_ID, AssetKind.IMAGE);
        var resolved = assertInstanceOf(
                AssetResolutionPort.ResolveOutcome.Resolved.class,
                port.resolve(request()));

        assertInstanceOf(AssetResolutionPort.PrecheckOutcome.PrecheckPassed.class, precheck);
        assertEquals(new AssetResolver.PrecheckRequest(OWNER, ASSET_ID, AssetKind.IMAGE),
                resolver.lastPrecheck);
        assertEquals("render-1", resolver.lastResolve.renderRequestId());
        assertEquals(OWNER, resolver.lastResolve.ownerScope());
        assertEquals(RESOURCE_ID, resolver.lastResolve.resourceId());
        assertEquals(ASSET_ID, resolver.lastResolve.assetId());
        assertEquals(AssetKind.IMAGE, resolver.lastResolve.expectedKind());
        assertEquals("renderer-a", resolver.lastResolve.rendererAudience());
        assertEquals(9_000L, resolver.lastResolve.renderDeadlineEpochMilli());

        var fact = resolved.fact();
        assertEquals("7", fact.contentVersion());
        assertEquals(selected.sha256(), fact.sha256());
        assertEquals(selected.mediaType(), fact.mediaType());
        assertEquals(selected.byteLength(), fact.byteLength());
        assertEquals(selected.acceptanceProfileId(), fact.acceptanceProfileId());
        assertSame(descriptor, fact.technicalDescriptor());
        assertEquals(selected.lease().fetchUrl(), fact.fetchUrl());
        assertEquals(selected.lease().expiresAtEpochSecond(), fact.leaseExpiresAtEpochSecond());
    }

    @Test
    void mapsEveryClosedResolverFailureWithoutADefaultBucket() {
        assertRejected(AssetResolver.Rejection.SCOPE_MISMATCH,
                AssetResolutionPort.AdmissionRejection.SCOPE_MISMATCH);
        assertRejected(AssetResolver.Rejection.NOT_FOUND,
                AssetResolutionPort.AdmissionRejection.NOT_FOUND);
        assertRejected(AssetResolver.Rejection.DELETED,
                AssetResolutionPort.AdmissionRejection.NOT_ACTIVE);
        assertRejected(AssetResolver.Rejection.KIND_MISMATCH,
                AssetResolutionPort.AdmissionRejection.KIND_MISMATCH);

        assertInstanceOf(AssetResolutionPort.ResolveOutcome.ResolveConflict.class,
                mapped(new AssetResolver.ResolveConflict()));
        assertInstanceOf(AssetResolutionPort.ResolveOutcome.ResolveTimedOut.class,
                mapped(new AssetResolver.ResolveTimedOut()));
        assertInstanceOf(AssetResolutionPort.ResolveOutcome.ResolveUnavailable.class,
                mapped(new AssetResolver.ResolveUnavailable()));
    }

    @Test
    void mapsPrecheckRejectionsAndUnavailability() {
        for (var rejection : AssetResolver.Rejection.values()) {
            var resolver = new ScriptedResolver(
                    new AssetResolver.PrecheckRejected(rejection),
                    new AssetResolver.ResolveUnavailable());
            var outcome = new AssetResolverToRenderingAdapter(resolver)
                    .precheckAdmission(OWNER, ASSET_ID, AssetKind.IMAGE);
            var rejected = assertInstanceOf(
                    AssetResolutionPort.PrecheckOutcome.PrecheckRejected.class, outcome);
            assertEquals(expectedRejection(rejection), rejected.reason());
        }
        var unavailable = new AssetResolverToRenderingAdapter(new ScriptedResolver(
                new AssetResolver.PrecheckUnavailable(),
                new AssetResolver.ResolveUnavailable()))
                .precheckAdmission(OWNER, ASSET_ID, AssetKind.IMAGE);
        assertInstanceOf(AssetResolutionPort.PrecheckOutcome.PrecheckUnavailable.class, unavailable);
    }

    private static void assertRejected(
            AssetResolver.Rejection source,
            AssetResolutionPort.AdmissionRejection expected
    ) {
        var rejected = assertInstanceOf(
                AssetResolutionPort.ResolveOutcome.ResolveRejected.class,
                mapped(new AssetResolver.ResolveRejected(source)));
        assertEquals(expected, rejected.reason());
    }

    private static AssetResolutionPort.ResolveOutcome mapped(
            AssetResolver.ResolveOutcome source
    ) {
        return new AssetResolverToRenderingAdapter(new ScriptedResolver(
                new AssetResolver.PrecheckPassed(), source)).resolve(request());
    }

    private static AssetResolutionPort.AdmissionRejection expectedRejection(
            AssetResolver.Rejection source
    ) {
        return switch (source) {
            case SCOPE_MISMATCH -> AssetResolutionPort.AdmissionRejection.SCOPE_MISMATCH;
            case NOT_FOUND -> AssetResolutionPort.AdmissionRejection.NOT_FOUND;
            case DELETED -> AssetResolutionPort.AdmissionRejection.NOT_ACTIVE;
            case KIND_MISMATCH -> AssetResolutionPort.AdmissionRejection.KIND_MISMATCH;
        };
    }

    private static AssetResolutionPort.ResolveRequest request() {
        return new AssetResolutionPort.ResolveRequest(
                new RenderRequestId("render-1"),
                OWNER,
                new AssetResolutionPort.ResourceId(RESOURCE_ID),
                ASSET_ID,
                AssetKind.IMAGE,
                new AssetResolutionPort.RendererAudience("renderer-a"),
                9_000L);
    }

    private static final class ScriptedResolver implements AssetResolver {
        private final PrecheckOutcome precheck;
        private final ResolveOutcome resolve;
        private PrecheckRequest lastPrecheck;
        private ResolveRequest lastResolve;

        private ScriptedResolver(PrecheckOutcome precheck, ResolveOutcome resolve) {
            this.precheck = precheck;
            this.resolve = resolve;
        }

        @Override
        public PrecheckOutcome precheck(PrecheckRequest request) {
            lastPrecheck = request;
            return precheck;
        }

        @Override
        public ResolveOutcome resolve(ResolveRequest request) {
            lastResolve = request;
            return resolve;
        }
    }
}
