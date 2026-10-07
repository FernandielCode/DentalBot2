package com.example.Project1.Controller;

import com.example.Project1.Model.DTO.WhatsAppWebhookPayload;
import com.example.Project1.Service.WhatsAppBotService;
import com.example.Project1.config.WhatsAppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/whatsapp/webhook")
public class WhatsAppWebhookController {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final WhatsAppProperties properties;
    private final WhatsAppBotService botService;

    public WhatsAppWebhookController(WhatsAppProperties properties, WhatsAppBotService botService) {
        this.properties = properties;
        this.botService = botService;
    }

    /**
     * Webhook verification endpoint used by Meta Developers Portal when registering webhook.
     */
    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {

        log.info("Received Meta webhook verification request: mode={}, token={}", mode, token);

        if ("subscribe".equals(mode) && properties.getVerifyToken().equals(token)) {
            log.info("Webhook verification succeeded!");
            return ResponseEntity.ok(challenge);
        } else {
            log.warn("Webhook verification failed! Provided token: {}, Expected: {}", token, properties.getVerifyToken());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden: Token mismatch");
        }
    }

    /**
     * Webhook event receiver endpoint for incoming messages and status updates from Meta.
     */
    @PostMapping
    public ResponseEntity<String> receiveWebhookEvent(@RequestBody WhatsAppWebhookPayload payload) {
        try {
            botService.processWebhookEvent(payload);
        } catch (Exception e) {
            log.error("Error processing incoming WhatsApp webhook: {}", e.getMessage(), e);
        }
        // Always acknowledge Meta with 200 OK
        return ResponseEntity.ok("EVENT_RECEIVED");
    }
}
