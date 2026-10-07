package com.example.Project1.Model.DTO;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SendMessageRequest(
        @JsonProperty("messaging_product") String messagingProduct,
        @JsonProperty("recipient_type") String recipientType,
        String to,
        String type,
        TextMessage text
) {
    public static SendMessageRequest createTextMessage(String to, String text) {
        return new SendMessageRequest(
                "whatsapp",
                "individual",
                to,
                "text",
                new TextMessage(false, text)
        );
    }

    public record TextMessage(
            @JsonProperty("preview_url") boolean previewUrl,
            String body
    ) {}
}
