package cn.hbads.renderweave.inference.admission;

import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.run.NewInferenceRun;

import java.util.List;
import java.util.Objects;

/** Public-command projection after GatewayAssertion verification; it contains no confirmation boolean. */
public record ImageOnlyLiveAdmissionRequest(
        String idempotencyKey,
        GatewayRequestIdentity gatewayIdentity,
        String profileId,
        ExternalTransferNotice.Identity presentedNotice,
        InputProvenance inputProvenance,
        SensitivityClass sensitivityClass,
        List<InferenceInput.BinaryInput> images
) {
    public ImageOnlyLiveAdmissionRequest {
        idempotencyKey = NewInferenceRun.validateIdempotencyKey(idempotencyKey);
        Objects.requireNonNull(gatewayIdentity, "gatewayIdentity");
        profileId = ExternalTransferNotice.requireText(profileId, "profileId", 128);
        Objects.requireNonNull(presentedNotice, "presentedNotice");
        images = List.copyOf(Objects.requireNonNull(images, "images"));
    }
}
