package cn.hbads.renderweave.inference.certification;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Closed authority inventory for starting a fresh IMAGE_ONLY Profile certification cycle. */
public final class CertificationAuthorityInventory {
    public static final String VERSION = "renderweave-image-only-authority-inventory/1.0";
    private static final String RESOURCE =
            "image-only-production-admission/authority-inventory-v1.json";
    private static final Set<String> EXPECTED_REUSABLE = Set.of(
            "document-observation-ir/1.0",
            "n9-r1-evaluator/1.0",
            "production-admission-decisions-01-08-14",
            "trial-signal-2026-08-17"
    );
    private static final Set<String> EXPECTED_PROHIBITED = Set.of(
            "n7", "r5-v2", "r5p-v1", "r5p2", "historical-v45-j1",
            "historical-v45-ledger", "n7-closeout-evidence"
    );
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Map<String, AuthorityEntry> entries;
    private final ProviderAccounting providerAccounting;
    private final String canonicalSha256;

    private CertificationAuthorityInventory(InventoryDocument document, byte[] source) {
        if (!VERSION.equals(document.version())) {
            throw new IllegalArgumentException("IMAGE_ONLY_AUTHORITY_INVENTORY_VERSION_INVALID");
        }
        Objects.requireNonNull(document.entries(), "entries");
        var loaded = new LinkedHashMap<String, AuthorityEntry>();
        for (var entry : document.entries()) {
            entry.validate();
            if (loaded.putIfAbsent(entry.referenceId(), entry) != null) {
                throw new IllegalArgumentException("IMAGE_ONLY_AUTHORITY_INVENTORY_DUPLICATE_REFERENCE");
            }
        }
        entries = Map.copyOf(loaded);
        providerAccounting = Objects.requireNonNull(document.providerAccounting(), "providerAccounting");
        providerAccounting.requireZero();
        requireExpectedAuthority();
        canonicalSha256 = sha256(source);
    }

    public static CertificationAuthorityInventory loadCanonical() {
        try (var input = CertificationAuthorityInventory.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("IMAGE_ONLY_AUTHORITY_INVENTORY_MISSING");
            return parse(input.readAllBytes());
        } catch (IOException exception) {
            throw new IllegalStateException("IMAGE_ONLY_AUTHORITY_INVENTORY_UNREADABLE", exception);
        }
    }

    public static CertificationAuthorityInventory parse(byte[] source) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("IMAGE_ONLY_AUTHORITY_INVENTORY_EMPTY");
        }
        try {
            return new CertificationAuthorityInventory(JSON.readValue(source, InventoryDocument.class), source.clone());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("IMAGE_ONLY_AUTHORITY_INVENTORY_INVALID", exception);
        }
    }

    public AuthorityEntry require(String referenceId) {
        var entry = entries.get(referenceId);
        if (entry == null) {
            throw new CertificationAuthorityViolation(
                    "IMAGE_ONLY_CERTIFICATION_REFERENCE_UNKNOWN", referenceId);
        }
        return entry;
    }

    public AuthorityEntry requireReusable(String referenceId) {
        var entry = require(referenceId);
        if (!"REUSABLE".equals(entry.reuseDisposition())) {
            throw new CertificationAuthorityViolation(
                    "IMAGE_ONLY_CERTIFICATION_REFERENCE_PROHIBITED", referenceId);
        }
        return entry;
    }

    public Set<String> reusableReferenceIds() {
        var result = new HashSet<String>();
        entries.values().stream()
                .filter(entry -> "REUSABLE".equals(entry.reuseDisposition()))
                .map(AuthorityEntry::referenceId)
                .forEach(result::add);
        return Set.copyOf(result);
    }

    public ProviderAccounting providerAccounting() {
        return providerAccounting;
    }

    public String canonicalSha256() {
        return canonicalSha256;
    }

    private void requireExpectedAuthority() {
        if (!reusableReferenceIds().equals(EXPECTED_REUSABLE)) {
            throw new IllegalArgumentException("IMAGE_ONLY_AUTHORITY_REUSE_WHITELIST_DRIFT");
        }
        for (var referenceId : EXPECTED_PROHIBITED) {
            var entry = entries.get(referenceId);
            if (entry == null || !"CLOSED".equals(entry.lifecycle())
                    || !"PROHIBITED".equals(entry.reuseDisposition())) {
                throw new IllegalArgumentException("IMAGE_ONLY_AUTHORITY_PROHIBITED_SET_DRIFT");
            }
        }
        var baseline = entries.get("dashscope-qwen38-max-product-v45-hybrid-generic");
        if (baseline == null || !"ACTIVE_EXPERIMENTAL".equals(baseline.lifecycle())
                || !"SOURCE_ONLY".equals(baseline.reuseDisposition())) {
            throw new IllegalArgumentException("IMAGE_ONLY_AUTHORITY_BASELINE_DRIFT");
        }
    }

    private static String sha256(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }

    private record InventoryDocument(
            List<AuthorityEntry> entries,
            ProviderAccounting providerAccounting,
            String version
    ) { }

    public record AuthorityEntry(
            String authorityKind,
            String lifecycle,
            String referenceId,
            String reuseDisposition
    ) {
        private void validate() {
            if (authorityKind == null || !authorityKind.matches("[A-Z][A-Z0-9_]{2,63}")
                    || lifecycle == null || !lifecycle.matches("[A-Z][A-Z0-9_]{2,63}")
                    || referenceId == null || !referenceId.matches("[a-z0-9][a-z0-9./-]{1,127}")
                    || !Set.of("REUSABLE", "PROHIBITED", "SOURCE_ONLY").contains(reuseDisposition)) {
                throw new IllegalArgumentException("IMAGE_ONLY_AUTHORITY_ENTRY_INVALID");
            }
        }
    }

    public record ProviderAccounting(
            int apiKeyReads,
            int attempts,
            long costMicrosCny,
            int reservations
    ) {
        private void requireZero() {
            if (apiKeyReads != 0 || attempts != 0 || costMicrosCny != 0 || reservations != 0) {
                throw new IllegalArgumentException("IMAGE_ONLY_AUTHORITY_PROVIDER_ACCOUNTING_NONZERO");
            }
        }
    }
}
