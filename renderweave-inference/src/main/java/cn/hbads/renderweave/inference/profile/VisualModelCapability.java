package cn.hbads.renderweave.inference.profile;

import cn.hbads.renderweave.inference.input.ImageNormalizer;
import cn.hbads.renderweave.inference.input.InputNormalizer;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable facts used to decide whether one exact model may back the visual-next request contract. */
public record VisualModelCapability(
        String capabilityVersion,
        String capabilityId,
        String provider,
        String model,
        String providerProtocol,
        boolean visionInputSupported,
        boolean jsonObjectOutputSupported,
        boolean nonStreamingSupported,
        boolean thinkingCanBeDisabled,
        boolean exactModelResponseRequired,
        int productMaximumImagesPerRequest,
        int productMaximumNormalizedImageEdge,
        int productMaximumNormalizedImagePixels,
        int productMaximumOutputTokens,
        int productStageTimeoutSeconds,
        Integer advertisedMaximumBase64Images,
        Integer advertisedMaximumImagePixels,
        Integer advertisedMaximumOutputTokens,
        VerificationBasis verificationBasis,
        String verifiedAt,
        List<String> sourceReferences
) {
    public static final String VERSION = "renderweave-visual-model-capability/1.0";
    private static final Pattern CAPABILITY_ID = Pattern.compile("^[a-z][a-z0-9-]{0,127}$");

    public VisualModelCapability {
        if (!VERSION.equals(capabilityVersion)) {
            throw new IllegalArgumentException("Unsupported visual model capability version");
        }
        if (capabilityId == null || !CAPABILITY_ID.matcher(capabilityId).matches()) {
            throw new IllegalArgumentException("Visual model capability id is invalid");
        }
        if (!"DASHSCOPE".equals(provider) || model == null || model.isBlank()
                || !"OPENAI_CHAT_COMPLETIONS".equals(providerProtocol)) {
            throw new IllegalArgumentException("Visual model provider identity is invalid");
        }
        if (!visionInputSupported || !jsonObjectOutputSupported || !nonStreamingSupported
                || !thinkingCanBeDisabled || !exactModelResponseRequired) {
            throw new IllegalArgumentException("Visual-next requires the complete bounded capability set");
        }
        if (productMaximumImagesPerRequest != InputNormalizer.MAX_IMAGES
                || productMaximumNormalizedImageEdge != ImageNormalizer.MAX_LONG_EDGE
                || productMaximumNormalizedImagePixels != ImageNormalizer.MAX_NORMALIZED_PIXELS
                || productMaximumOutputTokens < 1
                || productMaximumOutputTokens > 8192
                || productStageTimeoutSeconds < 1
                || productStageTimeoutSeconds > 300) {
            throw new IllegalArgumentException("Visual-next product capability bounds are invalid");
        }
        positiveIfPresent(advertisedMaximumBase64Images, "advertisedMaximumBase64Images");
        positiveIfPresent(advertisedMaximumImagePixels, "advertisedMaximumImagePixels");
        positiveIfPresent(advertisedMaximumOutputTokens, "advertisedMaximumOutputTokens");
        Objects.requireNonNull(verificationBasis, "verificationBasis");
        LocalDate.parse(Objects.requireNonNull(verifiedAt, "verifiedAt"));
        sourceReferences = List.copyOf(Objects.requireNonNull(sourceReferences, "sourceReferences"));
        if (sourceReferences.isEmpty() || sourceReferences.size() > 4) {
            throw new IllegalArgumentException("Visual model capability requires bounded evidence references");
        }
        for (var reference : sourceReferences) {
            if (reference == null || reference.isBlank() || reference.length() > 512) {
                throw new IllegalArgumentException("Visual model capability evidence reference is invalid");
            }
            if (reference.startsWith("https://")) URI.create(reference);
            else if (!reference.startsWith("repository:")) {
                throw new IllegalArgumentException("Capability references must be HTTPS or repository-local");
            }
        }
        if ((verificationBasis == VerificationBasis.OFFICIAL_DOCS_AND_N2_LIVE
                || verificationBasis == VerificationBasis.OFFICIAL_DOCS_PENDING_CANARY)
                && (advertisedMaximumBase64Images == null
                || advertisedMaximumImagePixels == null
                || advertisedMaximumOutputTokens == null)) {
            throw new IllegalArgumentException("Official capability rows require advertised limits");
        }
        if ((advertisedMaximumBase64Images != null
                && productMaximumImagesPerRequest > advertisedMaximumBase64Images)
                || (advertisedMaximumImagePixels != null
                && productMaximumNormalizedImagePixels > advertisedMaximumImagePixels)
                || (advertisedMaximumOutputTokens != null
                && productMaximumOutputTokens > advertisedMaximumOutputTokens)) {
            throw new IllegalArgumentException("Product request bounds exceed the advertised model capability");
        }
    }

    void requireCompatible(InferenceProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (!provider.equals(profile.provider()) || !model.equals(profile.model())
                || !providerProtocol.equals(profile.providerProtocol())
                || !"JSON_OBJECT".equals(profile.responseFormat())
                || profile.thinkingEnabled() || profile.toolsAllowed() || profile.remoteMediaAllowed()
                || !("renderweave-inference-pipeline/4.0".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.1".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.2".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.3".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.4".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.5".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.6".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.7".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.8".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.9".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.10".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.11".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.12".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.13".equals(profile.pipelineVersion())
                || "renderweave-inference-pipeline/4.14".equals(profile.pipelineVersion()))
                || !profile.supportedModes().equals(List.of(
                cn.hbads.renderweave.inference.input.InferenceMode.IMAGE_ONLY
        ))
                || profile.maximumOutputTokens() != productMaximumOutputTokens
                || profile.stageTimeoutSeconds() != productStageTimeoutSeconds) {
            throw new IllegalArgumentException("Visual-next Profile does not match its model capability");
        }
    }

    private static void positiveIfPresent(Integer value, String name) {
        if (value != null && value < 1) throw new IllegalArgumentException(name + " must be positive");
    }

    public enum VerificationBasis {
        OFFICIAL_DOCS_AND_N2_LIVE,
        OFFICIAL_DOCS_PENDING_CANARY,
        N2_EXACT_ALIAS_LIVE_ONLY
    }
}
