package cn.hbads.renderweave.schema;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class DraftApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearDrafts() {
        jdbcClient.sql("delete from schema_reference_edge").update();
        jdbcClient.sql("delete from schema_draft_revision").update();
        jdbcClient.sql("delete from schema_draft").update();
    }

    @Test
    void createGetAndSaveExposeThePersistedDraftContract() throws Exception {
        mockMvc.perform(post("/api/v1/schema-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest("http-card", "  HTTP 卡片  ", "title")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/schema-drafts/http-card"))
                .andExpect(jsonPath("$.schemaKey").value("http-card"))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.definition.displayName").value("HTTP 卡片"))
                .andExpect(jsonPath("$.definition.description").doesNotExist())
                .andExpect(jsonPath("$.creationSource").value("USER"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.savedAt").isString());

        mockMvc.perform(get("/api/v1/schema-drafts/http-card"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.definition.fields[0].fieldKey").value("title"));

        mockMvc.perform(put("/api/v1/schema-drafts/http-card")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(0, "HTTP 卡片 v2", "headline")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.definition.displayName").value("HTTP 卡片 v2"))
                .andExpect(jsonPath("$.definition.fields[0].fieldKey").value("headline"));

        mockMvc.perform(get("/api/v1/schema-drafts")
                        .queryParam("page", "1")
                        .queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].schemaKey").value("http-card"))
                .andExpect(jsonPath("$.items[0].revision").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("HTTP 卡片 v2"))
                .andExpect(jsonPath("$.items[0].fieldCount").value(1))
                .andExpect(jsonPath("$.items[0].creationSource").value("USER"))
                .andExpect(jsonPath("$.items[0].updatedAt").isString());

        mockMvc.perform(get("/api/v1/schema-drafts/http-card"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.definition.fields[0].fieldKey").value("headline"));
    }

    @Test
    void validationConflictAndNotFoundUseStableRfc9457Problems() throws Exception {
        mockMvc.perform(post("/api/v1/schema-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaKey":"invalid-card","definition":{
                                  "dslVersion":"renderweave-schema/1.0","displayName":"无效","fields":[
                                    {"fieldKey":"title","fieldId":"forbidden","required":false,"value":{"type":"text"}}
                                  ]
                                }}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SCHEMA_DEFINITION_INVALID"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.violations[0].code").value("DSL_UNKNOWN_MEMBER"))
                .andExpect(jsonPath("$.violations[0].pointer").value("/definition/fields/0/fieldId"));

        mockMvc.perform(get("/api/v1/schema-drafts/missing-card"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DRAFT_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/schema-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest("conflict-card", "原始", "title")))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/v1/schema-drafts/conflict-card")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(0, "胜者", "winner")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/schema-drafts/conflict-card")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(0, "过期", "stale")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
                .andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    void malformedDuplicateAndUnknownRequestMembersAreRejectedBeforeAnyWrite() throws Exception {
        mockMvc.perform(post("/api/v1/schema-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaKey":"duplicate-json","definition":{
                                  "dslVersion":"renderweave-schema/1.0",
                                  "displayName":"第一次","displayName":"第二次","fields":[]
                                }}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_JSON"));

        mockMvc.perform(post("/api/v1/schema-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaKey":"unknown-envelope","definition":{
                                  "dslVersion":"renderweave-schema/1.0","displayName":"合法","fields":[]
                                },"force":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/schema-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest("system-reserved", "非法 key", "title")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SCHEMA_KEY_INVALID"));

        mockMvc.perform(get("/api/v1/schema-drafts/missing-card"))
                .andExpect(status().isNotFound());
    }

    @Test
    void referenceLifecycleHistoryCopyDeleteAndRestoreHaveStableHttpSemantics() throws Exception {
        mockMvc.perform(post("/api/v1/schema-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaKey":"api-leaf","definition":{
                                  "dslVersion":"renderweave-schema/1.0","displayName":"叶节点","fields":[]
                                }}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resolvedRevisions['api-leaf']").value(0));

        mockMvc.perform(post("/api/v1/schema-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaKey":"api-parent","definition":{
                                  "dslVersion":"renderweave-schema/1.0","displayName":"父节点","fields":[
                                    {"fieldKey":"child","required":true,"value":{
                                      "type":"reference","ref":{"schemaKey":"api-leaf"}
                                    }}
                                  ]
                                }}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resolvedRevisions['api-parent']").value(0))
                .andExpect(jsonPath("$.resolvedRevisions['api-leaf']").value(0));

        mockMvc.perform(get("/api/v1/schema-drafts/api-parent/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].revision").value(0))
                .andExpect(jsonPath("$.items[0].fieldCount").value(1));

        mockMvc.perform(get("/api/v1/schema-drafts/api-parent/revisions/0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.fields[0].value.ref.schemaKey").value("api-leaf"));

        mockMvc.perform(post("/api/v1/schema-drafts/api-parent/copies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaKey":"api-parent-copy","displayName":"父节点副本"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/schema-drafts/api-parent-copy"))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.definition.displayName").value("父节点副本"))
                .andExpect(jsonPath("$.resolvedRevisions['api-leaf']").value(0));

        mockMvc.perform(delete("/api/v1/schema-drafts/api-leaf")
                        .queryParam("expectedRevision", "0"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DRAFT_DELETE_BLOCKED"))
                .andExpect(jsonPath("$.violations[0].code").value("ACTIVE_INCOMING_DRAFT_REFERENCE"));

        mockMvc.perform(delete("/api/v1/schema-drafts/api-parent-copy")
                        .queryParam("expectedRevision", "0"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/schema-drafts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
        mockMvc.perform(delete("/api/v1/schema-drafts/api-parent")
                        .queryParam("expectedRevision", "0"))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/schema-drafts/api-leaf")
                        .queryParam("expectedRevision", "0"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/schema-drafts/api-parent/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"expectedRevision\":0,\"sourceRevision\":0" +
                                "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0].code").value("SCHEMA_REFERENCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/schema-drafts/api-leaf/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"sourceRevision\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        mockMvc.perform(post("/api/v1/schema-drafts/api-parent/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"sourceRevision\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.resolvedRevisions['api-leaf']").value(1));
    }

    private static String createRequest(String schemaKey, String displayName, String fieldKey) {
        return """
                {"schemaKey":"%s","definition":%s}
                """.formatted(schemaKey, definition(displayName, fieldKey));
    }

    private static String saveRequest(long expectedRevision, String displayName, String fieldKey) {
        return """
                {"expectedRevision":%d,"definition":%s}
                """.formatted(expectedRevision, definition(displayName, fieldKey));
    }

    private static String definition(String displayName, String fieldKey) {
        return """
                {
                  "dslVersion":"renderweave-schema/1.0",
                  "displayName":"%s",
                  "description":"   ",
                  "fields":[
                    {"fieldKey":"%s","required":false,"value":{"type":"text","constraints":{"minLength":1}}}
                  ]
                }
                """.formatted(displayName, fieldKey);
    }
}
