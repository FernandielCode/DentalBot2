package com.example.Project1.Model.Entity;

/**
 * Tracks the patient's response to an appointment reminder separately from the
 * doctor/date confirmation status stored in Appointment.appointmentStatus.
 */
public enum PatientConfirmationStatus {
    PENDING,
    WAITING_PATIENT_ANSWER,
    CONFIRMED,
    CANCELLED
}
