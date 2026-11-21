package com.example.Project1.Service;

import com.example.Project1.Config.WhatsAppProperties;
import com.example.Project1.Model.DTO.SendInteractiveButtonRequest;
import com.example.Project1.Model.DTO.SendInteractiveListRequest;
import com.example.Project1.Model.DTO.SendMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

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
     * @param to Destination phone number with country code (e.g., 593983673346)
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
     * @param to Destination phone number with country code (e.g., 593983673346)
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
     * @param to Destination phone number with country code (e.g., 593983673346)
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
        if (properties.getPhoneNumberId() == null || properties.getPhoneNumberId().isBlank()
                || properties.getAccessToken() == null || properties.getAccessToken().isBlank()) {
            log.warn("[LOCAL DEV SIMULATION] Buttons to {}: {} [Buttons: {}]", to, bodyText, buttons);
            return true;
        }

        try {
            SendInteractiveButtonRequest request =
                    SendInteractiveButtonRequest.createMultipleButtons(to, bodyText, buttons);
            String uri = "/" + properties.getPhoneNumberId() + "/messages";

            String response = restClient.post()
                    .uri(uri)
                    .header("Authorization", "Bearer " + properties.getAccessToken())
                    .body(request)
                    .retrieve()
                    .body(String.class);

            log.info("Sent WhatsApp interactive buttons to {}. Response: {}", to, response);
            return true;
        } catch (Exception e) {
            log.error("Failed to send WhatsApp interactive buttons to {}: {}", to, e.getMessage(), e);
            return false;
        }
    }
}
