package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderDocumentCanonicalWriterTest {

    @Test
    void admitsStrictJsonContainerDepthBelowAndAtTheInclusiveBudget() {
        var below = assertDoesNotThrow(() -> write(nestedContainers(127)));
        var at = assertDoesNotThrow(() -> write(nestedContainers(128)));

        assertTrue(new String(below, StandardCharsets.UTF_8).contains("{[not-depth]}"));
        assertTrue(at.length > below.length);
    }

    @Test
    void rejectsStrictJsonContainerDepthAboveTheBudgetBeforeCommit() {
        var failure = assertThrows(
                RenderDocumentCanonicalWriter.CapacityExceeded.class,
                () -> write(nestedContainers(129)));

        assertEquals(EvaluationStage.DOCUMENT_SEAL, failure.problem().stage());
        assertEquals(ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                failure.problem().code());
        assertEquals("renderDocument.jsonDepth",
                failure.problem().limitId().orElseThrow().value());
    }

    @Test
    void observesPathMaximumWithoutAccumulatingSiblingContainers() {
        var siblings = java.util.stream.IntStream.range(0, 129)
                .mapToObj(ignored -> CanonicalJson.arrayValue(List.of()))
                .toList();

        assertDoesNotThrow(() -> write(CanonicalJson.arrayValue(siblings)));
    }

    private static byte[] write(CanonicalJson.CanonicalValue value) {
        return RenderDocumentCanonicalWriter.write(
                value,
                new RenderingPipelineCapacityGuard().newRequestTracker());
    }

    private static CanonicalJson.CanonicalValue nestedContainers(int depth) {
        CanonicalJson.CanonicalValue value = CanonicalJson.stringValue("{[not-depth]}");
        for (int index = 0; index < depth; index++) {
            value = index % 2 == 0
                    ? CanonicalJson.arrayValue(List.of(value))
                    : CanonicalJson.objectValue(Map.of("nested", value));
        }
        return value;
    }
}
