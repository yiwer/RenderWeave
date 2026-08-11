package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import cn.hbads.renderweave.inference.eval.visual.VisualStageRasterizer;
import cn.hbads.renderweave.inference.vision.DocumentVisionArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly opt-in, local-only OCR capability canary. It never creates an inference Provider. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_DOCUMENT_VISION_CANARY", matches = "true")
class DocumentVisionRuntimeCanaryTest {
    @Test
    void exactLocalCapabilityProcessesARepositoryOwnedSyntheticImage() {
        var adapter = LocalProcessDocumentVisionPreprocessor.fromConfiguration(
                true,
                required("RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE"),
                required("RENDERWEAVE_DOCUMENT_VISION_ADAPTER_SCRIPT"),
                required("RENDERWEAVE_DOCUMENT_VISION_MODEL_ROOT"),
                60,
                LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID
        );
        assertThat(adapter.capability().available())
                .as(adapter.capability().diagnosticCode())
                .isTrue();
        var evaluationCase = new VisualStageCorpus().require("transit-board-v3");
        var image = new VisualStageRasterizer().render(evaluationCase);

        var observation = adapter.preprocess(List.of(new DocumentVisionArtifact(
                image.sha256(), 0, image.mediaType(), image.bytes(), image.width(), image.height()
        )));

        assertThat(observation.capabilityId())
                .isEqualTo(LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID);
        assertThat(observation.artifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.artifactId()).isEqualTo(image.sha256());
            assertThat(artifact.lines()).isNotEmpty();
        });
        System.out.printf(
                "documentVisionCanary=PASS capability=%s imageSha256=%s lineCount=%d%n",
                observation.capabilityId(), image.sha256(), observation.lineCount()
        );
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the document vision canary");
        }
        return value;
    }
}
