package cn.hbads.renderweave.inference.profile;

import cn.hbads.renderweave.inference.input.InferenceMode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceProfileRegistryTest {
    @Test
    void p4ExposesOnlyTheVersionedZeroNetworkReplayProfile() {
        var registry = new InferenceProfileRegistry();
        var resource = registry.require("replay-v1");
        var profile = resource.profile();

        assertEquals(Set.of("replay-v1"), registry.profileIds());
        assertEquals("renderweave-inference-profile/1.0", profile.profileVersion());
        assertEquals("REPLAY", profile.provider());
        assertEquals("deterministic-synthetic-replay-v1", profile.model());
        assertFalse(profile.networkAllowed());
        assertEquals(Set.of(InferenceMode.values()), Set.copyOf(profile.supportedModes()));
        assertEquals(8_000, profile.lowConfidenceThresholdBps());
        assertEquals(2, profile.maximumRepairRounds());
        assertEquals(6, profile.maximumTotalCalls());
        assertEquals("REPLAY_ONLY", profile.certification());
        assertTrue(resource.snapshotJson().contains("\"networkAllowed\":false"));
        assertThrows(IllegalArgumentException.class, () -> registry.require("live-provider"));
    }
}
