package cn.hbads.renderweave.inference.certification;

import java.util.List;
import java.util.UUID;

public interface ProfileCertificationStore {
    void append(ProfileCertificationEvent event);

    List<ProfileCertificationEvent> events(UUID cycleId);
}
