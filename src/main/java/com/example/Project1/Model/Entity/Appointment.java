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

    @ManyToMany
    @JoinTable(
        name = "appointment_treatments",
        joinColumns = @JoinColumn(name = "appointment_id"),
        inverseJoinColumns = @JoinColumn(name = "treatment_id")
    )
    private List<Treatments> treatments;

    private Timestamp startAppointmentAt;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String appointmentStatus;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PatientState patientState;

    private String notes;

    @Column(nullable = false)
    private Timestamp createdAt;

    @Column(nullable = true)
    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    @JsonIgnore
    @OneToMany(mappedBy = "notificationAppointment")
    private List<Notification> appointmentNotificationList;

    public Appointment() {
    }

    public Appointment(UUID appointmentId, Patient patientAppointment, Dentist dentistAppointment,
                       List<Treatments> treatments, Timestamp startAppointmentAt, String category,
                       String appointmentStatus, PatientState patientState, String notes, Timestamp createdAt,
                       PaymentMethod paymentMethod) {
        this.appointmentId = appointmentId;
        this.patientAppointment = patientAppointment;
        this.dentistAppointment = dentistAppointment;
        this.treatments = treatments;
        this.startAppointmentAt = startAppointmentAt;
        this.category = category;
        this.patientState = patientState;
        this.appointmentStatus = appointmentStatus;
        this.notes = notes;
        this.createdAt = createdAt;
        this.paymentMethod = paymentMethod;
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

    public List<Treatments> getTreatments() {
        return treatments;
    }

    public void setTreatments(List<Treatments> treatments) {
        this.treatments = treatments;
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

    public PatientState getPatientState() {
        return patientState;
    }

    public void setPatientState(PatientState patientState) {
        this.patientState = patientState;
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

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }
}
