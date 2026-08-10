package cn.hbads.renderweave.inference.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VisualStageCheckpointReaderTest {
    @Test
    void durableAttemptCountCanAccountForFailedCallsMissingFromTheWorkflowCounter() {
        var checkpoint = new LiveWorkflowJsonCodec().write(LiveWorkflowCheckpoint.observed());
        var reader = new VisualStageCheckpointReader();

        assertEquals(0, reader.read(checkpoint).providerCalls());
        assertEquals(1, reader.read(checkpoint, 1).providerCalls());
        assertThrows(IllegalArgumentException.class, () -> reader.read(checkpoint, -1));
    }
}
