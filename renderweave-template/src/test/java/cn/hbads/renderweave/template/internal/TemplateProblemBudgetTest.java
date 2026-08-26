package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.TemplateApplication;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateProblemBudgetTest {
    private static final int MAX_ITEMS = 200;
    private static final int MAX_ITEM_BYTES = 4096;
    private static final int MAX_TOTAL_BYTES = 262_144;
    private static final int MAX_ORDINARY_BYTES = MAX_TOTAL_BYTES - 1024;

    @Test
    void oversizedSingleProblemBecomesAByteMarkerAndCannotBeConfirmed() {
        var oversized = problem(
                "/designRoot",
                Collections.nCopies(8, "x".repeat(512))
        );

        var report = TemplateProblemBudget.bounded(List.of(oversized));

        assertTrue(report.truncated());
        assertFalse(report.confirmable());
        assertEquals(1, report.problems().size());
        assertEquals("PROBLEM_LIMIT_REACHED", report.problems().getFirst().code());
        assertEquals("BYTES", report.problems().getFirst().messageArgs().getFirst());
        assertCanonicalBounds(report);
    }

    @Test
    void totalByteLimitPreservesMarkerReserveAndStableFingerprint() {
        var problems = new ArrayList<TemplateApplication.ValidationProblem>();
        for (int index = 0; index < MAX_ITEMS; index++) {
            var prefix = "/%03d".formatted(index);
            problems.add(problem(prefix + "p".repeat(2048 - prefix.length()), List.of()));
        }

        var report = TemplateProblemBudget.bounded(problems);
        var reversed = new ArrayList<>(problems);
        Collections.reverse(reversed);
        var replay = TemplateProblemBudget.bounded(reversed);

        assertTrue(report.truncated());
        assertFalse(report.confirmable());
        assertEquals("PROBLEM_LIMIT_REACHED", report.problems().getLast().code());
        assertEquals("BYTES", report.problems().getLast().messageArgs().getFirst());
        assertEquals(report.problems(), replay.problems());
        assertEquals(report.fingerprint(), replay.fingerprint());
        assertCanonicalBounds(report);
        var ordinaryBytes = report.problems().stream()
                .filter(problem -> !problem.code().equals("PROBLEM_LIMIT_REACHED"))
                .mapToInt(TemplateProblemBudget::canonicalSize)
                .sum();
        assertTrue(ordinaryBytes <= MAX_ORDINARY_BYTES);
    }

    private static TemplateApplication.ValidationProblem problem(
            String pointer,
            List<String> arguments
    ) {
        return new TemplateApplication.ValidationProblem(
                "TEMPLATE_ASSET_NOT_FOUND",
                TemplateApplication.ProblemCategory.DEPENDENCY,
                TemplateApplication.ProblemSeverity.ERROR,
                pointer,
                arguments
        );
    }

    private static void assertCanonicalBounds(TemplateApplication.ValidationReport report) {
        assertTrue(report.problems().stream()
                .allMatch(problem -> TemplateProblemBudget.canonicalSize(problem)
                        <= MAX_ITEM_BYTES));
        assertTrue(report.problems().stream()
                .mapToInt(TemplateProblemBudget::canonicalSize)
                .sum() <= MAX_TOTAL_BYTES);
    }
}
