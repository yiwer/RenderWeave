package cn.hbads.renderweave.inference.run;

import java.util.Set;

public enum InferenceStage {
    NORMALIZE,
    OBSERVE,
    HIERARCHY,
    ELEMENT_BINDING,
    STRUCTURE,
    DETERMINISTIC_VALIDATE,
    CRITIQUE,
    REPAIR,
    USER_APPROVAL,
    ATOMIC_CREATE;

    public boolean canTransitionTo(InferenceStage next) {
        return switch (this) {
            case NORMALIZE -> next == OBSERVE;
            case OBSERVE -> Set.of(HIERARCHY, STRUCTURE).contains(next);
            case HIERARCHY -> next == ELEMENT_BINDING;
            case ELEMENT_BINDING -> next == STRUCTURE;
            case STRUCTURE -> next == DETERMINISTIC_VALIDATE;
            case DETERMINISTIC_VALIDATE -> next == CRITIQUE;
            case CRITIQUE -> Set.of(REPAIR, USER_APPROVAL).contains(next);
            case REPAIR -> next == DETERMINISTIC_VALIDATE;
            case USER_APPROVAL -> next == ATOMIC_CREATE;
            case ATOMIC_CREATE -> false;
        };
    }
}
