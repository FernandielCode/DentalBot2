package com.example.Project1.Model.Entity;

import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.UUID;

@Entity(name = "Message")
@Table(name = "messages")
public class Message {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int messageId;

    @ManyToOne
    @JoinColumn(name = "thread_id")
    private MessageThread messageThread;

    private String senderType;
    private UUID senderId;
    @Column(columnDefinition = "TEXT")
    private String messageText;
    private Timestamp sentAt;
    private Timestamp readAt;

    public Message() {
    }

    public Message(int messageId, MessageThread messageThread, String senderType, UUID senderId,
                   String messageText, Timestamp sentAt, Timestamp readAt) {
        this.messageId = messageId;
        this.messageThread = messageThread;
        this.senderType = senderType;
        this.senderId = senderId;
        this.messageText = messageText;
        this.sentAt = sentAt;
        this.readAt = readAt;
    }

    public int getMessageId() {
        return messageId;
    }

    public void setMessageId(int messageId) {
        this.messageId = messageId;
    }

    public MessageThread getMessageThread() {
        return messageThread;
    }

    public void setMessageThread(MessageThread messageThread) {
        this.messageThread = messageThread;
    }

    public String getSenderType() {
        return senderType;
    }

    public void setSenderType(String senderType) {
        this.senderType = senderType;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public void setSenderId(UUID senderId) {
        this.senderId = senderId;
    }

    public String getMessageText() {
        return messageText;
    }

    public void setMessageText(String messageText) {
        this.messageText = messageText;
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
}
