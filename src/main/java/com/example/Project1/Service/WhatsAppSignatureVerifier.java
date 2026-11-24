package com.example.Project1.Service;

import com.example.Project1.Config.WhatsAppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

@Component
public class WhatsAppSignatureVerifier {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppSignatureVerifier.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final WhatsAppProperties properties;

    public WhatsAppSignatureVerifier(WhatsAppProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isSignatureVerificationEnabled();
    }

    public boolean isValid(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("Missing X-Hub-Signature-256 header");
            return false;
        }

        signatureHeader = signatureHeader.trim();

        if (!signatureHeader.regionMatches(true, 0, SIGNATURE_PREFIX, 0, SIGNATURE_PREFIX.length())) {
            log.warn("Invalid X-Hub-Signature-256 format");
            return false;
        }

        String appSecret = properties.getAppSecret();
        if (appSecret == null || appSecret.isBlank()) {
            log.error("WhatsApp app secret is not configured; cannot verify webhook signature");
            return false;
        }

        String expectedHex = hmacSha256Hex(appSecret, rawBody);
        String providedHex = signatureHeader.substring(SIGNATURE_PREFIX.length())
                .toLowerCase(Locale.ROOT);

        boolean valid = MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.US_ASCII),
                providedHex.getBytes(StandardCharsets.US_ASCII));

        if (!valid) {
            log.warn("WhatsApp webhook signature mismatch");
        }
        return valid;
    }

    private String hmacSha256Hex(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to compute WhatsApp webhook signature", e);
        }
    }
}
