package cn.hbads.renderweave.template.spi;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact, sorted dependency facts bound to a Template validation decision. */
public final class TemplateDependencySnapshot {
    private static final byte[] DOMAIN =
            "renderweave-template-dependency-snapshot/1\0".getBytes(StandardCharsets.UTF_8);

    private final List<AssetFact> assets;
    private final List<TemplateFact> templates;
    private final String fingerprint;

    public TemplateDependencySnapshot(List<AssetFact> assets, List<TemplateFact> templates) {
        this.assets = sortedUniqueAssets(assets);
        this.templates = sortedUniqueTemplates(templates);
        this.fingerprint = digest(this.assets, this.templates);
    }

    public static TemplateDependencySnapshot empty() {
        return new TemplateDependencySnapshot(List.of(), List.of());
    }

    public List<AssetFact> assets() {
        return assets;
    }

    public List<TemplateFact> templates() {
        return templates;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public record AssetFact(
            String assetId,
            Optional<DependencyResolution.AssetState> state
    ) {
        public AssetFact {
            requireIdentity(assetId, "assetId");
            state = Objects.requireNonNull(state, "state");
        }

        public static AssetFact missing(String assetId) {
            return new AssetFact(assetId, Optional.empty());
        }

        public static AssetFact resolved(
                String assetId,
                DependencyResolution.AssetState state
        ) {
            return new AssetFact(assetId, Optional.of(Objects.requireNonNull(state, "state")));
        }
    }

    public record TemplateFact(
            String templateId,
            Optional<DependencyResolution.TemplateState> state
    ) {
        public TemplateFact {
            requireIdentity(templateId, "templateId");
            state = Objects.requireNonNull(state, "state");
        }

        public static TemplateFact missing(String templateId) {
            return new TemplateFact(templateId, Optional.empty());
        }

        public static TemplateFact resolved(
                String templateId,
                DependencyResolution.TemplateState state
        ) {
            return new TemplateFact(
                    templateId,
                    Optional.of(Objects.requireNonNull(state, "state"))
            );
        }
    }

    private static List<AssetFact> sortedUniqueAssets(List<AssetFact> input) {
        var sorted = List.copyOf(Objects.requireNonNull(input, "assets")).stream()
                .sorted(Comparator.comparing(AssetFact::assetId))
                .toList();
        ensureUnique(sorted.stream().map(AssetFact::assetId).toList(), "asset");
        return sorted;
    }

    private static List<TemplateFact> sortedUniqueTemplates(List<TemplateFact> input) {
        var sorted = List.copyOf(Objects.requireNonNull(input, "templates")).stream()
                .sorted(Comparator.comparing(TemplateFact::templateId))
                .toList();
        ensureUnique(sorted.stream().map(TemplateFact::templateId).toList(), "template");
        return sorted;
    }

    private static void ensureUnique(List<String> identities, String kind) {
        for (int index = 1; index < identities.size(); index++) {
            if (identities.get(index - 1).equals(identities.get(index))) {
                throw new IllegalArgumentException("duplicate " + kind + " dependency fact");
            }
        }
    }

    private static String digest(List<AssetFact> assets, List<TemplateFact> templates) {
        try {
            var bytes = new ByteArrayOutputStream();
            bytes.write(DOMAIN);
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(assets.size());
                for (var fact : assets) {
                    write(output, fact.assetId());
                    output.writeBoolean(fact.state().isPresent());
                    if (fact.state().isPresent()) {
                        var state = fact.state().orElseThrow();
                        write(output, state.ownerScope().value());
                        write(output, state.kind());
                        write(output, state.lifecycle().name());
                        output.writeLong(state.assetRevision());
                        output.writeLong(state.currentContentVersion());
                    }
                }
                output.writeInt(templates.size());
                for (var fact : templates) {
                    write(output, fact.templateId());
                    output.writeBoolean(fact.state().isPresent());
                    if (fact.state().isPresent()) {
                        var state = fact.state().orElseThrow();
                        write(output, state.templateId());
                        write(output, state.ownerScope().value());
                        output.writeLong(state.currentRevision());
                        write(output, state.lifecycle().name());
                        write(output, state.readiness().name());
                        write(output, state.staticSchema().schemaKey().value());
                        write(output, state.staticSchema().versionTag().value());
                        write(output, state.contentHash());
                        output.writeInt(state.uses().size());
                        for (var use : state.uses()) {
                            write(output, use.canonicalPointer());
                            write(output, use.targetTemplateId());
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            );
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void requireIdentity(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
    }
}
