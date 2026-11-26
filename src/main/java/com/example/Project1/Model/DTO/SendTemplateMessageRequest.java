package com.example.Project1.Model.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Payload for a WhatsApp Cloud API template message.
 *
 * The template itself (including its quick-reply buttons) must be approved in
 * Meta Business Manager. This DTO only supplies the body parameters.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SendTemplateMessageRequest(
        @JsonProperty("messaging_product") String messagingProduct,
        String to,
        String type,
        Template template
) {

    public static SendTemplateMessageRequest create(String to,
                                                    String templateName,
                                                    String languageCode,
                                                    List<String> bodyParameters) {
        List<Component> components = bodyParameters.isEmpty()
                ? List.of()
                : List.of(new Component("body", bodyParameters.stream()
                        .map(value -> new Parameter("text", value))
                        .toList()));

        return new SendTemplateMessageRequest(
                "whatsapp",
                to,
                "template",
                new Template(templateName, new Language(languageCode), components));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Template(
            String name,
            Language language,
            List<Component> components
    ) {}

    public record Language(String code) {}

    public record Component(
            String type,
            List<Parameter> parameters
    ) {}

    public record Parameter(
            String type,
            String text
    ) {}
}
