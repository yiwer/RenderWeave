package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CanonicalDesignDslAuthorityTest {

    private static final String CANONICAL_BASELINE = """
            {"definitions":[],"designRoot":{"bindings":[],"children":[],"heightMm":297,"kind":"canvas","nodeId":"00000000-0000-4000-8000-000000000001","widthMm":210},"displayName":"Baseline","dslVersion":"renderweave-design/1.0","expressionProfile":"renderweave-expression/1.0"}""";

    private final DesignDslAuthority authority = new CanonicalDesignDslAuthority();

    @Test
    void admitsTheFrozenSystemEmptySingleCanvasBaseline() {
        var raw = """
                {
                  "dslVersion": "renderweave-design/1.0",
                  "expressionProfile": "renderweave-expression/1.0",
                  "displayName": "Baseline",
                  "definitions": [],
                  "designRoot": {
                    "nodeId": "00000000-0000-4000-8000-000000000001",
                    "kind": "canvas",
                    "widthMm": 210,
                    "heightMm": 297,
                    "bindings": [],
                    "children": []
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);

        var outcome = authority.admit(raw);
        var admitted = assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                outcome,
                outcome::toString
        );

        assertArrayEquals(
                CANONICAL_BASELINE.getBytes(StandardCharsets.UTF_8),
                admitted.canonicalUtf8()
        );
        assertEquals(
                "sha256:618bcfe94db5f8779c6113b99712b39ef1cd361fc0911a97b6c48b474caf5f49",
                admitted.contentHash()
        );
    }

    @Test
    void rejectsIllegalUtf8BeforeDesignModeling() {
        var raw = "{\"displayName\":\"".getBytes(StandardCharsets.UTF_8);
        var invalid = new byte[raw.length + 1];
        System.arraycopy(raw, 0, invalid, 0, raw.length);
        invalid[raw.length] = (byte) 0x80;

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                authority.admit(invalid)
        );

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_UTF8_INVALID, rejected.code());
        assertEquals(DesignDslAuthority.FailureStage.DESIGN_PARSE, rejected.stage());
    }

    @Test
    void normalizesMetadataAndArbitraryPrecisionCanvasDecimalsWithoutUnicodeNormalization() {
        var raw = """
                {
                  "designRoot": {
                    "widthMm": 2.10e2,
                    "nodeId": "00000000-0000-4000-8000-000000000001",
                    "children": [],
                    "bleed": {
                      "leftMm": 2.5000,
                      "topMm": -0,
                      "bottomMm": 1e0,
                      "rightMm": 0.00
                    },
                    "kind": "canvas",
                    "bindings": [],
                    "displayName": "  画布 e\u0301  ",
                    "heightMm": 2.9700e2
                  },
                  "description": "  \\t ",
                  "definitions": [],
                  "displayName": "  A/会员 e\u0301  ",
                  "expressionProfile": "renderweave-expression/1.0",
                  "dslVersion": "renderweave-design/1.0"
                }
                """.getBytes(StandardCharsets.UTF_8);

        var outcome = authority.admit(raw);
        var admitted = assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                outcome,
                outcome::toString
        );
        var expected = "{\"definitions\":[],\"designRoot\":{\"bindings\":[],"
                + "\"bleed\":{\"bottomMm\":1,\"leftMm\":2.5,\"rightMm\":0,\"topMm\":0},"
                + "\"children\":[],\"displayName\":\"画布 e\u0301\",\"heightMm\":297,"
                + "\"kind\":\"canvas\",\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"widthMm\":210},\"displayName\":\"A/会员 e\u0301\","
                + "\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\"}";

        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), admitted.canonicalUtf8());
        assertEquals(
                "sha256:12ab67ae5fda681500facee245d0b92af2e1a1590a58a7ffcf12ea520f423333",
                admitted.contentHash()
        );
    }

    @Test
    void admitsOnlyCanonicalUppercaseRgbaWithoutExpandingTheOmittedDefault() {
        var admitted = assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                authority.admit(baselineWith("\"backgroundColor\":\"#10A0B0FF\","))
        );
        assertEquals(
                "{\"definitions\":[],\"designRoot\":{\"backgroundColor\":\"#10A0B0FF\","
                        + "\"bindings\":[],\"children\":[],\"heightMm\":297,\"kind\":\"canvas\","
                        + "\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                        + "\"widthMm\":210},\"displayName\":\"Baseline\","
                        + "\"dslVersion\":\"renderweave-design/1.0\","
                        + "\"expressionProfile\":\"renderweave-expression/1.0\"}",
                new String(admitted.canonicalUtf8(), StandardCharsets.UTF_8)
        );

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                authority.admit(baselineWith("\"backgroundColor\":\"#10a0b0ff\","))
        );
        assertEquals(DesignDslAuthority.FailureCode.DESIGN_VALUE_INVALID, rejected.code());
        assertEquals("/designRoot/backgroundColor", rejected.pointer());
    }

    @Test
    void rejectsJsonNullEverywhereBeforeClosedObjectValidation() {
        var raw = """
                {
                  "dslVersion":"renderweave-design/1.0",
                  "expressionProfile":"renderweave-expression/1.0",
                  "displayName":"Baseline",
                  "description":null,
                  "definitions":[],
                  "designRoot":{
                    "nodeId":"00000000-0000-4000-8000-000000000001",
                    "kind":"canvas",
                    "widthMm":210,
                    "heightMm":297,
                    "bindings":[],
                    "children":[]
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                authority.admit(raw)
        );

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_VALUE_INVALID, rejected.code());
        assertEquals("/description", rejected.pointer());
    }

    @Test
    void rejectsEnumCatalogMemberOnListValueTypeVariant() {
        var raw = """
                {
                  "dslVersion":"renderweave-design/1.0",
                  "expressionProfile":"renderweave-expression/1.0",
                  "displayName":"Baseline",
                  "definitions":[{
                    "definitionId":"00000000-0000-4000-8000-000000000002",
                    "kind":"custom",
                    "displayName":"Items",
                    "exposure":"PRIVATE",
                    "valueType":{"type":"list","items":"text","catalogId":"not-a-list-member"},
                    "defaultValue":[]
                  }],
                  "designRoot":{
                    "nodeId":"00000000-0000-4000-8000-000000000001",
                    "kind":"canvas",
                    "widthMm":210,
                    "heightMm":297,
                    "bindings":[],
                    "children":[]
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                authority.admit(raw)
        );

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_MEMBER_UNKNOWN, rejected.code());
        assertEquals("/definitions/0/valueType/catalogId", rejected.pointer());
    }

    @Test
    void rejectsLoopIdMemberOnInvocationSelectorDomainVariant() {
        var raw = """
                {
                  "dslVersion":"renderweave-design/1.0",
                  "expressionProfile":"renderweave-expression/1.0",
                  "displayName":"Baseline",
                  "definitions":[],
                  "designRoot":{
                    "nodeId":"00000000-0000-4000-8000-000000000001",
                    "kind":"canvas",
                    "widthMm":210,
                    "heightMm":297,
                    "bindings":[],
                    "children":[{
                      "nodeId":"00000000-0000-4000-8000-000000000002",
                      "kind":"templateUse",
                      "bindings":[],
                      "useId":"00000000-0000-4000-8000-000000000003",
                      "templateRef":{"templateId":"00000000-0000-4000-8000-000000000004"},
                      "contextSelector":{
                        "kind":"context",
                        "domain":{
                          "kind":"invocation",
                          "loopId":"00000000-0000-4000-8000-000000000005"
                        },
                        "pointer":"",
                        "contextAbsentPolicy":"ERROR"
                      },
                      "fills":[],
                      "placement":{
                        "type":"ABSOLUTE",
                        "xMm":0,
                        "yMm":0,
                        "widthMode":"HUG_CONTENT",
                        "heightMode":"HUG_CONTENT"
                      }
                    }]
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                authority.admit(raw)
        );

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_MEMBER_UNKNOWN, rejected.code());
        assertEquals("/designRoot/children/0/contextSelector/domain/loopId",
                rejected.pointer());
    }

    @Test
    void reservesTheRawUtf8ByteLimitBeforeParsing() {
        var raw = new byte[16 * 1024 * 1024 + 1];
        Arrays.fill(raw, (byte) ' ');

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                authority.admit(raw)
        );

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED, rejected.code());
        assertEquals(DesignDslAuthority.FailureStage.DESIGN_PARSE, rejected.stage());
        assertEquals(
                DesignDslAuthority.Limit.RAW_UTF8_BYTES,
                rejected.limit().orElseThrow()
        );
    }

    @Test
    void reservesEachObjectMemberBeforeModelAllocation() {
        var raw = new StringBuilder("{");
        for (int index = 0; index < 1_025; index++) {
            if (index > 0) {
                raw.append(',');
            }
            raw.append('"').append('m').append(index).append("\":0");
        }
        raw.append('}');

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                authority.admit(raw.toString().getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED, rejected.code());
        assertEquals(
                DesignDslAuthority.Limit.OBJECT_MEMBERS,
                rejected.limit().orElseThrow()
        );
    }

    @Test
    void rejectsCanonicalExpansionThroughTheSixteenMebibyteCountingSink() {
        var raw = """
                {
                  "dslVersion":"renderweave-design/1.0",
                  "expressionProfile":"renderweave-expression/1.0",
                  "displayName":"Baseline",
                  "definitions":[],
                  "designRoot":{
                    "nodeId":"00000000-0000-4000-8000-000000000001",
                    "kind":"canvas",
                    "widthMm":1e16777216,
                    "heightMm":297,
                    "bindings":[],
                    "children":[]
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                authority.admit(raw)
        );

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED, rejected.code());
        assertEquals(DesignDslAuthority.FailureStage.DESIGN_CANONICAL_COUNT, rejected.stage());
        assertEquals(
                DesignDslAuthority.Limit.CANONICAL_BYTES,
                rejected.limit().orElseThrow()
        );
    }

    private byte[] baselineWith(String canvasPrefix) {
        return ("""
                {
                  "dslVersion":"renderweave-design/1.0",
                  "expressionProfile":"renderweave-expression/1.0",
                  "displayName":"Baseline",
                  "definitions":[],
                  "designRoot":{
                """ + canvasPrefix + """
                    "nodeId":"00000000-0000-4000-8000-000000000001",
                    "kind":"canvas",
                    "widthMm":210,
                    "heightMm":297,
                    "bindings":[],
                    "children":[]
                  }
                }
                """).getBytes(StandardCharsets.UTF_8);
    }
}
