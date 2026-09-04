package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.vision.AcquisitionPolicy;
import cn.hbads.renderweave.inference.vision.ArtifactSet;
import cn.hbads.renderweave.inference.vision.DocumentVisionArtifact;
import cn.hbads.renderweave.inference.vision.DocumentVisionCapability;
import cn.hbads.renderweave.inference.vision.DocumentVisionException;
import cn.hbads.renderweave.inference.vision.DocumentVisionObservation;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import cn.hbads.renderweave.inference.vision.DocumentVisionPreprocessor;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.VisualEvidenceAcquisitionException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

/** Optional local process adapter. It never invokes a shell and exposes only a fixed stdin/stdout protocol. */
final class LocalProcessDocumentVisionPreprocessor
        implements ConfiguredVisualEvidenceAcquisition {
    static final String EXPECTED_CAPABILITY_ID =
            RapidOcrBaselineContract.CAPABILITY_IDENTITY;
    static final String EXPECTED_ENGINE = RapidOcrBaselineContract.ENGINE;
    static final String EXPECTED_ENGINE_VERSION = RapidOcrBaselineContract.ENGINE_VERSION;
    static final String EXPECTED_MODEL_MANIFEST_SHA256 =
            RapidOcrBaselineContract.MODEL_MANIFEST_SHA256;
    static final String PROCESS_CAPABILITY_VERSION =
            "renderweave-document-vision-process-capability/1.0";
    static final String REQUEST_VERSION = "renderweave-document-vision-request/1.0";
    static final String RESPONSE_VERSION = "renderweave-document-vision-response/1.0";
    static final int MAX_REQUEST_BYTES = 42 * 1024 * 1024;
    static final int MAX_RESPONSE_BYTES = RapidOcrBaselineContract.MAXIMUM_RESPONSE_BYTES;
    static final String ADAPTER_IDENTITY = RapidOcrBaselineContract.ADAPTER_IDENTITY;
    static final String PREPROCESSING_IDENTITY = RapidOcrBaselineContract.PREPROCESSING_IDENTITY;
    static final String POSTPROCESSING_IDENTITY = RapidOcrBaselineContract.POSTPROCESSING_IDENTITY;
    static final String COORDINATE_SPACE_IDENTITY = RapidOcrBaselineContract.COORDINATE_SPACE_IDENTITY;
    static final String BOX_SEMANTICS_IDENTITY = RapidOcrBaselineContract.BOX_SEMANTICS_IDENTITY;
    static final String PROJECTION_IDENTITY = RapidOcrBaselineContract.PROJECTION_IDENTITY;
    static final String READING_ORDER_IDENTITY = RapidOcrBaselineContract.READING_ORDER_IDENTITY;
    static final String CANONICALIZATION_IDENTITY = RapidOcrBaselineContract.CANONICALIZATION_IDENTITY;
    static final String CONFIDENCE_SCALE_IDENTITY = RapidOcrBaselineContract.CONFIDENCE_SCALE_IDENTITY;
    static final String CONFIDENCE_BUCKET_IDENTITY = RapidOcrBaselineContract.CONFIDENCE_BUCKET_IDENTITY;

    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final List<String> commandPrefix;
    private final Path modelRoot;
    private final Duration timeout;
    private final String expectedCapabilityId;
    private final ProcessRunner processRunner;
    private final DocumentVisionCapability capability;
    private final AcquisitionPolicy acquisitionPolicy;

    private LocalProcessDocumentVisionPreprocessor(
            List<String> commandPrefix,
            Path modelRoot,
            Duration timeout,
            String expectedCapabilityId,
            ProcessRunner processRunner
    ) {
        this.commandPrefix = List.copyOf(commandPrefix);
        this.modelRoot = modelRoot;
        this.timeout = timeout;
        this.expectedCapabilityId = expectedCapabilityId;
        this.processRunner = processRunner;
        this.capability = probe();
        this.acquisitionPolicy = acquisitionPolicy(capability, timeout);
    }

    static DocumentVisionPreprocessor fromConfiguration(
            boolean enabled,
            String executable,
            String adapterScript,
            String modelRoot,
            long timeoutSeconds,
            String expectedCapabilityId
    ) {
        if (!enabled) return DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_DISABLED");
        if (executable == null || executable.isBlank()) {
            return DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_EXECUTABLE_MISSING");
        }
        if (adapterScript == null || adapterScript.isBlank()) {
            return DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_ADAPTER_MISSING");
        }
        if (modelRoot == null || modelRoot.isBlank()) {
            return DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_MODEL_MISSING");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 60) {
            return DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_TIMEOUT_INVALID");
        }
        try {
            var executablePath = Path.of(executable).toAbsolutePath().normalize().toRealPath();
            var scriptPath = Path.of(adapterScript).toAbsolutePath().normalize().toRealPath();
            var modelPath = Path.of(modelRoot).toAbsolutePath().normalize().toRealPath();
            if (!Files.isRegularFile(executablePath)) {
                return DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_EXECUTABLE_MISSING");
            }
            if (!Files.isRegularFile(scriptPath)) {
                return DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_ADAPTER_MISSING");
            }
            if (!Files.isDirectory(modelPath)) {
                return DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_MODEL_MISSING");
            }
            return new LocalProcessDocumentVisionPreprocessor(
                    List.of(executablePath.toString(), "-X", "utf8", scriptPath.toString()),
                    modelPath, Duration.ofSeconds(timeoutSeconds), requireCapabilityId(expectedCapabilityId),
                    new DefaultProcessRunner()
            );
        } catch (DocumentVisionException failure) {
            return DocumentVisionPreprocessor.unavailable(failure.code());
        } catch (IOException | RuntimeException failure) {
            return DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_STARTUP_PROBE_FAILED");
        }
    }

    static LocalProcessDocumentVisionPreprocessor forTest(
            List<String> commandPrefix,
            Path modelRoot,
            Duration timeout,
            String expectedCapabilityId,
            ProcessRunner processRunner
    ) {
        return new LocalProcessDocumentVisionPreprocessor(
                commandPrefix, modelRoot, timeout, requireCapabilityId(expectedCapabilityId), processRunner
        );
    }

    /**
     * Production OCR seam: HTTP/1.1 over the sidecar's Unix domain socket. The startup capability
     * probe must report the exact frozen identity or the feature fails closed.
     */
    static DocumentVisionPreprocessor forUnixSocket(
            Path socketPath,
            Duration timeout,
            String expectedCapabilityId
    ) {
        if (socketPath == null || socketPath.toString().isBlank()) {
            return DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_SOCKET_MISSING");
        }
        if (timeout.toSeconds() < 1 || timeout.toSeconds() > 60) {
            return DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_TIMEOUT_INVALID");
        }
        try {
            var socket = socketPath.toAbsolutePath().normalize();
            return new LocalProcessDocumentVisionPreprocessor(
                    List.of(), socket.getParent() == null ? Path.of(".") : socket.getParent(),
                    timeout, requireCapabilityId(expectedCapabilityId),
                    new UnixDomainSocketDocumentVisionRunner(socket)
            );
        } catch (DocumentVisionException failure) {
            return DocumentVisionPreprocessor.unavailable(failure.code());
        } catch (RuntimeException failure) {
            return DocumentVisionPreprocessor.unavailable("DOCUMENT_VISION_STARTUP_PROBE_FAILED");
        }
    }

    @Override
    public DocumentVisionCapability capability() {
        return capability;
    }

    @Override
    public AcquisitionPolicy acquisitionPolicy() {
        return acquisitionPolicy;
    }

    @Override
    public DocumentObservationIR acquire(ArtifactSet artifactSet, AcquisitionPolicy policy) {
        Objects.requireNonNull(artifactSet, "artifactSet");
        Objects.requireNonNull(policy, "policy");
        if (!acquisitionPolicy.equals(policy)) {
            throw new VisualEvidenceAcquisitionException("DOCUMENT_OBSERVATION_POLICY_MISMATCH");
        }
        try {
            var request = new ProcessRequest(
                    REQUEST_VERSION,
                    artifactSet.artifacts().stream()
                            .map(item -> new ProcessArtifact(
                                    item.artifactId(), item.sourceOrdinal(), item.mediaType(), item.width(),
                                    item.height(), Base64.getEncoder().encodeToString(item.bytes())
                            )).toList()
            );
            var requestBytes = JSON.writeValueAsBytes(request);
            if (requestBytes.length > MAX_REQUEST_BYTES) {
                throw new DocumentVisionException("DOCUMENT_VISION_INPUT_TOO_LARGE");
            }
            var raw = run(List.of(), requestBytes);
            if (raw.length > policy.maximumResponseBytes()) {
                throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_TOO_LARGE");
            }
            return observationIr(artifactSet, parseResponse(raw), policy);
        } catch (VisualEvidenceAcquisitionException known) {
            throw known;
        } catch (DocumentVisionException known) {
            throw new VisualEvidenceAcquisitionException(known.code());
        } catch (Exception invalid) {
            throw new VisualEvidenceAcquisitionException("DOCUMENT_VISION_OUTPUT_INVALID");
        }
    }

    @Override
    public DocumentVisionObservation preprocess(List<DocumentVisionArtifact> artifacts) {
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if (!capability.available() || artifacts.isEmpty()
                || artifacts.size() > DocumentVisionObservation.MAX_ARTIFACTS) {
            throw new DocumentVisionException(capability.available()
                    ? "DOCUMENT_VISION_INPUT_INVALID" : capability.diagnosticCode());
        }
        var artifactIds = new HashSet<String>();
        var ordinals = new HashSet<Integer>();
        if (artifacts.stream().anyMatch(item -> !artifactIds.add(item.artifactId())
                || !ordinals.add(item.sourceOrdinal()))) {
            throw new DocumentVisionException("DOCUMENT_VISION_INPUT_INVALID");
        }
        try {
            var request = new ProcessRequest(
                    REQUEST_VERSION,
                    artifacts.stream().sorted(Comparator.comparingInt(DocumentVisionArtifact::sourceOrdinal))
                            .map(item -> new ProcessArtifact(
                                    item.artifactId(), item.sourceOrdinal(), item.mediaType(), item.width(),
                                    item.height(), Base64.getEncoder().encodeToString(item.bytes())
                            )).toList()
            );
            var requestBytes = JSON.writeValueAsBytes(request);
            if (requestBytes.length > MAX_REQUEST_BYTES) {
                throw new DocumentVisionException("DOCUMENT_VISION_INPUT_TOO_LARGE");
            }
            var response = parseResponse(run(List.of(), requestBytes));
            return observation(artifacts, response);
        } catch (DocumentVisionException known) {
            throw known;
        } catch (Exception invalid) {
            throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
        }
    }

    private DocumentVisionCapability probe() {
        try {
            var value = JSON.readValue(run(List.of("--capability"), new byte[0]), ProcessCapability.class);
            if (!PROCESS_CAPABILITY_VERSION.equals(value.protocolVersion())
                    || !expectedCapabilityId.equals(value.capabilityId())
                    || !EXPECTED_ENGINE.equals(value.engine())
                    || !EXPECTED_ENGINE_VERSION.equals(value.engineVersion())
                    || !EXPECTED_MODEL_MANIFEST_SHA256.equals(value.modelManifestSha256())) {
                throw new DocumentVisionException("DOCUMENT_VISION_CAPABILITY_MISMATCH");
            }
            return DocumentVisionCapability.available(
                    value.capabilityId(), value.engine(), value.engineVersion(), value.modelManifestSha256()
            );
        } catch (DocumentVisionException known) {
            throw known;
        } catch (Exception invalid) {
            throw new DocumentVisionException("DOCUMENT_VISION_STARTUP_PROBE_FAILED");
        }
    }

    private byte[] run(List<String> flags, byte[] input) {
        var command = new ArrayList<>(commandPrefix);
        command.addAll(flags);
        command.add("--model-root");
        command.add(modelRoot.toString());
        return processRunner.execute(command, input, timeout, minimalEnvironment());
    }

    private ProcessResponse parseResponse(byte[] raw) {
        try {
            var response = JSON.readValue(raw, ProcessResponse.class);
            if (response.protocolVersion() == null || response.capabilityId() == null
                    || response.artifacts() == null) {
                throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
            }
            if (!RESPONSE_VERSION.equals(response.protocolVersion())
                    || !expectedCapabilityId.equals(response.capabilityId())) {
                throw new DocumentVisionException("DOCUMENT_VISION_CAPABILITY_MISMATCH");
            }
            return response;
        } catch (DocumentVisionException known) {
            throw known;
        } catch (Exception invalid) {
            throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
        }
    }

    private DocumentVisionObservation observation(
            List<DocumentVisionArtifact> inputs,
            ProcessResponse response
    ) {
        var inputById = new HashMap<String, DocumentVisionArtifact>();
        inputs.forEach(item -> inputById.put(item.artifactId(), item));
        if (response.artifacts() == null || response.artifacts().size() != inputs.size()) {
            throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
        }
        var seen = new HashSet<String>();
        var artifacts = new ArrayList<DocumentVisionObservation.ArtifactObservation>();
        var totalLines = 0;
        for (var rawArtifact : response.artifacts()) {
            var input = inputById.get(rawArtifact.artifactId());
            if (input == null || !seen.add(rawArtifact.artifactId())
                    || input.sourceOrdinal() != rawArtifact.sourceOrdinal() || rawArtifact.lines() == null) {
                throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
            }
            var lines = new ArrayList<>(rawArtifact.lines());
            lines.sort(Comparator.comparingInt(ProcessLine::top).thenComparingInt(ProcessLine::left)
                    .thenComparingInt(ProcessLine::bottom).thenComparingInt(ProcessLine::right)
                    .thenComparing(ProcessLine::text, Comparator.nullsFirst(Comparator.naturalOrder())));
            totalLines = Math.addExact(totalLines, lines.size());
            if (totalLines > DocumentVisionObservation.MAX_LINES) {
                throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_TOO_LARGE");
            }
            var canonical = new ArrayList<DocumentVisionObservation.TextLine>();
            for (var index = 0; index < lines.size(); index++) {
                var line = lines.get(index);
                validateLine(line, input);
                canonical.add(new DocumentVisionObservation.TextLine(
                        "ocr-%02d-%03d".formatted(input.sourceOrdinal(), index), index,
                        canonicalBox(line, input.width(), input.height()),
                        confidence(line.confidenceBps()), line.text()
                ));
            }
            artifacts.add(new DocumentVisionObservation.ArtifactObservation(
                    input.artifactId(), input.sourceOrdinal(), canonical
            ));
        }
        try {
            return DocumentVisionObservation.canonical(expectedCapabilityId, artifacts);
        } catch (IllegalArgumentException invalid) {
            throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
        }
    }

    private DocumentObservationIR observationIr(
            ArtifactSet inputs,
            ProcessResponse response,
            AcquisitionPolicy policy
    ) {
        if (response.artifacts() == null || response.artifacts().size() != inputs.artifacts().size()) {
            throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
        }
        var seen = new HashSet<String>();
        var artifacts = new ArrayList<DocumentObservationIR.ArtifactObservation>();
        var totalLines = 0;
        for (var artifactIndex = 0; artifactIndex < response.artifacts().size(); artifactIndex++) {
            var rawArtifact = response.artifacts().get(artifactIndex);
            var input = inputs.artifacts().get(artifactIndex);
            if (!input.artifactId().equals(rawArtifact.artifactId())
                    || !seen.add(rawArtifact.artifactId())
                    || input.sourceOrdinal() != rawArtifact.sourceOrdinal() || rawArtifact.lines() == null) {
                throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
            }
            var lines = new ArrayList<>(rawArtifact.lines());
            lines.sort(Comparator.comparingInt(ProcessLine::top).thenComparingInt(ProcessLine::left)
                    .thenComparingInt(ProcessLine::bottom).thenComparingInt(ProcessLine::right)
                    .thenComparing(ProcessLine::text, Comparator.nullsFirst(Comparator.naturalOrder())));
            totalLines = Math.addExact(totalLines, lines.size());
            if (totalLines > policy.maximumObservations()) {
                throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_TOO_LARGE");
            }
            var canonical = new ArrayList<DocumentObservationIR.TextLine>();
            for (var index = 0; index < lines.size(); index++) {
                var line = lines.get(index);
                validateLine(line, input.width(), input.height());
                canonical.add(new DocumentObservationIR.TextLine(
                        "ocr-%02d-%03d".formatted(input.sourceOrdinal(), index), index,
                        new DocumentObservationIR.SourcePixelBox(
                                line.left(), line.top(), line.right(), line.bottom()
                        ),
                        new DocumentObservationIR.Confidence(
                                line.confidenceBps(), CONFIDENCE_SCALE_IDENTITY,
                                confidenceIr(line.confidenceBps()), CONFIDENCE_BUCKET_IDENTITY
                        ),
                        line.text(), DocumentObservationIR.Sensitivity.EPHEMERAL_UNTRUSTED
                ));
            }
            artifacts.add(new DocumentObservationIR.ArtifactObservation(
                    input.artifactId(), input.sourceOrdinal(), input.mediaType(), input.width(), input.height(),
                    input.orientationApplied(), canonical
            ));
        }
        try {
            return DocumentObservationIR.canonical(policy, provenance(policy), artifacts);
        } catch (IllegalArgumentException invalid) {
            if ("DOCUMENT_OBSERVATION_TEXT_LIMIT_EXCEEDED".equals(invalid.getMessage())
                    || "DOCUMENT_OBSERVATION_COUNT_LIMIT_EXCEEDED".equals(invalid.getMessage())) {
                throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_TOO_LARGE");
            }
            throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
        }
    }

    private static void validateLine(ProcessLine line, DocumentVisionArtifact input) {
        validateLine(line, input.width(), input.height());
    }

    private static void validateLine(ProcessLine line, int width, int height) {
        if (line.left() < 0 || line.top() < 0 || line.left() >= line.right()
                || line.top() >= line.bottom() || line.right() > width
                || line.bottom() > height || line.confidenceBps() < 0
                || line.confidenceBps() > 10_000) {
            throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
        }
    }

    private static CandidateBoundingBox canonicalBox(ProcessLine line, int width, int height) {
        return new CandidateBoundingBox(
                (int) Math.floorDiv((long) line.left() * 10_000L, width),
                (int) Math.floorDiv((long) line.top() * 10_000L, height),
                (int) Math.ceilDiv((long) line.right() * 10_000L, width),
                (int) Math.ceilDiv((long) line.bottom() * 10_000L, height)
        );
    }

    private static DocumentVisionObservation.ConfidenceBucket confidence(int value) {
        if (value < 6_000) return DocumentVisionObservation.ConfidenceBucket.LOW;
        if (value < 8_500) return DocumentVisionObservation.ConfidenceBucket.MEDIUM;
        return DocumentVisionObservation.ConfidenceBucket.HIGH;
    }

    private static DocumentObservationIR.ConfidenceBucket confidenceIr(int value) {
        if (value < 6_000) return DocumentObservationIR.ConfidenceBucket.LOW;
        if (value < 8_500) return DocumentObservationIR.ConfidenceBucket.MEDIUM;
        return DocumentObservationIR.ConfidenceBucket.HIGH;
    }

    private static AcquisitionPolicy acquisitionPolicy(
            DocumentVisionCapability capability,
            Duration timeout
    ) {
        if (!RapidOcrBaselineContract.CAPABILITY_IDENTITY.equals(capability.capabilityId())
                || !RapidOcrBaselineContract.ENGINE.equals(capability.engine())
                || !RapidOcrBaselineContract.ENGINE_VERSION.equals(capability.engineVersion())
                || !RapidOcrBaselineContract.MODEL_MANIFEST_SHA256.equals(capability.modelManifestSha256())) {
            throw new DocumentVisionException("DOCUMENT_VISION_CAPABILITY_MISMATCH");
        }
        return RapidOcrBaselineContract.policy(Math.toIntExact(timeout.toMillis()));
    }

    private static DocumentObservationIR.Provenance provenance(AcquisitionPolicy policy) {
        return new DocumentObservationIR.Provenance(
                policy.capabilityIdentity(), policy.adapterIdentity(), policy.engine(), policy.engineVersion(),
                policy.modelManifestSha256(), policy.preprocessingIdentity(), policy.postprocessingIdentity(),
                policy.readingOrderDerivationIdentity(), policy.projectionIdentity(),
                policy.confidenceScaleIdentity(), policy.confidenceBucketProjectionIdentity(),
                policy.canonicalizationIdentity()
        );
    }

    @Override
    public String toString() {
        return "LocalProcessDocumentVisionPreprocessor[capabilityIdentity=" + capability.capabilityId()
                + ", acquisitionPolicyIdentity=" + acquisitionPolicy.identity() + "]";
    }

    private static String requireCapabilityId(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9._:-]{0,190}")) {
            throw new IllegalArgumentException("Document vision expected capability id is invalid");
        }
        return value;
    }

    private static Map<String, String> minimalEnvironment() {
        var inherited = System.getenv();
        var result = new LinkedHashMap<String, String>();
        for (var key : List.of("SystemRoot", "WINDIR", "ComSpec", "PATHEXT", "TEMP", "TMP", "LANG")) {
            var value = inherited.get(key);
            if (value != null && !value.isBlank()) result.put(key, value);
        }
        result.put("PYTHONUTF8", "1");
        result.put("PYTHONNOUSERSITE", "1");
        result.put("PYTHONDONTWRITEBYTECODE", "1");
        result.put("NO_PROXY", "*");
        result.put("no_proxy", "*");
        return Map.copyOf(result);
    }

    interface ProcessRunner {
        byte[] execute(List<String> command, byte[] input, Duration timeout, Map<String, String> environment);
    }

    static final class DefaultProcessRunner implements ProcessRunner {
        @Override
        public byte[] execute(
                List<String> command,
                byte[] input,
                Duration timeout,
                Map<String, String> environment
        ) {
            Process process = null;
            try {
                var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
                builder.environment().clear();
                builder.environment().putAll(environment);
                process = builder.start();
                var running = process;
                try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
                    var writer = tasks.submit(() -> {
                        try (var output = running.getOutputStream()) {
                            output.write(input);
                        }
                        return null;
                    });
                    var reader = tasks.submit(() -> readBounded(running));
                    if (!running.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        running.destroyForcibly();
                        throw new DocumentVisionException("DOCUMENT_VISION_TIMEOUT");
                    }
                    writer.get();
                    var output = reader.get();
                    if (running.exitValue() != 0) {
                        throw new DocumentVisionException("DOCUMENT_VISION_PROCESS_FAILED");
                    }
                    return output;
                }
            } catch (DocumentVisionException known) {
                throw known;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DocumentVisionException("DOCUMENT_VISION_INTERRUPTED");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof DocumentVisionException known) throw known;
                throw new DocumentVisionException("DOCUMENT_VISION_PROCESS_FAILED");
            } catch (IOException failure) {
                throw new DocumentVisionException("DOCUMENT_VISION_PROCESS_FAILED");
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
        }

        private static byte[] readBounded(Process process) throws IOException {
            try (var input = process.getInputStream(); var output = new ByteArrayOutputStream()) {
                var buffer = new byte[8 * 1024];
                for (var read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                    if (read == 0) continue;
                    if (output.size() + read > MAX_RESPONSE_BYTES) {
                        throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_TOO_LARGE");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
    }

    private record ProcessCapability(
            String protocolVersion,
            String capabilityId,
            String engine,
            String engineVersion,
            String modelManifestSha256
    ) { }

    private record ProcessRequest(String protocolVersion, List<ProcessArtifact> artifacts) { }

    private record ProcessArtifact(
            String artifactId,
            int sourceOrdinal,
            String mediaType,
            int width,
            int height,
            String base64
    ) { }

    private record ProcessResponse(
            String protocolVersion,
            String capabilityId,
            List<ProcessArtifactResult> artifacts
    ) { }

    private record ProcessArtifactResult(
            String artifactId,
            int sourceOrdinal,
            List<ProcessLine> lines
    ) { }

    private record ProcessLine(
            int left,
            int top,
            int right,
            int bottom,
            int confidenceBps,
            String text
    ) { }
}
