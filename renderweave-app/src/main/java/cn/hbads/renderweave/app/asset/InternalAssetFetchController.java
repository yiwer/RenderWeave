package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.spi.AssetBlobPersistence;
import cn.hbads.renderweave.asset.spi.AssetFetchEndpoint;
import cn.hbads.renderweave.asset.spi.AssetPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Bearer-protected renderer endpoint; deliberately absent from the public API namespace. */
@RestController
@ConditionalOnBean(SignedAssetFetchEndpoint.class)
final class InternalAssetFetchController {

    private static final int STREAM_CHUNK_BYTES = 1_048_576;

    private final SignedAssetFetchEndpoint endpoint;
    private final AssetPersistence persistence;
    private final AssetBlobPersistence blobs;

    InternalAssetFetchController(
            SignedAssetFetchEndpoint endpoint,
            AssetPersistence persistence,
            AssetBlobPersistence blobs
    ) {
        this.endpoint = endpoint;
        this.persistence = persistence;
        this.blobs = blobs;
    }

    @GetMapping(SignedAssetFetchEndpoint.ROUTE_PREFIX + "{token}")
    ResponseEntity<StreamingResponseBody> fetch(
            @PathVariable String token,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) String range,
            @RequestHeader(name = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestHeader(name = HttpHeaders.ACCEPT_ENCODING, required = false) String encoding
    ) {
        if (range != null || cookie != null
                || (encoding != null && !"identity".equalsIgnoreCase(encoding.strip()))) {
            return ResponseEntity.notFound().build();
        }
        SignedAssetFetchEndpoint.ParsedToken parsed = endpoint.parse(token);
        if (parsed == null || parsed.expiresAtEpochSecond() <= endpoint.nowEpochSecond()) {
            return ResponseEntity.notFound().build();
        }

        AssetPersistence.RenderLeaseLoadOutcome loaded = persistence.loadRenderSelection(
                new AssetPersistence.RenderLeaseLookup(parsed.leaseHandle()));
        if (loaded instanceof AssetPersistence.RenderLeaseUnavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!(loaded instanceof AssetPersistence.RenderLeaseLoaded exact)) {
            return ResponseEntity.notFound().build();
        }
        AssetPersistence.RenderSelection selection = exact.selection();
        AssetFetchEndpoint.IssueRequest claims;
        try {
            claims = new AssetFetchEndpoint.IssueRequest(
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
            );
        } catch (RuntimeException corrupt) {
            return ResponseEntity.notFound().build();
        }
        if (!endpoint.verifies(claims, parsed)
                || claims.expiresAtEpochSecond() <= endpoint.nowEpochSecond()) {
            return ResponseEntity.notFound().build();
        }

        AssetBlobPersistence.LoadOutcome blob = blobs.load(
                selection.ownerScope().value(), selection.content().sha256());
        if (blob instanceof AssetBlobPersistence.LoadUnavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!(blob instanceof AssetBlobPersistence.Loaded present)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        byte[] bytes = present.bytes();
        if (bytes.length != selection.content().byteLength()
                || !selection.content().sha256().equals(sha256(bytes))) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        // Every byte is verified before the response/stream exists. Chunking bounds the
        // servlet write checkpoint while preserving an identity body with exact length.
        StreamingResponseBody body = output -> {
            int offset = 0;
            while (offset < bytes.length) {
                int length = Math.min(STREAM_CHUNK_BYTES, bytes.length - offset);
                output.write(bytes, offset, length);
                offset += length;
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(selection.content().mediaType()));
        headers.setContentLength(bytes.length);
        headers.set(HttpHeaders.CONTENT_ENCODING, "identity");
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
