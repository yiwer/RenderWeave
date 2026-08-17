package cn.hbads.renderweave.app.validation;

import cn.hbads.renderweave.validation.RootDocumentValidationService;
import cn.hbads.renderweave.validation.ValidationTargetResolver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ValidationApplicationConfiguration {

    @Bean
    RootDocumentValidationService rootDocumentValidationService(ValidationTargetResolver resolver) {
        return new RootDocumentValidationService(resolver);
    }
}
