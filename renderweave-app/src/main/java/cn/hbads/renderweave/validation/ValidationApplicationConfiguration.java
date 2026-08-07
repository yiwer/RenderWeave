package cn.hbads.renderweave.validation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ValidationApplicationConfiguration {

    @Bean
    RootDocumentValidationService rootDocumentValidationService(ValidationTargetResolver resolver) {
        return new RootDocumentValidationService(resolver);
    }
}
