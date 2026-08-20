package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.spi.AssetFetchEndpoint;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;

/** Deterministic HMAC materializer for an opaque renderer-only internal fetch URL. */
final class SignedAssetFetchEndpoint implements AssetFetchEndpoint {

    static final String ROUTE_PREFIX = "/internal/render-assets/";

    private final AssetResolutionSecrets secrets;
    private final String canonicalOrigin;
    private final Clock clock;

    SignedAssetFetchEndpoint(
            AssetResolutionSecrets secrets,
            String fetchBaseUrl,
            Clock clock
    ) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.canonicalOrigin = canonicalOrigin(fetchBaseUrl);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public FetchIssueOutcome issue(IssueRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.expiresAtEpochSecond() <= clock.instant().getEpochSecond()) {
            return new FetchUnavailable();
        }
        try {
            String signature = secrets.sign(request);
            String token = "v1." + request.leaseHandle() + "."
                    + request.expiresAtEpochSecond() + "." + signature;
            return new FetchIssued(canonicalOrigin + ROUTE_PREFIX + token);
        } catch (AssetResolutionSecrets.SecretFailure unavailable) {
            return new FetchUnavailable();
        }
    }

    boolean verifies(IssueRequest request, ParsedToken token) {
        return token.leaseHandle().equals(request.leaseHandle())
                && token.expiresAtEpochSecond() == request.expiresAtEpochSecond()
                && secrets.verifies(request, token.signature());
    }

    ParsedToken parse(String token) {
        if (token == null || token.length() > 256) {
            return null;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4 || !"v1".equals(parts[0])
                || !parts[1].matches("[0-9a-f]{64}")
                || !parts[3].matches("[A-Za-z0-9_-]{43}")) {
            return null;
        }
        try {
            long expires = Long.parseLong(parts[2]);
            if (expires <= 0) {
                return null;
            }
            return new ParsedToken(parts[1], expires, parts[3]);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    long nowEpochSecond() {
        return clock.instant().getEpochSecond();
    }

    private static String canonicalOrigin(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("renderweave.asset.fetch-base-url must be configured");
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("asset fetch base URL must be canonical HTTPS", malformed);
        }
        String path = uri.getRawPath();
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(path == null || path.isEmpty() || "/".equals(path))
                || !uri.getHost().equals(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("asset fetch base URL must be a canonical HTTPS origin");
        }
        try {
            return new URI("https", null, uri.getHost(), uri.getPort(), null, null, null)
                    .toASCIIString();
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException("asset fetch base URL must be canonical HTTPS", impossible);
        }
    }

    record ParsedToken(String leaseHandle, long expiresAtEpochSecond, String signature) {
    }
}
