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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentReminderServiceTest {

    private static final String ZONE = "America/Guayaquil";
    private static final String PATIENT_PHONE = "593911111111";

    @Mock
    private AppointmentReminderRepository reminderRepository;
    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private WhatsAppClient whatsAppClient;

    private AppointmentReminderService service;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        ReminderProperties properties = new ReminderProperties();
        properties.setZone(ZONE);
        properties.setSendTime("09:00");
        properties.setMaxAttempts(3);
        properties.setBatchSize(10);
        properties.getTemplate().setEnabled(true);
        properties.getTemplate().setName("appointment_reminder_es");
        properties.getTemplate().setLanguage("es");
        properties.getTemplate().setClinicName("Dental Clinic");

        service = new AppointmentReminderService(
                reminderRepository, appointmentRepository, whatsAppClient, properties);

        Patient patient = new Patient();
        patient.setPatientName("Ana");
        patient.setPatientLastname("Lopez");
        patient.setPatientPhone(PATIENT_PHONE);

        appointment = new Appointment();
        appointment.setAppointmentId(UUID.randomUUID());
        appointment.setPatientAppointment(patient);
        appointment.setAppointmentStatus("CONFIRMED");
        appointment.setAwaitingDateConfirmation(false);
        appointment.setPatientConfirmationStatus(PatientConfirmationStatus.PENDING);

        when(reminderRepository.save(any(AppointmentReminder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsThreeDayAndOneDayReminders() {
        LocalDate appointmentDate = LocalDate.now(ZoneId.of(ZONE)).plusDays(10);
        appointment.setStartAppointmentAt(Timestamp.valueOf(appointmentDate.atTime(10, 0)));

        when(reminderRepository.findByAppointment_AppointmentIdAndReminderType(
                eq(appointment.getAppointmentId()), any(ReminderType.class)))
                .thenReturn(Optional.empty());

        service.scheduleFor(appointment);

        ArgumentCaptor<AppointmentReminder> captor =
                ArgumentCaptor.forClass(AppointmentReminder.class);
        verify(reminderRepository, times(2)).save(captor.capture());

        List<AppointmentReminder> saved = captor.getAllValues();
        assertEquals(2, saved.size());
        assertEquals(ReminderType.THREE_DAYS, saved.get(0).getReminderType());
        assertEquals(ReminderType.ONE_DAY, saved.get(1).getReminderType());
        assertEquals(ReminderStatus.PENDING, saved.get(0).getStatus());
        assertEquals(ReminderStatus.PENDING, saved.get(1).getStatus());
        assertNotNull(saved.get(0).getScheduledAt());
        assertNotNull(saved.get(1).getScheduledAt());
    }

    @Test
    void skipsThreeDayReminderWhenItsTimeAlreadyPassed() {
        LocalDate appointmentDate = LocalDate.now(ZoneId.of(ZONE)).plusDays(2);
        appointment.setStartAppointmentAt(Timestamp.valueOf(appointmentDate.atTime(23, 59)));

        when(reminderRepository.findByAppointment_AppointmentIdAndReminderType(
                eq(appointment.getAppointmentId()), any(ReminderType.class)))
                .thenReturn(Optional.empty());

        service.scheduleFor(appointment);

        ArgumentCaptor<AppointmentReminder> captor =
                ArgumentCaptor.forClass(AppointmentReminder.class);
        verify(reminderRepository, times(1)).save(captor.capture());

        assertEquals(ReminderType.ONE_DAY, captor.getValue().getReminderType());
    }

    @Test
    void sendsOneDayReminderEvenWhenPatientAlreadyConfirmed() {
        appointment.setPatientConfirmationStatus(PatientConfirmationStatus.CONFIRMED);
        appointment.setStartAppointmentAt(Timestamp.valueOf(
                LocalDate.now(ZoneId.of(ZONE)).plusDays(3).atTime(10, 0)));

        AppointmentReminder reminder = reminder(ReminderType.ONE_DAY, ReminderStatus.SENDING);
        when(reminderRepository.findById(reminder.getReminderId()))
                .thenReturn(Optional.of(reminder));
        when(whatsAppClient.sendTemplateMessage(
                anyString(), anyString(), anyString(), anyList()))
                .thenReturn(WhatsAppSendResult.sent("wamid-1"));

        service.deliverReminder(reminder.getReminderId());

        assertEquals(ReminderStatus.SENT, reminder.getStatus());
        assertEquals("wamid-1", reminder.getProviderMessageId());
        assertEquals(PatientConfirmationStatus.CONFIRMED,
                appointment.getPatientConfirmationStatus());
    }

    @Test
    void successfulReminderMarksPatientAsWaitingForAnswer() {
        appointment.setStartAppointmentAt(Timestamp.valueOf(
                LocalDate.now(ZoneId.of(ZONE)).plusDays(3).atTime(10, 0)));

        AppointmentReminder reminder = reminder(ReminderType.THREE_DAYS, ReminderStatus.SENDING);
        when(reminderRepository.findById(reminder.getReminderId()))
                .thenReturn(Optional.of(reminder));
        when(whatsAppClient.sendTemplateMessage(
                anyString(), anyString(), anyString(), anyList()))
                .thenReturn(WhatsAppSendResult.sent("wamid-2"));
        when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.deliverReminder(reminder.getReminderId());

        assertEquals(ReminderStatus.SENT, reminder.getStatus());
        assertEquals(PatientConfirmationStatus.WAITING_PATIENT_ANSWER,
                appointment.getPatientConfirmationStatus());
    }

    private AppointmentReminder reminder(ReminderType type, ReminderStatus status) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of(ZONE));
        AppointmentReminder reminder = new AppointmentReminder();
        reminder.setReminderId(UUID.randomUUID());
        reminder.setAppointment(appointment);
        reminder.setReminderType(type);
        reminder.setScheduledAt(Timestamp.valueOf(now));
        reminder.setNextAttemptAt(Timestamp.valueOf(now));
        reminder.setStatus(status);
        reminder.setAttemptCount(1);
        return reminder;
    }
}
