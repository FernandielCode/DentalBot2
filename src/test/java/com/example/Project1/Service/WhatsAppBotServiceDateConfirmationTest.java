package com.example.Project1.Service;

import com.example.Project1.Config.WhatsAppProperties;
import com.example.Project1.Model.DTO.WhatsAppWebhookPayload;
import com.example.Project1.Model.Entity.Appointment;
import com.example.Project1.Model.Entity.Patient;
import com.example.Project1.Model.Entity.WhatsAppContact;
import com.example.Project1.Model.Entity.WhatsAppSession;
import com.example.Project1.repository.AppointmentRepository;
import com.example.Project1.repository.DentistRepository;
import com.example.Project1.repository.PatientRepository;
import com.example.Project1.repository.TreatmentsRepository;
import com.example.Project1.repository.WhatsAppContactRepository;
import com.example.Project1.repository.WhatsAppMessageRepository;
import com.example.Project1.repository.WhatsAppSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppBotServiceDateConfirmationTest {

    private static final String DOCTOR_PHONE = "593900000000";
    private static final String PATIENT_PHONE = "593911111111";
    private static final UUID APPOINTMENT_ID = UUID.randomUUID();

    @Mock
    private WhatsAppClient whatsAppClient;
    @Mock
    private WhatsAppContactRepository contactRepository;
    @Mock
    private WhatsAppSessionRepository sessionRepository;
    @Mock
    private WhatsAppMessageRepository messageRepository;
    @Mock
    private TreatmentsRepository treatmentsRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private DentistRepository dentistRepository;
    @Mock
    private AppointmentReminderService reminderService;
    @Mock
    private AppointmentConfirmationService confirmationService;

    private WhatsAppBotService botService;
    private WhatsAppContact doctorContact;
    private WhatsAppSession doctorSession;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        WhatsAppProperties properties = new WhatsAppProperties();
        properties.getDoctor().setPhoneNumber(DOCTOR_PHONE);

        botService = new WhatsAppBotService(
                whatsAppClient,
                properties,
                contactRepository,
                sessionRepository,
                messageRepository,
                treatmentsRepository,
                patientRepository,
                appointmentRepository,
                dentistRepository,
                reminderService,
                confirmationService);

        doctorContact = new WhatsAppContact();
        doctorContact.setWaUserId(DOCTOR_PHONE);
        doctorContact.setDisplayNumber(DOCTOR_PHONE);

        doctorSession = new WhatsAppSession();
        doctorSession.setWhatsAppContact(doctorContact);
        doctorSession.setCurrentState("DOC_IDLE");
        doctorSession.setUpdatedAt(new Timestamp(System.currentTimeMillis()));

        Patient patient = new Patient();
        patient.setPatientName("Ana");
        patient.setPatientLastname("Lopez");
        patient.setPatientPhone(PATIENT_PHONE);

        appointment = new Appointment();
        appointment.setAppointmentId(APPOINTMENT_ID);
        appointment.setPatientAppointment(patient);
        appointment.setAppointmentStatus("PENDING");
        appointment.setAwaitingDateConfirmation(true);
        appointment.setDateConfirmationRequestedAt(new Timestamp(System.currentTimeMillis()));

        when(contactRepository.findByWaUserId(DOCTOR_PHONE))
                .thenReturn(Optional.of(doctorContact));
        when(contactRepository.save(any(WhatsAppContact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionRepository.findByWhatsAppContact_WaUserId(DOCTOR_PHONE))
                .thenReturn(Optional.of(doctorSession));
        when(sessionRepository.save(any(WhatsAppSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(appointmentRepository.findById(APPOINTMENT_ID))
                .thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void persistsSelectionAndConfirmsOnlyTheSelectedAppointment() {
        botService.processWebhookEvent(payload(buttonMessage("DATE_CONFIRM_" + APPOINTMENT_ID)));

        assertEquals("DOC_AWAITING_CONFIRMATION_DATE", doctorSession.getCurrentState());
        assertEquals(APPOINTMENT_ID.toString(), doctorSession.getContextJSON());
        assertTrue(appointment.isAwaitingDateConfirmation());

        botService.processWebhookEvent(payload(textMessage("24/11/2026 10:00")));

        assertEquals("CONFIRMED", appointment.getAppointmentStatus());
        assertFalse(appointment.isAwaitingDateConfirmation());
        assertNull(appointment.getDateConfirmationRequestedAt());
        assertNotNull(appointment.getStartAppointmentAt());
        assertEquals("DOC_IDLE", doctorSession.getCurrentState());
        assertNull(doctorSession.getContextJSON());

        verify(appointmentRepository).save(appointment);
        verify(reminderService).scheduleFor(appointment);
        verify(whatsAppClient).sendTextMessage(eq(PATIENT_PHONE), contains("confirmado"));
    }

    private WhatsAppWebhookPayload payload(WhatsAppWebhookPayload.IncomingMessage message) {
        WhatsAppWebhookPayload.Value value = new WhatsAppWebhookPayload.Value(
                "whatsapp", null, null, List.of(message));
        WhatsAppWebhookPayload.Change change =
                new WhatsAppWebhookPayload.Change("messages", value);
        WhatsAppWebhookPayload.Entry entry =
                new WhatsAppWebhookPayload.Entry("entry-1", List.of(change));
        return new WhatsAppWebhookPayload("whatsapp_business_account", List.of(entry));
    }

    private WhatsAppWebhookPayload.IncomingMessage buttonMessage(String buttonId) {
        WhatsAppWebhookPayload.Selection selection =
                new WhatsAppWebhookPayload.Selection(buttonId, "Asignar fecha", null);
        WhatsAppWebhookPayload.InteractiveReply interactive =
                new WhatsAppWebhookPayload.InteractiveReply("button_reply", null, selection);
        return new WhatsAppWebhookPayload.IncomingMessage(
                DOCTOR_PHONE, "msg-button", "1", "interactive", null, interactive);
    }

    private WhatsAppWebhookPayload.IncomingMessage textMessage(String body) {
        WhatsAppWebhookPayload.TextMessage text =
                new WhatsAppWebhookPayload.TextMessage(body);
        return new WhatsAppWebhookPayload.IncomingMessage(
                DOCTOR_PHONE, "msg-text", "2", "text", text, null);
    }
}
