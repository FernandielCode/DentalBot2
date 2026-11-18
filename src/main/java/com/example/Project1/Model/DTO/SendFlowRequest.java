package com.example.Project1.Model.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Data Transfer Object (DTO) for Meta WhatsApp Cloud API Flow messages.
 *
 * <p>Flow messages are interactive messages of type {@code flow}. The Flow JSON
 * (screens and inputs) lives in WhatsApp Manager / Flows API; this payload only
 * references the published {@code flow_id} and carries the initial screen data.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SendFlowRequest(
        @JsonProperty("messaging_product") String messagingProduct,
        @JsonProperty("recipient_type") String recipientType,
        String to,
        String type,
        Interactive interactive
) {
    public static SendFlowRequest create(
            String to,
            String flowId,
            String flowToken,
            String flowCta,
            String headerText,
            String bodyText,
            String footerText,
            String screenId,
            Map<String, Object> data
    ) {
        // Meta rejects an empty object ("data": {}). When there is no initial data,
        // omit the property entirely instead of sending an empty Map.
        Map<String, Object> effectiveData =
                (data == null || data.isEmpty()) ? null : data;

        return new SendFlowRequest(
                "whatsapp",
                "individual",
                to,
                "interactive",
                new Interactive(
                        "flow",
                        headerText == null ? null : new Text(headerText),
                        new Body(bodyText),
                        footerText == null ? null : new Text(footerText),
                        new Action(
                                "flow",
                                new Parameters(
                                        "3",
                                        flowToken,
                                        flowId,
                                        flowCta,
                                        "navigate",
                                        new FlowActionPayload(screenId, effectiveData)
                                )
                        )
                )
        );
    }

    public record Interactive(
            String type,
            Text header,
            Body body,
            Text footer,
            Action action
    ) {}

    public record Text(
            String type,
            String text
    ) {
        public Text(String text) {
            this("text", text);
        }
    }

    public record Body(
            String text
    ) {}

    public record Action(
            String name,
            Parameters parameters
    ) {}

    public record Parameters(
            @JsonProperty("flow_message_version") String flowMessageVersion,
            @JsonProperty("flow_token") String flowToken,
            @JsonProperty("flow_id") String flowId,
            @JsonProperty("flow_cta") String flowCta,
            @JsonProperty("flow_action") String flowAction,
            @JsonProperty("flow_action_payload") FlowActionPayload flowActionPayload
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FlowActionPayload(
            String screen,
            Map<String, Object> data
    ) {}
}
