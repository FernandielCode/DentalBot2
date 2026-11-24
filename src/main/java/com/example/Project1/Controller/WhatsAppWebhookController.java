package com.example.Project1.Controller;

import com.example.Project1.Config.WhatsAppProperties;
import com.example.Project1.Model.DTO.WhatsAppWebhookPayload;
import com.example.Project1.Service.WhatsAppBotService;
import com.example.Project1.Service.WhatsAppSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

@RestController
@RequestMapping("/api/whatsapp/webhook")
public class WhatsAppWebhookController {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final WhatsAppProperties properties;
    private final WhatsAppBotService botService;
    private final WhatsAppSignatureVerifier signatureVerifier;
    private final JsonMapper jsonMapper;

    public WhatsAppWebhookController(WhatsAppProperties properties,
                                     WhatsAppBotService botService,
                                     WhatsAppSignatureVerifier signatureVerifier,
                                     JsonMapper jsonMapper) {
        this.properties = properties;
        this.botService = botService;
        this.signatureVerifier = signatureVerifier;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Webhook verification endpoint used by Meta Developers Portal when registering webhook.
     */
    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {

        log.info("Received Meta webhook verification request: mode={}", mode);

        if ("subscribe".equals(mode) && Objects.equals(properties.getVerifyToken(), token)) {
            log.info("Webhook verification succeeded!");
            return ResponseEntity.ok(challenge);
        }

        log.warn("Webhook verification failed: token mismatch");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden: Token mismatch");
    }

    /**
     * Webhook event receiver endpoint for incoming messages and status updates from Meta.
     */
    @PostMapping
    public ResponseEntity<String> receiveWebhookEvent(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {

        // Validate the HMAC over the exact raw body before parsing or persistence.
        if (signatureVerifier.isEnabled() && !signatureVerifier.isValid(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        WhatsAppWebhookPayload payload;
        try {
            payload = jsonMapper.readValue(rawBody, WhatsAppWebhookPayload.class);
        } catch (JacksonException e) {
            log.warn("Invalid WhatsApp webhook JSON: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid payload");
        }

        try {
            botService.processWebhookEvent(payload);
        } catch (Exception e) {
            // Keep 200 for authenticated events so Meta does not retry endlessly.
            log.error("Error processing incoming WhatsApp webhook: {}", e.getMessage(), e);
        }
        return ResponseEntity.ok("EVENT_RECEIVED");
    }
}
