package cn.hbads.renderweave.inference.admission;

@FunctionalInterface
public interface LiveAdmissionConfigurationResolver {
    LiveAdmissionConfiguration require(String profileId, String locale);
}
