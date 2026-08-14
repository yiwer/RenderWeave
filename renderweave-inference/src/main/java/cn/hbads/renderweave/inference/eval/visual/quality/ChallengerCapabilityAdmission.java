package cn.hbads.renderweave.inference.eval.visual.quality;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Fail-closed admission catalog for bounded, local-only R2 challengers. */
public final class ChallengerCapabilityAdmission {
    public static final String VERSION = "renderweave-challenger-capability-catalog/1.0";
    public static final String CAPABILITY_VERSION = "renderweave-challenger-capability/1.0";
    private static final String RESOURCE =
            "visual-eval/quality-repair/challenger-capabilities-v1.json";
    private static final String SUCCESSOR_SPEC_SHA256 =
            "4632b609d4ce5726b0671e8a56fc6674e182f22152231b727622662e14b50a0e";
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();

    private final Document document;
    private final String identity;
    private final List<Capability> challengers;
    private final Map<String, Capability> byId;

    private ChallengerCapabilityAdmission(Document document, byte[] source) {
        this.document = Objects.requireNonNull(document, "document");
        if (!VERSION.equals(document.catalogVersion())
                || !SUCCESSOR_SPEC_SHA256.equals(document.successorSpecSha256())) {
            throw invalid("CHALLENGER_CATALOG_AUTHORITY_INVALID");
        }
        identity = VERSION + ":" + sha256(normalizeLineEndings(source));
        if (document.optionalThirdChallenger() != OptionalThirdChallenger.NONE) {
            throw invalid("CHALLENGER_OPTIONAL_THIRD_INVALID");
        }
        validateGlobalCeilings(document.maximumResourceEnvelope());
        var sourceCapabilities = List.copyOf(Objects.requireNonNull(document.challengers(), "challengers"));
        if (sourceCapabilities.size() != 2
                || !List.of("pp-structurev3", "tesseract-tsv-hocr").equals(
                sourceCapabilities.stream().map(CapabilityDocument::challengerId).toList())) {
            throw invalid("CHALLENGER_SET_INVALID");
        }
        challengers = sourceCapabilities.stream().map(this::validate).toList();
        byId = challengers.stream().collect(Collectors.toUnmodifiableMap(
                Capability::challengerId, Function.identity()));
    }

    public static ChallengerCapabilityAdmission load() {
        return load(ChallengerCapabilityAdmission.class.getClassLoader());
    }

    static ChallengerCapabilityAdmission load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        try (var input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalid("CHALLENGER_CATALOG_RESOURCE_MISSING");
            var bytes = input.readAllBytes();
            return new ChallengerCapabilityAdmission(JSON.readValue(bytes, Document.class), bytes);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("CHALLENGER_CAPABILITY_CATALOG_INVALID", failure);
        }
    }

    public String identity() { return identity; }

    public OptionalThirdChallenger optionalThirdChallenger() {
        return document.optionalThirdChallenger();
    }

    public List<Capability> challengers() { return challengers; }

    public Capability require(String challengerId) {
        var result = byId.get(challengerId);
        if (result == null) throw invalid("CHALLENGER_UNKNOWN");
        return result;
    }

    private Capability validate(CapabilityDocument value) {
        Objects.requireNonNull(value, "capability");
        requireId(value.challengerId(), "CHALLENGER_ID_INVALID");
        if (value.priority() <= 0 || value.adapterKind() == null || value.adapterKind().isBlank()
                || value.role() == null || value.role().isBlank()
                || value.backend() == null || value.backend().isBlank()) {
            throw invalid("CHALLENGER_ROLE_INVALID");
        }
        if (value.runtimeNetworkPolicy() != RuntimeNetworkPolicy.DENY_ALL
                || value.runtimeDownloadAllowed()) {
            throw invalid("CHALLENGER_RUNTIME_NETWORK_INVALID");
        }
        validateEnvelope(value.resourceEnvelope(), document.maximumResourceEnvelope());
        validateLicense(value.codeLicense(), "CODE");
        validateLicense(value.weightLicense(), "WEIGHT");
        if (value.codeLicense().evidenceReference().equals(value.weightLicense().evidenceReference())) {
            throw invalid("CHALLENGER_LICENSE_EVIDENCE_NOT_SEPARATE");
        }
        validatePins(value.packagePins(), "PACKAGE");
        validatePins(value.weightPins(), "WEIGHT");
        var missing = List.copyOf(Objects.requireNonNull(
                value.missingAdmissionDimensions(), "missingAdmissionDimensions"));
        if (missing.isEmpty() || new HashSet<>(missing).size() != missing.size()
                || missing.stream().anyMatch(item -> !item.matches("[A-Z][A-Z0-9_]{0,127}"))) {
            throw invalid("CHALLENGER_MISSING_DIMENSION_INVALID");
        }
        if (value.admissionDisposition() != AdmissionDisposition.NOT_ADMITTED
                || value.executable()
                || value.codeLicense().decision() != LicenseDecision.J0_PENDING
                || value.weightLicense().decision() != LicenseDecision.J0_PENDING
                || !missing.contains("LICENSE_J1")) {
            throw invalid("CHALLENGER_PREMATURE_ADMISSION");
        }
        if (!"UNVERIFIED_J0".equals(value.windowsDeployment())
                || !"LOCAL_PROCESS_SHADOW_ONLY".equals(value.deploymentForm())
                || value.preprocessingIdentity() == null || value.preprocessingIdentity().isBlank()
                || value.postprocessingIdentity() == null || value.postprocessingIdentity().isBlank()) {
            throw invalid("CHALLENGER_DEPLOYMENT_INVALID");
        }
        var provenance = List.copyOf(Objects.requireNonNull(value.provenanceReferences(), "provenance"));
        if (provenance.size() < 2 || provenance.stream().anyMatch(reference ->
                reference == null || !reference.startsWith("https://"))) {
            throw invalid("CHALLENGER_PROVENANCE_INVALID");
        }
        var capabilityIdentity = CAPABILITY_VERSION + ":" + sha256(List.of(identity, value.challengerId()));
        return new Capability(value.challengerId(), value.priority(), value.role(), value.adapterKind(),
                value.backend(), value.packagePins(), value.weightPins(), value.codeLicense(),
                value.weightLicense(), value.preprocessingIdentity(), value.postprocessingIdentity(),
                value.resourceEnvelope(), value.windowsDeployment(), value.deploymentForm(),
                value.runtimeNetworkPolicy(), value.runtimeDownloadAllowed(), provenance,
                value.admissionDisposition(), missing, value.executable(), capabilityIdentity);
    }

    private static void validatePins(List<SupplyChainPin> source, String kind) {
        source = List.copyOf(Objects.requireNonNull(source, kind));
        if (source.isEmpty()) throw invalid("CHALLENGER_" + kind + "_PIN_MISSING");
        for (var pin : source) {
            if (pin == null || pin.name() == null || pin.name().isBlank()
                    || pin.sourceReference() == null || !pin.sourceReference().startsWith("https://")
                    || pin.immutableRevision() == null || pin.immutableRevision().isBlank()
                    || pin.pinDisposition() == PinDisposition.PINNED_LOCAL_VERIFIED) {
                throw invalid("CHALLENGER_" + kind + "_PIN_INVALID");
            }
            if (pin.pinDisposition() == PinDisposition.UPSTREAM_PIN_RECORDED_NOT_STAGED) {
                if (pin.version() == null || pin.version().isBlank()
                        || pin.sha256() == null || !pin.sha256().matches("[0-9a-f]{64}")) {
                    throw invalid("CHALLENGER_" + kind + "_UPSTREAM_PIN_INVALID");
                }
            } else if (pin.pinDisposition()
                    == PinDisposition.UPSTREAM_REVISION_RECORDED_SHA256_MISSING) {
                if (pin.version() == null || pin.version().isBlank() || pin.sha256() != null
                        || pin.upstreamObjectIdentity() == null
                        || !pin.upstreamObjectIdentity().matches("git-blob-sha1:[0-9a-f]{40}")) {
                    throw invalid("CHALLENGER_" + kind + "_REVISION_PIN_INVALID");
                }
            } else {
                throw invalid("CHALLENGER_" + kind + "_PIN_INVALID");
            }
        }
    }

    private static void validateLicense(LicenseRecord value, String kind) {
        Objects.requireNonNull(value, kind + "License");
        if (value.spdxExpression() == null || value.spdxExpression().isBlank()
                || value.evidenceReference() == null || !value.evidenceReference().startsWith("https://")
                || value.decision() != LicenseDecision.J0_PENDING
                || value.reasonCode() == null || !value.reasonCode().matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw invalid("CHALLENGER_" + kind + "_LICENSE_INVALID");
        }
    }

    private static void validateGlobalCeilings(ResourceEnvelope value) {
        Objects.requireNonNull(value, "maximumResourceEnvelope");
        if (value.maximumStartupMillis() != 300_000
                || value.maximumCaseP95Millis() != 120_000
                || value.maximumPeakRamMiB() != 12_288
                || value.maximumDiskMiB() != 15_360
                || value.maximumGpuVramMiB() != 12_288) {
            throw invalid("CHALLENGER_GLOBAL_RESOURCE_CEILING_INVALID");
        }
    }

    private static void validateEnvelope(ResourceEnvelope value, ResourceEnvelope maximum) {
        Objects.requireNonNull(value, "resourceEnvelope");
        if (value.maximumStartupMillis() <= 0
                || value.maximumStartupMillis() > maximum.maximumStartupMillis()
                || value.maximumCaseP95Millis() <= 0
                || value.maximumCaseP95Millis() > maximum.maximumCaseP95Millis()
                || value.maximumPeakRamMiB() <= 0
                || value.maximumPeakRamMiB() > maximum.maximumPeakRamMiB()
                || value.maximumDiskMiB() <= 0
                || value.maximumDiskMiB() > maximum.maximumDiskMiB()
                || value.maximumGpuVramMiB() < 0
                || value.maximumGpuVramMiB() > maximum.maximumGpuVramMiB()) {
            throw invalid("CHALLENGER_RESOURCE_ENVELOPE_INVALID");
        }
    }

    private static void requireId(String value, String code) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,127}")) throw invalid(code);
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }

    private static String sha256(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                var bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }

    private static byte[] normalizeLineEndings(byte[] source) {
        var normalized = new java.io.ByteArrayOutputStream(source.length);
        for (var index = 0; index < source.length; index++) {
            if (source[index] != '\r') {
                normalized.write(source[index]);
                continue;
            }
            if (index + 1 >= source.length || source[index + 1] != '\n') {
                throw invalid("CHALLENGER_CATALOG_LINE_ENDING_INVALID");
            }
            normalized.write('\n');
            index++;
        }
        return normalized.toByteArray();
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public enum OptionalThirdChallenger { NONE }

    public enum AdmissionDisposition { NOT_ADMITTED, ADMITTED }

    public enum LicenseDecision { J0_PENDING, J1_APPROVED }

    public enum RuntimeNetworkPolicy { DENY_ALL }

    public enum PinDisposition {
        UPSTREAM_PIN_RECORDED_NOT_STAGED,
        UPSTREAM_REVISION_RECORDED_SHA256_MISSING,
        PINNED_LOCAL_VERIFIED
    }

    public record LicenseRecord(
            String spdxExpression,
            String evidenceReference,
            LicenseDecision decision,
            String reasonCode
    ) { }

    public record SupplyChainPin(
            String name,
            String version,
            String sha256,
            String sourceReference,
            String immutableRevision,
            String upstreamObjectIdentity,
            PinDisposition pinDisposition
    ) { }

    public record ResourceEnvelope(
            long maximumStartupMillis,
            long maximumCaseP95Millis,
            long maximumPeakRamMiB,
            long maximumDiskMiB,
            long maximumGpuVramMiB
    ) { }

    public record Capability(
            String challengerId,
            int priority,
            String role,
            String adapterKind,
            String backend,
            List<SupplyChainPin> packagePins,
            List<SupplyChainPin> weightPins,
            LicenseRecord codeLicense,
            LicenseRecord weightLicense,
            String preprocessingIdentity,
            String postprocessingIdentity,
            ResourceEnvelope resourceEnvelope,
            String windowsDeployment,
            String deploymentForm,
            RuntimeNetworkPolicy runtimeNetworkPolicy,
            boolean runtimeDownloadAllowed,
            List<String> provenanceReferences,
            AdmissionDisposition admissionDisposition,
            List<String> missingAdmissionDimensions,
            boolean executable,
            String identity
    ) {
        public Capability {
            packagePins = List.copyOf(packagePins);
            weightPins = List.copyOf(weightPins);
            provenanceReferences = List.copyOf(provenanceReferences);
            missingAdmissionDimensions = List.copyOf(missingAdmissionDimensions);
        }
    }

    private record CapabilityDocument(
            String challengerId,
            int priority,
            String role,
            String adapterKind,
            String backend,
            List<SupplyChainPin> packagePins,
            List<SupplyChainPin> weightPins,
            LicenseRecord codeLicense,
            LicenseRecord weightLicense,
            String preprocessingIdentity,
            String postprocessingIdentity,
            ResourceEnvelope resourceEnvelope,
            String windowsDeployment,
            String deploymentForm,
            RuntimeNetworkPolicy runtimeNetworkPolicy,
            boolean runtimeDownloadAllowed,
            List<String> provenanceReferences,
            AdmissionDisposition admissionDisposition,
            List<String> missingAdmissionDimensions,
            boolean executable
    ) {
        private CapabilityDocument {
            packagePins = List.copyOf(Objects.requireNonNull(packagePins, "packagePins"));
            weightPins = List.copyOf(Objects.requireNonNull(weightPins, "weightPins"));
            provenanceReferences = List.copyOf(Objects.requireNonNull(
                    provenanceReferences, "provenanceReferences"));
            missingAdmissionDimensions = List.copyOf(Objects.requireNonNull(
                    missingAdmissionDimensions, "missingAdmissionDimensions"));
        }
    }

    private record Document(
            String catalogVersion,
            String successorSpecSha256,
            OptionalThirdChallenger optionalThirdChallenger,
            ResourceEnvelope maximumResourceEnvelope,
            List<CapabilityDocument> challengers
    ) { }
}
