package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.vision.AcquisitionPolicy;
import cn.hbads.renderweave.inference.vision.ArtifactSet;
import cn.hbads.renderweave.inference.vision.DocumentObservationCompatibilityProjection;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.DocumentVisionArtifact;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import cn.hbads.renderweave.inference.vision.VisualEvidenceAcquisition;
import cn.hbads.renderweave.inference.vision.VisualEvidenceAcquisitionException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProcessVisualEvidenceAcquisitionTest {
    private static final String CAPABILITY = LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID;
    private static final String FIRST_ARTIFACT = "a".repeat(64);
    private static final String SECOND_ARTIFACT = "b".repeat(64);
    private static final String MANIFEST =
            LocalProcessDocumentVisionPreprocessor.EXPECTED_MODEL_MANIFEST_SHA256;
    private static final String CAPABILITY_JSON = """
            {"protocolVersion":"renderweave-document-vision-process-capability/1.0",
             "capabilityId":"%s","engine":"rapidocr-openvino-ppocrv6-small",
             "engineVersion":"rapidocr-3.9.2+openvino-2026.0.0",
             "modelManifestSha256":"%s"}
            """.formatted(CAPABILITY, MANIFEST);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void exactPolicyProducesCanonicalSourcePixelIrWithNativeConfidence() {
        var runner = new StubRunner("""
                {"protocolVersion":"renderweave-document-vision-response/1.0",
                 "capabilityId":"%s","artifacts":[
                   {"artifactId":"%s","sourceOrdinal":0,"lines":[
                     {"left":10,"top":5,"right":40,"bottom":15,"confidenceBps":9200,"text":"站点\\t名称"}
                   ]},
                   {"artifactId":"%s","sourceOrdinal":1,"lines":[
                     {"left":50,"top":20,"right":99,"bottom":49,"confidenceBps":5900,"text":" second "}
                   ]}
                 ]}
                """.formatted(CAPABILITY, FIRST_ARTIFACT, SECOND_ARTIFACT));
        var adapter = adapter(runner);
        var artifacts = ArtifactSet.canonical(List.of(
                new ArtifactSet.Artifact(SECOND_ARTIFACT, 1, "image/jpeg", new byte[]{4, 5}, 101, 51, true),
                new ArtifactSet.Artifact(FIRST_ARTIFACT, 0, "image/png", new byte[]{1, 2, 3}, 100, 100, true)
        ));

        var ir = adapter.acquire(artifacts, adapter.acquisitionPolicy());
        adapter.acquire(artifacts, adapter.acquisitionPolicy());

        assertInstanceOf(VisualEvidenceAcquisition.class, adapter);
        assertEquals(RapidOcrBaselineContract.policy(10_000), adapter.acquisitionPolicy());
        assertEquals(DocumentObservationIR.VERSION, ir.contractVersion());
        assertEquals(adapter.acquisitionPolicy().identity(), ir.acquisitionPolicyIdentity());
        assertEquals(List.of(FIRST_ARTIFACT, SECOND_ARTIFACT),
                ir.artifacts().stream().map(DocumentObservationIR.ArtifactObservation::artifactId).toList());
        var firstLine = ir.artifacts().getFirst().observations().getFirst();
        assertEquals(new DocumentObservationIR.SourcePixelBox(10, 5, 40, 15), firstLine.sourcePixelBox());
        assertEquals(9_200, firstLine.confidence().nativeValueBps());
        assertEquals("basis-points/1.0", firstLine.confidence().nativeScaleIdentity());
        assertEquals(DocumentObservationIR.ConfidenceBucket.HIGH, firstLine.confidence().derivedBucket());
        assertEquals("站点 名称", firstLine.text());
        assertEquals(3, runner.calls.size());
        assertTrue(runner.calls.getFirst().command().contains("--capability"));
        assertFalse(runner.calls.get(1).command().contains("--capability"));
        assertFalse(runner.calls.get(2).command().contains("--capability"));
        var request = new String(runner.calls.getLast().input(), StandardCharsets.UTF_8);
        assertTrue(request.indexOf(FIRST_ARTIFACT) < request.indexOf(SECOND_ARTIFACT));
        assertFalse(adapter.toString().contains("站点"));
        assertFalse(ir.toString().contains("站点"));
    }

    @Test
    void completeBranchRejectsResponseArtifactReordering() {
        var runner = new StubRunner("""
                {"protocolVersion":"renderweave-document-vision-response/1.0",
                 "capabilityId":"%s","artifacts":[
                   {"artifactId":"%s","sourceOrdinal":1,"lines":[]},
                   {"artifactId":"%s","sourceOrdinal":0,"lines":[]}
                 ]}
                """.formatted(CAPABILITY, SECOND_ARTIFACT, FIRST_ARTIFACT));
        var adapter = adapter(runner);
        var artifacts = ArtifactSet.canonical(List.of(
                new ArtifactSet.Artifact(FIRST_ARTIFACT, 0, "image/png", new byte[]{1}, 10, 10, true),
                new ArtifactSet.Artifact(SECOND_ARTIFACT, 1, "image/png", new byte[]{2}, 10, 10, true)
        ));

        var failure = assertThrows(VisualEvidenceAcquisitionException.class,
                () -> adapter.acquire(artifacts, adapter.acquisitionPolicy()));

        assertEquals("DOCUMENT_VISION_OUTPUT_INVALID", failure.code());
        assertEquals(2, runner.calls.size());
    }

    @Test
    void policyMismatchAndInvalidOutputFailWithPayloadFreeStableCodes() {
        var adapter = adapter(new StubRunner("{}"));
        var exact = adapter.acquisitionPolicy();
        var mismatched = copyWithAdapter(exact, "another-local-adapter/1.0");
        var artifacts = ArtifactSet.canonical(List.of(
                new ArtifactSet.Artifact(FIRST_ARTIFACT, 0, "image/png", new byte[]{1}, 10, 10, true)
        ));

        var mismatch = assertThrows(VisualEvidenceAcquisitionException.class,
                () -> adapter.acquire(artifacts, mismatched));
        assertEquals("DOCUMENT_OBSERVATION_POLICY_MISMATCH", mismatch.code());

        var invalid = assertThrows(VisualEvidenceAcquisitionException.class,
                () -> adapter.acquire(artifacts, exact));
        assertEquals("DOCUMENT_VISION_OUTPUT_INVALID", invalid.code());
        assertEquals("DOCUMENT_VISION_OUTPUT_INVALID", invalid.getMessage());
        assertFalse(invalid.getMessage().contains(FIRST_ARTIFACT));
    }

    @Test
    void successorProjectionIsObjectAndByteEquivalentToTheV45Oracle() throws Exception {
        var runner = new StubRunner("""
                {"protocolVersion":"renderweave-document-vision-response/1.0",
                 "capabilityId":"%s","artifacts":[
                   {"artifactId":"%s","sourceOrdinal":0,"lines":[
                     {"left":100,"top":50,"right":101,"bottom":51,"confidenceBps":8500,
                      "text":"  edge\\tline  "},
                     {"left":1,"top":2,"right":100,"bottom":50,"confidenceBps":6000,
                      "text":"middle"}
                   ]}
                 ]}
                """.formatted(CAPABILITY, FIRST_ARTIFACT));
        var adapter = adapter(runner);
        var bytes = new byte[]{1, 2, 3};

        var oracle = adapter.preprocess(List.of(new DocumentVisionArtifact(
                FIRST_ARTIFACT, 0, "image/jpeg", bytes, 101, 51
        )));
        var ir = adapter.acquire(ArtifactSet.canonical(List.of(new ArtifactSet.Artifact(
                FIRST_ARTIFACT, 0, "image/jpeg", bytes, 101, 51, true
        ))), adapter.acquisitionPolicy());
        var successor = new DocumentObservationCompatibilityProjection().project(ir);

        assertEquals(oracle, successor);
        assertEquals(new String(JSON.writeValueAsBytes(oracle), StandardCharsets.UTF_8),
                new String(JSON.writeValueAsBytes(successor), StandardCharsets.UTF_8));
        assertEquals(3, runner.calls.size());
    }

    private static LocalProcessDocumentVisionPreprocessor adapter(StubRunner runner) {
        return LocalProcessDocumentVisionPreprocessor.forTest(
                List.of("python", "adapter.py"), Path.of("models"), Duration.ofSeconds(10), CAPABILITY, runner
        );
    }

    private static AcquisitionPolicy copyWithAdapter(AcquisitionPolicy source, String adapterIdentity) {
        return new AcquisitionPolicy(
                source.policyVersion(), source.observationContractVersion(), source.capabilityIdentity(),
                adapterIdentity, source.engine(), source.engineVersion(), source.modelManifestSha256(),
                source.preprocessingIdentity(), source.postprocessingIdentity(), source.coordinateSpaceIdentity(),
                source.boxSemanticsIdentity(), source.projectionIdentity(),
                source.readingOrderDerivationIdentity(), source.canonicalizationIdentity(),
                source.confidenceScaleIdentity(), source.confidenceBucketProjectionIdentity(),
                source.textExposure(), source.maximumArtifacts(), source.maximumObservations(),
                source.maximumLineTextBytes(), source.maximumTotalTextBytes(), source.maximumResponseBytes(),
                source.timeoutMillis()
        );
    }

    private static final class StubRunner implements LocalProcessDocumentVisionPreprocessor.ProcessRunner {
        private final byte[] response;
        private final List<Call> calls = new ArrayList<>();

        private StubRunner(String response) {
            this.response = response.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] execute(
                List<String> command,
                byte[] input,
                Duration timeout,
                Map<String, String> environment
        ) {
            calls.add(new Call(List.copyOf(command), input.clone(), Map.copyOf(environment)));
            return command.contains("--capability")
                    ? CAPABILITY_JSON.getBytes(StandardCharsets.UTF_8)
                    : response.clone();
        }
    }

    private record Call(List<String> command, byte[] input, Map<String, String> environment) {
        private Call {
            input = input.clone();
        }

        @Override
        public byte[] input() {
            return input.clone();
        }
    }
}
