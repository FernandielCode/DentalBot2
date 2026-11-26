package com.example.Project1.Model.Entity;

import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Persisted delivery state for one appointment reminder.
 *
 * Keeping reminders in their own table gives us idempotency, retries, and an
 * audit trail without overloading Appointment with delivery concerns.
 */
@Entity(name = "AppointmentReminder")
@Table(
        name = "appointment_reminders",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_appointment_reminder_type",
                columnNames = {"appointment_id", "reminder_type"}))
public class AppointmentReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID reminderId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @Enumerated(EnumType.STRING)
    @Column(name = "reminder_type", nullable = false)
    private ReminderType reminderType;

    @Column(name = "scheduled_at", nullable = false)
    private Timestamp scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReminderStatus status = ReminderStatus.PENDING;

    /**
     * Meta's message id for the sent reminder. Incoming template quick-reply
     * buttons include this id in context.id, which lets us resolve the exact
     * appointment without relying on names or dates.
     */
    @Column(name = "provider_message_id")
    private String providerMessageId;

    private Timestamp sentAt;
    private Timestamp lastAttemptAt;
    private Timestamp nextAttemptAt;
    private int attemptCount;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(nullable = false)
    private Timestamp createdAt;

    @Column(nullable = false)
    private Timestamp updatedAt;

    public AppointmentReminder() {
    }

    @PrePersist
    void onCreate() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (nextAttemptAt == null && status == ReminderStatus.PENDING) {
            nextAttemptAt = scheduledAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }

    public void markSent(String providerMessageId, Timestamp at) {
        this.status = ReminderStatus.SENT;
        this.providerMessageId = providerMessageId;
        this.sentAt = at;
        this.nextAttemptAt = null;
        this.lastError = null;
        this.updatedAt = at;
    }

    public void markFailed(String error, Timestamp at, Timestamp nextAttemptAt, boolean finalAttempt) {
        this.status = finalAttempt ? ReminderStatus.FAILED : ReminderStatus.PENDING;
        this.lastError = error;
        this.lastAttemptAt = at;
        this.nextAttemptAt = finalAttempt ? null : nextAttemptAt;
        this.updatedAt = at;
    }

    public void cancel(Timestamp at) {
        if (status == ReminderStatus.PENDING || status == ReminderStatus.SENDING) {
            this.status = ReminderStatus.CANCELLED;
            this.nextAttemptAt = null;
            this.updatedAt = at;
        }
    }

    public UUID getReminderId() {
        return reminderId;
    }

    public void setReminderId(UUID reminderId) {
        this.reminderId = reminderId;
    }

    public Appointment getAppointment() {
        return appointment;
    }

    public void setAppointment(Appointment appointment) {
        this.appointment = appointment;
    }

    public ReminderType getReminderType() {
        return reminderType;
    }

    public void setReminderType(ReminderType reminderType) {
        this.reminderType = reminderType;
    }

    public Timestamp getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Timestamp scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public ReminderStatus getStatus() {
        return status;
    }

    public void setStatus(ReminderStatus status) {
        this.status = status;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public void setProviderMessageId(String providerMessageId) {
        this.providerMessageId = providerMessageId;
    }

    public Timestamp getSentAt() {
        return sentAt;
    }

    public void setSentAt(Timestamp sentAt) {
        this.sentAt = sentAt;
    }

    public Timestamp getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Timestamp lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Timestamp getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Timestamp nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}
