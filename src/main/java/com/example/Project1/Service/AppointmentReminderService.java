package com.example.Project1.Service;

import com.example.Project1.Config.ReminderProperties;
import com.example.Project1.Model.DTO.WhatsAppSendResult;
import com.example.Project1.Model.Entity.Appointment;
import com.example.Project1.Model.Entity.AppointmentReminder;
import com.example.Project1.Model.Entity.Patient;
import com.example.Project1.Model.Entity.PatientConfirmationStatus;
import com.example.Project1.Model.Entity.ReminderStatus;
import com.example.Project1.Model.Entity.ReminderType;
import com.example.Project1.repository.AppointmentReminderRepository;
import com.example.Project1.repository.AppointmentRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Creates, claims, delivers, retries, and cancels appointment reminders.
 *
 * Scheduling is based on Appointment.startAppointmentAt, the same field used by
 * the doctor date-confirmation flow. The reminder table is the source of truth
 * for delivery state, so restarts do not duplicate reminders.
 */
@Service
public class AppointmentReminderService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentReminderService.class);

    private static final Set<ReminderStatus> OPEN_STATUSES =
            EnumSet.of(ReminderStatus.PENDING, ReminderStatus.SENDING);

    private static final DateTimeFormatter REMINDER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'a las' hh:mm a", Locale.of("es", "ES"));

    private final AppointmentReminderRepository reminderRepository;
    private final AppointmentRepository appointmentRepository;
    private final WhatsAppClient whatsAppClient;
    private final ReminderProperties properties;

    public AppointmentReminderService(AppointmentReminderRepository reminderRepository,
                                      AppointmentRepository appointmentRepository,
                                      WhatsAppClient whatsAppClient,
                                      ReminderProperties properties) {
        this.reminderRepository = reminderRepository;
        this.appointmentRepository = appointmentRepository;
        this.whatsAppClient = whatsAppClient;
        this.properties = properties;
    }

    /**
     * Creates or updates the two reminders for an appointment.
     * Called when the doctor confirms or changes the appointment date.
     */
    @Transactional
    public void scheduleFor(Appointment appointment) {
        LocalDateTime now = now();

        if (!isEligible(appointment)) {
            cancelOpen(appointment.getAppointmentId());
            return;
        }

        LocalDateTime appointmentAt = appointment.resolveScheduledAt();
        upsert(appointment, ReminderType.THREE_DAYS, reminderAt(appointmentAt, 3), now);
        upsert(appointment, ReminderType.ONE_DAY, reminderAt(appointmentAt, 1), now);
    }

    /**
     * Cancels outstanding reminders, for example when the doctor cancels the
     * appointment or the patient taps Cancelar.
     */
    @Transactional
    public void cancelFor(Appointment appointment) {
        cancelOpen(appointment.getAppointmentId());
    }

    /**
     * Claims a batch of due reminders. The caller must deliver each returned id.
     */
    @Transactional
    public List<UUID> claimDueReminders() {
        Timestamp now = Timestamp.valueOf(now());
        requeueStaleClaims(now);

        List<AppointmentReminder> due = reminderRepository.findDueForUpdate(
                ReminderStatus.PENDING,
                now,
                PageRequest.of(0, properties.getBatchSize()));

        due.forEach(reminder -> {
            reminder.setStatus(ReminderStatus.SENDING);
            reminder.setLastAttemptAt(now);
            reminder.setAttemptCount(reminder.getAttemptCount() + 1);
        });
        reminderRepository.saveAll(due);

        if (!due.isEmpty()) {
            log.info("Claimed {} appointment reminder(s)", due.size());
        }

        return due.stream()
                .map(AppointmentReminder::getReminderId)
                .toList();
    }

    /**
     * Sends one already-claimed reminder and records the outcome.
     */
    @Transactional
    public void deliverReminder(UUID reminderId) {
        AppointmentReminder reminder = reminderRepository.findById(reminderId).orElse(null);
        if (reminder == null || reminder.getStatus() != ReminderStatus.SENDING) {
            return;
        }

        Appointment appointment = reminder.getAppointment();
        Timestamp now = Timestamp.valueOf(now());

        if (!isEligible(appointment)) {
            reminder.cancel(now);
            reminderRepository.save(reminder);
            return;
        }

        String phone = appointment.getPatientAppointment().getPatientPhone();
        if (phone == null || phone.isBlank()) {
            reminder.markFailed("Paciente sin teléfono", now, null, true);
            reminderRepository.save(reminder);
            log.warn("Reminder {} skipped: appointment {} has no patient phone",
                    reminderId, appointment.getAppointmentId());
            return;
        }

        WhatsAppSendResult result = send(reminder, appointment, phone);

        if (result.success()) {
            reminder.markSent(result.providerMessageId(), now);
            reminderRepository.save(reminder);

            // A successful send means we are now waiting for the patient's answer.
            // A later 1-day reminder must still remain scheduled after a 3-day confirmation.
            if (appointment.getPatientConfirmationStatus() == null
                    || appointment.getPatientConfirmationStatus() == PatientConfirmationStatus.PENDING) {
                appointment.setPatientConfirmationStatus(PatientConfirmationStatus.WAITING_PATIENT_ANSWER);
                appointmentRepository.save(appointment);
            }

            log.info("Sent reminder {} ({}) for appointment {}",
                    reminderId, reminder.getReminderType(), appointment.getAppointmentId());
        } else {
            boolean finalAttempt = reminder.getAttemptCount() >= properties.getMaxAttempts();
            Timestamp nextAttempt = finalAttempt
                    ? null
                    : Timestamp.valueOf(now().plusMinutes(5L * Math.max(1, reminder.getAttemptCount())));

            reminder.markFailed(result.error(), now, nextAttempt, finalAttempt);
            reminderRepository.save(reminder);

            log.warn("Reminder {} failed (attempt {}/{}): {}",
                    reminderId,
                    reminder.getAttemptCount(),
                    properties.getMaxAttempts(),
                    result.error());
        }
    }

    /**
     * Nightly safety net for appointments that were created or changed outside
     * the bot. It only creates missing reminders; it never resets a SENT row.
     */
    @Transactional
    public void rebuildUpcomingReminders() {
        LocalDateTime now = now();
        LocalDateTime horizon = now.plusDays(4);

        List<Appointment> candidates = appointmentRepository.findReminderCandidates(
                Timestamp.valueOf(now),
                Timestamp.valueOf(horizon));

        int created = 0;
        for (Appointment appointment : candidates) {
            if (!isEligible(appointment)) {
                continue;
            }

            LocalDateTime appointmentAt = appointment.resolveScheduledAt();
            created += ensureReminder(appointment, ReminderType.THREE_DAYS,
                    reminderAt(appointmentAt, 3), now);
            created += ensureReminder(appointment, ReminderType.ONE_DAY,
                    reminderAt(appointmentAt, 1), now);
        }

        if (created > 0) {
            log.info("Reminder rebuild created {} missing reminder(s)", created);
        }
    }

    private void upsert(Appointment appointment,
                        ReminderType type,
                        LocalDateTime scheduledAt,
                        LocalDateTime now) {
        Optional<AppointmentReminder> existingOpt = reminderRepository
                .findByAppointment_AppointmentIdAndReminderType(appointment.getAppointmentId(), type);

        AppointmentReminder reminder = existingOpt.orElseGet(AppointmentReminder::new);

        // Short-notice rule: a reminder whose time already passed is not sent.
        if (!scheduledAt.isAfter(now)) {
            if (existingOpt.isPresent()
                    && reminder.getStatus() != ReminderStatus.SENT
                    && reminder.getStatus() != ReminderStatus.CANCELLED) {
                reminder.cancel(Timestamp.valueOf(now));
                reminderRepository.save(reminder);
            }
            return;
        }

        // Do not resend a reminder that was already sent for the same date.
        if (existingOpt.isPresent()
                && reminder.getStatus() == ReminderStatus.SENT
                && Objects.equals(reminder.getScheduledAt(), Timestamp.valueOf(scheduledAt))) {
            return;
        }

        // Reuse the same row so the unique (appointment, type) constraint holds.
        reminder.setAppointment(appointment);
        reminder.setReminderType(type);
        reminder.setScheduledAt(Timestamp.valueOf(scheduledAt));
        reminder.setNextAttemptAt(Timestamp.valueOf(scheduledAt));
        reminder.setStatus(ReminderStatus.PENDING);
        reminder.setAttemptCount(0);
        reminder.setLastError(null);
        reminder.setProviderMessageId(null);
        reminder.setSentAt(null);
        reminder.setLastAttemptAt(null);
        reminder.setUpdatedAt(Timestamp.valueOf(now));
        reminderRepository.save(reminder);
    }

    private int ensureReminder(Appointment appointment,
                               ReminderType type,
                               LocalDateTime scheduledAt,
                               LocalDateTime now) {
        if (!scheduledAt.isAfter(now)) {
            return 0;
        }

        if (reminderRepository
                .findByAppointment_AppointmentIdAndReminderType(appointment.getAppointmentId(), type)
                .isPresent()) {
            return 0;
        }

        AppointmentReminder reminder = new AppointmentReminder();
        reminder.setAppointment(appointment);
        reminder.setReminderType(type);
        reminder.setScheduledAt(Timestamp.valueOf(scheduledAt));
        reminder.setNextAttemptAt(Timestamp.valueOf(scheduledAt));
        reminder.setStatus(ReminderStatus.PENDING);
        reminder.setAttemptCount(0);
        reminder.setCreatedAt(Timestamp.valueOf(now));
        reminder.setUpdatedAt(Timestamp.valueOf(now));
        reminderRepository.save(reminder);
        return 1;
    }

    private void cancelOpen(UUID appointmentId) {
        Timestamp now = Timestamp.valueOf(now());
        List<AppointmentReminder> open = reminderRepository
                .findByAppointment_AppointmentIdAndStatusIn(appointmentId, OPEN_STATUSES);
        open.forEach(reminder -> reminder.cancel(now));
        reminderRepository.saveAll(open);
    }

    private void requeueStaleClaims(Timestamp now) {
        Timestamp cutoff = Timestamp.valueOf(now()
                .minusMinutes(properties.getClaimTimeoutMinutes()));

        List<AppointmentReminder> stale = reminderRepository
                .findByStatusAndLastAttemptAtBefore(ReminderStatus.SENDING, cutoff);

        stale.forEach(reminder -> {
            reminder.setStatus(ReminderStatus.PENDING);
            reminder.setNextAttemptAt(now);
            reminder.setUpdatedAt(now);
        });
        reminderRepository.saveAll(stale);

        if (!stale.isEmpty()) {
            log.warn("Requeued {} stale reminder claim(s)", stale.size());
        }
    }

    private WhatsAppSendResult send(AppointmentReminder reminder,
                                    Appointment appointment,
                                    String phone) {
        if (properties.getTemplate().isEnabled()) {
            return whatsAppClient.sendTemplateMessage(
                    phone,
                    properties.getTemplate().getName(),
                    properties.getTemplate().getLanguage(),
                    List.of(
                            fullName(appointment.getPatientAppointment()),
                            properties.getTemplate().getClinicName(),
                            formatReminderDate(appointment.resolveScheduledAt())
                    ));
        }

        // Local-dev fallback only. Production reminders require an approved template.
        return whatsAppClient.sendReminderButtons(
                phone,
                buildDevReminderBody(appointment),
                reminder.getReminderId());
    }

    private boolean isEligible(Appointment appointment) {
        if (appointment == null
                || appointment.getPatientAppointment() == null
                || appointment.getPatientAppointment().getPatientPhone() == null
                || appointment.getPatientAppointment().getPatientPhone().isBlank()
                || appointment.resolveScheduledAt() == null
                || !appointment.resolveScheduledAt().isAfter(now())
                || appointment.isAwaitingDateConfirmation()
                || "CANCELLED".equalsIgnoreCase(appointment.getAppointmentStatus())) {
            return false;
        }

        // Confirmed patients still receive the later 1-day reminder by design.
        return true;
    }

    private LocalDateTime reminderAt(LocalDateTime appointmentAt, int daysBefore) {
        return appointmentAt.toLocalDate()
                .minusDays(daysBefore)
                .atTime(LocalTime.parse(properties.getSendTime()));
    }

    private String buildDevReminderBody(Appointment appointment) {
        return "Hola " + fullName(appointment.getPatientAppointment())
                + ", te recordamos tu cita en "
                + properties.getTemplate().getClinicName()
                + " para el " + formatReminderDate(appointment.resolveScheduledAt()) + ".";
    }

    private String formatReminderDate(LocalDateTime dateTime) {
        return dateTime == null ? "fecha por definir" : dateTime.format(REMINDER_DATE_FORMAT);
    }

    private String fullName(Patient patient) {
        if (patient == null) {
            return "paciente";
        }
        String full = ((patient.getPatientName() == null ? "" : patient.getPatientName()) + " "
                + (patient.getPatientLastname() == null ? "" : patient.getPatientLastname())).trim();
        return full.isBlank() ? "paciente" : full;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of(properties.getZone()));
    }
}
