package cn.hbads.renderweave.app.validation;

import cn.hbads.renderweave.schema.draft.DraftService;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaService;
import cn.hbads.renderweave.validation.ValidationBatchRequestParser;
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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RootDocumentValidationApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DraftService drafts;

    @Autowired
    private StaticSchemaService statics;

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
    void validatesBatchAgainstOneFrozenRootInclusiveDraftRevisionGraph() throws Exception {
        drafts.create("validation-child", """
                {"dslVersion":"renderweave-schema/1.0","displayName":"Child","fields":[
                  {"fieldKey":"city","required":true,"value":{"type":"text"}}
                ]}
                """);
        drafts.create("validation-root", """
                {"dslVersion":"renderweave-schema/1.0","displayName":"Root","fields":[
                  {"fieldKey":"name","required":true,"value":{"type":"text","constraints":{"minLength":2}}},
                  {"fieldKey":"address","required":true,"value":{"type":"reference","ref":{"schemaKey":"validation-child"}}}
                ]}
                """);
        drafts.save("validation-child", 0, """
                {"dslVersion":"renderweave-schema/1.0","displayName":"Child v2","fields":[
                  {"fieldKey":"city","required":true,"value":{"type":"text"}},
                  {"fieldKey":"postal","required":true,"value":{"type":"text","constraints":{"pattern":"^[0-9]{5}$"}}}
                ]}
                """);

        mockMvc.perform(post("/api/v1/root-document-validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "target":{"kind":"draft","schemaKey":"validation-root"},
                                  "documents":[
                                    {"document":{"name":"Ada","address":{"city":"London","postal":"12345"},"unknown":null}},
                                    {"document":{"address":{"city":"Paris","postal":null}}}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target.kind").value("draft"))
                .andExpect(jsonPath("$.target.schemaKey").value("validation-root"))
                .andExpect(jsonPath("$.target.revision").value(0))
                .andExpect(jsonPath("$.resolvedSchemas", hasSize(2)))
                .andExpect(jsonPath("$.resolvedSchemas[0].schemaKey").value("validation-root"))
                .andExpect(jsonPath("$.resolvedSchemas[0].revision").value(0))
                .andExpect(jsonPath("$.resolvedSchemas[1].schemaKey").value("validation-child"))
                .andExpect(jsonPath("$.resolvedSchemas[1].revision").value(1))
                .andExpect(jsonPath("$.summary.total").value(2))
                .andExpect(jsonPath("$.summary.valid").value(1))
                .andExpect(jsonPath("$.summary.invalid").value(1))
                .andExpect(jsonPath("$.documents[0].valid").value(true))
                .andExpect(jsonPath("$.documents[1].valid").value(false))
                .andExpect(jsonPath("$.documents[1].problems[0].code").value("REQUIRED_FIELD_MISSING"))
                .andExpect(jsonPath("$.documents[1].problems[0].instancePath").value("/name"))
                .andExpect(jsonPath("$.documents[1].problems[1].code").value("NULL_VALUE_UNSUPPORTED"))
                .andExpect(jsonPath("$.documents[1].problems[1].instancePath").value("/address/postal"))
                .andExpect(jsonPath("$.documents[1].problems[1].schemaPath")
                        .value("/schemas/draft/validation-child/1/definition/fields/1/value/type"))
                .andExpect(jsonPath("$.documents[1].truncated").value(false));
    }

    @Test
    void exactStaticTargetUsesImmutableNestedSnapshotsAfterDraftsChange() throws Exception {
        drafts.create("static-validation-child", """
                {"dslVersion":"renderweave-schema/1.0","displayName":"Child","fields":[
                  {"fieldKey":"value","required":true,"value":{"type":"decimal","constraints":{"min":1}}}
                ]}
                """);
        statics.publish("static-validation-child", 0, "v1", "child");
        drafts.create("static-validation-root", """
                {"dslVersion":"renderweave-schema/1.0","displayName":"Root","fields":[
                  {"fieldKey":"child","required":true,"value":{"type":"reference","ref":{"schemaKey":"static-validation-child","versionTag":"v1"}}}
                ]}
                """);
        statics.publish("static-validation-root", 0, "v1", "root");

        drafts.save("static-validation-child", 0, """
                {"dslVersion":"renderweave-schema/1.0","displayName":"Child changed","fields":[
                  {"fieldKey":"value","required":true,"value":{"type":"decimal","constraints":{"min":100}}}
                ]}
                """);

        mockMvc.perform(post("/api/v1/root-document-validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "target":{"kind":"static","schemaKey":"static-validation-root","versionTag":"v1"},
                                  "documents":[{"document":{"child":{"value":2}}}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target.kind").value("static"))
                .andExpect(jsonPath("$.target.versionTag").value("v1"))
                .andExpect(jsonPath("$.target.revision").doesNotExist())
                .andExpect(jsonPath("$.resolvedSchemas", hasSize(2)))
                .andExpect(jsonPath("$.resolvedSchemas[1].schemaKey").value("static-validation-child"))
                .andExpect(jsonPath("$.documents[0].valid").value(true));
    }

    @Test
    void strictInputAndMissingTargetsReturnStableRfc9457Problems() throws Exception {
        drafts.create("strict-validation", """
                {"dslVersion":"renderweave-schema/1.0","displayName":"Strict","fields":[]}
                """);

        mockMvc.perform(post("/api/v1/root-document-validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"target":{"kind":"draft","schemaKey":"strict-validation"},
                                 "documents":[{"document":{"same":1,"same":2}}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_DUPLICATE_MEMBER"))
                .andExpect(jsonPath("$.violations[0].code").value("VALIDATION_DUPLICATE_MEMBER"));

        mockMvc.perform(post("/api/v1/root-document-validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"target":{"kind":"draft","schemaKey":"missing-validation"},
                                 "documents":[{"document":{}}]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRAFT_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/root-document-validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"target":{"kind":"draft","schemaKey":"strict-validation"},
                                 "documents":[{"document":[]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].valid").value(false))
                .andExpect(jsonPath("$.documents[0].problems[0].code").value("ROOT_TYPE_UNSUPPORTED"));

        var oversizedArray = new StringBuilder("[");
        for (int index = 0; index <= ValidationBatchRequestParser.MAX_ARRAY_ITEMS; index++) {
            if (index > 0) {
                oversizedArray.append(',');
            }
            oversizedArray.append('0');
        }
        oversizedArray.append(']');
        mockMvc.perform(post("/api/v1/root-document-validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":{\"kind\":\"draft\",\"schemaKey\":\"strict-validation\"},"
                                + "\"documents\":[{\"document\":" + oversizedArray + "}]}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ARRAY_LIMIT_EXCEEDED"));
    }
}
