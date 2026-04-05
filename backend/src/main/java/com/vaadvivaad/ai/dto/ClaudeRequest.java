// ai/dto/ClaudeRequest.java

package com.vaadvivaad.ai.dto;

import java.util.List;

/*
 * These records map exactly to the Claude API JSON structure.
 * Jackson serializes them automatically.
 *
 * WHY nested records instead of a flat DTO?
 * Because the Claude API uses nested JSON:
 * { "messages": [ { "role": "user", "content": "..." } ] }
 *
 * A flat DTO would require custom Jackson serialization.
 * Nested records serialize naturally to nested JSON.
 */
public record ClaudeRequest(
        String model,
        int max_tokens,
        List<Message> messages
) {
    public record Message(
            String role,
            String content
    ) {}
}