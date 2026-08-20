package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.api.AssetResolver;
import cn.hbads.renderweave.asset.spi.AssetFetchEndpoint;
import cn.hbads.renderweave.asset.spi.AssetPersistence;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AssetResolverContractTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-20T08:00:00Z"), ZoneOffset.UTC);
    private static final AssetApplication.AssetId ASSET_ID = AssetApplication.AssetId.of(
            "3d7b7e1f-84bc-4d12-a353-b9b5ad0ba3cc");
    private static final AssetApplication.OwnerScope OWNER_SCOPE =
            new AssetApplication.OwnerScope("owner:test");
    private static final String RESOURCE_ID = "rwres_"
            + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void resolvesExactSelectionAndIssuesACompletelyBoundLease() {
        var persistedQuery = new AtomicReference<AssetPersistence.RenderSelectionQuery>();
        AssetPersistence persistence = persistence((method, args) -> {
            if (!method.equals("resolveForRender")) {
                throw new UnsupportedOperationException(method);
            }
            var query = (AssetPersistence.RenderSelectionQuery) args[0];
            persistedQuery.set(query);
            return new AssetPersistence.RenderSelectionResolved(selection(query));
        });
        var issuedRequest = new AtomicReference<AssetFetchEndpoint.IssueRequest>();
        AssetFetchEndpoint endpoint = request -> {
            issuedRequest.set(request);
            return new AssetFetchEndpoint.FetchIssued(
                    "https://render.internal.example/internal/render-assets/v1.token");
        };
        AssetResolver resolver = AssetModule.resolver(persistence, endpoint, CLOCK);
        long deadline = CLOCK.millis() + 60_123;

        AssetResolver.ResolveOutcome outcome = resolver.resolve(new AssetResolver.ResolveRequest(
                "render-request-01",
                OWNER_SCOPE,
                RESOURCE_ID,
                ASSET_ID,
                AssetAcceptanceAuthority.AssetKind.IMAGE,
                "renderer:v1",
                deadline
        ));

        var resolved = assertInstanceOf(AssetResolver.Resolved.class, outcome).asset();
        assertEquals(RESOURCE_ID, resolved.resourceId());
        assertEquals(ASSET_ID, resolved.assetId());
        assertEquals(7, resolved.contentVersion());
        assertEquals("renderweave-asset-acceptance/1.0", resolved.acceptanceProfileId());
        assertEquals(Math.floorDiv(deadline + 5_000 + 999, 1_000),
                resolved.lease().expiresAtEpochSecond());
        assertEquals(deadline + 300_000, persistedQuery.get().recordExpiresAtEpochMilli());

        var issued = issuedRequest.get();
        assertEquals("render-request-01", issued.renderRequestId());
        assertEquals(RESOURCE_ID, issued.resourceId());
        assertEquals(ASSET_ID, issued.assetId());
        assertEquals(7, issued.contentVersion());
        assertEquals("renderer:v1", issued.rendererAudience());
        assertEquals(resolved.sha256(), issued.sha256());
        assertEquals(resolved.byteLength(), issued.byteLength());
        assertEquals(resolved.lease().expiresAtEpochSecond(), issued.expiresAtEpochSecond());
    }

    @Test
    void precheckMapsEveryClosedPersistenceOutcome() {
        for (var rejection : AssetPersistence.RenderRejection.values()) {
            AssetResolver resolver = AssetModule.resolver(
                    persistence((method, args) -> new AssetPersistence.RenderPrecheckRejected(
                            rejection)),
                    request -> new AssetFetchEndpoint.FetchUnavailable(),
                    CLOCK);

            var outcome = assertInstanceOf(
                    AssetResolver.PrecheckRejected.class,
                    resolver.precheck(new AssetResolver.PrecheckRequest(
                            OWNER_SCOPE, ASSET_ID,
                            AssetAcceptanceAuthority.AssetKind.IMAGE)));

            assertEquals(expected(rejection), outcome.reason());
        }
        AssetResolver unavailable = AssetModule.resolver(
                persistence((method, args) -> new AssetPersistence.RenderPrecheckUnavailable()),
                request -> new AssetFetchEndpoint.FetchUnavailable(),
                CLOCK);
        assertInstanceOf(AssetResolver.PrecheckUnavailable.class,
                unavailable.precheck(new AssetResolver.PrecheckRequest(
                        OWNER_SCOPE, ASSET_ID, AssetAcceptanceAuthority.AssetKind.IMAGE)));
    }

    @Test
    void resolveMapsEveryClosedFailureAndDoesNotIssueAfterASelectionFailure() {
        for (var rejection : AssetPersistence.RenderRejection.values()) {
            var endpointCalls = new AtomicInteger();
            AssetResolver resolver = AssetModule.resolver(
                    persistence((method, args) -> new AssetPersistence.RenderSelectionRejected(
                            rejection)),
                    request -> {
                        endpointCalls.incrementAndGet();
                        return new AssetFetchEndpoint.FetchUnavailable();
                    },
                    CLOCK);

            var outcome = assertInstanceOf(AssetResolver.ResolveRejected.class,
                    resolver.resolve(request(CLOCK.millis() + 60_000)));

            assertEquals(expected(rejection), outcome.reason());
            assertEquals(0, endpointCalls.get());
        }
        assertInstanceOf(AssetResolver.ResolveConflict.class,
                resolverReturning(new AssetPersistence.RenderSelectionConflict())
                        .resolve(request(CLOCK.millis() + 60_000)));
        assertInstanceOf(AssetResolver.ResolveUnavailable.class,
                resolverReturning(new AssetPersistence.RenderSelectionUnavailable())
                        .resolve(request(CLOCK.millis() + 60_000)));
    }

    @Test
    void deadlineAndFetchFailuresAreClosedWithoutLeakingASelection() {
        var persistenceCalls = new AtomicInteger();
        AssetResolver timed = AssetModule.resolver(
                persistence((method, args) -> {
                    persistenceCalls.incrementAndGet();
                    return null;
                }),
                request -> new AssetFetchEndpoint.FetchUnavailable(),
                CLOCK);
        assertInstanceOf(AssetResolver.ResolveTimedOut.class,
                timed.resolve(request(CLOCK.millis())));
        assertEquals(0, persistenceCalls.get());

        AssetResolver fetchUnavailable = AssetModule.resolver(
                persistence((method, args) -> {
                    var query = (AssetPersistence.RenderSelectionQuery) args[0];
                    return new AssetPersistence.RenderSelectionResolved(selection(query));
                }),
                request -> new AssetFetchEndpoint.FetchUnavailable(),
                CLOCK);
        assertInstanceOf(AssetResolver.ResolveUnavailable.class,
                fetchUnavailable.resolve(request(CLOCK.millis() + 60_000)));
    }

    private static AssetResolver resolverReturning(
            AssetPersistence.RenderSelectionOutcome outcome
    ) {
        return AssetModule.resolver(
                persistence((method, args) -> outcome),
                request -> new AssetFetchEndpoint.FetchUnavailable(),
                CLOCK);
    }

    private static AssetResolver.ResolveRequest request(long deadline) {
        return new AssetResolver.ResolveRequest(
                "render-request-01",
                OWNER_SCOPE,
                RESOURCE_ID,
                ASSET_ID,
                AssetAcceptanceAuthority.AssetKind.IMAGE,
                "renderer:v1",
                deadline);
    }

    private static AssetPersistence.RenderSelection selection(
            AssetPersistence.RenderSelectionQuery query
    ) {
        return new AssetPersistence.RenderSelection(
                query.renderRequestId(),
                query.ownerScope(),
                query.resourceId(),
                query.assetId(),
                query.expectedKind(),
                query.rendererAudience(),
                query.requestFingerprint(),
                "lease-handle-01",
                new AssetPersistence.ResolutionContent(
                        7,
                        "a".repeat(64),
                        "image/png",
                        321,
                        new AssetAcceptanceAuthority.ImageDescriptor(
                                20, 10,
                                AssetAcceptanceAuthority.Orientation.IDENTITY,
                                20, 10, 1,
                                AssetAcceptanceAuthority.ColorEncoding.SRGB_8BIT)),
                query.issuedAtEpochMilli(),
                query.leaseExpiresAtEpochSecond(),
                query.recordExpiresAtEpochMilli());
    }

    private static AssetResolver.Rejection expected(
            AssetPersistence.RenderRejection rejection
    ) {
        return switch (rejection) {
            case SCOPE_MISMATCH -> AssetResolver.Rejection.SCOPE_MISMATCH;
            case NOT_FOUND -> AssetResolver.Rejection.NOT_FOUND;
            case DELETED -> AssetResolver.Rejection.DELETED;
            case KIND_MISMATCH -> AssetResolver.Rejection.KIND_MISMATCH;
        };
    }

    private static AssetPersistence persistence(Invocation invocation) {
        return (AssetPersistence) Proxy.newProxyInstance(
                AssetPersistence.class.getClassLoader(),
                new Class<?>[]{AssetPersistence.class},
                (proxy, method, args) -> invocation.invoke(method.getName(), args)
        );
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args);
    }
}
