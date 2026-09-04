package cn.hbads.renderweave.inference.admission;

import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.InputNormalizer;
import cn.hbads.renderweave.inference.input.NormalizedInput;
import cn.hbads.renderweave.inference.run.NewInferenceRun;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Primary IMAGE_ONLY production create seam. Public adapters submit one signed command; this module
 * owns classification, dual-switch revalidation, server notice/Profile resolution, normalization,
 * identity binding and the single durable admission call. Certification/authority/call gates join
 * this seam in later phases.
 */
public final class ImageOnlyProductionAdmission {
    public static final String LIVE_PATH = "/api/v1/inference-runs/live";
    private static final String LIVE_SOURCE_REFERENCE = "production-live";

    private final InputNormalizer inputNormalizer;
    private final LiveAdmissionStore store;
    private final LiveAdmissionConfigurationResolver configurations;
    private final ImageOnlyAdmissionPolicyStore policyStore;
    private final ProviderEgressPermit egressPermit;
    private final Clock clock;
    private final Supplier<UUID> runIds;
    private final Supplier<UUID> confirmationIds;

    public ImageOnlyProductionAdmission(
            BlobStore blobStore,
            LiveAdmissionStore store,
            LiveAdmissionConfigurationResolver configurations,
            ImageOnlyAdmissionPolicyStore policyStore,
            ProviderEgressPermit egressPermit,
            Clock clock,
            Supplier<UUID> runIds,
            Supplier<UUID> confirmationIds
    ) {
        this.inputNormalizer = new InputNormalizer(Objects.requireNonNull(blobStore, "blobStore"));
        this.store = Objects.requireNonNull(store, "store");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.policyStore = Objects.requireNonNull(policyStore, "policyStore");
        this.egressPermit = Objects.requireNonNull(egressPermit, "egressPermit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runIds = Objects.requireNonNull(runIds, "runIds");
        this.confirmationIds = Objects.requireNonNull(confirmationIds, "confirmationIds");
    }

    public LiveAdmissionStore.Result admit(ImageOnlyLiveAdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        requireGatewayBinding(request);
        requireClassification(request.inputProvenance(), request.sensitivityClass());
        requireDualSwitches();

        var configuration = configurations.require(
                request.profileId(), request.presentedNotice().locale()
        );
        if (!request.presentedNotice().equals(configuration.notice().identity())) {
            throw problem("LIVE_TRANSFER_NOTICE_STALE", "The external-transfer notice is stale.");
        }

        var normalized = inputNormalizer.normalize(new InferenceInput(
                InferenceMode.IMAGE_ONLY,
                configuration.profile().profile().profileId(),
                LIVE_SOURCE_REFERENCE,
                true,
                request.images(),
                List.of()
        ));
        var manifest = LiveInputManifest.from(normalized);
        var boundInput = withManifestFingerprint(normalized, manifest.sha256());
        var confirmedAt = clock.instant();
        var runId = runIds.get();
        var confirmation = ExternalTransferConfirmation.issue(
                confirmationIds.get(), runId, request.gatewayIdentity(),
                request.inputProvenance(), request.sensitivityClass(),
                configuration, manifest, confirmedAt
        );
        var run = new NewInferenceRun(
                runId, request.idempotencyKey(), confirmation.requestFingerprint(), boundInput,
                configuration.profile().snapshotJson(),
                configuration.notice().maximumCostMicrosCny(), Optional.empty(), confirmedAt
        );

        // Do not delete content-addressed bytes on an ambiguous persistence failure: the transaction
        // may have committed before the response was lost. P2-03/04 add encrypted orphan reconciliation.
        return store.admit(new NewLiveInferenceRun(
                run, manifest, configuration.notice(), confirmation
        ));
    }

    private static void requireGatewayBinding(ImageOnlyLiveAdmissionRequest request) {
        var identity = request.gatewayIdentity();
        if (!"POST".equals(identity.method()) || !LIVE_PATH.equals(identity.path())) {
            throw problem(
                    "GATEWAY_ASSERTION_REQUEST_MISMATCH",
                    "The verified gateway identity does not bind the live create command."
            );
        }
        var expected = GatewayAssertionAuthority.idempotencyKeyDigest(request.idempotencyKey());
        if (!expected.equals(identity.idempotencyKeyDigest())) {
            throw problem(
                    "GATEWAY_ASSERTION_IDEMPOTENCY_MISMATCH",
                    "The verified gateway identity does not bind the idempotency key."
            );
        }
    }

    private void requireDualSwitches() {
        if (!policyStore.current().enabled()) {
            throw problem(
                    ImageOnlyAdmissionPolicy.DISABLED_REASON_CODE,
                    "IMAGE_ONLY live admission is closed by the persisted application policy."
            );
        }
        if (!egressPermit.snapshot().enabled()) {
            throw problem(
                    ProviderEgressPermit.DISABLED_REASON_CODE,
                    "Provider egress is closed by the external orchestrator authority."
            );
        }
    }

    private static void requireClassification(
            InputProvenance provenance,
            SensitivityClass sensitivity
    ) {
        if (provenance != InputProvenance.USER_PROVIDED) {
            throw problem(
                    "LIVE_INPUT_PROVENANCE_NOT_ADMISSIBLE",
                    "Only user-provided input is admissible for live transfer."
            );
        }
        if (sensitivity == null) {
            throw problem(
                    "LIVE_INPUT_CLASSIFICATION_REQUIRED",
                    "A closed sensitivity classification is required."
            );
        }
        if (sensitivity != SensitivityClass.ORDINARY_DESIGN) {
            throw problem(
                    "LIVE_INPUT_CLASSIFICATION_NOT_ADMISSIBLE",
                    "Restricted input cannot be transferred to the Provider."
            );
        }
    }

    private static NormalizedInput withManifestFingerprint(NormalizedInput input, String fingerprint) {
        return new NormalizedInput(
                input.mode(), input.profileId(), input.sourceReference(), fingerprint,
                input.artifacts(), input.references(), input.newlyCreatedLocators()
        );
    }

    private static LiveAdmissionProblem problem(String code, String message) {
        return new LiveAdmissionProblem(code, message);
    }
}
