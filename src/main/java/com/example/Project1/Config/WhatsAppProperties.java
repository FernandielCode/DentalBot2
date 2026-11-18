package com.example.Project1.Config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsAppProperties {

    private String apiUrl = "https://graph.facebook.com/v26.0";
    private String phoneNumberId = "${WHATSAPP_PHONE_NUMBER_ID}";
    private String accessToken = "${WHATSAPP_ACCESS_TOKEN}";
    private String verifyToken = "${WHATSAPP_VERIFY_TOKEN}";
    private Doctor doctor = new Doctor();
    private Flows flows = new Flows();

    public static class Doctor {
        private String phoneNumber;
        /**
         * Development switch: when true, the doctor's number is also treated as a
         * patient number, so greetings fall through to the patient intake flow.
         */
        private boolean patientMode = false;

        public String getPhoneNumber() {
            return phoneNumber;
        }

        public void setPhoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }

        public boolean isPatientMode() {
            return patientMode;
        }

        public void setPatientMode(boolean patientMode) {
            this.patientMode = patientMode;
        }
    }

    public static class Flows {
        private String patientRegistration;
        private String updateAppointment;
        private String searchPatient;

        public String getPatientRegistration() {
            return patientRegistration;
        }

        public void setPatientRegistration(String patientRegistration) {
            this.patientRegistration = patientRegistration;
        }

        public String getUpdateAppointment() {
            return updateAppointment;
        }

        public void setUpdateAppointment(String updateAppointment) {
            this.updateAppointment = updateAppointment;
        }

        public String getSearchPatient() {
            return searchPatient;
        }

        public void setSearchPatient(String searchPatient) {
            this.searchPatient = searchPatient;
        }
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getPhoneNumberId() {
        return phoneNumberId;
    }

    public void setPhoneNumberId(String phoneNumberId) {
        this.phoneNumberId = phoneNumberId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getVerifyToken() {
        return verifyToken;
    }

    public void setVerifyToken(String verifyToken) {
        this.verifyToken = verifyToken;
    }

    public Doctor getDoctor() {
        return doctor;
    }

    public void setDoctor(Doctor doctor) {
        this.doctor = doctor;
    }

    public Flows getFlows() {
        return flows;
    }

    public void setFlows(Flows flows) {
        this.flows = flows;
    }
}
