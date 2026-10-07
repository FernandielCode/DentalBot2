package com.example.Project1.Model.Entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Entity(name = "BotCommand")
@Table(name = "bot_commands")
public class BotCommand {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID botCommandId;

    @ManyToOne
    @JoinColumn(name = "whatsapp_session_id")
    private WhatsAppSession whatsAppSession;

    @JsonIgnore
    @OneToMany(mappedBy = "botCommand")
    private List<WhatsAppMessage> whatsAppMessageList_BotCommand;

    @Column(nullable = false)
    private String commandName;
    @Column(columnDefinition = "TEXT")
    private String commandPayload;
    private String resultStatus;
    private String errorMessage;

    @Column(nullable = false)
    private Timestamp botCommandCreatedAt;

    public BotCommand() {
    }

    public BotCommand(UUID botCommandId, WhatsAppSession whatsAppSession, List<WhatsAppMessage> whatsAppMessageList_BotCommand,
                      String commandName, String commandPayload, String resultStatus, String errorMessage,
                      Timestamp botCommandCreatedAt) {
        this.botCommandId = botCommandId;
        this.whatsAppSession = whatsAppSession;
        this.whatsAppMessageList_BotCommand = whatsAppMessageList_BotCommand;
        this.commandName = commandName;
        this.commandPayload = commandPayload;
        this.resultStatus = resultStatus;
        this.errorMessage = errorMessage;
        this.botCommandCreatedAt = botCommandCreatedAt;
    }

    public UUID getBotCommandId() {
        return botCommandId;
    }

    public void setBotCommandId(UUID botCommandId) {
        this.botCommandId = botCommandId;
    }

    public WhatsAppSession getWhatsAppSession() {
        return whatsAppSession;
    }

    public void setWhatsAppSession(WhatsAppSession whatsAppSession) {
        this.whatsAppSession = whatsAppSession;
    }

    public List<WhatsAppMessage> getWhatsAppMessageList_BotCommand() {
        return whatsAppMessageList_BotCommand;
    }

    public void setWhatsAppMessageList_BotCommand(List<WhatsAppMessage> whatsAppMessageList_BotCommand) {
        this.whatsAppMessageList_BotCommand = whatsAppMessageList_BotCommand;
    }

    public String getCommandName() {
        return commandName;
    }

    public void setCommandName(String commandName) {
        this.commandName = commandName;
    }

    public String getCommandPayload() {
        return commandPayload;
    }

    public void setCommandPayload(String commandPayload) {
        this.commandPayload = commandPayload;
    }

    public String getResultStatus() {
        return resultStatus;
    }

    public void setResultStatus(String resultStatus) {
        this.resultStatus = resultStatus;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Timestamp getBotCommandCreatedAt() {
        return botCommandCreatedAt;
    }

    public void setBotCommandCreatedAt(Timestamp botCommandCreatedAt) {
        this.botCommandCreatedAt = botCommandCreatedAt;
    }
}
