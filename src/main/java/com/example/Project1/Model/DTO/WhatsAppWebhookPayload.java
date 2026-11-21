package com.example.Project1.Model.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Data Transfer Object (DTO) mapping Meta's incoming WhatsApp Cloud API webhook JSON payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WhatsAppWebhookPayload(
        String object,
        List<Entry> entry
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            String id,
            List<Change> changes
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(
            String field,
            Value value
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Value(
            @JsonProperty("messaging_product") String messagingProduct,
            Metadata metadata,
            List<Contact> contacts,
            List<IncomingMessage> messages
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(
            @JsonProperty("display_phone_number") String displayPhoneNumber,
            @JsonProperty("phone_number_id") String phoneNumberId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contact(
            Profile profile,
            @JsonProperty("wa_id") String waId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(
            String name
    ) {}

    /**
     * Represents an incoming message. Supports standard text messages as well as
     * interactive button/list selections sent by users.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IncomingMessage(
            String from,
            String id,
            String timestamp,
            String type,
            TextMessage text,
            InteractiveReply interactive
    ) {
        /**
         * Convenience helper to extract the textual content of the message,
         * whether it was sent as plain text or selected from an interactive list/button menu.
         */
        public String getExtractedText() {
            if (text != null && text.body() != null && !text.body().isBlank()) {
                return text.body().trim();
            }
            if (interactive != null) {
                if (interactive.listReply() != null && interactive.listReply().title() != null) {
                    return interactive.listReply().title().trim();
                }
                if (interactive.buttonReply() != null && interactive.buttonReply().title() != null) {
                    return interactive.buttonReply().title().trim();
                }
            }
            return "";
        }

        /**
         * Returns the unique ID of the selected interactive option (e.g. "TRAT_1"), if applicable.
         */
        public String getInteractiveSelectionId() {
            if (interactive != null) {
                if (interactive.listReply() != null) {
                    return interactive.listReply().id();
                }
                if (interactive.buttonReply() != null) {
                    return interactive.buttonReply().id();
                }
            }
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TextMessage(
            String body
    ) {}

    /**
     * Payload returned when a user taps a button or row in an interactive message.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InteractiveReply(
            String type,
            @JsonProperty("list_reply") Selection listReply,
            @JsonProperty("button_reply") Selection buttonReply
    ) {}

    /**
     * Selected item details from a list or button click.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Selection(
            String id,
            String title,
            String description
    ) {}
}
