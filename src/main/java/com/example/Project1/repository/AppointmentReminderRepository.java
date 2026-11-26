package com.example.Project1.repository;

import com.example.Project1.Model.Entity.AppointmentReminder;
import com.example.Project1.Model.Entity.ReminderStatus;
import com.example.Project1.Model.Entity.ReminderType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppointmentReminderRepository extends JpaRepository<AppointmentReminder, UUID> {

    Optional<AppointmentReminder> findByProviderMessageId(String providerMessageId);

    Optional<AppointmentReminder> findByAppointment_AppointmentIdAndReminderType(
            UUID appointmentId, ReminderType reminderType);

    List<AppointmentReminder> findByAppointment_AppointmentIdAndStatusIn(
            UUID appointmentId, Collection<ReminderStatus> statuses);

    List<AppointmentReminder> findByStatusAndLastAttemptAtBefore(
            ReminderStatus status, Timestamp cutoff);

    /**
     * Claims due reminders under a write lock. This makes duplicate delivery
     * unlikely when more than one application instance is running.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from AppointmentReminder r
            where r.status = :status
              and r.nextAttemptAt <= :now
            order by r.nextAttemptAt asc
            """)
    List<AppointmentReminder> findDueForUpdate(@Param("status") ReminderStatus status,
                                               @Param("now") Timestamp now,
                                               Pageable pageable);
}
