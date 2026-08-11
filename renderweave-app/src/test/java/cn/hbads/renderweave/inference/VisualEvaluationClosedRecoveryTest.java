package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly opt-in, zero-Provider finalization for a CLOSED visual evaluation journal. */
@EnabledIfSystemProperty(named = "renderweave.visual-evaluation.closed-recovery", matches = "true")
class VisualEvaluationClosedRecoveryTest {
    @Test
    void archivesReservedExecutionsAndDropsUnreservedExecutionsWithoutOpeningProviderPath() {
        var root = repositoryRoot();
        var json = JsonMapper.builder().build();
        var authorization = VisualEvaluationAuthorization.load(
                VisualEvaluationAuthorizationLocator.resolve(root), json
        );
        authorization.requireClosed();
        var now = Clock.systemUTC().instant();
        var goalBudget = new VisualEvaluationGoalBudget(
                root.resolve(".sdlc/evidence").resolve(VisualEvaluationGoalBudget.GOAL_ID), json, now
        );
        var journal = new VisualEvaluationJournal(
                root.resolve(".sdlc/evidence").resolve(authorization.authorizationId()),
                authorization, new VisualStageCorpus(), json, now
        );
        try (var ignored = journal.acquireClosedRecoveryLease()) {
            var recovery = journal.recoverInterruptedAfterClosure(goalBudget, now);
            assertThat(recovery.retriableCaseIds())
                    .allMatch(authorization.caseIds()::contains);
            assertThat(recovery.abandonedCaseIds())
                    .allMatch(authorization.caseIds()::contains);
        }
        assertThat(journal.snapshot().executions())
                .noneMatch(item -> "IN_PROGRESS".equals(item.status()));
    }

    private static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        while (current != null && (!Files.isRegularFile(current.resolve("pom.xml"))
                || !Files.isDirectory(current.resolve(".sdlc")))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Visual evaluation repository is unavailable");
        return current;
    }
}
