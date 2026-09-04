package cn.hbads.renderweave.inference.admission;

import java.util.Optional;
import java.util.UUID;

public interface LiveAdmissionStore {
    Result admit(NewLiveInferenceRun command);

    Optional<ExternalTransferConfirmation> findConfirmation(UUID runId);

    record Result(
            UUID runId,
            UUID confirmationId,
            String manifestIdentity,
            boolean created
    ) { }
}
