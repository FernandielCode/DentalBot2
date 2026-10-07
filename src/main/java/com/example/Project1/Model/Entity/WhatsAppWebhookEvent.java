package com.example.Project1.Model.Entity;

import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.UUID;

@Entity(name = "WhatsAppWebhookEvent")
@Table(name = "whatsapp_webhook_events")
public class WhatsAppWebhookEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID eventId;

    @ManyToOne
    @JoinColumn(name = "whatsapp_contact_id")
    private WhatsAppContact whatsAppContact;

    @Column(unique = true)
    private String eventKey;
    @Column(nullable = false)
    private String eventType;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJSON;
    @Column(nullable = false)
    private String processingStatus;
    @Column(nullable = false)
    private Timestamp recievedAt;
    private Timestamp processedAt;

    public WhatsAppWebhookEvent() {
    }

    public WhatsAppWebhookEvent(UUID eventId, WhatsAppContact whatsAppContact, String eventKey, String eventType,
                                String payloadJSON, String processingStatus, Timestamp recievedAt, Timestamp processedAt) {
        this.eventId = eventId;
        this.whatsAppContact = whatsAppContact;
        this.eventKey = eventKey;
        this.eventType = eventType;
        this.payloadJSON = payloadJSON;
        this.processingStatus = processingStatus;
        this.recievedAt = recievedAt;
        this.processedAt = processedAt;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public WhatsAppContact getWhatsAppContact() {
        return whatsAppContact;
    }

    public void setWhatsAppContact(WhatsAppContact whatsAppContact) {
        this.whatsAppContact = whatsAppContact;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayloadJSON() {
        return payloadJSON;
    }

    public void setPayloadJSON(String payloadJSON) {
        this.payloadJSON = payloadJSON;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public Timestamp getRecievedAt() {
        return recievedAt;
    }

    public void setRecievedAt(Timestamp recievedAt) {
        this.recievedAt = recievedAt;
    }

    public Timestamp getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Timestamp processedAt) {
        this.processedAt = processedAt;
    }
}
