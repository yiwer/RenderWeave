package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.spi.AssetBlobPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;

/**
 * S3-protocol blob adapter: production OSS, local compose and Testcontainers MinIO use the
 * same code path. Keys are scope-partitioned SHA-256 content addresses; store is idempotent.
 */
@Repository
@ConditionalOnBean(S3Client.class)
public class S3AssetBlobPersistence implements AssetBlobPersistence {
    private final S3Client s3;
    private final String bucket;

    S3AssetBlobPersistence(
            S3Client s3,
            @Value("${renderweave.asset.s3.bucket}") String bucket
    ) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public StoreOutcome store(String ownerScope, String sha256, byte[] bytes) {
        try {
            String key = keyOf(ownerScope, sha256);
            boolean exists = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build()).sdkHttpResponse().isSuccessful();
            if (!exists) {
                s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build(), RequestBody.fromBytes(bytes));
            }
            return new Stored(!exists);
        } catch (NoSuchKeyException missing) {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(keyOf(ownerScope, sha256))
                    .build(), RequestBody.fromBytes(bytes));
            return new Stored(true);
        } catch (RuntimeException unavailable) {
            return new StoreUnavailable();
        }
    }

    @Override
    public LoadOutcome load(String ownerScope, String sha256) {
        try {
            byte[] bytes;
            try (var stream = s3.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(keyOf(ownerScope, sha256))
                    .build())) {
                bytes = stream.readAllBytes();
            }
            return new Loaded(bytes);
        } catch (NoSuchKeyException missing) {
            return new LoadNotFound();
        } catch (RuntimeException | IOException unavailable) {
            return new LoadUnavailable();
        }
    }

    private static String keyOf(String ownerScope, String sha256) {
        return ownerScope + "/blobs/" + sha256;
    }
}
