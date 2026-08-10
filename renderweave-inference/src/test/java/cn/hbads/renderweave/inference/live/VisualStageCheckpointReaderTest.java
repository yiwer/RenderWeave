package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VisualStageCheckpointReaderTest {
    @Test
    void readsTheStrictRunStoreNormalizeEnvelopeWhenTheFirstProviderCallNeverStarts() {
        var checkpoint = """
                {"completedStage":"NORMALIZE","inputFingerprint":"%s"}
                """.formatted("a".repeat(64));
        var reader = new VisualStageCheckpointReader();

        var snapshot = reader.read(checkpoint, 0);

        assertEquals(InferenceStage.NORMALIZE, snapshot.completedStage());
        assertEquals(0, snapshot.providerCalls());
    }

    @Test
    void rejectsAFormerlyVersionlessNormalizeEnvelopeWithAnyUnknownMember() {
        var checkpoint = """
                {"completedStage":"NORMALIZE","inputFingerprint":"%s","candidateJson":{}}
                """.formatted("a".repeat(64));

        assertThrows(RuntimeException.class, () -> new VisualStageCheckpointReader().read(checkpoint, 0));
    }

    @Test
    void durableAttemptCountCanAccountForFailedCallsMissingFromTheWorkflowCounter() {
        var checkpoint = new LiveWorkflowJsonCodec().write(LiveWorkflowCheckpoint.observed());
        var reader = new VisualStageCheckpointReader();

        assertEquals(0, reader.read(checkpoint).providerCalls());
        assertEquals(1, reader.read(checkpoint, 1).providerCalls());
        assertThrows(IllegalArgumentException.class, () -> reader.read(checkpoint, -1));
    }
}
