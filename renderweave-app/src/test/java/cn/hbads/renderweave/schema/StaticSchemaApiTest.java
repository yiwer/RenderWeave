package cn.hbads.renderweave.schema;

import cn.hbads.renderweave.schema.draft.DraftService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class StaticSchemaApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DraftService drafts;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearUserSchemas() {
        jdbcClient.sql("delete from schema_reference_edge").update();
        jdbcClient.sql("delete from static_schema_reference_edge where source_schema_key not like 'system-%'")
                .update();
        jdbcClient.sql("delete from static_schema where origin = 'DRAFT'").update();
        jdbcClient.sql("delete from schema_draft_revision").update();
        jdbcClient.sql("delete from schema_draft").update();
    }

    @Test
    void presetsPublishReadDownloadAndCopyThroughTheContract() throws Exception {
        mockMvc.perform(get("/api/v1/static-schemas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.items[0].schemaKey").value("system-basic-boolean"))
                .andExpect(jsonPath("$.items[0].origin").value("SYSTEM"));

        mockMvc.perform(get("/api/v1/static-schemas/system-empty/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaKey").value("system-empty"))
                .andExpect(jsonPath("$.versionTag").value("v1"))
                .andExpect(jsonPath("$.definition.fields").isEmpty())
                .andExpect(jsonPath("$.compilerVersion").value("renderweave-json-schema/1.0"));

        drafts.create("api-static", """
                {"dslVersion":"renderweave-schema/1.0","displayName":"API Static","fields":[
                  {"fieldKey":"value","required":true,"value":{"type":"text"}}
                ]}
                """);
        mockMvc.perform(post("/api/v1/static-schemas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaKey":"api-static","expectedRevision":0,
                                 "versionTag":"v1","releaseNote":"  API 发布  "}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/static-schemas/api-static/v1"))
                .andExpect(jsonPath("$.sourceDraftRevision").value(0))
                .andExpect(jsonPath("$.releaseNote").value("API 发布"))
                .andExpect(jsonPath("$.referenceDepth").value(1));

        mockMvc.perform(get("/api/v1/static-schemas/api-static/v1/compiled-json-schema"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/schema+json"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"x-renderweave-static-schema-ref\":{\"schemaKey\":\"api-static\",\"versionTag\":\"v1\"}"
                )));
        mockMvc.perform(get("/api/v1/static-schemas/api-static/v1/definition"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.displayName").value("API Static"));

        mockMvc.perform(post("/api/v1/static-schemas/api-static/v1/copies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaKey\":\"api-static-copy\",\"displayName\":\"API 副本\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/schema-drafts/api-static-copy"))
                .andExpect(jsonPath("$.definition.displayName").value("API 副本"))
                .andExpect(jsonPath("$.revision").value(0));
    }

    @Test
    void immutableSurfaceAndStaticProblemsHaveStableCodes() throws Exception {
        mockMvc.perform(get("/api/v1/static-schemas/missing/v1"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("STATIC_SCHEMA_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/static-schemas/system-empty/INVALID!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERSION_TAG_INVALID"));

        mockMvc.perform(put("/api/v1/static-schemas/system-empty/v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/v1/static-schemas/system-empty/v1"))
                .andExpect(status().isMethodNotAllowed());
    }
}
