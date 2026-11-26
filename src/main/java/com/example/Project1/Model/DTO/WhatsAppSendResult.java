package com.example.Project1.Model.DTO;

/**
 * Result of an outbound WhatsApp API call.
 *
 * @param success           true when Meta accepted the message (or local simulation is active)
 * @param providerMessageId Meta message id used to correlate template button replies
 * @param error             failure detail when success is false
 */
public record WhatsAppSendResult(boolean success, String providerMessageId, String error) {

    public static WhatsAppSendResult sent(String providerMessageId) {
        return new WhatsAppSendResult(true, providerMessageId, null);
    }

    public static WhatsAppSendResult simulated() {
        return new WhatsAppSendResult(true, null, null);
    }

    public static WhatsAppSendResult failed(String error) {
        return new WhatsAppSendResult(false, null, error);
    }
}
