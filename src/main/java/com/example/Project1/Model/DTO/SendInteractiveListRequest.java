package com.example.Project1.Model.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Data Transfer Object (DTO) for Meta WhatsApp Cloud API Interactive List Messages.
 *
 * WhatsApp Interactive List messages display a message with a menu button that
 * opens a native bottom-sheet containing sections and selectable rows (up to 10).
 *
 * @see <a href="https://developers.facebook.com/docs/whatsapp/cloud-api/messages/interactive-list-messages">Meta Interactive List Messages</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SendInteractiveListRequest(
        @JsonProperty("messaging_product") String messagingProduct,
        @JsonProperty("recipient_type") String recipientType,
        String to,
        String type,
        Interactive interactive
) {

    /**
     * Factory helper to build a single-section Interactive List Message with sensible defaults.
     *
     * @param to Recipient phone number with country code (e.g., "593983673346")
     * @param bodyText Prompt text shown inside the chat balloon
     * @param buttonText Text written on the button that triggers the menu (max 20 characters)
     * @param rows List of selectable options (max 10 rows per list)
     * @return Formatted SendInteractiveListRequest ready for JSON serialization
     */
    public static SendInteractiveListRequest create(
            String to,
            String bodyText,
            String buttonText,
            List<Row> rows
    ) {
        return new SendInteractiveListRequest(
                "whatsapp",
                "individual",
                to,
                "interactive",
                new Interactive(
                        "list",
                        new Body(bodyText),
                        new Action(buttonText, List.of(new Section("Opciones", rows)))
                )
        );
    }

    /**
     * Inner container defining the interactive payload structure.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Interactive(
            String type,
            Body body,
            Action action
    ) {}

    /**
     * Main message text displayed to the user.
     */
    public record Body(
            String text
    ) {}

    /**
     * Action element containing the menu trigger button and sections.
     */
    public record Action(
            String button,
            List<Section> sections
    ) {}

    /**
     * Section element grouping rows under a section header.
     */
    public record Section(
            String title,
            List<Row> rows
    ) {}

    /**
     * An individual selectable row in the menu.
     *
     * @param id Unique identifier returned in the webhook when selected (max 200 chars)
     * @param title Title of the item (max 24 characters)
     * @param description Optional description or price shown below the title (max 72 characters)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(
            String id,
            String title,
            String description
    ) {}
}
