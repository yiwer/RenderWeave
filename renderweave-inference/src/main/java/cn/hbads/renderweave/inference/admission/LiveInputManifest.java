package cn.hbads.renderweave.inference.admission;

import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.input.NormalizedInput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Ordered payload-free identity of the exact normalized images sent by one live run. */
public record LiveInputManifest(
        String version,
        String sha256,
        long aggregateNormalizedBytes,
        List<Item> items
) {
    public static final String VERSION = "renderweave-live-input-manifest/1.0";

    public LiveInputManifest {
        if (!VERSION.equals(version)) throw new IllegalArgumentException("Live input manifest version is unsupported");
        sha256 = ExternalTransferNotice.requireSha(sha256, "sha256");
        if (aggregateNormalizedBytes < 1) {
            throw new IllegalArgumentException("aggregateNormalizedBytes must be positive");
        }
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty() || items.size() > 10) {
            throw new IllegalArgumentException("A live input manifest must contain 1..10 images");
        }
        long total = 0;
        for (var index = 0; index < items.size(); index++) {
            var item = items.get(index);
            if (item.ordinal() != index) {
                throw new IllegalArgumentException("Live input manifest ordinals must be contiguous and ordered");
            }
            total = Math.addExact(total, item.byteLength());
        }
        if (total != aggregateNormalizedBytes) {
            throw new IllegalArgumentException("aggregateNormalizedBytes does not match manifest items");
        }
        if (!digest(items).equals(sha256)) {
            throw new IllegalArgumentException("sha256 does not identify the exact ordered manifest");
        }
    }

    public static LiveInputManifest from(NormalizedInput input) {
        Objects.requireNonNull(input, "input");
        if (input.mode() != InferenceMode.IMAGE_ONLY) {
            throw new LiveAdmissionProblem(
                    "LIVE_INPUT_MODE_NOT_ADMISSIBLE", "Production admission accepts IMAGE_ONLY input only."
            );
        }
        var artifacts = new HashMap<String, NormalizedArtifact>();
        input.artifacts().forEach(artifact -> artifacts.put(artifact.artifactId(), artifact));
        var items = new ArrayList<Item>();
        for (var reference : input.references()) {
            if (reference.kind() != NormalizedArtifact.Kind.IMAGE) {
                throw new LiveAdmissionProblem(
                        "LIVE_INPUT_MODE_NOT_ADMISSIBLE", "Production admission accepts image artifacts only."
                );
            }
            var artifact = artifacts.get(reference.artifactId());
            if (artifact == null || artifact.kind() != NormalizedArtifact.Kind.IMAGE
                    || !"image/png".equals(artifact.mediaType())
                    || artifact.width() == null || artifact.height() == null) {
                throw new LiveAdmissionProblem(
                        "LIVE_INPUT_MANIFEST_INVALID", "A normalized image artifact is not exact."
                );
            }
            items.add(new Item(
                    reference.ordinal(), artifact.artifactId(), artifact.mediaType(),
                    artifact.byteLength(), artifact.width(), artifact.height()
            ));
        }
        if (items.isEmpty() || items.size() > 10) {
            throw new LiveAdmissionProblem(
                    "LIVE_INPUT_COUNT_INVALID", "Production admission accepts 1..10 images."
            );
        }
        long total = 0;
        for (var item : items) total = Math.addExact(total, item.byteLength());
        return new LiveInputManifest(VERSION, digest(items), total, items);
    }

    public String identity() {
        return version + ":" + sha256;
    }

    private static String digest(List<Item> items) {
        var fields = new ArrayList<Object>();
        fields.add(items.size());
        for (var item : items) {
            fields.add(item.ordinal());
            fields.add(item.artifactSha256());
            fields.add(item.mediaType());
            fields.add(item.byteLength());
            fields.add(item.width());
            fields.add(item.height());
        }
        return AdmissionDigests.sha256(VERSION, fields.toArray());
    }

    public record Item(
            int ordinal,
            String artifactSha256,
            String mediaType,
            long byteLength,
            int width,
            int height
    ) {
        public Item {
            if (ordinal < 0 || ordinal > 9) throw new IllegalArgumentException("ordinal must be 0..9");
            artifactSha256 = ExternalTransferNotice.requireSha(artifactSha256, "artifactSha256");
            if (!"image/png".equals(mediaType)) {
                throw new IllegalArgumentException("Normalized live images must use image/png");
            }
            if (byteLength < 1 || width < 1 || height < 1) {
                throw new IllegalArgumentException("Normalized image metadata must be positive");
            }
        }
    }
}
