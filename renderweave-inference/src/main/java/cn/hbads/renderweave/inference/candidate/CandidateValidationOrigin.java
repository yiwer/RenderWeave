package cn.hbads.renderweave.inference.candidate;

/** Distinguishes untrusted inference output from a Candidate changed through the review service. */
public enum CandidateValidationOrigin {
    LIVE_PROVIDER_OUTPUT,
    TRUSTED_REPLAY_OUTPUT,
    USER_REVIEW
}
