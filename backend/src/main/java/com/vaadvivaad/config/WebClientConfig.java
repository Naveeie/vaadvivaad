// config/WebClientConfig.java
package com.vaadvivaad.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    /*
     * WHY these specific timeouts?
     *
     * eCourts is a government portal — it is SLOW.
     * connectTimeout: 10s — if server doesn't respond to TCP handshake in 10s,
     *                       it's likely down. Don't wait forever.
     * readTimeout:    30s — page can take 20+ seconds to render on their end.
     *                       30s gives buffer without hanging a thread indefinitely.
     *
     * These are NOT the same thing:
     * - Connect timeout = TCP handshake
     * - Read timeout = waiting for response bytes after connection established
     */
    @Bean(name = "eCourtWebClient")
    public WebClient eCourtWebClient() {

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(30))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS)));

        return WebClient.builder()
                .baseUrl("https://services.ecourts.gov.in")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                /*
                 * WHY these headers?
                 * eCourts checks the User-Agent header. Requests without a
                 * browser-like UA are blocked or return empty responses.
                 * This is the most common reason scraping "doesn't work"
                 * for beginners — they send the default WebClient UA.
                 */
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36")
                .defaultHeader(HttpHeaders.ACCEPT,
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-IN,en;q=0.9,hi;q=0.8")
                .defaultHeader("Accept-Encoding", "gzip, deflate, br")
                /*
                 * Referer header matters — eCourts validates that requests
                 * appear to come from their own portal
                 */
                .defaultHeader(HttpHeaders.REFERER,
                        "https://services.ecourts.gov.in/ecourtindia_v6/")
                .filter(logRequest())
                .filter(logResponse())
                .build();
    }

    /*
     * WHY logging filters here instead of just using application logs?
     *
     * WebClient requests happen on Netty I/O threads. Standard @Slf4j
     * logging in service classes won't capture the actual HTTP-level
     * details (status codes, headers). Filter functions run in the
     * reactive pipeline and have access to the actual exchange.
     *
     * For scraping specifically, knowing EXACTLY what was sent and
     * received is critical for debugging.
     */
    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            log.debug("Scraper → [{}] {} | Headers: {}",
                    request.method(), request.url(), request.headers());
            return Mono.just(request);
        });
    }

    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            log.debug("Scraper ← Status: {} | Headers: {}",
                    response.statusCode(), response.headers().asHttpHeaders());
            return Mono.just(response);
        });
    }
    
    @Value("${claude.api-key}")
    private String claudeApiKey;

    @Value("${claude.base-url}")
    private String claudeBaseUrl;

    @Value("${claude.timeout-seconds:30}")
    private int claudeTimeoutSeconds;

    /*
     * WHY a separate WebClient bean for Claude?
     *
     * Claude and eCourts have completely different:
     * - Base URLs
     * - Auth headers (API key vs. session cookies)
     * - Timeout requirements (Claude is faster, more reliable)
     * - Content types (JSON vs. form-urlencoded)
     *
     * Sharing one WebClient would mean configuring it with no base URL
     * and passing full URLs everywhere — losing the benefit of base URL
     * configuration. Separate beans, separate concerns.
     */
    @Bean(name = "claudeWebClient")
    public WebClient claudeWebClient() {

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(claudeTimeoutSeconds));

        return WebClient.builder()
                .baseUrl(claudeBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE)
                /*
                 * Claude uses x-api-key header for authentication.
                 * NOT Bearer token — this is a common mistake.
                 * Sending "Authorization: Bearer {key}" returns 401.
                 */
                .defaultHeader("x-api-key", claudeApiKey)
                /*
                 * Anthropic requires this header on every request.
                 * Missing it returns 400 Bad Request with a clear error,
                 * but it's easy to miss in initial setup.
                 */
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }
}