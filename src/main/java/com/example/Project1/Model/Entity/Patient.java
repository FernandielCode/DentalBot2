package com.example.Project1.Model.Entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Table(name = "patients")
@Entity(name = "Patient")
public class Patient {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID patientUUID;
    private int patientNumber;
    private String patientName;
    private String patientLastname;
    private String patientPhone;
    private Integer patientAge;

    @JsonIgnore
    @OneToMany(mappedBy = "patient")
    private List<MessageThread> patientMessageThreadList;

    @JsonIgnore
    @OneToMany(mappedBy = "patientContact")
    private List<WhatsAppContact> whatsAppContactList;

    @JsonIgnore
    @OneToMany(mappedBy = "patientAppointment")
    private List<Appointment> patientAppointmentList;

    @Enumerated(EnumType.STRING)
    @Column(name = "patient_state", nullable = false)
    private PatientState patientState;

    @Column
    @Enumerated(EnumType.STRING)
    private PatientPaymentState patientPaymentState;

    @Column
    private LocalDateTime appointment;

    public Patient() {
    }

    public Patient(UUID patientUUID, int patientNumber, String patientName,
                   String patientLastname, String patientPhone, Integer patientAge,
                   PatientState patientState, PatientPaymentState patientPaymentState,
                   LocalDateTime appointment) {
        this.patientUUID = patientUUID;
        this.patientNumber = patientNumber;
        this.patientName = patientName;
        this.patientLastname = patientLastname;
        this.patientPhone = patientPhone;
        this.patientAge = patientAge;
        this.patientState = patientState;
        this.patientPaymentState = patientPaymentState;
        this.appointment = appointment;
    }

    public UUID getPatientUUID() {
        return patientUUID;
    }

    public void setPatientUUID(UUID patientUUID) {
        this.patientUUID = patientUUID;
    }

    public int getPatientNumber() {
        return patientNumber;
    }

    public void setPatientNumber(int patientNumber) {
        this.patientNumber = patientNumber;
    }

    public String getPatientName() {
        return patientName;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    public String getPatientLastname() {
        return patientLastname;
    }

    public void setPatientLastname(String patientLastname) {
        this.patientLastname = patientLastname;
    }

    public String getPatientPhone() {
        return patientPhone;
    }

    public void setPatientPhone(String patientPhone) {
        this.patientPhone = patientPhone;
    }

    public Integer getPatientAge() {
        return patientAge;
    }

    public void setPatientAge(Integer patientAge) {
        this.patientAge = patientAge;
    }

    public List<MessageThread> getPatientMessageThreadList() {
        return patientMessageThreadList;
    }

    public void setPatientMessageThreadList(List<MessageThread> patientMessageThreadList) {
        this.patientMessageThreadList = patientMessageThreadList;
    }

    public List<WhatsAppContact> getWhatsAppContactList() {
        return whatsAppContactList;
    }

    public void setWhatsAppContactList(List<WhatsAppContact> whatsAppContactList) {
        this.whatsAppContactList = whatsAppContactList;
    }

    public List<Appointment> getPatientAppointmentList() {
        return patientAppointmentList;
    }

    public void setPatientAppointmentList(List<Appointment> patientAppointmentList) {
        this.patientAppointmentList = patientAppointmentList;
    }

    public PatientState getPatientState() {
        return patientState;
    }

    public void setPatientState(PatientState patientState) {
        this.patientState = patientState;
    }

    public PatientPaymentState getPatientPaymentState() {
        return patientPaymentState;
    }

    public void setPatientPaymentState(PatientPaymentState patientPaymentState) {
        this.patientPaymentState = patientPaymentState;
    }

    public LocalDateTime getAppointment() {
        return appointment;
    }

    public void setAppointment(LocalDateTime appointment) {
        this.appointment = appointment;
    }
}
