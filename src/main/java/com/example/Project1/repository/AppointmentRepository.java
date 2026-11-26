package com.example.Project1.repository;

import com.example.Project1.Model.Entity.Appointment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {
    List<Appointment> findByAppointmentStatus(String appointmentStatus);
    List<Appointment> findByPatientAppointment_PatientUUID(UUID patientUUID);

    // Paginated query: retrieves appointments ordered from most recent to oldest
    Page<Appointment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Appointment> findByAwaitingDateConfirmationTrueOrderByDateConfirmationRequestedAtAsc();

    long countByAwaitingDateConfirmationTrue();

    /**
     * Appointments that may need reminder rows. Used by the nightly safety net.
     */
    @Query("""
            select a from Appointment a
            where a.startAppointmentAt is not null
              and a.startAppointmentAt >= :now
              and a.startAppointmentAt <= :horizon
              and a.awaitingDateConfirmation = false
              and a.appointmentStatus <> 'CANCELLED'
            """)
    List<Appointment> findReminderCandidates(@Param("now") Timestamp now,
                                             @Param("horizon") Timestamp horizon);
}
