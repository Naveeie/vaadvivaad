// ai/client/ClaudeClient.java

package com.vaadvivaad.ai.client;

import com.vaadvivaad.ai.dto.ClaudeRequest;
import com.vaadvivaad.ai.dto.ClaudeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;

@Component
public class ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);
    private static final String MESSAGES_PATH = "/v1/messages";

    private final WebClient claudeWebClient;

    @Value("${claude.model}")
    private String model;

    @Value("${claude.max-tokens}")
    private int maxTokens;

    @Value("${claude.timeout-seconds:30}")
    private int timeoutSeconds;

    public ClaudeClient(@Qualifier("claudeWebClient") WebClient claudeWebClient) {
        this.claudeWebClient = claudeWebClient;
    }

    /*
     * Single public method — given a prompt, return the text response.
     *
     * WHY accept a String prompt instead of hearing details?
     *
     * The client should not know about hearings, cases, or Hindi.
     * Those are domain concerns. The client's job is:
     *   "call Claude with this prompt, return the text"
     *
     * Prompt construction belongs in SummaryService.
     * This makes ClaudeClient reusable for any future AI feature
     * (case summaries, lawyer recommendations, etc.)
     *
     * INTERVIEW: "How do you keep AI integration maintainable?"
     * "I wrap the AI client behind a thin adapter that accepts and
     * returns strings. Domain logic builds the prompt. The client
     * knows nothing about the domain."
     */
    public String complete(String prompt) {
        log.debug("Calling Claude API — model: {}, prompt length: {} chars",
                model, prompt.length());

        ClaudeRequest request = new ClaudeRequest(
                model,
                maxTokens,
                List.of(new ClaudeRequest.Message("user", prompt))
        );

        try {
            ClaudeResponse response = claudeWebClient.post()
                    .uri(MESSAGES_PATH)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(ClaudeResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (response == null || response.extractText() == null) {
                log.warn("Claude returned null or empty response");
                return null;
            }

            String text = response.extractText();
            log.debug("Claude response received — {} chars", text.length());
            return text;

        } catch (WebClientResponseException e) {
            /*
             * WHY log the response body on error?
             *
             * Claude returns detailed error messages in the body:
             * {"type":"error","error":{"type":"authentication_error","message":"..."}}
             *
             * Without logging the body, a 401 just looks like "HTTP 401"
             * and you spend 30 minutes wondering if it's the key, the header
             * name, or the header value. Log the body, solve in 30 seconds.
             */
            log.error("Claude API error {} — body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return null;

        } catch (Exception e) {
            log.error("Unexpected error calling Claude: {}", e.getMessage(), e);
            return null;
        }
    }
}