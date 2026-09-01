package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.internal.RenderingModule;
import cn.hbads.renderweave.rendering.spi.RendererProfileAuthority;
import cn.hbads.renderweave.rendering.spi.RenderingAuthority;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Explicit local idea-validation assembly; it never publishes a Certified Profile authority. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "renderweave.template.candidate-preview.enabled",
        havingValue = "true")
class CandidatePreviewApplicationConfiguration {

    @Bean
    CandidatePreviewApplication candidatePreviewApplication(
            Evaluator evaluator,
            RendererProcessAdapter engine,
            RenderingAuthority authority,
            @Qualifier("renderingClock") Clock renderingClock
    ) {
        RendererProfileAuthority candidateProfile = output ->
                new RendererProfileAuthority.Available(
                        "renderweave-renderer/1.0",
                        "renderweave-layout/1.0");
        return new CandidatePreviewApplication(RenderingModule.application(
                evaluator,
                engine,
                authority,
                candidateProfile,
                renderingClock));
    }
}
