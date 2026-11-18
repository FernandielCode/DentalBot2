package com.example.Project1.repository;

import com.example.Project1.Model.Entity.Appointment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {
    List<Appointment> findByAppointmentStatus(String appointmentStatus);

    // Paginated query: retrieves appointments ordered from most recent to oldest
    Page<Appointment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // Retrieves the most recent appointment with a given status (e.g. "PENDING")
    Optional<Appointment> findTopByAppointmentStatusOrderByCreatedAtDesc(String appointmentStatus);

    Optional<Appointment> findTopByOrderByCreatedAtDesc();

    // Latest appointment for a patient matched by name (fallback for the update-appointment flow)
    Optional<Appointment> findTopByPatientAppointment_PatientNameContainingIgnoreCaseOrderByCreatedAtDesc(
            String patientName);

    // Latest appointment for a patient resolved from the flow token
    Optional<Appointment> findTopByPatientAppointment_PatientUUIDOrderByCreatedAtDesc(UUID patientUUID);

    // Search by patient name and/or appointment date (used by the search-patient flow)
    List<Appointment> findByPatientAppointment_PatientNameContainingIgnoreCase(String patientName);

    List<Appointment> findByStartAppointmentAtBetween(Timestamp start, Timestamp end);

    List<Appointment> findByPatientAppointment_PatientNameContainingIgnoreCaseAndStartAppointmentAtBetween(
            String patientName, Timestamp start, Timestamp end);
}
