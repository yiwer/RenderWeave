package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import cn.hbads.renderweave.inference.eval.visual.VisualStageRasterizer;
import cn.hbads.renderweave.inference.vision.ArtifactSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly opt-in, local-only OCR capability canary. It never creates an inference Provider. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_DOCUMENT_VISION_CANARY", matches = "true")
class DocumentVisionRuntimeCanaryTest {
    @Test
    void exactLocalCapabilityProcessesARepositoryOwnedSyntheticImage() {
        var configured = LocalProcessDocumentVisionPreprocessor.fromConfiguration(
                true,
                required("RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE"),
                required("RENDERWEAVE_DOCUMENT_VISION_ADAPTER_SCRIPT"),
                required("RENDERWEAVE_DOCUMENT_VISION_MODEL_ROOT"),
                60,
                LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID
        );
        assertThat(configured.capability().available())
                .as(configured.capability().diagnosticCode())
                .isTrue();
        assertThat(configured).isInstanceOf(LocalProcessDocumentVisionPreprocessor.class);
        var adapter = (LocalProcessDocumentVisionPreprocessor) configured;
        var evaluationCase = new VisualStageCorpus().require("transit-board-v3");
        var image = new VisualStageRasterizer().render(evaluationCase);

        var observation = adapter.acquire(ArtifactSet.canonical(List.of(new ArtifactSet.Artifact(
                image.sha256(), 0, image.mediaType(), image.bytes(), image.width(), image.height(), true
        ))), adapter.acquisitionPolicy());

        assertThat(observation.capabilityIdentity())
                .isEqualTo(LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID);
        assertThat(observation.artifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.artifactId()).isEqualTo(image.sha256());
            assertThat(artifact.observations()).isNotEmpty();
        });
        System.out.printf(
                "documentObservationCanary=PASS capability=%s policy=%s artifactCount=%d observationCount=%d%n",
                observation.capabilityIdentity(), observation.acquisitionPolicyIdentity(),
                observation.artifacts().size(), observation.observationCount()
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
