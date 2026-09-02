package cn.hbads.renderweave.app.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class TemplateApiTest {

    private static final String DESIGN_MEDIA_TYPE = "application/vnd.renderweave.design+json";
    private static final String DESIGN = """
            {"dslVersion":"renderweave-design/1.0",
             "expressionProfile":"renderweave-expression/1.0",
             "displayName":"  API template  ",
             "definitions":[],
             "designRoot":{"nodeId":"123e4567-e89b-42d3-a456-426614174000",
               "kind":"canvas","widthMm":210,"heightMm":297,
               "bindings":[],"children":[]}}
            """;
    private static final String MISSING_IMAGE_DESIGN = """
            {"dslVersion":"renderweave-design/1.0",
             "expressionProfile":"renderweave-expression/1.0",
             "displayName":"Missing image dependency",
             "definitions":[],
             "designRoot":{"nodeId":"123e4567-e89b-42d3-a456-426614174001",
               "kind":"canvas","widthMm":210,"heightMm":297,"bindings":[],
               "children":[{"nodeId":"123e4567-e89b-42d3-a456-426614174002",
                 "kind":"image","bindings":[],
                 "placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                   "widthMode":"FIXED","widthMm":10,
                   "heightMode":"FIXED","heightMm":10},
                 "imageRef":{"assetId":"00000000-0000-4000-8000-0000000000ff"}}]}}
            """;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clearTemplates() {
        jdbc.sql("""
                truncate table template_use_reference,
                                 template_asset_reference,
                                 template_revision,
                                 template_aggregate
                cascade
                """).update();
    }

    @Test
    void createGetSaveAndConflictUseRawDesignBodyWithoutOwnerScopeInput() throws Exception {
        var create = mockMvc.perform(post("/api/v1/templates")
                        .queryParam("schemaKey", "system-empty")
                        .queryParam("versionTag", "v1")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.disclosure").value("READABLE"))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.staticSchema.schemaKey").value("system-empty"))
                .andExpect(jsonPath("$.staticSchema.versionTag").value("v1"))
                .andExpect(jsonPath("$.designDsl.displayName").value("API template"))
                .andExpect(jsonPath("$.ownerScope").doesNotExist())
                .andReturn();
        var templateId = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(create.getResponse().getContentAsByteArray())
                .path("templateId")
                .asText();

        mockMvc.perform(get("/api/v1/templates/{templateId}", templateId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateId").value(templateId))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.contentHash").value(
                        org.hamcrest.Matchers.matchesPattern("sha256:[0-9a-f]{64}")
                ));

        mockMvc.perform(put("/api/v1/templates/{templateId}", templateId)
                        .queryParam("expectedRevision", "0")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));

        mockMvc.perform(put("/api/v1/templates/{templateId}", templateId)
                        .queryParam("expectedRevision", "0")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("TEMPLATE_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.currentRevision").value(1));

        mockMvc.perform(post("/api/v1/templates")
                        .queryParam("schemaKey", "system-empty")
                        .queryParam("versionTag", "v1")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN.substring(0, DESIGN.length() - 2) + ",\"ownerScope\":\"evil\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DESIGN_MEMBER_UNKNOWN"))
                .andExpect(jsonPath("$.pointer").value("/ownerScope"));
    }

    @Test
    void getIsSideEffectFreeAndExplicitReadinessRecheckBindsTheCheckedCurrent() throws Exception {
        var create = mockMvc.perform(post("/api/v1/templates")
                        .queryParam("schemaKey", "system-empty")
                        .queryParam("versionTag", "v1")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN))
                .andExpect(status().isCreated())
                .andReturn();
        var created = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(create.getResponse().getContentAsByteArray());
        var templateId = created.path("templateId").asText();
        var contentHash = created.path("contentHash").asText();
        jdbc.sql("update template_aggregate set readiness = 'STALE' where template_id = :id")
                .param("id", templateId)
                .update();

        mockMvc.perform(get("/api/v1/templates/{templateId}", templateId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readiness").value("STALE"));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.sql("select readiness from template_aggregate where template_id = :id")
                        .param("id", templateId)
                        .query(String.class)
                        .single()
        ).isEqualTo("STALE");

        mockMvc.perform(post(
                        "/api/v1/templates/{templateId}/readiness-recheck",
                        templateId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateId").value(templateId))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.contentHash").value(contentHash))
                .andExpect(jsonPath("$.readiness").value("READY"));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.sql("select readiness from template_aggregate where template_id = :id")
                        .param("id", templateId)
                        .query(String.class)
                        .single()
        ).isEqualTo("READY");
    }

    @Test
    void catalogReturnsOnlySafeActiveCurrentSummariesWithStableCursorAndSearch() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var alpha = mapper.readTree(mockMvc.perform(post("/api/v1/templates")
                        .queryParam("schemaKey", "system-empty")
                        .queryParam("versionTag", "v1")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN.replace("  API template  ", "Alpha layout")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
        var beta = mapper.readTree(mockMvc.perform(post("/api/v1/templates")
                        .queryParam("schemaKey", "system-empty")
                        .queryParam("versionTag", "v1")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN.replace("  API template  ", "Beta layout")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
        jdbc.sql("update template_aggregate set updated_at = cast(:updatedAt as timestamptz) where template_id = :id")
                .param("updatedAt", "2026-08-25T01:00:00Z")
                .param("id", alpha.path("templateId").asText())
                .update();
        jdbc.sql("update template_aggregate set updated_at = cast(:updatedAt as timestamptz) where template_id = :id")
                .param("updatedAt", "2026-08-25T02:00:00Z")
                .param("id", beta.path("templateId").asText())
                .update();

        var firstPage = mockMvc.perform(get("/api/v1/templates")
                        .queryParam("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].templateId")
                        .value(beta.path("templateId").asText()))
                .andExpect(jsonPath("$.items[0].displayName").value("Beta layout"))
                .andExpect(jsonPath("$.items[0].revision").value(0))
                .andExpect(jsonPath("$.items[0].staticSchema.schemaKey").value("system-empty"))
                .andExpect(jsonPath("$.items[0].staticSchema.versionTag").value("v1"))
                .andExpect(jsonPath("$.items[0].readiness").value("READY"))
                .andExpect(jsonPath("$.items[0].updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].designDsl").doesNotExist())
                .andExpect(jsonPath("$.items[0].contentHash").doesNotExist())
                .andExpect(jsonPath("$.items[0].ownerScope").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        var cursor = mapper.readTree(firstPage.getResponse().getContentAsByteArray())
                .path("nextCursor").asText();

        mockMvc.perform(get("/api/v1/templates")
                        .queryParam("limit", "1")
                        .queryParam("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].templateId")
                        .value(alpha.path("templateId").asText()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        mockMvc.perform(get("/api/v1/templates")
                        .queryParam("search", "  beta  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].templateId")
                        .value(beta.path("templateId").asText()));

        mockMvc.perform(get("/api/v1/templates")
                        .queryParam("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMPLATE_REQUEST_INVALID"));
    }

    @Test
    void dependencySaveRequiresExactNamedConfirmationAndCommitsInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/templates")
                        .queryParam("schemaKey", "system-empty")
                        .queryParam("versionTag", "v1")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(MISSING_IMAGE_DESIGN))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("TEMPLATE_DEPENDENCY_REJECTED"))
                .andExpect(jsonPath("$.problems[0].code").value("TEMPLATE_ASSET_NOT_FOUND"))
                .andExpect(jsonPath("$.problems[0].category").value("DEPENDENCY"))
                .andExpect(jsonPath("$.problems[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.problems[0].canonicalPointer")
                        .value("/designRoot/children/0/imageRef"))
                .andExpect(jsonPath("$.truncated").value(false));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.sql("select count(*) from template_aggregate").query(Long.class).single()
        ).isZero();

        var create = mockMvc.perform(post("/api/v1/templates")
                        .queryParam("schemaKey", "system-empty")
                        .queryParam("versionTag", "v1")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN))
                .andExpect(status().isCreated())
                .andReturn();
        var templateId = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(create.getResponse().getContentAsByteArray())
                .path("templateId")
                .asText();

        var offerResponse = mockMvc.perform(put("/api/v1/templates/{templateId}", templateId)
                        .queryParam("expectedRevision", "0")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(MISSING_IMAGE_DESIGN))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code")
                        .value("TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.proposedContentHash")
                        .value(org.hamcrest.Matchers.matchesPattern("sha256:[0-9a-f]{64}")))
                .andExpect(jsonPath("$.confirmationToken")
                        .value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.problems[0].code").value("TEMPLATE_ASSET_NOT_FOUND"))
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn();
        var token = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(offerResponse.getResponse().getContentAsByteArray())
                .path("confirmationToken")
                .asText();
        org.assertj.core.api.Assertions.assertThat(
                jdbc.sql("select count(*) from template_revision").query(Long.class).single()
        ).isEqualTo(1);

        mockMvc.perform(put("/api/v1/templates/{templateId}", templateId)
                        .queryParam("expectedRevision", "0")
                        .header("X-Confirmation-Token", token)
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(MISSING_IMAGE_DESIGN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.readiness").value("INVALID"));
    }

    @Test
    void completeWireRoundTripsThroughRealTemplateEndpointsWithoutSemanticLoss() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var canonicalFixture = Files.readString(repoFile(
                "renderweave-template/src/test/resources/cn/hbads/renderweave/template/"
                        + "complete-wire-v1/all-kinds.json"
        ), StandardCharsets.UTF_8);
        var originalDesign = mapper.readTree(canonicalFixture);

        var create = mockMvc.perform(post("/api/v1/templates")
                        .queryParam("schemaKey", "system-basic-text")
                        .queryParam("versionTag", "v1")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN))
                .andExpect(status().isCreated())
                .andReturn();
        var templateId = mapper.readTree(create.getResponse().getContentAsByteArray())
                .path("templateId")
                .asText();

        var initialOffer = mockMvc.perform(put("/api/v1/templates/{templateId}", templateId)
                        .queryParam("expectedRevision", "0")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(canonicalFixture))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code")
                        .value("TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn();
        var initialToken = mapper.readTree(initialOffer.getResponse().getContentAsByteArray())
                .path("confirmationToken")
                .asText();

        var committed = mockMvc.perform(put("/api/v1/templates/{templateId}", templateId)
                        .queryParam("expectedRevision", "0")
                        .header("X-Confirmation-Token", initialToken)
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(canonicalFixture))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.readiness").value("INVALID"))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(
                mapper.readTree(committed.getResponse().getContentAsByteArray()).path("designDsl")
        ).isEqualTo(originalDesign);

        var editedDesign = originalDesign.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) editedDesign)
                .put("displayName", "Complete wire edited");
        var editedJson = mapper.writeValueAsBytes(editedDesign);
        var editOffer = mockMvc.perform(put("/api/v1/templates/{templateId}", templateId)
                        .queryParam("expectedRevision", "1")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(editedJson))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value("TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn();
        var editToken = mapper.readTree(editOffer.getResponse().getContentAsByteArray())
                .path("confirmationToken")
                .asText();

        mockMvc.perform(put("/api/v1/templates/{templateId}", templateId)
                        .queryParam("expectedRevision", "1")
                        .header("X-Confirmation-Token", editToken)
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(editedJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.readiness").value("INVALID"));

        var reloaded = mockMvc.perform(get("/api/v1/templates/{templateId}", templateId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.readiness").value("INVALID"))
                .andReturn();
        var reloadedBody = mapper.readTree(reloaded.getResponse().getContentAsByteArray());
        org.assertj.core.api.Assertions.assertThat(reloadedBody.path("designDsl"))
                .isEqualTo(editedDesign);
    }

    @Test
    void missingSchemaAndWrongMediaTypeFailWithoutWrites() throws Exception {
        mockMvc.perform(post("/api/v1/templates")
                        .queryParam("schemaKey", "missing-schema")
                        .queryParam("versionTag", "v1")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("TEMPLATE_STATIC_SCHEMA_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        mockMvc.perform(post("/api/v1/templates")
                        .queryParam("schemaKey", "system-empty")
                        .queryParam("versionTag", "v1")
                        .contentType("application/json")
                        .content(DESIGN))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post("/api/v1/templates")
                        .queryParam("schemaKey", "INVALID")
                        .queryParam("versionTag", "v1")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("SCHEMA_KEY_INVALID"));
        mockMvc.perform(get("/api/v1/templates/{templateId}", "x".repeat(129)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMPLATE_REQUEST_INVALID"));
        mockMvc.perform(post("/api/v1/templates")
                        .queryParam("versionTag", "v1")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("TEMPLATE_REQUEST_INVALID"));
        mockMvc.perform(put("/api/v1/templates/{templateId}", "opaque-id")
                        .queryParam("expectedRevision", "not-a-number")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("TEMPLATE_REQUEST_INVALID"));
        mockMvc.perform(put("/api/v1/templates/{templateId}", "opaque-id")
                        .queryParam("expectedRevision", "0")
                        .header("X-Confirmation-Token", "force")
                        .contentType(DESIGN_MEDIA_TYPE)
                        .content(DESIGN))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("TEMPLATE_REQUEST_INVALID"));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.sql("select count(*) from template_aggregate").query(Long.class).single()
        ).isZero();
    }

    private static Path repoFile(String relative) {
        var cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            var candidate = cursor.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("repository file is absent: " + relative);
    }
}
