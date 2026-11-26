package com.example.Project1.Service;

import com.example.Project1.Config.WhatsAppProperties;
import com.example.Project1.Model.DTO.SendInteractiveButtonRequest;
import com.example.Project1.Model.DTO.SendInteractiveListRequest;
import com.example.Project1.Model.DTO.WhatsAppWebhookPayload;
import com.example.Project1.Model.Entity.*;
import com.example.Project1.repository.*;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Core business service managing the conversational state machine for the WhatsApp bot.
 * Handles both patient appointment intake workflows and doctor administrative commands.
 */
@Service
public class WhatsAppBotService {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppBotService.class);

    private static final String MENU_BUTTON = "Ver Opciones";
    private static final String MENU_SEARCH = "DOC_MENU_SEARCH";
    private static final String MENU_UPDATE = "DOC_MENU_UPDATE";
    private static final String MENU_PAY = "DOC_MENU_PAY";
    private static final String MENU_PATIENTS = "DOC_MENU_PATIENTS";
    private static final String MENU_REGISTER = "DOC_MENU_REGISTER";
    private static final String MENU_DATES = "DOC_MENU_DATES";

    private static final String DATE_CONFIRM_PREFIX = "DATE_CONFIRM_";
    private static final String DOCTOR_DATE_STATE = "DOC_AWAITING_CONFIRMATION_DATE";
    private static final String DOCTOR_IDLE_STATE = "DOC_IDLE";

    private static final String TARGET_PATIENT = "UPD_TARGET_PATIENT";
    private static final String TARGET_CITA = "UPD_TARGET_CITA";

    private static final String PATIENT_PREFIX = "UPD_PAT_";
    private static final String APPOINTMENT_PREFIX = "UPD_APPT_";
    private static final String FIELD_PREFIX = "UPD_FIELD_";
    private static final String CONFIRM_YES = "UPD_CONFIRM_YES";
    private static final String CONFIRM_NO = "UPD_CONFIRM_NO";
    private static final String PATIENTS_PAGE_PREFIX = "PATIENTS_PAGE_";

    private static final String SEARCH_PROMPT =
            "Ingrese el nombre, apellido, fecha o tratamiento del paciente que desea buscar";
    private static final String NOT_FOUND_MESSAGE = "El paciente que busca no está registrado";
    private static final String UPDATED_MESSAGE = "LOS DATOS HAN SIDO ACTUALIZADOS!";

    private static final List<String> PATIENT_REGISTRATION_FIELDS = List.of(
            "patientName", "patientLastname", "patientPhone", "patientAge",
            "patientState", "patientPaymentState");

    private final Map<String, DoctorSession> doctorSessions = new ConcurrentHashMap<>();

    private enum DoctorState {
        IDLE,
        AWAITING_SEARCH_QUERY,
        UPDATE_AWAITING_TARGET,
        UPDATE_AWAITING_PATIENT_SELECTION,
        UPDATE_AWAITING_APPOINTMENT_SELECTION,
        UPDATE_AWAITING_FIELD_SELECTION,
        UPDATE_AWAITING_NEW_VALUE,
        UPDATE_AWAITING_CONFIRM,
        REGISTER_AWAITING_FIELD
    }

    private enum UpdateTarget { PATIENT, CITA }

    private static final class DoctorSession {
        DoctorState state = DoctorState.IDLE;
        boolean updateMode;
        UpdateTarget updateTarget;
        List<UUID> patientCandidates = new ArrayList<>();
        UUID selectedPatientId;
        List<UUID> appointmentCandidates = new ArrayList<>();
        UUID selectedAppointmentId;
        String selectedField;
        Map<String, Object> pendingChanges = new LinkedHashMap<>();
        UUID registeringPatientId;
        int registerFieldIndex;
    }

    private record SearchOutcome(List<Patient> patients,
                                 Map<UUID, List<Appointment>> appointmentsByPatient) {}

    private final WhatsAppClient whatsAppClient;
    private final WhatsAppProperties properties;
    private final WhatsAppContactRepository contactRepository;
    private final WhatsAppSessionRepository sessionRepository;
    private final WhatsAppMessageRepository messageRepository;
    private final TreatmentsRepository treatmentsRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final DentistRepository dentistRepository;
    private final AppointmentReminderService reminderService;
    private final AppointmentConfirmationService confirmationService;

    public WhatsAppBotService(WhatsAppClient whatsAppClient,
                              WhatsAppProperties properties,
                              WhatsAppContactRepository contactRepository,
                              WhatsAppSessionRepository sessionRepository,
                              WhatsAppMessageRepository messageRepository,
                              TreatmentsRepository treatmentsRepository,
                              PatientRepository patientRepository,
                              AppointmentRepository appointmentRepository,
                              DentistRepository dentistRepository,
                              AppointmentReminderService reminderService,
                              AppointmentConfirmationService confirmationService) {
        this.whatsAppClient = whatsAppClient;
        this.properties = properties;
        this.contactRepository = contactRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.treatmentsRepository = treatmentsRepository;
        this.patientRepository = patientRepository;
        this.appointmentRepository = appointmentRepository;
        this.dentistRepository = dentistRepository;
        this.reminderService = reminderService;
        this.confirmationService = confirmationService;
    }

    /**
     * Entry point for processing raw incoming webhook payloads from Meta.
     */
    @Transactional
    public void processWebhookEvent(WhatsAppWebhookPayload payload) {
        if (payload == null || payload.entry() == null) {
            return;
        }

        for (WhatsAppWebhookPayload.Entry entry : payload.entry()) {
            if (entry.changes() == null) continue;

            for (WhatsAppWebhookPayload.Change change : entry.changes()) {
                if (change.value() == null || change.value().messages() == null) continue;

                for (WhatsAppWebhookPayload.IncomingMessage msg : change.value().messages()) {
                    handleIncomingMessage(msg, change.value().contacts());
                }
            }
        }
    }

    /**
     * Processes an individual incoming message, determining whether the sender
     * is the Doctor (admin commands) or a Patient (appointment scheduling).
     */
    private void handleIncomingMessage(WhatsAppWebhookPayload.IncomingMessage incomingMessage,
                                       List<WhatsAppWebhookPayload.Contact> contacts) {
        String fromPhone = incomingMessage.from();
        // Support both regular text and interactive button/list selections
        String messageBody = incomingMessage.getExtractedText();

        if (fromPhone == null || messageBody == null || messageBody.isBlank()) {
            return;
        }

        log.info("Received WhatsApp message from [{}]: {}", fromPhone, messageBody);

        // 1. Maintain Contact audit record
        WhatsAppContact contact = contactRepository.findByWaUserId(fromPhone)
                .orElseGet(() -> {
                    WhatsAppContact newContact = new WhatsAppContact();
                    newContact.setWaUserId(fromPhone);
                    newContact.setDisplayNumber(fromPhone);
                    newContact.setCreatedAt(new Timestamp(System.currentTimeMillis()));
                    newContact.setLastSeenAt(new Timestamp(System.currentTimeMillis()));
                    return contactRepository.save(newContact);
                });

        contact.setLastSeenAt(new Timestamp(System.currentTimeMillis()));
        contactRepository.save(contact);

        // Record incoming message in DB
        saveMessageRecord(contact, incomingMessage.id(), "INCOMING", messageBody);

        // -----------------------------------------------------------------------------------
        // DOCTOR COMMANDS WORKFLOW
        // If the sender is the clinic doctor, route to dedicated administrative handlers
        // rather than processing them as a new patient.
        // -----------------------------------------------------------------------------------
        String doctorPhone = properties.getDoctor() != null ? properties.getDoctor().getPhoneNumber() : null;
        if (doctorPhone != null && !doctorPhone.isBlank() && fromPhone.equals(doctorPhone)) {
            if (handleDoctorMessage(doctorPhone, incomingMessage, messageBody, contact)) {
                return;
            }
        }

        // -----------------------------------------------------------------------------------
        // PATIENT REMINDER RESPONSES
        // A Confirmar/Cancelar reply must bypass the patient intake state machine.
        // -----------------------------------------------------------------------------------
        if (confirmationService.handleReminderReply(fromPhone, incomingMessage)) {
            return;
        }

        // -----------------------------------------------------------------------------------
        // PATIENT INTAKE CONVERSATIONAL STATE MACHINE
        // -----------------------------------------------------------------------------------
        WhatsAppSession session = sessionRepository.findByWhatsAppContact_WaUserId(fromPhone)
                .orElseGet(() -> {
                    WhatsAppSession newSession = new WhatsAppSession();
                    newSession.setWhatsAppContact(contact);
                    newSession.setCurrentState("INITIAL");
                    newSession.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
                    return sessionRepository.save(newSession);
                });

        String state = session.getCurrentState() != null ? session.getCurrentState() : "INITIAL";

        if ("INITIAL".equalsIgnoreCase(state) || "COMPLETED".equalsIgnoreCase(state)) {
            // STEP 1 & 2: Patient greets the bot -> Bot responds asking for their full name & age
            String greetingPrompt = "Hello! Welcome to Dental Clinic.\n"
                    + "Please reply with your *Full Name* and *Age* (for example: *John Doe, 28*) so we can schedule your appointment with the Doctor.";

            whatsAppClient.sendTextMessage(fromPhone, greetingPrompt);
            saveMessageRecord(contact, null, "OUTGOING", greetingPrompt);

            session.setCurrentState("AWAITING_PATIENT_INFO");
            session.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            sessionRepository.save(session);

        } else if ("AWAITING_PATIENT_INFO".equalsIgnoreCase(state)) {
            // STEP 3: Patient replies with their details
            ParsedPatientInfo parsedInfo = parsePatientInfo(messageBody);

            if (parsedInfo == null || parsedInfo.name == null || parsedInfo.age == null) {
                String retryPrompt = "We couldn't quite extract your details. Please reply with your *Full Name* and *Age* (e.g. *Gabriel Santos, 25*).";
                whatsAppClient.sendTextMessage(fromPhone, retryPrompt);
                saveMessageRecord(contact, null, "OUTGOING", retryPrompt);
                return;
            }

            // Save or update Patient entity in DB
            Patient patient = patientRepository.findByPatientPhone(fromPhone)
                    .orElseGet(() -> {
                        Patient newPatient = new Patient();
                        newPatient.setPatientPhone(fromPhone);
                        return newPatient;
                    });

            patient.setPatientName(parsedInfo.name);
            patient.setPatientAge(parsedInfo.age);
            patient.setPatientState(PatientState.ON_TRACKING);
            patient = patientRepository.save(patient);

            contact.setPatientContact(patient);
            contactRepository.save(contact);

            // Compute suggested appointment slot (next available business day at 10 AM)
            String suggestedAppointment = generateSuggestedAppointment();

            // Create pending Appointment record
            Appointment appointment = new Appointment();
            appointment.setPatientAppointment(patient);
            appointment.setCategory("Consultation");
            appointment.setAppointmentStatus("PENDING");
            appointment.setNotes("Suggested: " + suggestedAppointment);
            appointment.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            appointment.setStartAppointmentAt(Timestamp.valueOf(LocalDate.now().plusDays(1).atTime(10, 0)));

            if (appointment.getPaymentMethod() == null) {
                patient.setPatientPaymentState(PatientPaymentState.UNDEFINED_PRICE);
                appointment.setPatientState(PatientState.UNDEFINED);
            }

            // Assign default active dentist if configured
            Optional<Dentist> activeDentist = dentistRepository.findFirstByIsDentistActiveTrue();
            activeDentist.ifPresent(appointment::setDentistAppointment);

            // Persist the fact that this specific appointment is waiting for the doctor's date.
            appointment.setAwaitingDateConfirmation(true);
            appointment.setDateConfirmationRequestedAt(new Timestamp(System.currentTimeMillis()));
            appointmentRepository.save(appointment);

            // Acknowledge receipt to the Patient
            String patientAck = "Gracias, " + parsedInfo.name + "! Hemos recibido tu información y notificado al Doctor para que confirme tu cita.";
            whatsAppClient.sendTextMessage(fromPhone, patientAck);
            saveMessageRecord(contact, null, "OUTGOING", patientAck);

            // Forward notification to the Doctor with an actionable button carrying the appointment id.
            String doctorMessage = "El paciente " + parsedInfo.name + " con edad " + parsedInfo.age
                    + " está esperando la fecha de su cita.\n"
                    + "Sugerencia: " + suggestedAppointment + ".\n"
                    + "Toque *Asignar fecha* para indicar la fecha.";
            if (doctorPhone != null && !doctorPhone.isBlank()) {
                whatsAppClient.sendInteractiveButton(
                        doctorPhone,
                        doctorMessage,
                        "Asignar fecha",
                        DATE_CONFIRM_PREFIX + appointment.getAppointmentId());
                log.info("Forwarded patient details to Doctor ({})", doctorPhone);
            } else {
                log.warn("Doctor phone is not configured; date confirmation request for appointment {} "
                        + "was not sent", appointment.getAppointmentId());
            }

            session.setCurrentState("COMPLETED");
            session.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            sessionRepository.save(session);
        }
    }

    /**
     * Handles administrative commands and interactive menu responses sent by the Doctor.
     *
     * @param doctorPhone     The verified phone number of the doctor
     * @param incomingMessage The incoming webhook message payload
     * @param messageBody     Text content of the message or selected menu option
     * @param contact         The persisted doctor contact, used for the date-confirmation session
     * @return true if the message was handled as a doctor command, false to allow default flow
     */
    private boolean handleDoctorMessage(String doctorPhone,
                                        WhatsAppWebhookPayload.IncomingMessage incomingMessage,
                                        String messageBody,
                                        WhatsAppContact contact) {
        DoctorSession session = doctorSessions.computeIfAbsent(doctorPhone, key -> new DoctorSession());
        synchronized (session) {
            return handleDoctorMessageLocked(doctorPhone, incomingMessage, messageBody, contact, session);
        }
    }

    private boolean handleDoctorMessageLocked(String doctorPhone,
                                              WhatsAppWebhookPayload.IncomingMessage incomingMessage,
                                              String messageBody,
                                              WhatsAppContact contact,
                                              DoctorSession session) {
        String selectedOptionId = incomingMessage.getInteractiveSelectionId();
        String trimmed = messageBody == null ? "" : messageBody.trim();

        if (isMenuText(trimmed)) {
            clearDoctorDateSelection(contact);
            resetDoctorSession(session);
            sendDoctorMenu(doctorPhone);
            return true;
        }

        if ("fechas".equalsIgnoreCase(trimmed)) {
            clearDoctorDateSelection(contact);
            resetDoctorSession(session);
            sendAwaitingDateList(doctorPhone);
            return true;
        }

        if (selectedOptionId != null && selectedOptionId.startsWith("DOC_MENU_")) {
            clearDoctorDateSelection(contact);
            return handleMenuSelection(doctorPhone, session, selectedOptionId);
        }

        if (selectedOptionId != null && selectedOptionId.startsWith(DATE_CONFIRM_PREFIX)) {
            resetDoctorSession(session);
            return handleDateConfirmationSelection(doctorPhone, contact, selectedOptionId);
        }

        if (session.state != DoctorState.IDLE) {
            return handleDoctorState(doctorPhone, session, trimmed, selectedOptionId);
        }

        if ("citas".equalsIgnoreCase(trimmed)) {
            clearDoctorDateSelection(contact);
            showAppointmentsPage(doctorPhone, 0);
            return true;
        }
        if ("pago".equalsIgnoreCase(trimmed)) {
            clearDoctorDateSelection(contact);
            sendTreatmentList(doctorPhone);
            return true;
        }
        if (selectedOptionId != null && selectedOptionId.startsWith("CITAS_PAGE_")) {
            clearDoctorDateSelection(contact);
            try {
                int nextPage = Integer.parseInt(selectedOptionId.replace("CITAS_PAGE_", ""));
                showAppointmentsPage(doctorPhone, nextPage);
            } catch (NumberFormatException e) {
                log.error("Failed to parse page number from action id: {}", selectedOptionId, e);
            }
            return true;
        }
        if (selectedOptionId != null && selectedOptionId.startsWith(PATIENTS_PAGE_PREFIX)) {
            clearDoctorDateSelection(contact);
            try {
                int nextPage = Integer.parseInt(selectedOptionId.replace(PATIENTS_PAGE_PREFIX, ""));
                showPatientsPage(doctorPhone, nextPage);
            } catch (NumberFormatException e) {
                log.error("Failed to parse page number from action id: {}", selectedOptionId, e);
            }
            return true;
        }

        UUID selectedAppointmentId = getDoctorSelectedAppointmentId(contact);
        if (selectedAppointmentId != null && selectedOptionId == null) {
            return handleDoctorDateConfirmation(doctorPhone, contact, selectedAppointmentId, trimmed);
        }

        if (selectedOptionId == null
                && appointmentRepository.countByAwaitingDateConfirmationTrue() > 0) {
            sendAwaitingDateList(doctorPhone);
            return true;
        }

        if (selectedOptionId != null
                && !selectedOptionId.startsWith("UPD_")
                && !selectedOptionId.startsWith("REG_")) {
            clearDoctorDateSelection(contact);
            handleLegacyTreatmentSelection(doctorPhone, messageBody, selectedOptionId);
            return true;
        }

        whatsAppClient.sendTextMessage(doctorPhone,
                "No entendí. Escriba *hola* para ver las opciones disponibles.");
        return true;
    }

    private boolean isMenuText(String text) {
        String normalized = normalize(text);
        return normalized.equals("hola") || normalized.equals("menu") || normalized.equals("opciones");
    }

    private void sendDoctorMenu(String doctorPhone) {
        List<SendInteractiveListRequest.Row> rows = List.of(
                new SendInteractiveListRequest.Row(MENU_SEARCH, "Buscar", "Buscar paciente"),
                new SendInteractiveListRequest.Row(MENU_UPDATE, "Cambiar", "Actualizar paciente o cita"),
                new SendInteractiveListRequest.Row(MENU_PAY, "Cobrar", "Registrar pago de paciente"),
                new SendInteractiveListRequest.Row(MENU_PATIENTS, "Pacientes", "Mostrar todos los pacientes"),
                new SendInteractiveListRequest.Row(MENU_DATES, "Fechas", "Asignar fechas pendientes"),
                new SendInteractiveListRequest.Row(MENU_REGISTER, "Registrar paciente", "Registrar un nuevo paciente")
        );
        whatsAppClient.sendInteractiveList(doctorPhone,
                "¡Hola Doctor! ¿Qué desea hacer?", MENU_BUTTON, rows);
    }

    private boolean handleMenuSelection(String doctorPhone, DoctorSession session, String menuId) {
        resetDoctorSession(session);
        switch (menuId) {
            case MENU_SEARCH -> startSearchFlow(doctorPhone, session, false);
            case MENU_UPDATE -> sendUpdateTargetButtons(doctorPhone, session);
            case MENU_PAY -> sendTreatmentList(doctorPhone);
            case MENU_PATIENTS -> showPatientsPage(doctorPhone, 0);
            case MENU_DATES -> sendAwaitingDateList(doctorPhone);
            case MENU_REGISTER -> startPatientRegistration(doctorPhone, session);
            default -> sendDoctorMenu(doctorPhone);
        }
        return true;
    }

    private boolean handleDoctorState(String doctorPhone, DoctorSession session,
                                      String message, String selectedOptionId) {
        switch (session.state) {
            case AWAITING_SEARCH_QUERY -> handleSearchQuery(doctorPhone, session, message);
            case UPDATE_AWAITING_TARGET ->
                    handleUpdateTargetSelection(doctorPhone, session, message, selectedOptionId);
            case UPDATE_AWAITING_PATIENT_SELECTION ->
                    handleUpdatePatientSelection(doctorPhone, session, selectedOptionId);
            case UPDATE_AWAITING_APPOINTMENT_SELECTION ->
                    handleUpdateAppointmentSelection(doctorPhone, session, selectedOptionId);
            case UPDATE_AWAITING_FIELD_SELECTION ->
                    handleUpdateFieldSelection(doctorPhone, session, selectedOptionId);
            case UPDATE_AWAITING_NEW_VALUE -> handleUpdateNewValue(doctorPhone, session, message);
            case UPDATE_AWAITING_CONFIRM ->
                    handleUpdateConfirmation(doctorPhone, session, selectedOptionId, message);
            case REGISTER_AWAITING_FIELD -> handleRegisterNewValue(doctorPhone, session, message);
            default -> {
                resetDoctorSession(session);
                return false;
            }
        }
        return true;
    }

    private void resetDoctorSession(DoctorSession session) {
        session.state = DoctorState.IDLE;
        session.updateMode = false;
        session.updateTarget = null;
        session.patientCandidates.clear();
        session.selectedPatientId = null;
        session.appointmentCandidates.clear();
        session.selectedAppointmentId = null;
        session.selectedField = null;
        session.pendingChanges.clear();
        session.registeringPatientId = null;
        session.registerFieldIndex = 0;
    }

    private boolean handleDateConfirmationSelection(String doctorPhone,
                                                     WhatsAppContact contact,
                                                     String selectedOptionId) {
        UUID appointmentId;
        try {
            appointmentId = UUID.fromString(
                    selectedOptionId.substring(DATE_CONFIRM_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            whatsAppClient.sendTextMessage(doctorPhone, "Cita inválida.");
            return true;
        }

        Appointment appointment = appointmentRepository.findById(appointmentId).orElse(null);
        if (appointment == null || !appointment.isAwaitingDateConfirmation()) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "Esa cita ya no está pendiente de fecha.");
            return true;
        }

        saveDoctorDateSelection(contact, appointmentId);

        String patientName = appointment.getPatientAppointment() != null
                ? fullName(appointment.getPatientAppointment())
                : "paciente";
        whatsAppClient.sendTextMessage(doctorPhone,
                "Escriba la nueva fecha para *" + patientName + "*:");
        return true;
    }

    private boolean handleDoctorDateConfirmation(String doctorPhone,
                                                 WhatsAppContact contact,
                                                 UUID appointmentId,
                                                 String messageBody) {
        LocalDateTime customDate = parsedDoctorCustomizedDate(messageBody);
        if (customDate == null) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "No logré reconocer la fecha. Por favor indíquela como: "
                            + "*día de mes hora* (Ejemplo: *24 de noviembre 10:00am*) "
                            + "o envíe *fechas* para ver el listado.");
            return true;
        }

        Appointment appointment = appointmentRepository.findById(appointmentId).orElse(null);
        if (appointment == null || !appointment.isAwaitingDateConfirmation()) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "Esa cita ya no está pendiente de fecha.");
            clearDoctorDateSelection(contact);
            return true;
        }

        appointment.setStartAppointmentAt(Timestamp.valueOf(customDate));
        appointment.setAppointmentStatus("CONFIRMED");
        appointment.setAwaitingDateConfirmation(false);
        appointment.setDateConfirmationRequestedAt(null);
        // A date change requires a fresh patient response.
        appointment.setPatientConfirmationStatus(PatientConfirmationStatus.PENDING);
        appointment.setPatientRespondedAt(null);
        appointmentRepository.save(appointment);

        // Create/update the 3-day and 1-day reminder rows.
        reminderService.scheduleFor(appointment);

        clearDoctorDateSelection(contact);

        DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' hh:mma", Locale.of("es", "ES"));
        String formattedDate = customDate.format(formatter);
        whatsAppClient.sendTextMessage(doctorPhone,
                "✅ Cita confirmada con éxito para el *" + formattedDate + "*.");

        Patient patient = appointment.getPatientAppointment();
        if (patient != null && patient.getPatientPhone() != null) {
            whatsAppClient.sendTextMessage(patient.getPatientPhone(),
                    "¡Hola! El doctor ha confirmado tu cita para el *"
                            + formattedDate + "*. Te esperamos en la clínica.");
        }
        return true;
    }

    private void sendAwaitingDateList(String doctorPhone) {
        List<Appointment> awaiting = appointmentRepository
                .findByAwaitingDateConfirmationTrueOrderByDateConfirmationRequestedAtAsc();

        if (awaiting.isEmpty()) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "No hay citas pendientes de fecha.");
            return;
        }

        List<SendInteractiveListRequest.Row> rows = awaiting.stream()
                .limit(10)
                .map(appointment -> {
                    String patientName = appointment.getPatientAppointment() != null
                            ? fullName(appointment.getPatientAppointment())
                            : "Sin nombre";
                    String description = "Solicitada: "
                            + formatRequestedAt(appointment.getDateConfirmationRequestedAt());
                    return new SendInteractiveListRequest.Row(
                            DATE_CONFIRM_PREFIX + appointment.getAppointmentId(),
                            truncate(patientName, 24),
                            truncate(description, 72));
                })
                .toList();

        whatsAppClient.sendInteractiveList(doctorPhone,
                "Seleccione la cita a la que desea asignar la fecha",
                "Ver Citas", rows);

        if (awaiting.size() > 10) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "Hay más de 10 citas pendientes. Atienda las más antiguas primero.");
        }
    }

    private String formatRequestedAt(Timestamp timestamp) {
        if (timestamp == null) {
            return "fecha no registrada";
        }
        return new SimpleDateFormat("dd/MM/yyyy HH:mm").format(timestamp);
    }

    private WhatsAppSession getOrCreateDoctorSession(WhatsAppContact contact) {
        Optional<WhatsAppSession> existing = sessionRepository
                .findByWhatsAppContact_WaUserId(contact.getWaUserId());
        if (existing.isPresent()) {
            return existing.get();
        }

        WhatsAppSession session = new WhatsAppSession();
        session.setWhatsAppContact(contact);
        session.setCurrentState(DOCTOR_IDLE_STATE);
        session.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        return sessionRepository.save(session);
    }

    private void saveDoctorDateSelection(WhatsAppContact contact, UUID appointmentId) {
        WhatsAppSession session = getOrCreateDoctorSession(contact);
        session.setCurrentState(DOCTOR_DATE_STATE);
        session.setContextJSON(appointmentId.toString());
        session.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        sessionRepository.save(session);
    }

    private UUID getDoctorSelectedAppointmentId(WhatsAppContact contact) {
        Optional<WhatsAppSession> sessionOpt = sessionRepository
                .findByWhatsAppContact_WaUserId(contact.getWaUserId());
        if (sessionOpt.isEmpty()) {
            return null;
        }

        WhatsAppSession session = sessionOpt.get();
        if (!DOCTOR_DATE_STATE.equals(session.getCurrentState())) {
            return null;
        }

        String context = session.getContextJSON();
        if (context == null || context.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(context.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid doctor date-confirmation context '{}'", context);
            return null;
        }
    }

    private void clearDoctorDateSelection(WhatsAppContact contact) {
        Optional<WhatsAppSession> sessionOpt = sessionRepository
                .findByWhatsAppContact_WaUserId(contact.getWaUserId());
        if (sessionOpt.isEmpty()) {
            return;
        }

        WhatsAppSession session = sessionOpt.get();
        if (!DOCTOR_DATE_STATE.equals(session.getCurrentState())) {
            return;
        }

        session.setCurrentState(DOCTOR_IDLE_STATE);
        session.setContextJSON(null);
        session.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        sessionRepository.save(session);
    }

    private void sendTreatmentList(String doctorPhone) {
        List<SendInteractiveListRequest.Row> treatmentRows =
                treatmentsRepository.getAllTreatmentsIgnoreCase();
        whatsAppClient.sendInteractiveList(doctorPhone,
                "Doctor, por favor seleccione el tratamiento que recibió el paciente:",
                "Ver Tratamientos", treatmentRows);
        log.info("Dispatched interactive treatment list to Doctor ({})", doctorPhone);
    }

    private void handleLegacyTreatmentSelection(String doctorPhone,
                                                String messageBody,
                                                String selectedOptionId) {
        log.info("Doctor selected treatment option: id={}, title={}", selectedOptionId, messageBody);
        String confirmationText = "Tratamiento registrado con éxito: *" + messageBody + "*.\n"
                + "El pago del paciente es de: $" + treatmentsRepository.findPriceByTreatment(selectedOptionId);
        whatsAppClient.sendTextMessage(doctorPhone, confirmationText);

        String promptForPayment = "De qué forma realizó el pago el paciente?: \n";
        String buttonTextPayment = "Ver métodos de pago";
        List<SendInteractiveListRequest.Row> paymentMethods = List.of(
                new SendInteractiveListRequest.Row("PAY_TRANSFERENCIA", "TRANSFERENCIA", "Por Banca móvil de cualquier banco"),
                new SendInteractiveListRequest.Row("PAY_EFECTIVO", "EFECTIVO", "Billetes/monedas")
        );
        whatsAppClient.sendInteractiveList(doctorPhone, promptForPayment, buttonTextPayment, paymentMethods);
        if ("PAY_TRANSFERENCIA".equals(selectedOptionId)) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "El Paciente ha pagado todo, parcialmente, o queda debiéndole?");
        }
    }

    private void startPatientRegistration(String doctorPhone, DoctorSession session) {
        Patient patient = new Patient();
        patient.setPatientState(PatientState.UNDEFINED);
        patient = patientRepository.save(patient);

        session.registeringPatientId = patient.getPatientUUID();
        session.registerFieldIndex = 0;
        session.state = DoctorState.REGISTER_AWAITING_FIELD;
        askRegisterField(doctorPhone, session);
    }

    private void askRegisterField(String doctorPhone, DoctorSession session) {
        String field = PATIENT_REGISTRATION_FIELDS.get(session.registerFieldIndex);
        switch (field) {
            case "patientState" -> whatsAppClient.sendInteractiveButtons(doctorPhone,
                    "¿Cuál es el estado del paciente?",
                    List.of(
                            SendInteractiveButtonRequest.replyButton(
                                    "REG_STATE_UNDEFINED", "POR DEFINIR"),
                            SendInteractiveButtonRequest.replyButton(
                                    "REG_STATE_ON_TRACKING", "EN SEGUIMIENTO"),
                            SendInteractiveButtonRequest.replyButton(
                                    "REG_STATE_CLOSED", "CERRADO")
                    ));
            case "patientPaymentState" -> whatsAppClient.sendInteractiveList(doctorPhone,
                    "¿Cuál es el estado de pago del paciente?",
                    "Ver Estados",
                    List.of(
                            new SendInteractiveListRequest.Row(
                                    "REG_PAY_COMPLETE", "COMPLETO", null),
                            new SendInteractiveListRequest.Row(
                                    "REG_PAY_PARTIALLY", "PARCIALMENTE", null),
                            new SendInteractiveListRequest.Row(
                                    "REG_PAY_IN_DEBT", "EN DEUDA", null),
                            new SendInteractiveListRequest.Row(
                                    "REG_PAY_UNDEFINED_PRICE", "POR DEFINIR", null)
                    ));
            default -> whatsAppClient.sendTextMessage(doctorPhone, registerFieldPrompt(field));
        }
    }

    private void handleRegisterNewValue(String doctorPhone, DoctorSession session, String message) {
        String field = PATIENT_REGISTRATION_FIELDS.get(session.registerFieldIndex);
        Object value;
        try {
            value = parseFieldValue(field, message);
        } catch (IllegalArgumentException e) {
            whatsAppClient.sendTextMessage(doctorPhone, e.getMessage());
            askRegisterField(doctorPhone, session);
            return;
        }

        Patient patient = patientRepository.findById(session.registeringPatientId).orElse(null);
        if (patient == null) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "No se encontró el paciente en registro.");
            resetDoctorSession(session);
            return;
        }

        switch (field) {
            case "patientName" -> patient.setPatientName((String) value);
            case "patientLastname" -> patient.setPatientLastname((String) value);
            case "patientPhone" -> patient.setPatientPhone((String) value);
            case "patientAge" -> patient.setPatientAge((Integer) value);
            case "patientState" -> patient.setPatientState((PatientState) value);
            case "patientPaymentState" ->
                    patient.setPatientPaymentState((PatientPaymentState) value);
            default -> {
            }
        }
        patientRepository.save(patient);

        session.registerFieldIndex++;
        if (session.registerFieldIndex >= PATIENT_REGISTRATION_FIELDS.size()) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "Paciente *" + fullName(patient) + "* registrado con éxito.");
            resetDoctorSession(session);
            return;
        }
        askRegisterField(doctorPhone, session);
    }

    private String registerFieldPrompt(String field) {
        return switch (field) {
            case "patientName" -> "¿Cuál es el nombre/s del paciente?";
            case "patientLastname" -> "¿Cuál es el apellido del paciente?";
            case "patientPhone" -> "¿Cuál es el teléfono del paciente?";
            case "patientAge" -> "¿Cuál es la edad del paciente?";
            case "patientState" ->
                    "¿Cuál es el estado del paciente? (POR DEFINIR, EN SEGUIMIENTO, CERRADO)";
            case "patientPaymentState" ->
                    "¿Cuál es el estado de pago del paciente? (COMPLETO, PARCIALMENTE, EN DEUDA, POR DEFINIR)";
            default -> "Ingrese el valor:";
        };
    }

    private void startSearchFlow(String doctorPhone, DoctorSession session, boolean updateMode) {
        session.patientCandidates.clear();
        session.selectedPatientId = null;
        session.appointmentCandidates.clear();
        session.selectedAppointmentId = null;
        session.selectedField = null;
        session.pendingChanges.clear();
        session.updateMode = updateMode;
        session.state = DoctorState.AWAITING_SEARCH_QUERY;
        whatsAppClient.sendTextMessage(doctorPhone, SEARCH_PROMPT);
    }

    private void sendUpdateTargetButtons(String doctorPhone, DoctorSession session) {
        session.updateMode = true;
        session.updateTarget = null;
        session.state = DoctorState.UPDATE_AWAITING_TARGET;
        whatsAppClient.sendInteractiveButtons(doctorPhone, "¿Qué desea buscar?",
                List.of(
                        SendInteractiveButtonRequest.replyButton(TARGET_PATIENT, "PACIENTE"),
                        SendInteractiveButtonRequest.replyButton(TARGET_CITA, "CITA")
                ));
    }

    private void handleUpdateTargetSelection(String doctorPhone, DoctorSession session,
                                             String message, String selectedOptionId) {
        String id = selectedOptionId;
        if (id == null) {
            String normalized = normalize(message);
            if (normalized.equals("paciente")) {
                id = TARGET_PATIENT;
            } else if (normalized.equals("cita")) {
                id = TARGET_CITA;
            }
        }

        if (TARGET_PATIENT.equals(id)) {
            session.updateTarget = UpdateTarget.PATIENT;
            startSearchFlow(doctorPhone, session, true);
        } else if (TARGET_CITA.equals(id)) {
            session.updateTarget = UpdateTarget.CITA;
            startSearchFlow(doctorPhone, session, true);
        } else {
            sendUpdateTargetButtons(doctorPhone, session);
        }
    }

    private void handleSearchQuery(String doctorPhone, DoctorSession session, String query) {
        if (query == null || query.isBlank()) {
            whatsAppClient.sendTextMessage(doctorPhone, SEARCH_PROMPT);
            return;
        }

        SearchOutcome outcome = searchPatientsAndAppointments(query);
        if (outcome.patients().isEmpty()) {
            whatsAppClient.sendTextMessage(doctorPhone, NOT_FOUND_MESSAGE);
            resetDoctorSession(session);
            return;
        }

        if (session.updateMode) {
            session.patientCandidates = outcome.patients().stream()
                    .map(Patient::getPatientUUID)
                    .collect(Collectors.toCollection(ArrayList::new));

            if (outcome.patients().size() == 1) {
                session.selectedPatientId = outcome.patients().get(0).getPatientUUID();
                proceedAfterPatientSelected(doctorPhone, session);
            } else {
                sendPatientSelectionList(doctorPhone, outcome.patients());
                session.state = DoctorState.UPDATE_AWAITING_PATIENT_SELECTION;
            }
            return;
        }

        if (outcome.patients().size() == 1) {
            Patient patient = outcome.patients().get(0);
            showPatientDetails(doctorPhone, patient,
                    outcome.appointmentsByPatient().getOrDefault(patient.getPatientUUID(), List.of()));
        } else {
            sendSearchResultsList(doctorPhone, outcome);
        }
        resetDoctorSession(session);
    }

    private void sendPatientSelectionList(String doctorPhone, List<Patient> patients) {
        List<SendInteractiveListRequest.Row> rows = patients.stream()
                .limit(10)
                .map(patient -> new SendInteractiveListRequest.Row(
                        PATIENT_PREFIX + patient.getPatientUUID(),
                        truncate(fullName(patient), 24),
                        truncate("Tel: " + nullSafe(patient.getPatientPhone())
                                + " | Edad: " + ageLabel(patient), 72)))
                .toList();

        whatsAppClient.sendInteractiveList(doctorPhone,
                "Seleccione el paciente que desea actualizar", "Ver Pacientes", rows);
        if (patients.size() > 10) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "Hay más de 10 resultados. Refine la búsqueda para ver el paciente correcto.");
        }
    }

    private void handleUpdatePatientSelection(String doctorPhone, DoctorSession session,
                                              String selectedOptionId) {
        if (selectedOptionId == null || !selectedOptionId.startsWith(PATIENT_PREFIX)) {
            whatsAppClient.sendTextMessage(doctorPhone, "Seleccione un paciente de la lista.");
            return;
        }

        UUID patientId;
        try {
            patientId = UUID.fromString(selectedOptionId.substring(PATIENT_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            whatsAppClient.sendTextMessage(doctorPhone, "Paciente inválido.");
            return;
        }

        if (patientRepository.findById(patientId).isEmpty()) {
            whatsAppClient.sendTextMessage(doctorPhone, "Paciente no encontrado.");
            return;
        }

        session.selectedPatientId = patientId;
        proceedAfterPatientSelected(doctorPhone, session);
    }

    private void proceedAfterPatientSelected(String doctorPhone, DoctorSession session) {
        Patient patient = patientRepository.findById(session.selectedPatientId).orElse(null);
        if (patient == null) {
            whatsAppClient.sendTextMessage(doctorPhone, "Paciente no encontrado.");
            resetDoctorSession(session);
            return;
        }

        if (session.updateTarget == UpdateTarget.CITA) {
            List<Appointment> appointments = sortedAppointments(
                    appointmentRepository.findByPatientAppointment_PatientUUID(patient.getPatientUUID()));

            if (appointments.isEmpty()) {
                whatsAppClient.sendTextMessage(doctorPhone,
                        "El paciente *" + fullName(patient) + "* no tiene citas registradas.");
                resetDoctorSession(session);
                return;
            }

            session.appointmentCandidates = appointments.stream()
                    .map(Appointment::getAppointmentId)
                    .collect(Collectors.toCollection(ArrayList::new));

            if (appointments.size() == 1) {
                session.selectedAppointmentId = appointments.get(0).getAppointmentId();
                showUpdateFieldList(doctorPhone, session);
            } else {
                sendAppointmentSelectionList(doctorPhone, appointments);
                session.state = DoctorState.UPDATE_AWAITING_APPOINTMENT_SELECTION;
            }
        } else {
            showUpdateFieldList(doctorPhone, session);
        }
    }

    private void sendAppointmentSelectionList(String doctorPhone, List<Appointment> appointments) {
        List<SendInteractiveListRequest.Row> rows = appointments.stream()
                .limit(10)
                .map(appointment -> new SendInteractiveListRequest.Row(
                        APPOINTMENT_PREFIX + appointment.getAppointmentId(),
                        truncate(formatDateTimeShort(appointment), 24),
                        truncate(appointmentSummary(appointment), 72)))
                .toList();

        whatsAppClient.sendInteractiveList(doctorPhone,
                "Seleccione la cita que desea actualizar", "Ver Citas", rows);
        if (appointments.size() > 10) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "Hay más de 10 citas. Refine la búsqueda para ver la cita correcta.");
        }
    }

    private void handleUpdateAppointmentSelection(String doctorPhone, DoctorSession session,
                                                  String selectedOptionId) {
        if (selectedOptionId == null || !selectedOptionId.startsWith(APPOINTMENT_PREFIX)) {
            whatsAppClient.sendTextMessage(doctorPhone, "Seleccione una cita de la lista.");
            return;
        }

        UUID appointmentId;
        try {
            appointmentId = UUID.fromString(selectedOptionId.substring(APPOINTMENT_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            whatsAppClient.sendTextMessage(doctorPhone, "Cita inválida.");
            return;
        }

        if (appointmentRepository.findById(appointmentId).isEmpty()) {
            whatsAppClient.sendTextMessage(doctorPhone, "Cita no encontrada.");
            return;
        }

        session.selectedAppointmentId = appointmentId;
        showUpdateFieldList(doctorPhone, session);
    }

    private void showUpdateFieldList(String doctorPhone, DoctorSession session) {
        Patient patient = patientRepository.findById(session.selectedPatientId).orElse(null);
        if (patient == null) {
            whatsAppClient.sendTextMessage(doctorPhone, "Paciente no encontrado.");
            resetDoctorSession(session);
            return;
        }

        Appointment appointment = null;
        if (session.updateTarget == UpdateTarget.CITA) {
            appointment = appointmentRepository.findById(session.selectedAppointmentId).orElse(null);
            if (appointment == null) {
                whatsAppClient.sendTextMessage(doctorPhone, "Cita no encontrada.");
                resetDoctorSession(session);
                return;
            }
        }

        List<SendInteractiveListRequest.Row> rows =
                buildUpdateFieldRows(patient, appointment, session.updateTarget);
        whatsAppClient.sendInteractiveList(doctorPhone,
                "¿Qué desearía cambiar?", "Ver Campos", rows);
        session.state = DoctorState.UPDATE_AWAITING_FIELD_SELECTION;
    }

    private List<SendInteractiveListRequest.Row> buildUpdateFieldRows(Patient patient,
                                                                      Appointment appointment,
                                                                      UpdateTarget target) {
        return validFields(target).stream()
                .map(field -> new SendInteractiveListRequest.Row(
                        FIELD_PREFIX + field,
                        truncate(fieldLabel(field), 24),
                        truncate("Actual: " + currentFieldValue(patient, appointment, field), 72)))
                .toList();
    }

    private List<String> validFields(UpdateTarget target) {
        return target == UpdateTarget.PATIENT
                ? List.of("patientName", "patientLastname", "patientPhone", "patientAge",
                          "patientState", "patientPaymentState")
                : List.of("patientAppointment", "dentistAppointment", "treatments", "category",
                          "appointmentStatus", "notes", "appointmentDateTime", "paymentMethod");
    }

    private void handleUpdateFieldSelection(String doctorPhone, DoctorSession session,
                                            String selectedOptionId) {
        if (selectedOptionId == null || !selectedOptionId.startsWith(FIELD_PREFIX)) {
            whatsAppClient.sendTextMessage(doctorPhone, "Seleccione un campo de la lista.");
            return;
        }

        String field = selectedOptionId.substring(FIELD_PREFIX.length());
        if (!validFields(session.updateTarget).contains(field)) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "Ese campo no es válido para esta actualización.");
            return;
        }

        session.selectedField = field;
        session.state = DoctorState.UPDATE_AWAITING_NEW_VALUE;
        whatsAppClient.sendTextMessage(doctorPhone,
                "Escriba el nuevo " + fieldPromptLabel(field) + " que desea poner:");
    }

    private void handleUpdateNewValue(String doctorPhone, DoctorSession session, String message) {
        try {
            Object value = parseFieldValue(session.selectedField, message);
            session.pendingChanges.put(canonicalField(session.selectedField), value);
        } catch (IllegalArgumentException e) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    e.getMessage() + "\nEscriba el nuevo "
                            + fieldPromptLabel(session.selectedField) + " que desea poner:");
            return;
        }

        session.state = DoctorState.UPDATE_AWAITING_CONFIRM;
        sendYesNoConfirmation(doctorPhone);
    }

    private void sendYesNoConfirmation(String doctorPhone) {
        whatsAppClient.sendInteractiveButtons(doctorPhone,
                "Eso es todo lo que desea cambiar de este paciente?",
                List.of(
                        SendInteractiveButtonRequest.replyButton(CONFIRM_YES, "SI"),
                        SendInteractiveButtonRequest.replyButton(CONFIRM_NO, "NO")
                ));
    }

    private void handleUpdateConfirmation(String doctorPhone, DoctorSession session,
                                          String selectedOptionId, String message) {
        String choice = selectedOptionId;
        if (choice == null) {
            String normalized = normalize(message);
            if (normalized.equals("si")) {
                choice = CONFIRM_YES;
            } else if (normalized.equals("no")) {
                choice = CONFIRM_NO;
            }
        }

        if (CONFIRM_NO.equals(choice)) {
            session.state = DoctorState.UPDATE_AWAITING_FIELD_SELECTION;
            showUpdateFieldList(doctorPhone, session);
            return;
        }

        if (CONFIRM_YES.equals(choice)) {
            applyPendingChanges(session);
            whatsAppClient.sendTextMessage(doctorPhone, UPDATED_MESSAGE);
            resetDoctorSession(session);
            return;
        }

        sendYesNoConfirmation(doctorPhone);
    }

    private void applyPendingChanges(DoctorSession session) {
        Patient patient = patientRepository.findById(session.selectedPatientId).orElse(null);
        if (patient == null) {
            log.warn("Cannot apply changes: patient {} not found", session.selectedPatientId);
            return;
        }

        Appointment appointment = session.selectedAppointmentId == null ? null
                : appointmentRepository.findById(session.selectedAppointmentId).orElse(null);

        boolean dateChanged = false;
        boolean statusChanged = false;

        for (Map.Entry<String, Object> entry : session.pendingChanges.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();

            switch (field) {
                case "patientName" -> patient.setPatientName((String) value);
                case "patientLastname" -> patient.setPatientLastname((String) value);
                case "patientPhone" -> patient.setPatientPhone((String) value);
                case "patientAge" -> patient.setPatientAge((Integer) value);
                case "patientState" -> patient.setPatientState((PatientState) value);
                case "patientPaymentState" ->
                        patient.setPatientPaymentState((PatientPaymentState) value);
                case "dentistAppointment" -> {
                    if (appointment != null) {
                        dentistRepository.findById((UUID) value)
                                .ifPresent(appointment::setDentistAppointment);
                    }
                }
                case "treatments" -> {
                    if (appointment != null) {
                        treatmentsRepository.findById((Long) value)
                                .ifPresent(treatment ->
                                        appointment.setTreatments(List.of(treatment)));
                    }
                }
                case "category" -> {
                    if (appointment != null) {
                        appointment.setCategory((String) value);
                    }
                }
                case "appointmentStatus" -> {
                    if (appointment != null) {
                        appointment.setAppointmentStatus((String) value);
                        statusChanged = true;
                    }
                }
                case "notes" -> {
                    if (appointment != null) {
                        appointment.setNotes((String) value);
                    }
                }
                case "paymentMethod" -> {
                    if (appointment != null) {
                        appointment.setPaymentMethod((PaymentMethod) value);
                    }
                }
                case "appointmentDateTime" -> {
                    if (appointment != null) {
                        LocalDateTime dateTime = (LocalDateTime) value;
                        appointment.setAppointmentDateTime(dateTime);
                        appointment.setStartAppointmentAt(Timestamp.valueOf(dateTime));
                        dateChanged = true;
                    }
                }
                default -> log.warn("Unknown pending change field: {}", field);
            }
        }

        patientRepository.save(patient);

        if (appointment != null) {
            if (dateChanged) {
                // The old patient response no longer applies to the new date.
                appointment.setPatientConfirmationStatus(PatientConfirmationStatus.PENDING);
                appointment.setPatientRespondedAt(null);
            }

            appointmentRepository.save(appointment);

            if (statusChanged && "CANCELLED".equalsIgnoreCase(appointment.getAppointmentStatus())) {
                reminderService.cancelFor(appointment);
            } else if (dateChanged || statusChanged) {
                // scheduleFor is idempotent for already-sent reminders on the same date.
                reminderService.scheduleFor(appointment);
            }
        }
    }

    private Object parseFieldValue(String field, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("El valor no puede estar vacío.");
        }

        return switch (canonicalField(field)) {
            case "patientName", "patientLastname", "patientPhone", "category", "notes" -> value;
            case "patientAge" -> parseAge(value);
            case "patientState" -> parsePatientState(value);
            case "patientPaymentState" -> parsePatientPaymentState(value);
            case "appointmentDateTime" -> parseRequiredDateTime(value);
            case "dentistAppointment" -> resolveDentistId(value);
            case "treatments" -> resolveTreatmentId(value);
            case "appointmentStatus" -> parseAppointmentStatus(value);
            case "paymentMethod" -> parsePaymentMethod(value);
            default -> throw new IllegalArgumentException("Campo desconocido.");
        };
    }

    private Integer parseAge(String value) {
        try {
            int age = Integer.parseInt(value);
            if (age < 0 || age > 120) {
                throw new IllegalArgumentException("La edad debe estar entre 0 y 120.");
            }
            return age;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("La edad debe ser un número.");
        }
    }

    private PatientState parsePatientState(String value) {
        String normalized = normalize(value);
        if (normalized.contains("por definir") || normalized.equals("undefined")) {
            return PatientState.UNDEFINED;
        }
        if (normalized.contains("seguimiento") || normalized.equals("on tracking")
                || normalized.equals("on_tracking")) {
            return PatientState.ON_TRACKING;
        }
        if (normalized.contains("cerrado") || normalized.equals("closed")) {
            return PatientState.CLOSED;
        }
        throw new IllegalArgumentException(
                "Estado inválido. Use: POR DEFINIR, EN SEGUIMIENTO o CERRADO.");
    }

    private PatientPaymentState parsePatientPaymentState(String value) {
        String normalized = normalize(value);
        if (normalized.contains("completo") || normalized.equals("complete")) {
            return PatientPaymentState.COMPLETE;
        }
        if (normalized.contains("parcial") || normalized.equals("partially")) {
            return PatientPaymentState.PARTIALLY;
        }
        if (normalized.contains("deuda") || normalized.equals("in debt")
                || normalized.equals("in_debt")) {
            return PatientPaymentState.IN_DEBT;
        }
        if (normalized.contains("por definir") || normalized.equals("undefined price")) {
            return PatientPaymentState.UNDEFINED_PRICE;
        }
        throw new IllegalArgumentException(
                "Estado de pago inválido. Use: COMPLETO, PARCIALMENTE, EN DEUDA o POR DEFINIR.");
    }

    private String parseAppointmentStatus(String value) {
        String normalized = normalize(value);
        if (normalized.contains("pendiente") || normalized.equals("pending")) {
            return "PENDING";
        }
        if (normalized.contains("confirmada") || normalized.contains("confirmado")
                || normalized.equals("confirmed")) {
            return "CONFIRMED";
        }
        if (normalized.contains("cancelada") || normalized.contains("cancelado")
                || normalized.equals("cancelled") || normalized.equals("canceled")) {
            return "CANCELLED";
        }
        if (normalized.contains("completada") || normalized.contains("completado")
                || normalized.equals("completed")) {
            return "COMPLETED";
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private PaymentMethod parsePaymentMethod(String value) {
        String normalized = normalize(value);
        if (normalized.contains("efectivo") || normalized.equals("cash")) {
            return PaymentMethod.CASH;
        }
        if (normalized.contains("transferencia") || normalized.equals("transfer")) {
            return PaymentMethod.TRANSFER;
        }
        throw new IllegalArgumentException(
                "Método de pago inválido. Use: EFECTIVO o TRANSFERENCIA.");
    }

    private UUID resolveDentistId(String value) {
        String query = normalize(value);
        return dentistRepository.findAll().stream()
                .filter(dentist -> normalize(fullDentistName(dentist)).contains(query)
                        || normalize(nullSafe(dentist.getDentistName())).contains(query)
                        || normalize(nullSafe(dentist.getDentistLastname())).contains(query))
                .findFirst()
                .map(Dentist::getDentistUUID)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No encontré un dentista con ese nombre."));
    }

    private Long resolveTreatmentId(String value) {
        String query = normalize(value);
        List<Treatments> treatments = treatmentsRepository.findAll();

        return treatments.stream()
                .filter(treatment -> normalize(treatment.getTreatment()).equals(query))
                .findFirst()
                .or(() -> treatments.stream()
                        .filter(treatment -> normalize(treatment.getTreatment()).contains(query))
                        .findFirst())
                .map(Treatments::getId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No encontré un tratamiento con ese nombre."));
    }

    private String canonicalField(String field) {
        return "patientAppointment".equals(field) ? "appointmentDateTime" : field;
    }

    private String fieldLabel(String field) {
        return switch (field) {
            case "patientName" -> "Nombre";
            case "patientLastname" -> "Apellido";
            case "patientPhone" -> "Teléfono";
            case "patientAge" -> "Edad";
            case "patientState" -> "Estado del paciente";
            case "patientPaymentState" -> "Estado de pago";
            case "patientAppointment" -> "Fecha/Hora de la cita";
            case "dentistAppointment" -> "Dentista";
            case "treatments" -> "Tratamiento";
            case "category" -> "Categoría";
            case "appointmentStatus" -> "Estado de la cita";
            case "notes" -> "Notas";
            case "appointmentDateTime" -> "Fecha/Hora de la cita (detalle)";
            case "paymentMethod" -> "Método de pago";
            default -> field;
        };
    }

    private String fieldPromptLabel(String field) {
        return fieldLabel(field).toLowerCase(Locale.ROOT);
    }

    private String currentFieldValue(Patient patient, Appointment appointment, String field) {
        return switch (field) {
            case "patientName" -> nullSafe(patient.getPatientName());
            case "patientLastname" -> nullSafe(patient.getPatientLastname());
            case "patientPhone" -> nullSafe(patient.getPatientPhone());
            case "patientAge" -> ageLabel(patient);
            case "patientState" -> patientStateLabel(patient);
            case "patientPaymentState" -> patientPaymentStateLabel(patient);
            case "patientAppointment", "appointmentDateTime" ->
                    appointment == null ? "Sin cita" : formatDateTimeShort(appointment);
            case "dentistAppointment" ->
                    appointment == null ? "Sin cita" : dentistName(appointment);
            case "treatments" ->
                    appointment == null ? "Sin cita" : treatmentLabel(appointment);
            case "category" ->
                    appointment == null ? "Sin cita" : nullSafe(appointment.getCategory());
            case "appointmentStatus" ->
                    appointment == null ? "Sin cita" : nullSafe(appointment.getAppointmentStatus());
            case "notes" ->
                    appointment == null ? "Sin cita" : nullSafe(appointment.getNotes());
            case "paymentMethod" ->
                    appointment == null ? "Sin cita" : paymentMethodLabel(appointment);
            default -> "";
        };
    }

    private SearchOutcome searchPatientsAndAppointments(String query) {
        String normalizedQuery = normalize(query);
        LocalDate dateQuery = parseSearchDate(query);

        List<Patient> allPatients = patientRepository.findAll();
        List<Appointment> allAppointments = appointmentRepository.findAll();

        Set<UUID> matchedPatientIds = new LinkedHashSet<>();
        for (Patient patient : allPatients) {
            if (matchesPatient(patient, normalizedQuery)) {
                matchedPatientIds.add(patient.getPatientUUID());
            }
        }
        for (Appointment appointment : allAppointments) {
            Patient patient = appointment.getPatientAppointment();
            if (patient == null) {
                continue;
            }
            if ((dateQuery != null && matchesDate(appointment, dateQuery))
                    || matchesTreatment(appointment, normalizedQuery)) {
                matchedPatientIds.add(patient.getPatientUUID());
            }
        }

        List<Patient> patients = allPatients.stream()
                .filter(patient -> matchedPatientIds.contains(patient.getPatientUUID()))
                .sorted(Comparator.comparing(patient -> normalize(fullName(patient))))
                .toList();

        Map<UUID, List<Appointment>> appointmentsByPatient = new LinkedHashMap<>();
        for (Patient patient : patients) {
            List<Appointment> appointments = sortedAppointments(
                    allAppointments.stream()
                            .filter(appointment -> appointment.getPatientAppointment() != null
                                    && patient.getPatientUUID().equals(
                                            appointment.getPatientAppointment().getPatientUUID()))
                            .toList());
            appointmentsByPatient.put(patient.getPatientUUID(), appointments);
        }

        return new SearchOutcome(patients, appointmentsByPatient);
    }

    private boolean matchesPatient(Patient patient, String query) {
        if (query.isBlank()) {
            return false;
        }
        return normalize(fullName(patient)).contains(query)
                || normalize(nullSafe(patient.getPatientName())).contains(query)
                || normalize(nullSafe(patient.getPatientLastname())).contains(query)
                || normalize(nullSafe(patient.getPatientPhone())).contains(query);
    }

    private boolean matchesDate(Appointment appointment, LocalDate date) {
        if (appointment.getStartAppointmentAt() != null
                && appointment.getStartAppointmentAt().toLocalDateTime().toLocalDate().equals(date)) {
            return true;
        }
        return appointment.getAppointmentDateTime() != null
                && appointment.getAppointmentDateTime().toLocalDate().equals(date);
    }

    private boolean matchesTreatment(Appointment appointment, String query) {
        if (query.isBlank() || appointment.getTreatments() == null) {
            return false;
        }
        for (Treatments treatment : appointment.getTreatments()) {
            if (treatment.getTreatment() != null
                    && normalize(treatment.getTreatment()).contains(query)) {
                return true;
            }
        }
        return false;
    }

    private List<Appointment> sortedAppointments(List<Appointment> appointments) {
        return appointments.stream()
                .sorted(Comparator.comparing(
                        Appointment::getStartAppointmentAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private void showPatientDetails(String doctorPhone, Patient patient,
                                    List<Appointment> appointments) {
        StringBuilder sb = new StringBuilder();
        sb.append("*Paciente:* ").append(fullName(patient)).append("\n");
        sb.append("Teléfono: ").append(nullSafe(patient.getPatientPhone())).append("\n");
        sb.append("Edad: ").append(ageLabel(patient)).append("\n");
        sb.append("Estado: ").append(patientStateLabel(patient)).append("\n");
        sb.append("Pago: ").append(patientPaymentStateLabel(patient)).append("\n\n");
        sb.append("*Citas:*\n");

        if (appointments.isEmpty()) {
            sb.append("Sin citas registradas.");
        } else {
            int index = 1;
            for (Appointment appointment : appointments.stream().limit(10).toList()) {
                sb.append(index++).append(". ").append(appointmentSummary(appointment)).append("\n");
            }
            if (appointments.size() > 10) {
                sb.append("... y ").append(appointments.size() - 10).append(" cita(s) más.\n");
            }
        }

        whatsAppClient.sendTextMessage(doctorPhone, sb.toString().trim());
    }

    private void sendSearchResultsList(String doctorPhone, SearchOutcome outcome) {
        StringBuilder sb = new StringBuilder("*Resultados:*\n\n");
        int index = 1;

        for (Patient patient : outcome.patients()) {
            if (index > 10) {
                sb.append("... y ").append(outcome.patients().size() - 10)
                        .append(" paciente(s) más. Refine la búsqueda.\n");
                break;
            }

            List<Appointment> appointments = outcome.appointmentsByPatient()
                    .getOrDefault(patient.getPatientUUID(), List.of());

            sb.append(index++).append(". *").append(fullName(patient)).append("*\n");
            sb.append("   Tel: ").append(nullSafe(patient.getPatientPhone()))
                    .append(" | Edad: ").append(ageLabel(patient)).append("\n");
            sb.append("   Estado: ").append(patientStateLabel(patient))
                    .append(" | Pago: ").append(patientPaymentStateLabel(patient)).append("\n");

            if (appointments.isEmpty()) {
                sb.append("   Cita: Sin citas registradas.\n");
            } else {
                sb.append("   Cita: ").append(formatDateTimeShort(appointments.get(0)))
                        .append(" — ").append(treatmentLabel(appointments.get(0))).append("\n");
            }
            sb.append("\n");
        }

        whatsAppClient.sendTextMessage(doctorPhone, sb.toString().trim());
    }

    private void showPatientsPage(String doctorPhone, int pageNumber) {
        int pageSize = 10;
        Page<Patient> page = patientRepository.findAll(
                PageRequest.of(pageNumber, pageSize,
                        Sort.by(Sort.Direction.ASC, "patientName")));

        if (page.isEmpty()) {
            whatsAppClient.sendTextMessage(doctorPhone, "No se encontraron pacientes registrados.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("*Pacientes (Página ").append(pageNumber + 1)
                .append(" de ").append(page.getTotalPages()).append(")*:\n\n");

        int index = pageNumber * pageSize + 1;
        for (Patient patient : page.getContent()) {
            sb.append(index++).append(". *").append(fullName(patient)).append("*\n");
            sb.append("   Tel: ").append(nullSafe(patient.getPatientPhone()))
                    .append(" | Edad: ").append(ageLabel(patient)).append("\n");
            sb.append("   Estado: ").append(patientStateLabel(patient))
                    .append(" | Pago: ").append(patientPaymentStateLabel(patient)).append("\n\n");
        }

        if (page.hasNext()) {
            whatsAppClient.sendInteractiveButton(doctorPhone, sb.toString().trim(),
                    "Ver más pacientes", PATIENTS_PAGE_PREFIX + (pageNumber + 1));
        } else {
            sb.append("🏁 _Fin del listado de pacientes._");
            whatsAppClient.sendTextMessage(doctorPhone, sb.toString().trim());
        }
    }

    private String appointmentSummary(Appointment appointment) {
        return "Tratamiento: " + treatmentLabel(appointment)
                + ". Fecha: " + formatDate(appointment)
                + ". Hora: " + formatTime(appointment)
                + ". Dentista: " + dentistName(appointment)
                + ". Estado: " + nullSafe(appointment.getAppointmentStatus())
                + ". Pago: " + paymentMethodLabel(appointment);
    }

    private String formatDateTimeShort(Appointment appointment) {
        if (appointment.getStartAppointmentAt() != null) {
            return new SimpleDateFormat("dd/MM/yyyy hh:mma")
                    .format(appointment.getStartAppointmentAt());
        }
        if (appointment.getAppointmentDateTime() != null) {
            return appointment.getAppointmentDateTime()
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        }
        return "Sin fecha";
    }

    private String formatDate(Appointment appointment) {
        if (appointment.getStartAppointmentAt() != null) {
            return new SimpleDateFormat("dd/MM/yyyy").format(appointment.getStartAppointmentAt());
        }
        if (appointment.getAppointmentDateTime() != null) {
            return appointment.getAppointmentDateTime().toLocalDate().toString();
        }
        return "Por definir";
    }

    private String formatTime(Appointment appointment) {
        if (appointment.getStartAppointmentAt() != null) {
            return new SimpleDateFormat("hh:mma")
                    .format(appointment.getStartAppointmentAt()).toLowerCase(Locale.ROOT);
        }
        if (appointment.getAppointmentDateTime() != null) {
            return appointment.getAppointmentDateTime().toLocalTime().toString();
        }
        return "Por definir";
    }

    private String treatmentLabel(Appointment appointment) {
        if (appointment.getTreatments() == null || appointment.getTreatments().isEmpty()) {
            return "Consulta general";
        }
        return appointment.getTreatments().stream()
                .map(Treatments::getTreatment)
                .collect(Collectors.joining(" & "));
    }

    private String dentistName(Appointment appointment) {
        return appointment.getDentistAppointment() == null
                ? "Por definir"
                : fullDentistName(appointment.getDentistAppointment());
    }

    private String fullDentistName(Dentist dentist) {
        String full = (nullSafe(dentist.getDentistName()) + " "
                + nullSafe(dentist.getDentistLastname())).trim();
        return full.isBlank() ? "Sin nombre" : full;
    }

    private String paymentMethodLabel(Appointment appointment) {
        return appointment.getPaymentMethod() != null
                ? appointment.getPaymentMethod().getPaymentMethod()
                : "Por definir";
    }

    private String ageLabel(Patient patient) {
        return patient.getPatientAge() == null ? "N/A" : String.valueOf(patient.getPatientAge());
    }

    private String patientStateLabel(Patient patient) {
        return patient.getPatientState() != null
                ? patient.getPatientState().getPatientState()
                : PatientState.UNDEFINED.getPatientState();
    }

    private String patientPaymentStateLabel(Patient patient) {
        return patient.getPatientPaymentState() != null
                ? patient.getPatientPaymentState().getPatientPaymentState()
                : PatientPaymentState.UNDEFINED_PRICE.getPatientPaymentState();
    }

    private String fullName(Patient patient) {
        String full = (nullSafe(patient.getPatientName()) + " "
                + nullSafe(patient.getPatientLastname())).trim();
        return full.isBlank() ? "Sin nombre" : full;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value.trim();
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

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mma", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm a", Locale.ENGLISH)
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    );

    private LocalDateTime parseDateTime(String value) {
        String text = value == null ? "" : value.trim();

        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(text, formatter).atTime(10, 0);
            } catch (DateTimeParseException ignored) {
            }
        }
        return parsedDoctorCustomizedDate(text);
    }

    private LocalDateTime parseRequiredDateTime(String value) {
        LocalDateTime parsed = parseDateTime(value);
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "Fecha inválida. Use el formato dd/MM/yyyy HH:mm.");
        }
        return parsed;
    }

    private LocalDate parseSearchDate(String value) {
        String text = value == null ? "" : value.trim();

        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(text, formatter).toLocalDate();
            } catch (DateTimeParseException ignored) {
            }
        }

        if (!looksLikeDate(text)) {
            return null;
        }
        LocalDateTime natural = parsedDoctorCustomizedDate(text);
        return natural != null ? natural.toLocalDate() : null;
    }

    private boolean looksLikeDate(String text) {
        String normalized = normalize(text);
        if (normalized.contains("/")) {
            return true;
        }
        return SPANISH_MONTHS.keySet().stream()
                .anyMatch(month -> normalized.matches(".*\\b" + Pattern.quote(month) + "\\b.*"));
    }

    /**
     * Renders up to 10 appointments per page with formatted details and a "Ver más citas" pagination button.
     *
     * @param doctorPhone Recipient phone number of the doctor
     * @param pageNumber  Zero-based page index (0 = first 10, 1 = next 10, etc.)
     */
    private void showAppointmentsPage(String doctorPhone, int pageNumber) {
        int pageSize = 10;
        Page<Appointment> appointmentPage = appointmentRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(pageNumber, pageSize));

        if (appointmentPage.isEmpty()) {
            whatsAppClient.sendTextMessage(doctorPhone, "No se encontraron citas registradas.");
            return;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy");
        SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mma");

        StringBuilder sb = new StringBuilder();
        sb.append("📋 *Citas (Página ").append(pageNumber + 1).append(" de ").append(appointmentPage.getTotalPages()).append(")*:\n\n");

        int index = (pageNumber * pageSize) + 1;
        for (Appointment appt : appointmentPage.getContent()) {
            // Patient Name
            String patientName = (appt.getPatientAppointment() != null && appt.getPatientAppointment().getPatientName() != null)
                    ? appt.getPatientAppointment().getPatientName()
                    : "Sin nombre";

            // Treatments joined by " & "
            String treatments = (appt.getTreatments() != null && !appt.getTreatments().isEmpty())
                    ? appt.getTreatments().stream().map(Treatments::getTreatment).collect(Collectors.joining(" & "))
                    : "Consulta general";

            // Date and Time
            String date = (appt.getStartAppointmentAt() != null) ? dateFormat.format(appt.getStartAppointmentAt()) : "Por definir";
            String time = (appt.getStartAppointmentAt() != null) ? timeFormat.format(appt.getStartAppointmentAt()).toLowerCase() : "Por definir";

            // State & Payment
            String state = (appt.getPatientState() != null)
                    ? appt.getPatientState().getPatientState()
                    : PatientState.UNDEFINED.getPatientState();

            String payment = (appt.getPaymentMethod() != null)
                    // the first .getPaymentMethod is from the Appointment.java (from the PaymentMethod Class)
                    // The second .getPaymentMethod is from  PaymentMethod Enum Class (String by itself)
                    ? appt.getPaymentMethod().getPaymentMethod()
                    : "Por definir";

            sb.append(index).append(". ")
                    .append("Paciente: ").append(patientName).append(". ")
                    .append("Tratamiento: ").append(treatments).append(". ")
                    .append("última/próxima fecha: ").append(date).append(". ")
                    .append("Hora: ").append(time).append(". ")
                    .append("Estado: ").append(state).append(". ")
                    .append("Método de pago: ").append(payment).append(".\n\n");

            index++;
        }

        // If there are more pages, append interactive button "Ver más citas"
        if (appointmentPage.hasNext()) {
            String nextPageButtonId = "CITAS_PAGE_" + (pageNumber + 1);
            whatsAppClient.sendInteractiveButton(
                    doctorPhone,
                    sb.toString().trim(),
                    "Ver más citas",
                    nextPageButtonId
            );
        } else {
            sb.append("🏁 _Fin del listado de citas._");
            whatsAppClient.sendTextMessage(doctorPhone, sb.toString().trim());
        }
    }

    private void saveMessageRecord(WhatsAppContact contact, String providerId, String direction, String text) {
        WhatsAppMessage msg = new WhatsAppMessage();
        msg.setWhatsAppContact(contact);
        msg.setDirection(direction);
        msg.setText(text);
        msg.setProviderMessageId(providerId);
        msg.setMessageType("text");
        msg.setProviderCreatedAt(new Timestamp(System.currentTimeMillis()));
        messageRepository.save(msg);
    }

    private String generateSuggestedAppointment() {
        LocalDate date = LocalDate.now().plusDays(1);
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEEE, MMM d 'a las' 10:00 AM", Locale.ENGLISH);
        return date.atTime(10, 0).format(formatter);
    }

    /**
     * Parses free-text strings such as:
     * - "Gabriel Santos, 25"
     * - "Gabriel Santos 25"
     * - "My name is John Doe and I am 32 years old"
     */
    private ParsedPatientInfo parsePatientInfo(String text) {
        if (text == null || text.isBlank()) return null;

        // Try comma separated format: "Name, Age"
        if (text.contains(",")) {
            String[] parts = text.split(",", 2);
            String name = parts[0].trim();
            Matcher ageMatcher = Pattern.compile("(\\d{1,3})").matcher(parts[1]);
            if (ageMatcher.find()) {
                int age = Integer.parseInt(ageMatcher.group(1));
                return new ParsedPatientInfo(name, age);
            }
        }

        // Try regex searching for age (1-3 digits) anywhere in text
        Matcher ageMatcher = Pattern.compile("\\b(\\d{1,3})\\b").matcher(text);
        if (ageMatcher.find()) {
            int age = Integer.parseInt(ageMatcher.group(1));
            // Remove the age number and common intro words to isolate the name
            String name = text.replaceAll("\\b" + age + "\\b", "")
                    .replaceAll("(?i)(my name is|i am|years old|age|years|años|anos|tengo|mi nombre es)", "")
                    .replaceAll("[,.:;-]", " ")
                    .trim()
                    .replaceAll("\\s+", " ");
            if (!name.isBlank()) {
                return new ParsedPatientInfo(name, age);
            }
        }

        return null;
    }

    // ===================================================================================
    // DOCTOR CUSTOM DATE PARSING UTILITIES
    // ===================================================================================

    /**
     * Fast, thread-safe dictionary mapping Spanish month names and standard abbreviations
     * to Java's {@link Month} enum.
     */
    private static final java.util.Map<String, Month> SPANISH_MONTHS = java.util.Map.ofEntries(
            java.util.Map.entry("enero", Month.JANUARY),
            java.util.Map.entry("ene", Month.JANUARY),
            java.util.Map.entry("febrero", Month.FEBRUARY),
            java.util.Map.entry("feb", Month.FEBRUARY),
            java.util.Map.entry("marzo", Month.MARCH),
            java.util.Map.entry("mar", Month.MARCH),
            java.util.Map.entry("abril", Month.APRIL),
            java.util.Map.entry("abr", Month.APRIL),
            java.util.Map.entry("mayo", Month.MAY),
            java.util.Map.entry("may", Month.MAY),
            java.util.Map.entry("junio", Month.JUNE),
            java.util.Map.entry("jun", Month.JUNE),
            java.util.Map.entry("julio", Month.JULY),
            java.util.Map.entry("jul", Month.JULY),
            java.util.Map.entry("agosto", Month.AUGUST),
            java.util.Map.entry("ago", Month.AUGUST),
            java.util.Map.entry("septiembre", Month.SEPTEMBER),
            java.util.Map.entry("setiembre", Month.SEPTEMBER),
            java.util.Map.entry("sep", Month.SEPTEMBER),
            java.util.Map.entry("octubre", Month.OCTOBER),
            java.util.Map.entry("oct", Month.OCTOBER),
            java.util.Map.entry("noviembre", Month.NOVEMBER),
            java.util.Map.entry("nov", Month.NOVEMBER),
            java.util.Map.entry("diciembre", Month.DECEMBER),
            java.util.Map.entry("dic", Month.DECEMBER)
    );

    /**
     * Identifies and returns the Spanish {@link Month} from a given word, ignoring punctuation and casing.
     *
     * @param word Single word or token (e.g. "noviembre", "nov", "noviembre,")
     * @return {@link Month} if matched, or {@code null} if not a month
     */
    private Month findMonth(String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        // Remove common punctuation attached to words like commas, periods, etc.
        String clean = word.toLowerCase().trim().replaceAll("[^a-záéíóúñ]", "");
        return SPANISH_MONTHS.get(clean);
    }

    /**
     * Extracts a time (hour & minute) from the doctor's message text.
     * Supports:
     *   - 12-hour format with AM/PM (e.g., "10am", "10:30am", "3pm", "3:15 pm")
     *   - 24-hour format (e.g., "15:00", "09:30", "18:45")
     *
     * @param text The full message string
     * @return Parsed {@link LocalTime}, or {@code null} if no explicit time was found in the text
     */
    private LocalTime findTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        // 1. Check for 12-hour format with am/pm (e.g. "10am", "10:30am", "3pm", "3:15 pm")
        Pattern ampmPattern = Pattern.compile("\\b(1[0-2]|0?[1-9])(?::([0-5][0-9]))?\\s*(am|pm)\\b", Pattern.CASE_INSENSITIVE);
        Matcher ampmMatcher = ampmPattern.matcher(text);
        if (ampmMatcher.find()) {
            int hour = Integer.parseInt(ampmMatcher.group(1));
            int minute = ampmMatcher.group(2) != null ? Integer.parseInt(ampmMatcher.group(2)) : 0;
            String ampm = ampmMatcher.group(3).toLowerCase();

            if ("pm".equals(ampm) && hour < 12) {
                hour += 12;
            } else if ("am".equals(ampm) && hour == 12) {
                hour = 0;
            }
            return LocalTime.of(hour, minute);
        }

        // 2. Check for 24-hour format with colon (e.g. "15:00", "09:30")
        Pattern h24Pattern = Pattern.compile("\\b([01]?[0-9]|2[0-3]):([0-5][0-9])\\b");
        Matcher h24Matcher = h24Pattern.matcher(text);
        if (h24Matcher.find()) {
            int hour = Integer.parseInt(h24Matcher.group(1));
            int minute = Integer.parseInt(h24Matcher.group(2));
            return LocalTime.of(hour, minute);
        }

        return null;
    }

    /**
     * Extracts a numeric day from a text string or single token.
     *
     * @param text String that may contain a day number
     * @return Day of month (1-31), or 0 if no valid day is found
     */
    public Integer extractDay(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher matcher = Pattern.compile("\\b([1-9]|[12][0-9]|3[01])\\b").matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }

    /**
     * Parses the doctor's custom date and time proposal from a natural text message.
     *
     * Supported formats:
     *   1. Natural Spanish:
     *      - "Me gustaría dejarle para el 5 de noviembre del 2026 a las 10:00"
     *      - "5 de noviembre a las 3pm"
     *      - "el 12 de diciembre" (defaults time to 10:00 AM)
     *   2. Standard Numeric (Latin America):
     *      - "24/11/2026 10:00"
     *      - "24-11-2026 15:30"
     *      - "24/11 a las 4pm" (defaults year to current year)
     *
     * @param messageDoctor Text message sent by the doctor
     * @return A valid {@link LocalDateTime} representing the scheduled appointment,
     *         or {@code null} if a date could not be determined.
     */
    private LocalDateTime parsedDoctorCustomizedDate(String messageDoctor) {
        if (messageDoctor == null || messageDoctor.isBlank()) {
            log.warn("Doctor message is null or blank for custom date parsing.");
            return null;
        }

        String input = messageDoctor.trim();

        // -----------------------------------------------------------------------------------
        // STEP 1: EXTRACT TIME (or default to 10:00 AM standard appointment hour)
        // -----------------------------------------------------------------------------------
        LocalTime timeFound = findTime(input);
        if (timeFound == null) {
            // Default clinic appointment time if doctor didn't specify an hour
            timeFound = LocalTime.of(10, 0);
        }

        // -----------------------------------------------------------------------------------
        // STEP 2: EXTRACT DATE (Numeric format or Spanish written format)
        // -----------------------------------------------------------------------------------
        int day = 0;
        int monthValue = 0;
        int year = LocalDate.now().getYear(); // Default to current year if omitted

        // Pattern A: Numeric date dd/MM/yyyy or dd-MM-yyyy or dd/MM (e.g., "24/11/2026", "24/11")
        Pattern numericDatePattern = Pattern.compile("\\b(\\d{1,2})[/\\-](\\d{1,2})(?:[/\\-](\\d{4}))?\\b");
        Matcher numericMatcher = numericDatePattern.matcher(input);

        if (numericMatcher.find()) {
            day = Integer.parseInt(numericMatcher.group(1));
            monthValue = Integer.parseInt(numericMatcher.group(2));
            if (numericMatcher.group(3) != null) {
                year = Integer.parseInt(numericMatcher.group(3));
            }
        } else {
            // Pattern B: Written Spanish date: e.g. "5 de noviembre del 2026", "el 12 nov 2026", "5 de noviembre"
            Pattern writtenDatePattern = Pattern.compile(
                    "\\b(\\d{1,2})\\s*(?:de)?\\s*([a-zA-ZáéíóúÁÉÍÓÚñÑ]+)(?:\\s*(?:del?|de)?\\s*(\\d{4}))?\\b",
                    Pattern.CASE_INSENSITIVE
            );
            Matcher writtenMatcher = writtenDatePattern.matcher(input);

            if (writtenMatcher.find()) {
                day = Integer.parseInt(writtenMatcher.group(1));
                String monthWord = writtenMatcher.group(2);
                Month month = findMonth(monthWord);
                if (month != null) {
                    monthValue = month.getValue();
                }
                if (writtenMatcher.group(3) != null) {
                    year = Integer.parseInt(writtenMatcher.group(3));
                }
            }
        }

        // -----------------------------------------------------------------------------------
        // STEP 3: VALIDATE AND BUILD LOCALDATETIME
        // -----------------------------------------------------------------------------------
        if (day < 1 || day > 31 || monthValue < 1 || monthValue > 12) {
            log.warn("Could not extract a valid date from doctor's message: '{}'", messageDoctor);
            return null;
        }

        try {
            LocalDate date = LocalDate.of(year, monthValue, day);
            LocalDateTime scheduledDateTime = LocalDateTime.of(date, timeFound);
            log.info("Successfully parsed customized doctor appointment: {}", scheduledDateTime);
            return scheduledDateTime;
        } catch (DateTimeException e) {
            log.error("Invalid calendar date constructed from doctor message '{}': {}", messageDoctor, e.getMessage());
            return null;
        }
    }
    private record ParsedPatientInfo(String name, Integer age) {}
}
