package com.example.Project1.Service;

import com.example.Project1.Config.WhatsAppProperties;
import com.example.Project1.Model.DTO.SendInteractiveButtonRequest;
import com.example.Project1.Model.DTO.SendInteractiveListRequest;
import com.example.Project1.Model.DTO.SendMessageRequest;
import com.example.Project1.Model.DTO.SendMessageResponse;
import com.example.Project1.Model.DTO.SendTemplateMessageRequest;
import com.example.Project1.Model.DTO.WhatsAppSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * Service client responsible for dispatching HTTP requests to Meta's WhatsApp Cloud API.
 * Handles authentication headers, endpoint URI generation, and payload transmission.
 */
@Component
public class WhatsAppClient {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppClient.class);
    private final WhatsAppProperties properties;
    private final RestClient restClient;

    public WhatsAppClient(WhatsAppProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getApiUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * Sends a plain text message to a WhatsApp user.
     *
     * @param to Destination phone number with country code
     * @param message Text body of the message
     * @return true if successfully delivered to Meta, false otherwise
     */
    public boolean sendTextMessage(String to, String message) {
        if (properties.getPhoneNumberId() == null || properties.getPhoneNumberId().isBlank()
                || properties.getAccessToken() == null || properties.getAccessToken().isBlank()) {
            log.warn("[LOCAL DEV SIMULATION] WhatsApp credentials not fully set. Outgoing to {}: {}", to, message);
            return true;
        }

        try {
            SendMessageRequest request = SendMessageRequest.createTextMessage(to, message);
            String uri = "/" + properties.getPhoneNumberId() + "/messages";

            String response = restClient.post()
                    .uri(uri)
                    .header("Authorization", "Bearer " + properties.getAccessToken())
                    .body(request)
                    .retrieve()
                    .body(String.class);

            log.info("Sent WhatsApp message to {}. Response: {}", to, response);
            return true;
        } catch (Exception e) {
            log.error("Failed to send WhatsApp message to {}: {}", to, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Sends an Interactive List Menu message to a WhatsApp user.
     *
     * @param to Destination phone number with country code
     * @param bodyText Prompt message displayed inside the chat balloon
     * @param buttonText Label displayed on the menu trigger button (max 20 characters)
     * @param rows List of selectable options (max 10 rows per menu)
     * @return true if successfully sent to Meta, false otherwise
     */
    public boolean sendInteractiveList(String to, String bodyText, String buttonText, List<SendInteractiveListRequest.Row> rows) {
        if (properties.getPhoneNumberId() == null || properties.getPhoneNumberId().isBlank()
                || properties.getAccessToken() == null || properties.getAccessToken().isBlank()) {
            log.warn("[LOCAL DEV SIMULATION] WhatsApp credentials not fully set. Outgoing interactive list to {}: {}", to, bodyText);
            return true;
        }

        try {
            SendInteractiveListRequest request = SendInteractiveListRequest.create(to, bodyText, buttonText, rows);
            String uri = "/" + properties.getPhoneNumberId() + "/messages";

            String response = restClient.post()
                    .uri(uri)
                    .header("Authorization", "Bearer " + properties.getAccessToken())
                    .body(request)
                    .retrieve()
                    .body(String.class);

            log.info("Sent WhatsApp interactive list to {}. Response: {}", to, response);
            return true;
        } catch (Exception e) {
            log.error("Failed to send WhatsApp interactive list to {}: {}", to, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Sends an interactive reply button message to a WhatsApp user.
     *
     * @param to Destination phone number with country code
     * @param bodyText Message text displayed in the chat balloon
     * @param buttonText Text written on the action button (e.g., "Ver más citas")
     * @param buttonId Unique action identifier (e.g., "CITAS_PAGE_1")
     * @return true if successfully sent to Meta, false otherwise
     */
    public boolean sendInteractiveButton(String to, String bodyText, String buttonText, String buttonId) {
        return sendInteractiveButtons(to, bodyText,
                List.of(SendInteractiveButtonRequest.replyButton(buttonId, buttonText)));
    }

    public boolean sendInteractiveButtons(String to, String bodyText,
                                          List<SendInteractiveButtonRequest.Button> buttons) {
        return sendInteractiveButtonsWithResult(to, bodyText, buttons).success();
    }

    /**
     * Sends interactive buttons and returns Meta's message id.
     * The message id is needed for template/quick-reply correlation.
     */
    public WhatsAppSendResult sendInteractiveButtonsWithResult(
            String to,
            String bodyText,
            List<SendInteractiveButtonRequest.Button> buttons) {
        if (credentialsMissing()) {
            log.warn("[LOCAL DEV SIMULATION] Buttons to {}: {} [Buttons: {}]", to, bodyText, buttons);
            return WhatsAppSendResult.simulated();
        }

        try {
            SendInteractiveButtonRequest request =
                    SendInteractiveButtonRequest.createMultipleButtons(to, bodyText, buttons);
            String uri = "/" + properties.getPhoneNumberId() + "/messages";

            SendMessageResponse response = restClient.post()
                    .uri(uri)
                    .header("Authorization", "Bearer " + properties.getAccessToken())
                    .body(request)
                    .retrieve()
                    .body(SendMessageResponse.class);

            String providerMessageId = extractProviderMessageId(response);
            log.info("Sent WhatsApp interactive buttons to {}. Provider message id: {}",
                    to, providerMessageId);
            return WhatsAppSendResult.sent(providerMessageId);
        } catch (Exception e) {
            log.error("Failed to send WhatsApp interactive buttons to {}: {}", to, e.getMessage(), e);
            return WhatsAppSendResult.failed(e.getMessage());
        }
    }

    /**
     * Sends a pre-approved template message. The template defines its body and
     * quick-reply buttons; this method supplies only the body parameters.
     */
    public WhatsAppSendResult sendTemplateMessage(String to,
                                                  String templateName,
                                                  String languageCode,
                                                  List<String> bodyParameters) {
        if (credentialsMissing()) {
            log.warn("[LOCAL DEV SIMULATION] Template to {}: {} {} {}",
                    to, templateName, languageCode, bodyParameters);
            return WhatsAppSendResult.simulated();
        }

        try {
            SendTemplateMessageRequest request = SendTemplateMessageRequest.create(
                    to, templateName, languageCode, bodyParameters);
            String uri = "/" + properties.getPhoneNumberId() + "/messages";

            SendMessageResponse response = restClient.post()
                    .uri(uri)
                    .header("Authorization", "Bearer " + properties.getAccessToken())
                    .body(request)
                    .retrieve()
                    .body(SendMessageResponse.class);

            String providerMessageId = extractProviderMessageId(response);
            log.info("Sent WhatsApp template {} to {}. Provider message id: {}",
                    templateName, to, providerMessageId);
            return WhatsAppSendResult.sent(providerMessageId);
        } catch (Exception e) {
            log.error("Failed to send WhatsApp template {} to {}: {}",
                    templateName, to, e.getMessage(), e);
            return WhatsAppSendResult.failed(e.getMessage());
        }
    }

    /**
     * Local-development reminder fallback. Production should use an approved template.
     */
    public WhatsAppSendResult sendReminderButtons(String to, String bodyText, UUID reminderId) {
        return sendInteractiveButtonsWithResult(to, bodyText, List.of(
                SendInteractiveButtonRequest.replyButton(
                        ReminderButtonIds.CONFIRM_PREFIX + reminderId, "Confirmar"),
                SendInteractiveButtonRequest.replyButton(
                        ReminderButtonIds.CANCEL_PREFIX + reminderId, "Cancelar")));
    }

    private boolean credentialsMissing() {
        return properties.getPhoneNumberId() == null
                || properties.getPhoneNumberId().isBlank()
                || properties.getAccessToken() == null
                || properties.getAccessToken().isBlank();
    }

    private String extractProviderMessageId(SendMessageResponse response) {
        if (response == null || response.messages() == null || response.messages().isEmpty()) {
            return null;
        }
        return response.messages().get(0).id();
    }
}
