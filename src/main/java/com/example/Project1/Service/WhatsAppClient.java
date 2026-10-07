package com.example.Project1.Service;

import com.example.Project1.Model.DTO.SendMessageRequest;
import com.example.Project1.config.WhatsAppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
}
