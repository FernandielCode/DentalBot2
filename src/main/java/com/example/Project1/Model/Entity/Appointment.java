package com.example.Project1.Model.Entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Entity(name = "Appointment")
@Table(name = "appointments")
public class Appointment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID appointmentId;

    @ManyToOne
    @JoinColumn(name = "patient_id")
    private Patient patientAppointment;

    @ManyToOne
    @JoinColumn(name = "dentist_id")
    private Dentist dentistAppointment;

    private Timestamp startAppointmentAt;
    @Column(nullable = false)
    private String category;
    @Column(nullable = false)
    private String appointmentStatus;
    private String notes;
    @Column(nullable = false)
    private Timestamp createdAt;

    @JsonIgnore
    @OneToMany(mappedBy = "notificationAppointment")
    private List<Notification> appointmentNotificationList;

    public Appointment() {
    }

    public Appointment(UUID appointmentId, Patient patientAppointment, Dentist dentistAppointment,
                       Timestamp startAppointmentAt, String category, String appointmentStatus,
                       String notes, Timestamp createdAt) {
        this.appointmentId = appointmentId;
        this.patientAppointment = patientAppointment;
        this.dentistAppointment = dentistAppointment;
        this.startAppointmentAt = startAppointmentAt;
        this.category = category;
        this.appointmentStatus = appointmentStatus;
        this.notes = notes;
        this.createdAt = createdAt;
    }

    public UUID getAppointmentId() {
        return appointmentId;
    }

    public void setAppointmentId(UUID appointmentId) {
        this.appointmentId = appointmentId;
    }

    public Patient getPatientAppointment() {
        return patientAppointment;
    }

    public void setPatientAppointment(Patient patientAppointment) {
        this.patientAppointment = patientAppointment;
    }

    public Dentist getDentistAppointment() {
        return dentistAppointment;
    }

    public void setDentistAppointment(Dentist dentistAppointment) {
        this.dentistAppointment = dentistAppointment;
    }

    public Timestamp getStartAppointmentAt() {
        return startAppointmentAt;
    }

    public void setStartAppointmentAt(Timestamp startAppointmentAt) {
        this.startAppointmentAt = startAppointmentAt;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getAppointmentStatus() {
        return appointmentStatus;
    }

    public void setAppointmentStatus(String appointmentStatus) {
        this.appointmentStatus = appointmentStatus;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public List<Notification> getAppointmentNotificationList() {
        return appointmentNotificationList;
    }

    public void setAppointmentNotificationList(List<Notification> appointmentNotificationList) {
        this.appointmentNotificationList = appointmentNotificationList;
    }
}
