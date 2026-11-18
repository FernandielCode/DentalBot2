package com.example.Project1.Model.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Data Transfer Object (DTO) for Meta WhatsApp Cloud API Interactive Button Messages.
 *
 * WhatsApp Interactive Button messages display a text balloon accompanied by up to
 * three quick-reply action buttons below the text.
 *
 * @see <a href="https://developers.facebook.com/docs/whatsapp/cloud-api/messages/interactive-reply-buttons-messages">Meta Interactive Reply Buttons</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SendInteractiveButtonRequest(
        @JsonProperty("messaging_product") String messagingProduct,
        @JsonProperty("recipient_type") String recipientType,
        String to,
        String type,
        Interactive interactive
) {
    /**
     * Factory helper to build a single-button interactive message.
     *
     * @param to Destination phone number with country code
     * @param bodyText Message text displayed in the chat balloon
     * @param buttonText Text written on the button (max 20 characters)
     * @param buttonId Unique action identifier returned in webhook upon tap (max 256 characters)
     */
    public static SendInteractiveButtonRequest createSingleButton(
            String to,
            String bodyText,
            String buttonText,
            String buttonId
    ) {
        return new SendInteractiveButtonRequest(
                "whatsapp",
                "individual",
                to,
                "interactive",
                new Interactive(
                        "button",
                        new Body(bodyText),
                        new Action(List.of(
                                new Button("reply", new Reply(buttonId, buttonText))
                        ))
                )
        );
    }
    public static SendInteractiveButtonRequest createMultipleButtons(
            String to,
            String bodyText,
            List<Button> buttons
    ) {
        return new SendInteractiveButtonRequest(
                "whatsapp",
                "individual",
                to,
                "interactive",
                new Interactive(
                        "button",
                        new Body(bodyText),
                        new Action(buttons)
                )
        );
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Interactive(
            String type,
            Body body,
            Action action
    ) {}

    public record Body(
            String text
    ) {}

    public record Action(
            List<Button> buttons
    ) {}

    public record Button(
            String type,
            Reply reply
    ) {}

    public record Reply(
            String id,
            String title
    ) {}
}
