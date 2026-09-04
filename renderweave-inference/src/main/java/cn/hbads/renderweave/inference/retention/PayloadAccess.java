package cn.hbads.renderweave.inference.retention;

/** Operations that must stop at payload expiry or an immutable deletion tombstone. */
public enum PayloadAccess {
    READ,
    RETRY,
    PROVIDER_CALL,
    APPLY
}
