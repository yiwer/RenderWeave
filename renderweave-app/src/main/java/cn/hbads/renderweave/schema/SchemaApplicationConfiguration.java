package cn.hbads.renderweave.schema;

import cn.hbads.renderweave.schema.draft.DraftService;
import cn.hbads.renderweave.schema.draft.DraftStore;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaService;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaStore;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;

@Configuration(proxyBeanMethods = false)
class SchemaApplicationConfiguration {

    @Bean
    DraftService draftService(DraftStore store) {
        return new DraftService(store);
    }

    @Bean
    StaticSchemaService staticSchemaService(DraftStore drafts, StaticSchemaStore statics) {
        return new StaticSchemaService(drafts, statics);
    }

    @Bean
    JsonMapperBuilderCustomizer strictRequestJson() {
        return builder -> builder
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
