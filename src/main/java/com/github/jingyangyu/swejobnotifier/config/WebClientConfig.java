package com.github.jingyangyu.swejobnotifier.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/** Provides a shared {@link WebClient.Builder} bean for HTTP API calls. */
@Configuration
public class WebClientConfig {

    private static final int MAX_BUFFER_SIZE = 10 * 1024 * 1024; // 10 MB

    @Bean
    public WebClient.Builder webClientBuilder() {
        ExchangeStrategies strategies =
                ExchangeStrategies.builder()
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(MAX_BUFFER_SIZE))
                        .build();
        return WebClient.builder().exchangeStrategies(strategies);
    }
}
