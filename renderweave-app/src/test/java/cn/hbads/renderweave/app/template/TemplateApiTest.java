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

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clearTemplates() {
        jdbc.sql("truncate table template_revision, template_aggregate").update();
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
        org.assertj.core.api.Assertions.assertThat(
                jdbc.sql("select count(*) from template_aggregate").query(Long.class).single()
        ).isZero();
    }
}
