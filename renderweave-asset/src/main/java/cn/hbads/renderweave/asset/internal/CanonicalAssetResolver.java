package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetResolver;
import cn.hbads.renderweave.asset.spi.AssetFetchEndpoint;
import cn.hbads.renderweave.asset.spi.AssetPersistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;

final class CanonicalAssetResolver implements AssetResolver {

    private static final byte[] FINGERPRINT_DOMAIN =
            "renderweave.asset-resolve-request/1\0".getBytes(StandardCharsets.UTF_8);
    private static final long LEASE_SAFETY_MARGIN_MILLIS = 5_000;
    private static final long RECOVERY_RETENTION_MILLIS = 300_000;

    private final AssetPersistence persistence;
    private final AssetFetchEndpoint fetchEndpoint;
    private final Clock clock;

    CanonicalAssetResolver(
            AssetPersistence persistence,
            AssetFetchEndpoint fetchEndpoint,
            Clock clock
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.fetchEndpoint = Objects.requireNonNull(fetchEndpoint, "fetchEndpoint");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PrecheckOutcome precheck(PrecheckRequest request) {
        Objects.requireNonNull(request, "request");
        AssetPersistence.RenderPrecheckOutcome outcome;
        try {
            outcome = persistence.precheckForRender(new AssetPersistence.RenderPrecheckQuery(
                    request.ownerScope(), request.assetId(), request.expectedKind()));
        } catch (RuntimeException unavailable) {
            return new PrecheckUnavailable();
        }
        if (outcome instanceof AssetPersistence.RenderPrecheckPassed) {
            return new PrecheckPassed();
        }
        if (outcome instanceof AssetPersistence.RenderPrecheckRejected rejected) {
            return new PrecheckRejected(map(rejected.reason()));
        }
        return new PrecheckUnavailable();
    }

    @Override
    public ResolveOutcome resolve(ResolveRequest request) {
        Objects.requireNonNull(request, "request");
        long issuedAt = clock.millis();
        if (issuedAt >= request.renderDeadlineEpochMilli()) {
            return new ResolveTimedOut();
        }

        long leaseExpiryMillis;
        long recordExpiryMillis;
        long leaseExpirySecond;
        try {
            leaseExpiryMillis = Math.addExact(
                    request.renderDeadlineEpochMilli(), LEASE_SAFETY_MARGIN_MILLIS);
            recordExpiryMillis = Math.addExact(
                    request.renderDeadlineEpochMilli(), RECOVERY_RETENTION_MILLIS);
            leaseExpirySecond = ceilEpochSecond(leaseExpiryMillis);
        } catch (ArithmeticException overflow) {
            return new ResolveTimedOut();
        }
        String fingerprint = fingerprint(request);
        var query = new AssetPersistence.RenderSelectionQuery(
                request.renderRequestId(),
                request.ownerScope(),
                request.resourceId(),
                request.assetId(),
                request.expectedKind(),
                request.rendererAudience(),
                request.renderDeadlineEpochMilli(),
                fingerprint,
                issuedAt,
                leaseExpirySecond,
                recordExpiryMillis
        );

        AssetPersistence.RenderSelectionOutcome outcome;
        try {
            outcome = persistence.resolveForRender(query);
        } catch (RuntimeException unavailable) {
            return new ResolveUnavailable();
        }
        if (outcome instanceof AssetPersistence.RenderSelectionRejected rejected) {
            return new ResolveRejected(map(rejected.reason()));
        }
        if (outcome instanceof AssetPersistence.RenderSelectionConflict) {
            return new ResolveConflict();
        }
        if (!(outcome instanceof AssetPersistence.RenderSelectionResolved resolved)) {
            return new ResolveUnavailable();
        }
        AssetPersistence.RenderSelection selection = resolved.selection();
        if (!matches(query, selection) || !contentMatchesKind(selection)) {
            return new ResolveUnavailable();
        }
        if (clock.millis() >= request.renderDeadlineEpochMilli()) {
            return new ResolveTimedOut();
        }

        AssetFetchEndpoint.FetchIssueOutcome issue;
        try {
            issue = fetchEndpoint.issue(new AssetFetchEndpoint.IssueRequest(
                    selection.leaseHandle(),
                    selection.requestFingerprint(),
                    selection.renderRequestId(),
                    selection.resourceId(),
                    selection.assetId(),
                    selection.content().contentVersion(),
                    selection.content().sha256(),
                    selection.content().byteLength(),
                    selection.rendererAudience(),
                    selection.leaseExpiresAtEpochSecond()
            ));
        } catch (RuntimeException unavailable) {
            return new ResolveUnavailable();
        }
        if (!(issue instanceof AssetFetchEndpoint.FetchIssued issued)) {
            return new ResolveUnavailable();
        }
        if (clock.millis() >= request.renderDeadlineEpochMilli()) {
            return new ResolveTimedOut();
        }

        try {
            var content = selection.content();
            return new Resolved(new ResolvedAsset(
                    selection.resourceId(),
                    selection.assetId(),
                    content.contentVersion(),
                    selection.kind(),
                    content.sha256(),
                    content.mediaType(),
                    content.byteLength(),
                    ACCEPTANCE_PROFILE_ID,
                    content.descriptor(),
                    new FetchLease(issued.fetchUrl(), selection.leaseExpiresAtEpochSecond())
            ));
        } catch (RuntimeException malformedAdapterResult) {
            return new ResolveUnavailable();
        }
    }

    private static boolean matches(
            AssetPersistence.RenderSelectionQuery query,
            AssetPersistence.RenderSelection selection
    ) {
        return query.renderRequestId().equals(selection.renderRequestId())
                && query.ownerScope().equals(selection.ownerScope())
                && query.resourceId().equals(selection.resourceId())
                && query.assetId().equals(selection.assetId())
                && query.expectedKind() == selection.kind()
                && query.rendererAudience().equals(selection.rendererAudience())
                && query.requestFingerprint().equals(selection.requestFingerprint())
                && query.leaseExpiresAtEpochSecond() == selection.leaseExpiresAtEpochSecond()
                && query.recordExpiresAtEpochMilli() == selection.recordExpiresAtEpochMilli();
    }

    private static boolean contentMatchesKind(AssetPersistence.RenderSelection selection) {
        String mediaType = selection.content().mediaType();
        AssetAcceptanceAuthority.TechnicalDescriptor descriptor =
                selection.content().descriptor();
        return switch (selection.kind()) {
            case IMAGE -> descriptor instanceof AssetAcceptanceAuthority.ImageDescriptor
                    && ("image/png".equals(mediaType)
                    || "image/jpeg".equals(mediaType)
                    || "image/webp".equals(mediaType));
            case FONT -> descriptor instanceof AssetAcceptanceAuthority.FontDescriptor
                    && ("font/ttf".equals(mediaType) || "font/otf".equals(mediaType));
        };
    }

    private static Rejection map(AssetPersistence.RenderRejection rejection) {
        return switch (rejection) {
            case SCOPE_MISMATCH -> Rejection.SCOPE_MISMATCH;
            case NOT_FOUND -> Rejection.NOT_FOUND;
            case DELETED -> Rejection.DELETED;
            case KIND_MISMATCH -> Rejection.KIND_MISMATCH;
        };
    }

    private static long ceilEpochSecond(long epochMilli) {
        long seconds = Math.floorDiv(epochMilli, 1_000);
        return Math.floorMod(epochMilli, 1_000) == 0 ? seconds : Math.addExact(seconds, 1);
    }

    private static String fingerprint(ResolveRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(FINGERPRINT_DOMAIN);
            update(digest, request.renderRequestId());
            update(digest, request.ownerScope().value());
            update(digest, request.resourceId());
            update(digest, request.assetId().value());
            update(digest, request.expectedKind().name());
            update(digest, request.rendererAudience());
            digest.update(ByteBuffer.allocate(Long.BYTES)
                    .putLong(request.renderDeadlineEpochMilli()).array());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
