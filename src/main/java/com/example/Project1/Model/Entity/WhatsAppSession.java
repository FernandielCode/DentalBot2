package com.example.Project1.Model.Entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Entity(name = "WhatsAppSession")
@Table(name = "whatsapp_sessions")
public class WhatsAppSession {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID sessionId;

    @ManyToOne
    @JoinColumn(name = "contact_id")
    private WhatsAppContact whatsAppContact;

    @ManyToOne
    @JoinColumn(name = "message_thread_id")
    private MessageThread messageThread;

    @JsonIgnore
    @OneToMany(mappedBy = "whatsAppSession")
    private List<BotCommand> botCommandList;

    private String currentState;
    @Column(columnDefinition = "TEXT")
    private String contextJSON;
    private Timestamp expiresAt;
    private Timestamp updatedAt;

    public WhatsAppSession() {
    }

    public WhatsAppSession(UUID sessionId, WhatsAppContact whatsAppContact, MessageThread messageThread,
                           String currentState, String contextJSON, Timestamp expiresAt, Timestamp updatedAt) {
        this.sessionId = sessionId;
        this.whatsAppContact = whatsAppContact;
        this.messageThread = messageThread;
        this.currentState = currentState;
        this.contextJSON = contextJSON;
        this.expiresAt = expiresAt;
        this.updatedAt = updatedAt;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public WhatsAppContact getWhatsAppContact() {
        return whatsAppContact;
    }

    public void setWhatsAppContact(WhatsAppContact whatsAppContact) {
        this.whatsAppContact = whatsAppContact;
    }

    public MessageThread getMessageThread() {
        return messageThread;
    }

    public void setMessageThread(MessageThread messageThread) {
        this.messageThread = messageThread;
    }

    public List<BotCommand> getBotCommandList() {
        return botCommandList;
    }

    public void setBotCommandList(List<BotCommand> botCommandList) {
        this.botCommandList = botCommandList;
    }

    public String getCurrentState() {
        return currentState;
    }

    public void setCurrentState(String currentState) {
        this.currentState = currentState;
    }

    public String getContextJSON() {
        return contextJSON;
    }

    public void setContextJSON(String contextJSON) {
        this.contextJSON = contextJSON;
    }

    public Timestamp getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Timestamp expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}
