package cn.hbads.renderweave.inference.admission;

import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.ImageNormalizer;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.InputNormalizer;
import cn.hbads.renderweave.inference.input.InvalidInferenceInputException;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOnlyProductionAdmissionTest {
    private static final Instant T0 = Instant.parse("2026-08-18T08:00:00Z");
    private static final UUID RUN_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CONFIRMATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String IDEMPOTENCY_KEY = "live-idempotency-001";

    private MemoryBlobStore blobs;
    private CapturingStore store;
    private LiveAdmissionConfiguration configuration;
    private ImageOnlyProductionAdmission admission;

    @BeforeEach
    void setUp() {
        blobs = new MemoryBlobStore();
        store = new CapturingStore();
        configuration = configuration();
        admission = admissionWith(ImageOnlyAdmissionPolicy.fixed(true),
                () -> new ProviderEgressPermit.Snapshot(true, "test-permit-1"));
    }

    private ImageOnlyProductionAdmission admissionWith(
            ImageOnlyAdmissionPolicyStore policy, ProviderEgressPermit egress
    ) {
        return new ImageOnlyProductionAdmission(
                blobs, store,
                (profileId, locale) -> {
                    assertEquals(configuration.profile().profile().profileId(), profileId);
                    assertEquals(configuration.notice().locale(), locale);
                    return configuration;
                },
                policy, egress,
                Clock.fixed(T0, ZoneOffset.UTC), () -> RUN_ID, () -> CONFIRMATION_ID
        );
    }

    @Test
    void closedAdmissionPolicyRejectsCreateWithTypedProblem() {
        var closed = admissionWith(ImageOnlyAdmissionPolicy.fixed(false),
                () -> new ProviderEgressPermit.Snapshot(true, "test-permit-1"));
        assertCode("LIVE_POLICY_DISABLED", () -> closed.admit(request(
                InputProvenance.USER_PROVIDED, SensitivityClass.ORDINARY_DESIGN,
                configuration.notice().identity(), List.of(image("local-only-name.png", 3, 2))
        )));
    }

    @Test
    void closedEgressPermitRejectsCreateWithTypedProblem() {
        var closed = admissionWith(ImageOnlyAdmissionPolicy.fixed(true),
                ProviderEgressPermit.disabled());
        assertCode("EGRESS_DISABLED", () -> closed.admit(request(
                InputProvenance.USER_PROVIDED, SensitivityClass.ORDINARY_DESIGN,
                configuration.notice().identity(), List.of(image("local-only-name.png", 3, 2))
        )));
    }

    @Test
    void bindsExactNormalizedManifestNoticeProfileCapsActorAndDeadlines() throws Exception {
        var result = admission.admit(request(
                InputProvenance.USER_PROVIDED, SensitivityClass.ORDINARY_DESIGN,
                configuration.notice().identity(), List.of(image("local-only-name.png", 3, 2))
        ));

        assertTrue(result.created());
        assertEquals(RUN_ID, result.runId());
        var command = store.command;
        assertNotNull(command);
        assertEquals(1, command.manifest().items().size());
        assertEquals("image/png", command.manifest().items().getFirst().mediaType());
        assertEquals(command.manifest().sha256(), command.run().normalizedInput().inputFingerprint());
        assertEquals(configuration.profile().canonicalSha256(), command.confirmation().profileSha256());
        assertEquals("actor-opaque-001", command.confirmation().actorId());
        assertEquals(T0.plusSeconds(15 * 60), command.confirmation().dispatchNotAfter());
        assertEquals(T0.plusSeconds(2 * 60 * 60), command.confirmation().providerCallsNotAfter());
        assertFalse(command.toString().contains("local-only-name.png"));
    }

    @Test
    void missingRestrictedOrNonUserClassificationFailsBeforeNormalization() throws Exception {
        assertCode("LIVE_INPUT_CLASSIFICATION_REQUIRED", () -> admission.admit(request(
                InputProvenance.USER_PROVIDED, null, configuration.notice().identity(),
                List.of(image("ignored.png", 1, 1))
        )));
        assertCode("LIVE_INPUT_CLASSIFICATION_NOT_ADMISSIBLE", () -> admission.admit(request(
                InputProvenance.USER_PROVIDED, SensitivityClass.RESTRICTED,
                configuration.notice().identity(), List.of(image("ignored.png", 1, 1))
        )));
        assertCode("LIVE_INPUT_PROVENANCE_NOT_ADMISSIBLE", () -> admission.admit(request(
                null, SensitivityClass.ORDINARY_DESIGN,
                configuration.notice().identity(), List.of(image("ignored.png", 1, 1))
        )));
        assertTrue(blobs.values.isEmpty());
        assertEquals(null, store.command);
    }

    @Test
    void staleNoticeAndGatewayBindingFailBeforeBytesAreStored() throws Exception {
        var stale = new ExternalTransferNotice.Identity(
                configuration.notice().version(), configuration.notice().locale(), "f".repeat(64)
        );
        assertCode("LIVE_TRANSFER_NOTICE_STALE", () -> admission.admit(request(
                InputProvenance.USER_PROVIDED, SensitivityClass.ORDINARY_DESIGN,
                stale, List.of(image("ignored.png", 1, 1))
        )));

        var wrongIdentity = new GatewayRequestIdentity(
                "actor-opaque-001", "request-opaque-001", "jti-001", "POST",
                ImageOnlyProductionAdmission.LIVE_PATH,
                GatewayAssertionAuthority.idempotencyKeyDigest("another-key"),
                T0, T0.plusSeconds(60), "gateway-2026-08-a"
        );
        var wrongRequest = new ImageOnlyLiveAdmissionRequest(
                IDEMPOTENCY_KEY, wrongIdentity, configuration.profile().profile().profileId(),
                configuration.notice().identity(), InputProvenance.USER_PROVIDED,
                SensitivityClass.ORDINARY_DESIGN, List.of(image("ignored.png", 1, 1))
        );
        assertCode("GATEWAY_ASSERTION_IDEMPOTENCY_MISMATCH", () -> admission.admit(wrongRequest));
        assertTrue(blobs.values.isEmpty());
    }

    @Test
    void freezesImageCountByteAndPixelBoundaries() throws Exception {
        assertEquals(10, InputNormalizer.MAX_IMAGES);
        assertEquals(10 * 1024 * 1024, InputNormalizer.MAX_IMAGE_BYTES);
        assertEquals(32 * 1024 * 1024, InputNormalizer.MAX_IMAGE_BATCH_BYTES);
        assertEquals(25_000_000L, ImageNormalizer.MAX_SOURCE_PIXELS);

        var eleven = new ArrayList<InferenceInput.BinaryInput>();
        for (var index = 0; index < 11; index++) eleven.add(image("ignored.png", 1, 1));
        assertInputCode("INFERENCE_INPUT_COUNT_INVALID", () -> admission.admit(request(
                InputProvenance.USER_PROVIDED, SensitivityClass.ORDINARY_DESIGN,
                configuration.notice().identity(), eleven
        )));

        assertInputCode("INFERENCE_IMAGE_SIZE_INVALID", () -> admission.admit(request(
                InputProvenance.USER_PROVIDED, SensitivityClass.ORDINARY_DESIGN,
                configuration.notice().identity(), List.of(new InferenceInput.BinaryInput(
                        "ignored.png", "image/png", new byte[InputNormalizer.MAX_IMAGE_BYTES + 1]
                ))
        )));

        var aggregate = List.of(
                new InferenceInput.BinaryInput("a", "image/png", new byte[8 * 1024 * 1024 + 1]),
                new InferenceInput.BinaryInput("b", "image/png", new byte[8 * 1024 * 1024]),
                new InferenceInput.BinaryInput("c", "image/png", new byte[8 * 1024 * 1024]),
                new InferenceInput.BinaryInput("d", "image/png", new byte[8 * 1024 * 1024])
        );
        assertInputCode("INFERENCE_IMAGE_BATCH_TOO_LARGE", () -> admission.admit(request(
                InputProvenance.USER_PROVIDED, SensitivityClass.ORDINARY_DESIGN,
                configuration.notice().identity(), aggregate
        )));

        assertInputCode("INFERENCE_IMAGE_DECODE_FAILED", () -> admission.admit(request(
                InputProvenance.USER_PROVIDED, SensitivityClass.ORDINARY_DESIGN,
                configuration.notice().identity(), List.of(pngEnvelope(5_000, 5_000))
        )));
        assertInputCode("INFERENCE_IMAGE_DIMENSIONS_INVALID", () -> admission.admit(request(
                InputProvenance.USER_PROVIDED, SensitivityClass.ORDINARY_DESIGN,
                configuration.notice().identity(), List.of(pngEnvelope(5_001, 5_000))
        )));
    }

    private ImageOnlyLiveAdmissionRequest request(
            InputProvenance provenance,
            SensitivityClass sensitivity,
            ExternalTransferNotice.Identity notice,
            List<InferenceInput.BinaryInput> images
    ) {
        return new ImageOnlyLiveAdmissionRequest(
                IDEMPOTENCY_KEY, gatewayIdentity("request-opaque-001", "jti-001"),
                configuration.profile().profile().profileId(), notice,
                provenance, sensitivity, images
        );
    }

    private static GatewayRequestIdentity gatewayIdentity(String requestId, String jti) {
        return new GatewayRequestIdentity(
                "actor-opaque-001", requestId, jti, "POST",
                ImageOnlyProductionAdmission.LIVE_PATH,
                GatewayAssertionAuthority.idempotencyKeyDigest(IDEMPOTENCY_KEY),
                T0, T0.plusSeconds(60), "gateway-2026-08-a"
        );
    }

    static LiveAdmissionConfiguration configuration() {
        var profile = new InferenceProfileRegistry().require(
                "dashscope-qwen38-max-product-v52-hybrid-generic"
        );
        var value = profile.profile();
        var notice = ExternalTransferNotice.issue(
                "renderweave-external-transfer-notice/1.0", "zh-CN",
                "Provider legal entity per the accepted standard online terms",
                value.provider(), value.model(), value.providerEndpoint(), "cn-beijing",
                "Generate a review-only RenderWeave schema Candidate from user-provided design images.",
                "No numerical Provider retention guarantee is claimed.",
                "No Provider secondary-use guarantee is claimed.",
                "Provider terms may permit technical or human review.",
                value.profileId(), profile.canonicalSha256(), value.maximumTotalCalls(),
                value.maximumEstimatedCostMicrosCny(), 7L * 24 * 60 * 60,
                "renderweave-image-only-admission-policy/1.0", "a".repeat(64),
                "dashscope-standard-pay-as-you-go-terms/2026-08-17", "b".repeat(64)
        );
        return new LiveAdmissionConfiguration(notice, profile);
    }

    private static InferenceInput.BinaryInput image(String name, int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new InferenceInput.BinaryInput(name, "image/png", output.toByteArray());
    }

    private static InferenceInput.BinaryInput pngEnvelope(int width, int height) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        var ihdr = ByteBuffer.allocate(13)
                .putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0)
                .array();
        writeChunk(output, "IHDR", ihdr);
        writeChunk(output, "IEND", new byte[0]);
        return new InferenceInput.BinaryInput("ignored.png", "image/png", output.toByteArray());
    }

    private static void writeChunk(ByteArrayOutputStream output, String type, byte[] data) {
        output.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        var typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        output.writeBytes(typeBytes);
        output.writeBytes(data);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        output.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static void assertCode(String expected, ThrowingCall call) {
        var problem = assertThrows(LiveAdmissionProblem.class, call::run);
        assertEquals(expected, problem.code());
    }

    private static void assertInputCode(String expected, ThrowingCall call) {
        var problem = assertThrows(InvalidInferenceInputException.class, call::run);
        assertEquals(expected, problem.code());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static final class MemoryBlobStore implements BlobStore {
        private final Map<String, byte[]> values = new LinkedHashMap<>();

        @Override
        public WriteReceipt write(String artifactId, byte[] bytes) {
            var locator = "memory:" + artifactId;
            var created = values.putIfAbsent(locator, bytes.clone()) == null;
            return new WriteReceipt(locator, created);
        }

        @Override
        public byte[] read(String locator) {
            return values.get(locator).clone();
        }

        @Override
        public void delete(String locator) {
            values.remove(locator);
        }
    }

    private static final class CapturingStore implements LiveAdmissionStore {
        private NewLiveInferenceRun command;

        @Override
        public Result admit(NewLiveInferenceRun value) {
            command = value;
            return new Result(
                    value.run().runId(), value.confirmation().confirmationId(),
                    value.manifest().identity(), true
            );
        }

        @Override
        public Optional<ExternalTransferConfirmation> findConfirmation(UUID runId) {
            return command == null || !command.run().runId().equals(runId)
                    ? Optional.empty() : Optional.of(command.confirmation());
        }
    }
}
