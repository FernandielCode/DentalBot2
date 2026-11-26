package com.example.Project1.Model.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Minimal representation of Meta's message-send response.
 * The provider message id is needed to correlate template quick-reply taps.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SendMessageResponse(
        List<Message> messages
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String id) {}
}
