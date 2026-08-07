package cn.hbads.renderweave.validation;

@FunctionalInterface
public interface ValidationTargetResolver {
    ResolvedValidationTarget resolve(ValidationTarget target);
}
