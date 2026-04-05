// ai/dto/ClaudeResponse.java

package com.vaadvivaad.ai.dto;

import java.util.List;

/*
 * WHY only map the fields we actually use?
 *
 * Claude returns many fields: id, type, role, model, stop_reason,
 * stop_sequence, usage, etc. We only need content[0].text.
 *
 * Jackson's default behavior ignores unknown fields if you add
 * @JsonIgnoreProperties(ignoreUnknown = true).
 * Without it, any new field Claude adds to their response
 * would cause a deserialization exception.
 *
 * Always add ignoreUnknown = true when consuming external APIs.
 * You do not control their response schema.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record ClaudeResponse(
        List<ContentBlock> content
) {
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ContentBlock(
            String type,
            String text
    ) {}

    /*
     * Convenience method — callers want the text, not the structure.
     * This hides the nested content[0].text navigation from callers.
     */
    public String extractText() {
        if (content == null || content.isEmpty()) {
            return null;
        }
        return content.get(0).text();
    }
}