package com.example.Project1.Service;

import com.example.Project1.Config.WhatsAppProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatsAppSignatureVerifierTest {

    private static final String BODY = "{\"object\":\"whatsapp_business_account\"}";
    private static final String SECRET = "test-secret";
    private static final String VALID_SIGNATURE =
            "sha256=60b2e09855b25cf88d59953a4025f83bc48cd03865abe988e974b754c7a285e8";

    private final WhatsAppProperties properties = new WhatsAppProperties();
    private final WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier(properties);

    @Test
    void acceptsValidSignature() {
        properties.setAppSecret(SECRET);

        assertTrue(verifier.isValid(BODY.getBytes(StandardCharsets.UTF_8), VALID_SIGNATURE));
    }

    @Test
    void acceptsUppercaseHex() {
        properties.setAppSecret(SECRET);

        assertTrue(verifier.isValid(
                BODY.getBytes(StandardCharsets.UTF_8),
                VALID_SIGNATURE.toUpperCase()));
    }

    @Test
    void rejectsInvalidSignature() {
        properties.setAppSecret(SECRET);

        assertFalse(verifier.isValid(
                BODY.getBytes(StandardCharsets.UTF_8),
                "sha256=deadbeef"));
    }

    @Test
    void rejectsMissingHeader() {
        properties.setAppSecret(SECRET);

        assertFalse(verifier.isValid(BODY.getBytes(StandardCharsets.UTF_8), null));
    }

    @Test
    void rejectsWrongPrefix() {
        properties.setAppSecret(SECRET);

        assertFalse(verifier.isValid(BODY.getBytes(StandardCharsets.UTF_8), "md5=deadbeef"));
    }

    @Test
    void rejectsWhenSecretIsMissing() {
        properties.setAppSecret(null);

        assertFalse(verifier.isValid(BODY.getBytes(StandardCharsets.UTF_8), VALID_SIGNATURE));
    }

    @Test
    void honorsSignatureVerificationToggle() {
        assertTrue(verifier.isEnabled());

        properties.setSignatureVerificationEnabled(false);

        assertFalse(verifier.isEnabled());
    }
}
