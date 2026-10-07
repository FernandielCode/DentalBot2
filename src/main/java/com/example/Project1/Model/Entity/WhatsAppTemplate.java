package com.example.Project1.Model.Entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Entity(name = "WhatsAppTemplate")
@Table(name = "whatsapp_templates")
public class WhatsAppTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID templateId;
    @Column(unique = true)
    private String templateName;
    @Column(unique = true)
    private String providerTemplateId;

    private String languageCode;
    @Column(nullable = false)
    private String category;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String bodyTemplate;
    @Column(nullable = false)
    private boolean isActive;
    @Column(nullable = false)
    private Timestamp createdAt;

    @JsonIgnore
    @OneToMany(mappedBy = "whatsAppTemplate")
    private List<WhatsAppMessage> whatsAppMessageList;

    public WhatsAppTemplate() {
    }

    public WhatsAppTemplate(UUID templateId, String templateName, String providerTemplateId,
                            String languageCode, String category, String bodyTemplate,
                            boolean isActive, Timestamp createdAt) {
        this.templateId = templateId;
        this.templateName = templateName;
        this.providerTemplateId = providerTemplateId;
        this.languageCode = languageCode;
        this.category = category;
        this.bodyTemplate = bodyTemplate;
        this.isActive = isActive;
        this.createdAt = createdAt;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public void setTemplateId(UUID templateId) {
        this.templateId = templateId;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public String getProviderTemplateId() {
        return providerTemplateId;
    }

    public void setProviderTemplateId(String providerTemplateId) {
        this.providerTemplateId = providerTemplateId;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public void setLanguageCode(String languageCode) {
        this.languageCode = languageCode;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public void setBodyTemplate(String bodyTemplate) {
        this.bodyTemplate = bodyTemplate;
    }

    public boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(boolean active) {
        isActive = active;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public List<WhatsAppMessage> getWhatsAppMessageList() {
        return whatsAppMessageList;
    }

    public void setWhatsAppMessageList(List<WhatsAppMessage> whatsAppMessageList) {
        this.whatsAppMessageList = whatsAppMessageList;
    }
}
