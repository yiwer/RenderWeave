package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveRepairPolicyTest {

    @Test
    void repairsOnlyWhenEveryBlockerIsDeterministicallyRepairable() {
        assertEquals(LiveRepairPolicy.Decision.REPAIR, LiveRepairPolicy.decide(List.of(
                problem("AI_REQUIRED_UNCONFIRMED", CandidateProblemSeverity.BLOCKER),
                problem("JSON_EVIDENCE_ITEM_MISSING", CandidateProblemSeverity.BLOCKER),
                problem("ADVISORY", CandidateProblemSeverity.WARNING)
        )));
    }

    @Test
    void warningsAndHumanOnlyBlockersRemainReviewable() {
        assertEquals(LiveRepairPolicy.Decision.REVIEW, LiveRepairPolicy.decide(List.of(
                problem("ADVISORY", CandidateProblemSeverity.WARNING)
        )));
        assertEquals(LiveRepairPolicy.Decision.REVIEW, LiveRepairPolicy.decide(List.of(
                problem("CANDIDATE_FIELD_KEY_INVALID", CandidateProblemSeverity.BLOCKER)
        )));
        assertEquals(LiveRepairPolicy.Decision.REVIEW, LiveRepairPolicy.decide(List.of(
                problem("CANDIDATE_FIELD_KEY_INVALID", CandidateProblemSeverity.BLOCKER),
                problem("CANDIDATE_TYPE_UNRESOLVED", CandidateProblemSeverity.BLOCKER),
                problem("LOW_CONFIDENCE_UNRESOLVED", CandidateProblemSeverity.BLOCKER)
        )));
    }

    @Test
    void mixedOrUnknownBlockerSetsFailClosed() {
        assertEquals(LiveRepairPolicy.Decision.REJECT, LiveRepairPolicy.decide(List.of(
                problem("AI_REQUIRED_UNCONFIRMED", CandidateProblemSeverity.BLOCKER),
                problem("CANDIDATE_TYPE_UNRESOLVED", CandidateProblemSeverity.BLOCKER)
        )));
        assertEquals(LiveRepairPolicy.Decision.REJECT, LiveRepairPolicy.decide(List.of(
                problem("FUTURE_UNCLASSIFIED_BLOCKER", CandidateProblemSeverity.BLOCKER)
        )));
    }

    private static CandidateProblem problem(String code, CandidateProblemSeverity severity) {
        return new CandidateProblem(code, severity, null, "/candidate", Map.of());
    }
}
