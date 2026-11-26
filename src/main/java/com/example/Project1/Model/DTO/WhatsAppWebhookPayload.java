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
            InteractiveReply interactive,
            ButtonMessage button,
            Context context
    ) {
        /**
         * Backwards-compatible constructor for existing tests and callers.
         */
        public IncomingMessage(String from,
                               String id,
                               String timestamp,
                               String type,
                               TextMessage text,
                               InteractiveReply interactive) {
            this(from, id, timestamp, type, text, interactive, null, null);
        }

        /**
         * Convenience helper to extract the textual content of the message,
         * whether it was sent as plain text, an interactive reply, or a template
         * quick-reply button.
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
            if (button != null) {
                if (button.payload() != null && !button.payload().isBlank()) {
                    return button.payload().trim();
                }
                if (button.text() != null && !button.text().isBlank()) {
                    return button.text().trim();
                }
            }
            return "";
        }

        /**
         * Returns the unique ID of the selected interactive option (e.g. "TRAT_1"),
         * or the template quick-reply payload when present.
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
            if (button != null) {
                return button.payload();
            }
            return null;
        }

        /**
         * Message id of the message the user replied to. For template quick replies
         * this is the id of the reminder message we sent.
         */
        public String getContextMessageId() {
            return context != null ? context.id() : null;
        }
    }

    /**
     * Payload for a reply to a template quick-reply button.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ButtonMessage(
            String payload,
            String text
    ) {}

    /**
     * Reply context returned by Meta when a user replies to a specific message.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Context(
            String id
    ) {}

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
