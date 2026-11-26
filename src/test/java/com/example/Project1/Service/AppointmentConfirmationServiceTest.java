package com.example.Project1.Service;

import com.example.Project1.Config.ReminderProperties;
import com.example.Project1.Config.WhatsAppProperties;
import com.example.Project1.Model.DTO.WhatsAppWebhookPayload;
import com.example.Project1.Model.Entity.Appointment;
import com.example.Project1.Model.Entity.AppointmentReminder;
import com.example.Project1.Model.Entity.Patient;
import com.example.Project1.Model.Entity.PatientConfirmationStatus;
import com.example.Project1.Model.Entity.ReminderStatus;
import com.example.Project1.Model.Entity.ReminderType;
import com.example.Project1.repository.AppointmentReminderRepository;
import com.example.Project1.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentConfirmationServiceTest {

    private static final String DOCTOR_PHONE = "593900000000";
    private static final String PATIENT_PHONE = "593911111111";
    private static final String OTHER_PHONE = "593922222222";
    private static final String CONTEXT_ID = "wamid-reminder-1";
    private static final String ZONE = "America/Guayaquil";

    @Mock
    private AppointmentReminderRepository reminderRepository;
    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private AppointmentReminderService reminderService;
    @Mock
    private WhatsAppClient whatsAppClient;

    private AppointmentConfirmationService service;
    private Appointment appointment;
    private AppointmentReminder reminder;

    @BeforeEach
    void setUp() {
        WhatsAppProperties whatsAppProperties = new WhatsAppProperties();
        whatsAppProperties.getDoctor().setPhoneNumber(DOCTOR_PHONE);

        ReminderProperties reminderProperties = new ReminderProperties();
        reminderProperties.setZone(ZONE);

        service = new AppointmentConfirmationService(
                reminderRepository,
                appointmentRepository,
                reminderService,
                whatsAppClient,
                whatsAppProperties,
                reminderProperties);

        Patient patient = new Patient();
        patient.setPatientName("Ana");
        patient.setPatientLastname("Lopez");
        patient.setPatientPhone(PATIENT_PHONE);

        appointment = new Appointment();
        appointment.setAppointmentId(UUID.randomUUID());
        appointment.setPatientAppointment(patient);
        appointment.setAppointmentStatus("CONFIRMED");
        appointment.setPatientConfirmationStatus(PatientConfirmationStatus.WAITING_PATIENT_ANSWER);
        appointment.setStartAppointmentAt(Timestamp.valueOf(
                LocalDate.now(ZoneId.of(ZONE)).plusDays(3).atTime(10, 0)));

        reminder = new AppointmentReminder();
        reminder.setReminderId(UUID.randomUUID());
        reminder.setAppointment(appointment);
        reminder.setReminderType(ReminderType.THREE_DAYS);
        reminder.setStatus(ReminderStatus.SENT);
        reminder.setProviderMessageId(CONTEXT_ID);
        reminder.setScheduledAt(new Timestamp(System.currentTimeMillis()));

        when(reminderRepository.findByProviderMessageId(CONTEXT_ID))
                .thenReturn(Optional.of(reminder));
    }

    @Test
    void confirmingKeepsTheLaterOneDayReminderScheduled() {
        when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        boolean handled = service.handleReminderReply(PATIENT_PHONE, buttonMessage("Confirmar"));

        assertTrue(handled);
        assertEquals(PatientConfirmationStatus.CONFIRMED,
                appointment.getPatientConfirmationStatus());
        assertNotNull(appointment.getPatientRespondedAt());
        verify(appointmentRepository).save(appointment);
        verify(reminderService, never()).cancelFor(any(Appointment.class));
        verify(whatsAppClient).sendTextMessage(eq(PATIENT_PHONE), contains("confirmada"));
    }

    @Test
    void cancellingMarksAppointmentCancelledAndNotifiesDoctor() {
        when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        boolean handled = service.handleReminderReply(PATIENT_PHONE, buttonMessage("Cancelar"));

        assertTrue(handled);
        assertEquals(PatientConfirmationStatus.CANCELLED,
                appointment.getPatientConfirmationStatus());
        assertEquals("CANCELLED", appointment.getAppointmentStatus());
        verify(appointmentRepository).save(appointment);
        verify(reminderService).cancelFor(appointment);
        verify(whatsAppClient).sendTextMessage(eq(PATIENT_PHONE), contains("cancelada"));
        verify(whatsAppClient).sendTextMessage(eq(DOCTOR_PHONE), contains("canceló"));
    }

    @Test
    void rejectsAnotherPhoneEvenWhenContextMatches() {
        boolean handled = service.handleReminderReply(OTHER_PHONE, buttonMessage("Confirmar"));

        assertTrue(handled);
        assertEquals(PatientConfirmationStatus.WAITING_PATIENT_ANSWER,
                appointment.getPatientConfirmationStatus());
        verify(appointmentRepository, never()).save(any(Appointment.class));
        verify(reminderService, never()).cancelFor(any(Appointment.class));
        verify(whatsAppClient).sendTextMessage(eq(OTHER_PHONE), contains("No pudimos identificar"));
    }

    private WhatsAppWebhookPayload.IncomingMessage buttonMessage(String buttonText) {
        return new WhatsAppWebhookPayload.IncomingMessage(
                PATIENT_PHONE,
                "msg-1",
                "1",
                "button",
                null,
                null,
                new WhatsAppWebhookPayload.ButtonMessage(buttonText, buttonText),
                new WhatsAppWebhookPayload.Context(CONTEXT_ID));
    }
}
