package com.example.Project1.Model.Entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Entity(name = "MessageThread")
@Table(name = "message_threads")
public class MessageThread {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID threadId;

    @ManyToOne
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne
    @JoinColumn(name = "dentist_id")
    private Dentist dentist;

    private String subject;
    private String threadStatus;
    private Timestamp createAt;

    @JsonIgnore
    @OneToMany(mappedBy = "messageThread")
    private List<Message> messageList;

    @JsonIgnore
    @OneToMany(mappedBy = "messageThread")
    private List<WhatsAppSession> whatsAppSessionList;

    @JsonIgnore
    @OneToMany(mappedBy = "messageThread")
    private List<WhatsAppMessage> whatsAppMessageList;

    public MessageThread() {
    }

    public MessageThread(UUID threadId, Patient patient, Dentist dentist, String subject, String threadStatus, Timestamp createAt) {
        this.threadId = threadId;
        this.patient = patient;
        this.dentist = dentist;
        this.subject = subject;
        this.threadStatus = threadStatus;
        this.createAt = createAt;
    }

    public UUID getThreadId() {
        return threadId;
    }

    public void setThreadId(UUID threadId) {
        this.threadId = threadId;
    }

    public Patient getPatient() {
        return patient;
    }

    public void setPatient(Patient patient) {
        this.patient = patient;
    }

    public Dentist getDentist() {
        return dentist;
    }

    public void setDentist(Dentist dentist) {
        this.dentist = dentist;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getThreadStatus() {
        return threadStatus;
    }

    public void setThreadStatus(String threadStatus) {
        this.threadStatus = threadStatus;
    }

    public Timestamp getCreateAt() {
        return createAt;
    }

    public void setCreateAt(Timestamp createAt) {
        this.createAt = createAt;
    }

    public List<Message> getMessageList() {
        return messageList;
    }

    public void setMessageList(List<Message> messageList) {
        this.messageList = messageList;
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
}
