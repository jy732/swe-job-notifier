package com.github.jingyangyu.swejobnotifier.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** Provides a shared {@link WebClient.Builder} bean for HTTP API calls. */
@Configuration
public class WebClientConfig {

    private static final int MAX_BUFFER_SIZE = 32 * 1024 * 1024; // 32 MB
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_S = 30;
    private static final int RESPONSE_TIMEOUT_S = 30;

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient =
                HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                        .responseTimeout(Duration.ofSeconds(RESPONSE_TIMEOUT_S))
                        .doOnConnected(
                                conn ->
                                        conn.addHandlerLast(
                                                        new ReadTimeoutHandler(
                                                                READ_TIMEOUT_S, TimeUnit.SECONDS))
                                                .addHandlerLast(
                                                        new WriteTimeoutHandler(
                                                                READ_TIMEOUT_S, TimeUnit.SECONDS)));

        ExchangeStrategies strategies =
                ExchangeStrategies.builder()
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(MAX_BUFFER_SIZE))
                        .build();
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies);
    }
}
