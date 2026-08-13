package cn.hbads.renderweave.inference;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Fail-closed G-LIVE(ticket) checks that execute before journal, reservation or Provider access. */
final class N7LiveAdmissionGate {
    private N7LiveAdmissionGate() { }

    static void requireExactAuthorization(
            N7LiveTicketContract contract,
            VisualEvaluationAuthorization authorization,
            String currentEvaluationIdentity,
            Instant now
    ) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(now, "now");
        if (!"OPEN".equals(authorization.status())) {
            throw failure("N7_LIVE_AUTHORIZATION_NOT_OPEN");
        }
        if (!Objects.equals(authorization.evaluationIdentity(), currentEvaluationIdentity)) {
            throw failure("N7_LIVE_EVALUATION_IDENTITY_MISMATCH");
        }
        if (!Objects.equals(authorization.profileSnapshotSha256(),
                contract.profileSnapshotSha256())) {
            throw failure("N7_LIVE_PROFILE_SNAPSHOT_MISMATCH");
        }
        if (!authorization.caseIds().equals(contract.caseIds())) {
            throw failure("N7_LIVE_CASE_ASSIGNMENT_MISMATCH");
        }
        if (!Objects.equals(authorization.approvalScope(), contract.contractIdentity())
                || authorization.approvedBy() == null || authorization.approvedBy().isBlank()) {
            throw failure("N7_LIVE_EXACT_J1_MISMATCH");
        }
        if (!VisualEvaluationAuthorization.VERSION.equals(authorization.authorizationVersion())
                || !contract.authorizationId().equals(authorization.authorizationId())
                || !"CANARY".equals(authorization.phase())
                || !contract.inputClassification().equals(authorization.inputClassification())
                || !contract.corpusVersion().equals(authorization.corpusVersion())
                || !contract.corpusSourceSha256().equals(authorization.corpusSourceSha256())
                || !contract.profileId().equals(authorization.profileId())
                || !contract.model().equals(authorization.model())) {
            throw failure("N7_LIVE_AUTHORIZATION_CONTRACT_MISMATCH");
        }
        if (authorization.maximumProviderAttempts() != contract.maximumProviderAttempts()
                || authorization.maximumTotalTokens() != contract.maximumTotalTokens()
                || authorization.maximumCostMicrosCny() != contract.maximumCostMicrosCny()
                || authorization.maximumCasesPerBatch() != contract.maximumCasesPerBatch()) {
            throw failure("N7_LIVE_AUTHORIZATION_BUDGET_MISMATCH");
        }
        try {
            var approved = Instant.parse(authorization.approvedAt());
            var expires = Instant.parse(authorization.expiresAt());
            if (approved.isAfter(now) || !expires.isAfter(now) || !expires.isAfter(approved)
                    || Duration.between(approved, expires).compareTo(
                            Duration.ofSeconds(contract.maximumAuthorizationWindowSeconds())) > 0) {
                throw failure("N7_LIVE_AUTHORIZATION_EXPIRED");
            }
        } catch (IllegalStateException expected) {
            throw expected;
        } catch (RuntimeException invalid) {
            throw failure("N7_LIVE_AUTHORIZATION_EXPIRED");
        }
    }

    static void requireGoalReady(
            N7LiveTicketContract contract,
            VisualEvaluationGoalBudget.ExistingSnapshot audit
    ) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(audit, "audit");
        if (audit.nonTerminalReservations() != 0) {
            throw failure("N7_LIVE_GOAL_NONTERMINAL_RESERVATION");
        }
        if (audit.breachedReservations() != 0
                || audit.slots().values().stream().anyMatch(
                VisualEvaluationGoalBudget.UsageAggregate::breached)) {
            throw failure("N7_LIVE_GOAL_BREACHED");
        }
        var slot = audit.slots().get(VisualEvaluationAuthorization.goalModel(contract.model()));
        var limits = audit.epochLimits();
        var maximumCost = limits.maximumCostMicrosCnyByModel().get(
                VisualEvaluationAuthorization.goalModel(contract.model()));
        if (slot == null
                || Math.addExact(slot.attempts(), contract.maximumProviderAttempts())
                > limits.maximumAttemptsPerModel()
                || Math.addExact(slot.tokens(), contract.maximumTotalTokens())
                > limits.maximumTokensPerModel()
                || Math.addExact(slot.costMicrosCny(), contract.maximumCostMicrosCny())
                > maximumCost) {
            throw failure("N7_LIVE_GOAL_CAPACITY_INSUFFICIENT");
        }
    }

    static LedgerAudit auditHistoricalClosedLedgers(Path repositoryRoot, ObjectMapper json) {
        var statuses = new LinkedHashMap<String, String>();
        for (var entry : VisualEvaluationAuthorizationLocator.LEDGERS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            var authorization = VisualEvaluationAuthorization.load(
                    repositoryRoot.toAbsolutePath().normalize().resolve(entry.getValue()), json);
            statuses.put(entry.getKey(), authorization.status());
            if (!"CLOSED".equals(authorization.status())) {
                throw failure("N7_LIVE_HISTORICAL_LEDGER_NOT_CLOSED");
            }
            authorization.requireClosed();
        }
        return new LedgerAudit(Map.copyOf(statuses), true);
    }

    static void requireExclusiveOpenLedger(
            Path repositoryRoot,
            String selected,
            VisualEvaluationAuthorization expected,
            ObjectMapper json
    ) {
        if (!VisualEvaluationAuthorizationLocator.LEDGERS.containsKey(selected)) {
            throw failure("N7_LIVE_LEDGER_SELECTOR_INVALID");
        }
        for (var entry : VisualEvaluationAuthorizationLocator.LEDGERS.entrySet()) {
            var actual = VisualEvaluationAuthorization.load(
                    repositoryRoot.toAbsolutePath().normalize().resolve(entry.getValue()), json);
            if (entry.getKey().equals(selected)) {
                if (!actual.equals(expected) || !"OPEN".equals(actual.status())) {
                    throw failure("N7_LIVE_SELECTED_LEDGER_NOT_EXACT_OPEN");
                }
            } else if (!"CLOSED".equals(actual.status())) {
                throw failure("N7_LIVE_CONCURRENT_LEDGER_NOT_CLOSED");
            }
        }
    }

    record LedgerAudit(Map<String, String> statuses, boolean allClosed) {
        LedgerAudit {
            statuses = Map.copyOf(Objects.requireNonNull(statuses, "statuses"));
            if (!statuses.keySet().equals(VisualEvaluationAuthorizationLocator.LEDGERS.keySet())
                    || allClosed != statuses.values().stream().allMatch("CLOSED"::equals)) {
                throw new IllegalArgumentException("N7 live ledger audit is invalid");
            }
        }
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }
}
