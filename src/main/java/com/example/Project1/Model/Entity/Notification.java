package com.example.Project1.Model.Entity;

import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.UUID;

@Entity(name = "Notification")
@Table(name = "notifications")
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID notificationId;

    @ManyToOne
    @JoinColumn(name = "appointment_id")
    private Appointment notificationAppointment;

    @Column(nullable = false)
    private String notificationType;

    private String title;
    @Column(columnDefinition = "TEXT")
    private String body;

    private Timestamp sentAt;
    private Timestamp readAt;

    @ManyToOne
    @JoinColumn(name = "whatsapp_contact_id")
    private WhatsAppContact notificationContact;

    public Notification() {
    }

    public Notification(UUID notificationId, Appointment notificationAppointment, String notificationType,
                        String title, String body, Timestamp sentAt, Timestamp readAt, WhatsAppContact notificationContact) {
        this.notificationId = notificationId;
        this.notificationAppointment = notificationAppointment;
        this.notificationType = notificationType;
        this.title = title;
        this.body = body;
        this.sentAt = sentAt;
        this.readAt = readAt;
        this.notificationContact = notificationContact;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(UUID notificationId) {
        this.notificationId = notificationId;
    }

    public Appointment getNotificationAppointment() {
        return notificationAppointment;
    }

    public void setNotificationAppointment(Appointment notificationAppointment) {
        this.notificationAppointment = notificationAppointment;
    }

    public String getNotificationType() {
        return notificationType;
    }

    public void setNotificationType(String notificationType) {
        this.notificationType = notificationType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Timestamp getSentAt() {
        return sentAt;
    }

    public void setSentAt(Timestamp sentAt) {
        this.sentAt = sentAt;
    }

    public Timestamp getReadAt() {
        return readAt;
    }

    public void setReadAt(Timestamp readAt) {
        this.readAt = readAt;
    }

    public WhatsAppContact getNotificationContact() {
        return notificationContact;
    }

    public void setNotificationContact(WhatsAppContact notificationContact) {
        this.notificationContact = notificationContact;
    }
}
