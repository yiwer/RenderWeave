package cn.hbads.renderweave.inference.candidate;

public enum CandidateResolution {
    NOT_REQUIRED,
    UNRESOLVED,
    CONFIRMED,
    RESOLVED_BY_EDIT,
    REMOVED;

    public boolean resolved() {
        return this == NOT_REQUIRED || this == CONFIRMED || this == RESOLVED_BY_EDIT || this == REMOVED;
    }
}
