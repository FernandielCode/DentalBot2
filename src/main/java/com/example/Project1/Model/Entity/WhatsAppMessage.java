package com.example.Project1.Model.Entity;

import jakarta.persistence.*;
import java.sql.Timestamp;
import java.util.UUID;

@Entity(name = "WhatsAppMessage")
@Table(name = "whatsapp_messages")
public class WhatsAppMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID whatsappMessageID;

    @ManyToOne
    @JoinColumn(name = "contact_id")
    private WhatsAppContact whatsAppContact;

    @ManyToOne
    @JoinColumn(name = "thread_id")
    private MessageThread messageThread;

    @ManyToOne
    @JoinColumn(name = "bot_command_id")
    private BotCommand botCommand;

    @ManyToOne
    @JoinColumn(name = "template_id")
    private WhatsAppTemplate whatsAppTemplate;

    private String providerMessageId;
    private String direction;
    private String messageType;
    @Column(columnDefinition = "TEXT")
    private String text;
    private String providesStatus;
    private Timestamp providerTimeStamp;
    private Timestamp providerCreatedAt;

    public WhatsAppMessage() {
    }

    public WhatsAppMessage(UUID whatsappMessageID, WhatsAppContact whatsAppContact, MessageThread messageThread,
                           String providerMessageId, String direction, String messageType, String text,
                           String providesStatus, Timestamp providerTimeStamp, Timestamp providerCreatedAt) {
        this.whatsappMessageID = whatsappMessageID;
        this.whatsAppContact = whatsAppContact;
        this.messageThread = messageThread;
        this.providerMessageId = providerMessageId;
        this.direction = direction;
        this.messageType = messageType;
        this.text = text;
        this.providesStatus = providesStatus;
        this.providerTimeStamp = providerTimeStamp;
        this.providerCreatedAt = providerCreatedAt;
    }

    public UUID getWhatsappMessageID() {
        return whatsappMessageID;
    }

    public void setWhatsappMessageID(UUID whatsappMessageID) {
        this.whatsappMessageID = whatsappMessageID;
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

    public BotCommand getBotCommand() {
        return botCommand;
    }

    public void setBotCommand(BotCommand botCommand) {
        this.botCommand = botCommand;
    }

    public WhatsAppTemplate getWhatsAppTemplate() {
        return whatsAppTemplate;
    }

    public void setWhatsAppTemplate(WhatsAppTemplate whatsAppTemplate) {
        this.whatsAppTemplate = whatsAppTemplate;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public void setProviderMessageId(String providerMessageId) {
        this.providerMessageId = providerMessageId;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getProvidesStatus() {
        return providesStatus;
    }

    public void setProvidesStatus(String providesStatus) {
        this.providesStatus = providesStatus;
    }

    public Timestamp getProviderTimeStamp() {
        return providerTimeStamp;
    }

    public void setProviderTimeStamp(Timestamp providerTimeStamp) {
        this.providerTimeStamp = providerTimeStamp;
    }

    public Timestamp getProviderCreatedAt() {
        return providerCreatedAt;
    }

    public void setProviderCreatedAt(Timestamp providerCreatedAt) {
        this.providerCreatedAt = providerCreatedAt;
    }
}
