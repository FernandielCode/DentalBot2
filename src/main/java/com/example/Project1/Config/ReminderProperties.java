package com.example.Project1.Config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Clinic-level appointment reminder settings.
 *
 * sendTime is kept as a String so it can be supplied through environment
 * variables as "09:00" without relying on binder-specific time conversions.
 */
@Configuration
@ConfigurationProperties(prefix = "app.reminders")
public class ReminderProperties {

    private boolean enabled = true;
    private String zone = "America/Guayaquil";
    private String sendTime = "09:00";
    private int batchSize = 50;
    private int maxAttempts = 3;
    private int claimTimeoutMinutes = 10;
    private Template template = new Template();

    public static class Template {
        private boolean enabled = true;
        private String name = "appointment_reminder_es";
        private String language = "es";
        private String clinicName = "Dental Clinic";
        private String confirmLabel = "Confirmar";
        private String cancelLabel = "Cancelar";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public String getClinicName() {
            return clinicName;
        }

        public void setClinicName(String clinicName) {
            this.clinicName = clinicName;
        }

        public String getConfirmLabel() {
            return confirmLabel;
        }

        public void setConfirmLabel(String confirmLabel) {
            this.confirmLabel = confirmLabel;
        }

        public String getCancelLabel() {
            return cancelLabel;
        }

        public void setCancelLabel(String cancelLabel) {
            this.cancelLabel = cancelLabel;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getSendTime() {
        return sendTime;
    }

    public void setSendTime(String sendTime) {
        this.sendTime = sendTime;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getClaimTimeoutMinutes() {
        return claimTimeoutMinutes;
    }

    public void setClaimTimeoutMinutes(int claimTimeoutMinutes) {
        this.claimTimeoutMinutes = claimTimeoutMinutes;
    }

    public Template getTemplate() {
        return template;
    }

    public void setTemplate(Template template) {
        this.template = template;
    }
}
