package cn.hbads.renderweave.app.asset;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "renderweave.template.single-owner.enabled=true",
        "renderweave.template.single-owner.owner-scope=api-owner",
        "renderweave.template.single-owner.capabilities=template.create,template.read,template.update"
})
@AutoConfigureMockMvc
class AssetApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-12-18T13-15-44Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("renderweave.asset.single-owner.enabled", () -> "true");
        registry.add("renderweave.asset.single-owner.owner-scope", () -> "api-scope");
        registry.add(
                "renderweave.asset.single-owner.capabilities",
                () -> "asset.read,asset.create,asset.update,asset.delete,asset.restore"
        );
        registry.add("renderweave.asset.s3.endpoint", () -> MINIO.getS3URL());
        registry.add("renderweave.asset.s3.access-key", MINIO::getUserName);
        registry.add("renderweave.asset.s3.secret-key", MINIO::getPassword);
        registry.add("renderweave.asset.s3.bucket", () -> "renderweave-assets");
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())
                ))
                .build()) {
            s3.createBucket(CreateBucketRequest.builder().bucket("renderweave-assets").build());
        }
    }

    @BeforeEach
    void clearAssets() {
        jdbc.sql("truncate table asset_idempotency, asset_content_revision, asset_aggregate").update();
        jdbc.sql("update asset_capacity set used_bytes = 0").update();
    }

    private static byte[] jpegBytes() {
        try (var stream = AssetApiTest.class.getResourceAsStream(
                "/asset-fixtures/grayscale-baseline.jpg")) {
            assertThat(stream).isNotNull();
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static MockMultipartFile contentPart() {
        return new MockMultipartFile(
                "content",
                "fixture.jpg",
                "image/jpeg",
                jpegBytes()
        );
    }

    @Test
    void createCurrentCatalogUpdateVersionsDownloadAndPreviewThroughHttp() throws Exception {
        var created = mockMvc.perform(multipart("/api/v1/assets")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .header("Idempotency-Key", "api-key-1")
                        .param("kind", "IMAGE")
                        .param("displayName", "API asset")
                        .param("tags", "api", "slice")
                        .param("sourceFileName", "fixture.jpg")
                        .file(contentPart()))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.matchesPattern(
                                "/api/v1/assets/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
                        )))
                .andExpect(jsonPath("$.disclosure").value("READABLE"))
                .andExpect(jsonPath("$.kind").value("IMAGE"))
                .andExpect(jsonPath("$.lifecycle").value("ACTIVE"))
                .andExpect(jsonPath("$.assetRevision").value(0))
                .andExpect(jsonPath("$.currentContentVersion").value(0))
                .andExpect(jsonPath("$.displayName").value("API asset"))
                .andExpect(jsonPath("$.tags").isArray())
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.sourceFileName").value("fixture.jpg"))
                .andExpect(jsonPath("$.mediaType").value("image/jpeg"))
                .andExpect(jsonPath("$.byteLength").value(jpegBytes().length))
                .andExpect(jsonPath("$.sha256").value(
                        org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")
                ))
                .andExpect(jsonPath("$.descriptor.encodedWidthPx").value(
                        org.hamcrest.Matchers.greaterThan(0)
                ))
                .andExpect(jsonPath("$.descriptor.orientation").exists())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.ownerScope").doesNotExist())
                .andReturn();
        var assetId = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(created.getResponse().getContentAsByteArray())
                .path("assetId")
                .asText();

        mockMvc.perform(get("/api/v1/assets/{assetId}", assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId))
                .andExpect(jsonPath("$.revision").doesNotExist())
                .andExpect(jsonPath("$.assetRevision").value(0));

        mockMvc.perform(get("/api/v1/assets")
                        .queryParam("kind", "IMAGE")
                        .queryParam("tagsAll", "api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].assetId").value(assetId))
                .andExpect(jsonPath("$.items[0].kind").value("IMAGE"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        mockMvc.perform(get("/api/v1/assets")
                        .queryParam("tagsAny", "missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        mockMvc.perform(put("/api/v1/assets/{assetId}/metadata", assetId)
                        .queryParam("expectedAssetRevision", "0")
                        .contentType("application/json")
                        .content("""
                                {"displayName":"Renamed","tags":["slice"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetRevision").value(1))
                .andExpect(jsonPath("$.displayName").value("Renamed"))
                .andExpect(jsonPath("$.tags.length()").value(1));

        mockMvc.perform(put("/api/v1/assets/{assetId}/metadata", assetId)
                        .queryParam("expectedAssetRevision", "0")
                        .contentType("application/json")
                        .content("""
                                {"displayName":"Stale","tags":[]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("ASSET_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.currentAssetRevision").value(1));

        mockMvc.perform(get("/api/v1/assets/{assetId}/versions", assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].contentVersion").value(0))
                .andExpect(jsonPath("$.items[0].sha256").value(
                        org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")
                ))
                .andExpect(jsonPath("$.items[0].sourceFileName").value("fixture.jpg"));

        mockMvc.perform(get("/api/v1/assets/{assetId}/download", assetId)
                        .queryParam("contentVersion", "0"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/jpeg"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(content().bytes(jpegBytes()));

        mockMvc.perform(get("/api/v1/assets/{assetId}/download", assetId)
                        .queryParam("contentVersion", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_CONTENT_VERSION_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/assets/{assetId}/preview", assetId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/jpeg"))
                .andExpect(content().bytes(jpegBytes()));
    }

    @Test
    void idempotentReplayReturnsSameAssetAndConflictRejectsDifferentInput() throws Exception {
        var first = mockMvc.perform(multipart("/api/v1/assets")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .header("Idempotency-Key", "api-key-2")
                        .param("kind", "IMAGE")
                        .param("displayName", "Same")
                        .file(contentPart()))
                .andExpect(status().isCreated())
                .andReturn();
        var replayed = mockMvc.perform(multipart("/api/v1/assets")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .header("Idempotency-Key", "api-key-2")
                        .param("kind", "IMAGE")
                        .param("displayName", "Same")
                        .file(contentPart()))
                .andExpect(status().isCreated())
                .andReturn();
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(mapper.readTree(first.getResponse().getContentAsByteArray()).path("assetId").asText())
                .isEqualTo(mapper.readTree(replayed.getResponse().getContentAsByteArray())
                        .path("assetId")
                        .asText());

        mockMvc.perform(multipart("/api/v1/assets")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .header("Idempotency-Key", "api-key-2")
                        .param("kind", "IMAGE")
                        .param("displayName", "Different")
                        .file(contentPart()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("ASSET_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void invalidRequestsFailWithoutWrites() throws Exception {
        mockMvc.perform(multipart("/api/v1/assets")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .param("kind", "IMAGE")
                        .param("displayName", "No key")
                        .file(contentPart()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("ASSET_REQUEST_INVALID"));

        mockMvc.perform(multipart("/api/v1/assets")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .header("Idempotency-Key", "api-key-3")
                        .param("kind", "VIDEO")
                        .param("displayName", "Bad kind")
                        .file(contentPart()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSET_REQUEST_INVALID"));

        mockMvc.perform(multipart("/api/v1/assets")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .header("Idempotency-Key", "api-key-3")
                        .param("kind", "IMAGE")
                        .param("displayName", "No content"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSET_REQUEST_INVALID"));

        mockMvc.perform(multipart("/api/v1/assets")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .header("Idempotency-Key", "api-key-3")
                        .param("kind", "IMAGE")
                        .param("displayName", "Rejected content")
                        .file(new MockMultipartFile(
                                "content",
                                "bad.bin",
                                "application/octet-stream",
                                new byte[]{(byte) 0x00, (byte) 0xFF, 0x01}
                        )))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("ASSET_CONTENT_INVALID"))
                .andExpect(jsonPath("$.stage").exists());

        mockMvc.perform(get("/api/v1/assets/{assetId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSET_REQUEST_INVALID"));

        mockMvc.perform(get("/api/v1/assets")
                        .queryParam("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSET_REQUEST_INVALID"));

        mockMvc.perform(put("/api/v1/assets/{assetId}/metadata",
                        "123e4567-e89b-42d3-a456-426614174000")
                        .queryParam("expectedAssetRevision", "-1")
                        .contentType("application/json")
                        .content("""
                                {"displayName":"Bad revision","tags":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSET_REQUEST_INVALID"));

        assertThat(
                jdbc.sql("select count(*) from asset_aggregate").query(Long.class).single()
        ).isZero();
    }
}
