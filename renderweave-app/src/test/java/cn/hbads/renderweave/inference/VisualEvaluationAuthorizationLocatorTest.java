package cn.hbads.renderweave.inference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VisualEvaluationAuthorizationLocatorTest {
    @Test
    void selectorCanOnlyChooseOneOfThreeFixedTrackedLedgers(@TempDir Path repository) {
        assertEquals(repository.resolve(".sdlc/live/visual-evaluation-qwen38-max.json")
                        .toAbsolutePath().normalize(),
                VisualEvaluationAuthorizationLocator.resolve(repository, "qwen38-max"));
        assertEquals(repository.resolve(".sdlc/live/visual-evaluation-qwen37-plus.json")
                        .toAbsolutePath().normalize(),
                VisualEvaluationAuthorizationLocator.resolve(repository, "qwen37-plus"));
        assertEquals(repository.resolve(".sdlc/live/visual-evaluation-qwen37-flash.json")
                        .toAbsolutePath().normalize(),
                VisualEvaluationAuthorizationLocator.resolve(repository, "qwen37-flash"));
        assertThrows(IllegalStateException.class,
                () -> VisualEvaluationAuthorizationLocator.resolve(repository, "../open"));
        assertThrows(IllegalStateException.class,
                () -> VisualEvaluationAuthorizationLocator.resolve(repository, null));
    }

    @Test
    void identityExclusionSetAlwaysContainsAllThreeLedgers(@TempDir Path repository) {
        assertEquals(List.of(
                        repository.resolve(".sdlc/live/visual-evaluation-qwen37-flash.json")
                                .toAbsolutePath().normalize(),
                        repository.resolve(".sdlc/live/visual-evaluation-qwen37-plus.json")
                                .toAbsolutePath().normalize(),
                        repository.resolve(".sdlc/live/visual-evaluation-qwen38-max.json")
                                .toAbsolutePath().normalize()
                ), VisualEvaluationAuthorizationLocator.all(repository));
    }
}
