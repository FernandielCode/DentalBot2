package com.example.Project1.Model.Entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Entity(name = "WhatsAppContact")
@Table(name = "whatsapp_contacts")
public class WhatsAppContact {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID contactId;

    @JoinColumn(name = "patient_id")
    @ManyToOne
    private Patient patientContact;

    @Column(unique = true, nullable = false)
    private String waUserId;
    private String displayNumber;
    private String optInStatus;
    private Timestamp lastSeenAt;
    private Timestamp createdAt;

    @JsonIgnore
    @OneToMany(mappedBy = "whatsAppContact")
    private List<WhatsAppSession> whatsAppSessionList;

    @JsonIgnore
    @OneToMany(mappedBy = "whatsAppContact")
    private List<WhatsAppMessage> whatsAppMessageList;

    @JsonIgnore
    @OneToMany(mappedBy = "whatsAppContact")
    private List<WhatsAppWebhookEvent> whatsAppWebhookEventList;

    @JsonIgnore
    @OneToMany(mappedBy = "notificationContact")
    private List<Notification> notificationList;

    public WhatsAppContact() {
    }

    public WhatsAppContact(UUID contactId, Patient patientContact, String waUserId, String displayNumber,
                           String optInStatus, Timestamp lastSeenAt, Timestamp createdAt) {
        this.contactId = contactId;
        this.patientContact = patientContact;
        this.waUserId = waUserId;
        this.displayNumber = displayNumber;
        this.optInStatus = optInStatus;
        this.lastSeenAt = lastSeenAt;
        this.createdAt = createdAt;
    }

    public UUID getContactId() {
        return contactId;
    }

    public void setContactId(UUID contactId) {
        this.contactId = contactId;
    }

    public Patient getPatientContact() {
        return patientContact;
    }

    public void setPatientContact(Patient patientContact) {
        this.patientContact = patientContact;
    }

    public String getWaUserId() {
        return waUserId;
    }

    public void setWaUserId(String waUserId) {
        this.waUserId = waUserId;
    }

    public String getDisplayNumber() {
        return displayNumber;
    }

    public void setDisplayNumber(String displayNumber) {
        this.displayNumber = displayNumber;
    }

    public String getOptInStatus() {
        return optInStatus;
    }

    public void setOptInStatus(String optInStatus) {
        this.optInStatus = optInStatus;
    }

    public Timestamp getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Timestamp lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public List<WhatsAppSession> getWhatsAppSessionList() {
        return whatsAppSessionList;
    }

    public void setWhatsAppSessionList(List<WhatsAppSession> whatsAppSessionList) {
        this.whatsAppSessionList = whatsAppSessionList;
    }

    public List<WhatsAppMessage> getWhatsAppMessageList() {
        return whatsAppMessageList;
    }

    public void setWhatsAppMessageList(List<WhatsAppMessage> whatsAppMessageList) {
        this.whatsAppMessageList = whatsAppMessageList;
    }

    public List<WhatsAppWebhookEvent> getWhatsAppWebhookEventList() {
        return whatsAppWebhookEventList;
    }

    public void setWhatsAppWebhookEventList(List<WhatsAppWebhookEvent> whatsAppWebhookEventList) {
        this.whatsAppWebhookEventList = whatsAppWebhookEventList;
    }

    public List<Notification> getNotificationList() {
        return notificationList;
    }

    public void setNotificationList(List<Notification> notificationList) {
        this.notificationList = notificationList;
    }
}
