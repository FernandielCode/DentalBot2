package com.example.Project1.Service;

import com.example.Project1.Config.ReminderProperties;
import com.example.Project1.Config.WhatsAppProperties;
import com.example.Project1.Model.DTO.WhatsAppWebhookPayload;
import com.example.Project1.Model.Entity.Appointment;
import com.example.Project1.Model.Entity.AppointmentReminder;
import com.example.Project1.Model.Entity.Patient;
import com.example.Project1.Model.Entity.PatientConfirmationStatus;
import com.example.Project1.Model.Entity.ReminderStatus;
import com.example.Project1.repository.AppointmentReminderRepository;
import com.example.Project1.repository.AppointmentRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles one-tap Confirmar/Cancelar replies from patient reminders.
 *
 * Template quick replies are correlated through context.id (the original
 * reminder message id). The local-development interactive buttons carry the
 * reminder id directly in the button id.
 */
@Service
public class AppointmentConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentConfirmationService.class);

    private static final DateTimeFormatter APPOINTMENT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' hh:mma", Locale.of("es", "ES"));

    private enum ReminderAction {
        CONFIRM,
        CANCEL
    }

    private record ResolvedReminder(AppointmentReminder reminder, ReminderAction action) {
    }

    private final AppointmentReminderRepository reminderRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentReminderService reminderService;
    private final WhatsAppClient whatsAppClient;
    private final WhatsAppProperties whatsAppProperties;
    private final ReminderProperties reminderProperties;

    public AppointmentConfirmationService(AppointmentReminderRepository reminderRepository,
                                          AppointmentRepository appointmentRepository,
                                          AppointmentReminderService reminderService,
                                          WhatsAppClient whatsAppClient,
                                          WhatsAppProperties whatsAppProperties,
                                          ReminderProperties reminderProperties) {
        this.reminderRepository = reminderRepository;
        this.appointmentRepository = appointmentRepository;
        this.reminderService = reminderService;
        this.whatsAppClient = whatsAppClient;
        this.whatsAppProperties = whatsAppProperties;
        this.reminderProperties = reminderProperties;
    }

    /**
     * @return true when the message was a reminder interaction and was handled;
     * false when it should continue through the normal patient intake flow.
     */
    @Transactional
    public boolean handleReminderReply(String fromPhone,
                                       WhatsAppWebhookPayload.IncomingMessage message) {
        String selectionId = message.getInteractiveSelectionId();
        boolean templateButton = message.button() != null;
        boolean devButton = selectionId != null
                && (selectionId.startsWith(ReminderButtonIds.CONFIRM_PREFIX)
                || selectionId.startsWith(ReminderButtonIds.CANCEL_PREFIX));

        if (!templateButton && !devButton) {
            return false;
        }

        ResolvedReminder resolved = resolveReminder(message, selectionId);
        if (resolved == null) {
            whatsAppClient.sendTextMessage(fromPhone,
                    "No encontramos esa cita. Por favor contacta a la clínica.");
            return true;
        }

        if (resolved.action() == null) {
            whatsAppClient.sendTextMessage(fromPhone,
                    "No entendí tu respuesta. Responde *Confirmar* o *Cancelar*.");
            return true;
        }

        AppointmentReminder reminder = resolved.reminder();
        Appointment appointment = reminder.getAppointment();
        Patient patient = appointment.getPatientAppointment();

        // Security: only the phone registered on the appointment may respond.
        if (patient == null || !samePhone(fromPhone, patient.getPatientPhone())) {
            whatsAppClient.sendTextMessage(fromPhone, "No pudimos identificar esa cita.");
            return true;
        }

        if ("CANCELLED".equalsIgnoreCase(appointment.getAppointmentStatus())) {
            whatsAppClient.sendTextMessage(fromPhone,
                    "Esta cita ya no está disponible. Contacta a la clínica.");
            return true;
        }

        // Old cancelled/stale reminder rows must not be able to change the appointment.
        if (reminder.getStatus() != ReminderStatus.SENT) {
            whatsAppClient.sendTextMessage(fromPhone,
                    "Esta invitación ya no está vigente. Usa el recordatorio más reciente.");
            return true;
        }

        LocalDateTime appointmentAt = appointment.resolveScheduledAt();
        if (appointmentAt == null || !appointmentAt.isAfter(now())) {
            whatsAppClient.sendTextMessage(fromPhone,
                    "Esta cita ya pasó o no tiene una fecha válida. Contacta a la clínica.");
            return true;
        }

        PatientConfirmationStatus current = currentStatus(appointment);

        if (resolved.action() == ReminderAction.CONFIRM) {
            if (current == PatientConfirmationStatus.CONFIRMED) {
                whatsAppClient.sendTextMessage(fromPhone,
                        "Tu cita ya estaba confirmada. ¡Te esperamos!");
                return true;
            }
            if (current == PatientConfirmationStatus.CANCELLED) {
                whatsAppClient.sendTextMessage(fromPhone,
                        "Esta cita fue cancelada. Contacta a la clínica para reprogramarla.");
                return true;
            }

            appointment.setPatientConfirmationStatus(PatientConfirmationStatus.CONFIRMED);
            appointment.setPatientRespondedAt(Timestamp.valueOf(now()));
            appointmentRepository.save(appointment);

            // Intentionally do not cancel the 1-day reminder after a 3-day confirmation.
            whatsAppClient.sendTextMessage(fromPhone,
                    "¡Gracias! Tu cita del " + formatDate(appointmentAt) + " está confirmada.");
            log.info("Patient {} confirmed appointment {}",
                    fromPhone, appointment.getAppointmentId());
            return true;
        }

        // CANCEL can move PENDING/WAITING/CONFIRMED to CANCELLED.
        if (current == PatientConfirmationStatus.CANCELLED) {
            whatsAppClient.sendTextMessage(fromPhone, "Tu cita ya estaba cancelada.");
            return true;
        }

        appointment.setPatientConfirmationStatus(PatientConfirmationStatus.CANCELLED);
        appointment.setAppointmentStatus("CANCELLED");
        appointment.setPatientRespondedAt(Timestamp.valueOf(now()));
        appointmentRepository.save(appointment);

        reminderService.cancelFor(appointment);
        whatsAppClient.sendTextMessage(fromPhone,
                "Tu cita del " + formatDate(appointmentAt)
                        + " fue cancelada. La clínica se pondrá en contacto.");
        notifyDoctorOfCancellation(appointment, patient);

        log.info("Patient {} cancelled appointment {}",
                fromPhone, appointment.getAppointmentId());
        return true;
    }

    private ResolvedReminder resolveReminder(WhatsAppWebhookPayload.IncomingMessage message,
                                             String selectionId) {
        // Production template quick reply: context.id matches the sent reminder message.
        String contextId = message.getContextMessageId();
        if (contextId != null) {
            Optional<AppointmentReminder> byContext = reminderRepository.findByProviderMessageId(contextId);
            if (byContext.isPresent()) {
                return new ResolvedReminder(byContext.get(), resolveAction(message, selectionId));
            }
        }

        // Local-dev interactive buttons: REMINDER_CONFIRM_<reminderId> / REMINDER_CANCEL_<reminderId>.
        if (selectionId == null) {
            return null;
        }

        ReminderAction action = null;
        String reminderIdText = null;

        if (selectionId.startsWith(ReminderButtonIds.CONFIRM_PREFIX)) {
            action = ReminderAction.CONFIRM;
            reminderIdText = selectionId.substring(ReminderButtonIds.CONFIRM_PREFIX.length());
        } else if (selectionId.startsWith(ReminderButtonIds.CANCEL_PREFIX)) {
            action = ReminderAction.CANCEL;
            reminderIdText = selectionId.substring(ReminderButtonIds.CANCEL_PREFIX.length());
        }

        if (reminderIdText == null) {
            return null;
        }

        try {
            AppointmentReminder reminder = reminderRepository
                    .findById(UUID.fromString(reminderIdText))
                    .orElse(null);
            return reminder == null ? null : new ResolvedReminder(reminder, action);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid reminder id in button selection '{}'", selectionId);
            return null;
        }
    }

    private ReminderAction resolveAction(WhatsAppWebhookPayload.IncomingMessage message,
                                         String selectionId) {
        if (selectionId != null && selectionId.startsWith(ReminderButtonIds.CONFIRM_PREFIX)) {
            return ReminderAction.CONFIRM;
        }
        if (selectionId != null && selectionId.startsWith(ReminderButtonIds.CANCEL_PREFIX)) {
            return ReminderAction.CANCEL;
        }

        String raw = null;
        if (message.button() != null) {
            // Template button text is the stable human-facing label.
            raw = firstNonBlank(message.button().text(), message.button().payload());
        }
        if (raw == null) {
            raw = message.getExtractedText();
        }

        String normalized = normalize(raw);
        String confirmLabel = normalize(reminderProperties.getTemplate().getConfirmLabel());
        String cancelLabel = normalize(reminderProperties.getTemplate().getCancelLabel());

        if ((!confirmLabel.isBlank() && normalized.contains(confirmLabel))
                || normalized.contains("confirm")) {
            return ReminderAction.CONFIRM;
        }
        if ((!cancelLabel.isBlank() && normalized.contains(cancelLabel))
                || normalized.contains("cancel")) {
            return ReminderAction.CANCEL;
        }
        return null;
    }

    private void notifyDoctorOfCancellation(Appointment appointment, Patient patient) {
        String doctorPhone = whatsAppProperties.getDoctor() != null
                ? whatsAppProperties.getDoctor().getPhoneNumber()
                : null;

        if (doctorPhone == null || doctorPhone.isBlank()) {
            log.warn("Doctor phone is not configured; cancellation alert for appointment {} not sent",
                    appointment.getAppointmentId());
            return;
        }

        String message = "El paciente " + fullName(patient)
                + " canceló la cita del " + formatDate(appointment.resolveScheduledAt()) + ".";
        whatsAppClient.sendTextMessage(doctorPhone, message);
    }

    private PatientConfirmationStatus currentStatus(Appointment appointment) {
        return appointment.getPatientConfirmationStatus() == null
                ? PatientConfirmationStatus.PENDING
                : appointment.getPatientConfirmationStatus();
    }

    private String formatDate(LocalDateTime dateTime) {
        return dateTime == null ? "fecha por definir" : dateTime.format(APPOINTMENT_DATE_FORMAT);
    }

    private String fullName(Patient patient) {
        if (patient == null) {
            return "paciente";
        }
        String full = ((patient.getPatientName() == null ? "" : patient.getPatientName()) + " "
                + (patient.getPatientLastname() == null ? "" : patient.getPatientLastname())).trim();
        return full.isBlank() ? "paciente" : full;
    }

    private boolean samePhone(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String a = left.replaceAll("\\D", "");
        String b = right.replaceAll("\\D", "");
        return !a.isBlank() && a.equals(b);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of(reminderProperties.getZone()));
    }
}
