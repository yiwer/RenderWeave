package cn.hbads.renderweave.validation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidationBatchRequestParserTest {

    private final ValidationBatchRequestParser parser = new ValidationBatchRequestParser();

    @Test
    void preservesDecimalSourceTokenAndAcceptsUnknownDocumentShape() {
        var parsed = parser.parse(request("{\"amount\":1.2300e+2,\"anything\":[null,{\"x\":true}]}"));

        var target = assertInstanceOf(ValidationTarget.DraftTarget.class, parsed.target());
        assertEquals("root", target.schemaKey().value());
        var root = assertInstanceOf(StrictJsonValue.ObjectValue.class, parsed.documents().getFirst());
        var amount = assertInstanceOf(StrictJsonValue.NumberValue.class, root.members().get("amount"));
        assertEquals("1.2300e+2", amount.rawToken());
    }

    @Test
    void rejectsDuplicateNamesAtAnyObjectLevelBeforeValidation() {
        var exception = assertThrows(
                InvalidValidationRequestException.class,
                () -> parser.parse(request("{\"nested\":{\"same\":1,\"same\":2}}"))
        );

        assertEquals(InvalidValidationRequestException.Kind.MALFORMED, exception.kind());
        assertEquals("VALIDATION_DUPLICATE_MEMBER", exception.code());
    }

    @Test
    void rejectsJsonExtensionsAndTrailingSyntax() {
        assertMalformed("{\"value\":NaN}");
        assertMalformed("{'value':1}");
        assertMalformed("{\"value\":1,}");
        assertMalformed("{/*comment*/\"value\":1}");
    }

    @Test
    void enforcesDocumentCountDepthArrayAndByteBudgets() {
        var noDocuments = """
                {"target":{"kind":"draft","schemaKey":"root"},"documents":[]}
                """.getBytes(StandardCharsets.UTF_8);
        assertCode("VALIDATION_DOCUMENT_COUNT_INVALID", () -> parser.parse(noDocuments));

        var depth32 = nestedObject(32);
        assertEquals(1, parser.parse(request(depth32)).documents().size());
        assertCode("VALIDATION_NESTING_DEPTH_EXCEEDED", () -> parser.parse(request(nestedObject(33))));

        var array = new StringBuilder("[");
        for (int index = 0; index <= ValidationBatchRequestParser.MAX_ARRAY_ITEMS; index++) {
            if (index > 0) {
                array.append(',');
            }
            array.append('0');
        }
        array.append(']');
        assertCode("VALIDATION_ARRAY_LIMIT_EXCEEDED", () -> parser.parse(request(array.toString())));

        var oversized = "{\"value\":\"" + "a".repeat(ValidationBatchRequestParser.MAX_DOCUMENT_BYTES) + "\"}";
        assertCode("VALIDATION_DOCUMENT_TOO_LARGE", () -> parser.parse(request(oversized)));
    }

    @Test
    void parsesExactStaticAndSystemPresetTargets() {
        var body = """
                {
                  "target":{"kind":"static","schemaKey":"system-basic-text","versionTag":"v1"},
                  "documents":[{"document":{"index":0,"value":"hello"}}]
                }
                """.getBytes(StandardCharsets.UTF_8);

        var target = assertInstanceOf(ValidationTarget.StaticTarget.class, parser.parse(body).target());
        assertEquals("system-basic-text", target.reference().schemaKey().value());
        assertEquals("v1", target.reference().versionTag().value());
    }

    @Test
    void rejectsAggregateDocumentBytesEvenWhenEveryDocumentIsIndividuallyValid() {
        var payload = "a".repeat(1_748_000);
        var body = new StringBuilder(
                "{\"target\":{\"kind\":\"draft\",\"schemaKey\":\"root\"},\"documents\":["
        );
        for (int index = 0; index < 6; index++) {
            if (index > 0) {
                body.append(',');
            }
            body.append("{\"document\":{\"value\":\"").append(payload).append("\"}}");
        }
        body.append("]}");

        assertCode(
                "VALIDATION_BATCH_TOO_LARGE",
                () -> parser.parse(body.toString().getBytes(StandardCharsets.UTF_8))
        );
    }

    private void assertMalformed(String document) {
        assertCode("VALIDATION_INVALID_JSON", () -> parser.parse(request(document)));
    }

    private static void assertCode(String code, Runnable operation) {
        var exception = assertThrows(InvalidValidationRequestException.class, operation::run);
        assertEquals(code, exception.code());
    }

    private static byte[] request(String document) {
        return ("{\"target\":{\"kind\":\"draft\",\"schemaKey\":\"root\"},"
                + "\"documents\":[{\"document\":" + document + "}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String nestedObject(int depth) {
        return "{\"x\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1);
    }
}
