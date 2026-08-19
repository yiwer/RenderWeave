package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.internal.AssetModule;
import cn.hbads.renderweave.asset.spi.AssetBlobPersistence;
import cn.hbads.renderweave.asset.spi.AssetOwnerScopeAuthority;
import cn.hbads.renderweave.asset.spi.AssetPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
class AssetApplicationConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = "renderweave.asset.single-owner.enabled",
            havingValue = "true"
    )
    AssetOwnerScopeAuthority configuredAssetOwnerScopeAuthority(
            @Value("${renderweave.asset.single-owner.owner-scope:}") String ownerScope,
            @Value("${renderweave.asset.single-owner.capabilities:}") String capabilities
    ) {
        return new ConfiguredSingleOwnerAssetScopeAuthority(
                new AssetApplication.OwnerScope(ownerScope),
                parseCapabilities(capabilities)
        );
    }

    @Bean
    @ConditionalOnMissingBean(AssetOwnerScopeAuthority.class)
    AssetOwnerScopeAuthority failClosedAssetOwnerScopeAuthority() {
        return new FailClosedAssetOwnerScopeAuthority();
    }

    @Bean
    @ConditionalOnMissingBean(S3Client.class)
    @ConditionalOnExpression("'${renderweave.asset.s3.endpoint:}' != ''")
    S3Client assetS3Client(
            @Value("${renderweave.asset.s3.endpoint:}") String endpoint,
            @Value("${renderweave.asset.s3.region:us-east-1}") String region,
            @Value("${renderweave.asset.s3.access-key:}") String accessKey,
            @Value("${renderweave.asset.s3.secret-key:}") String secretKey,
            @Value("${renderweave.asset.s3.path-style:true}") boolean pathStyle
    ) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .forcePathStyle(pathStyle)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                ))
                .build();
    }

    @Bean
    @ConditionalOnExpression("'${renderweave.asset.s3.endpoint:}' != ''")
    AssetApplication assetApplication(
            AssetOwnerScopeAuthority ownerScopes,
            AssetPersistence persistence,
            AssetBlobPersistence blobs
    ) {
        return AssetModule.application(ownerScopes, persistence, blobs);
    }

    private static Set<String> parseCapabilities(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
