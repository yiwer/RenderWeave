package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.GatewayAssertionAuthority;
import cn.hbads.renderweave.inference.admission.GatewayAssertionReplayStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
class ProductionEdgeSecurityConfiguration {
    @Bean
    @ConditionalOnProperty(
            prefix = "renderweave.security.gateway-assertion",
            name = "enabled",
            havingValue = "true"
    )
    GatewayPublicKeySet gatewayPublicKeySet(
            @Value("${renderweave.security.gateway-assertion.public-key-directory:}") String directory
    ) {
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("Gateway assertion public-key directory is required");
        }
        return GatewayPublicKeySet.load(Path.of(directory));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "renderweave.security.gateway-assertion",
            name = "enabled",
            havingValue = "true"
    )
    GatewayAssertionAuthority gatewayAssertionAuthority(
            GatewayPublicKeySet keys,
            GatewayAssertionReplayStore replayStore,
            Clock inferenceClock,
            @Value("${renderweave.security.gateway-assertion.issuer:}") String issuer,
            @Value("${renderweave.security.gateway-assertion.audience:}") String audience
    ) {
        return new GatewayAssertionAuthority(keys, replayStore, inferenceClock, issuer, audience);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "renderweave.security.gateway-assertion",
            name = "enabled",
            havingValue = "true"
    )
    FilterRegistrationBean<GatewayAssertionFilter> gatewayAssertionFilter(
            GatewayAssertionAuthority authority,
            ObjectMapper json,
            @Value("${renderweave.security.gateway-mtls.required:true}") boolean mtlsRequired,
            @Value("${renderweave.security.gateway-mtls.allowed-certificate-sha256:}") String fingerprints
    ) {
        var registration = new FilterRegistrationBean<>(new GatewayAssertionFilter(
                authority, new ClientCertificateGate(parseFingerprints(fingerprints)),
                mtlsRequired, json
        ));
        registration.setName("gatewayAssertionFilter");
        registration.addUrlPatterns("/api/*");
        registration.setOrder(-100);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "renderweave.security.actuator-mtls",
            name = "required",
            havingValue = "true",
            matchIfMissing = true
    )
    FilterRegistrationBean<InternalActuatorMtlsFilter> internalActuatorMtlsFilter(
            ObjectMapper json,
            @Value("${renderweave.security.actuator-mtls.allowed-certificate-sha256:}")
            String fingerprints
    ) {
        var registration = new FilterRegistrationBean<>(new InternalActuatorMtlsFilter(
                new ClientCertificateGate(parseFingerprints(fingerprints)), json
        ));
        registration.setName("internalActuatorMtlsFilter");
        registration.addUrlPatterns("/actuator/*");
        registration.setOrder(-110);
        return registration;
    }

    private static Set<String> parseFingerprints(String value) {
        if (value == null || value.isBlank()) return Set.of();
        var result = Arrays.stream(value.split(",", -1))
                .map(String::trim)
                .peek(item -> {
                    if (!item.matches("[0-9a-f]{64}")) {
                        throw new IllegalArgumentException("mTLS certificate SHA-256 is invalid");
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
        if (result.size() > 4) {
            throw new IllegalArgumentException("mTLS certificate rotation set is too large");
        }
        return result;
    }
}
