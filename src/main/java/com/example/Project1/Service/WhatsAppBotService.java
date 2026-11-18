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
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    private final WhatsAppClient whatsAppClient;
    private final WhatsAppProperties properties;
    private final WhatsAppContactRepository contactRepository;
    private final WhatsAppSessionRepository sessionRepository;
    private final WhatsAppMessageRepository messageRepository;
    private final TreatmentsRepository treatmentsRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final DentistRepository dentistRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String APPOINTMENT_CONFIRMED = "CONFIRMED";

    // Flow tokens identify which form produced a webhook nfm_reply.
    private static final String FLOW_TOKEN_REGISTER = "doc-reg:";
    private static final String FLOW_TOKEN_UPDATE_SEARCH = "doc-upd-srch:";
    private static final String FLOW_TOKEN_UPDATE = "doc-upd:";
    private static final String FLOW_TOKEN_SEARCH = "doc-srch:";
    private static final String UPD_PAT_PREFIX = "UPD_PAT_";

    /// Pending payment-flow flag. It is still shared across conversations and will be
    /// migrated to a SessionState when the payment flow is refactored.
    private boolean isLookingForPatient = false;

    public WhatsAppBotService(WhatsAppClient whatsAppClient,
                              WhatsAppProperties properties,
                              WhatsAppContactRepository contactRepository,
                              WhatsAppSessionRepository sessionRepository,
                              WhatsAppMessageRepository messageRepository,
                              TreatmentsRepository treatmentsRepository,
                              PatientRepository patientRepository,
                              AppointmentRepository appointmentRepository,
                              DentistRepository dentistRepository) {
        this.whatsAppClient = whatsAppClient;
        this.properties = properties;
        this.contactRepository = contactRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.treatmentsRepository = treatmentsRepository;
        this.patientRepository = patientRepository;
        this.appointmentRepository = appointmentRepository;
        this.dentistRepository = dentistRepository;
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
        // Flow responses arrive as interactive/nfm_reply with no text. Use the raw JSON
        // so the message is not discarded by the blank-message guard below.
        String messageBody = incomingMessage.isFlowResponse()
                ? incomingMessage.getFlowResponseJson()
                : incomingMessage.getExtractedText();

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

        // WhatsApp Flow completion: route by flow_token, regardless of who sent it.
        if (incomingMessage.isFlowResponse()) {
            handleFlowResponse(fromPhone, incomingMessage.getFlowResponseJson());
            return;
        }

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
        // PATIENT INTAKE CONVERSATIONAL STATE MACHINE
        // -----------------------------------------------------------------------------------
        WhatsAppSession session = sessionRepository.findByWhatsAppContact_WaUserId(fromPhone)
                .orElseGet(() -> {
                    WhatsAppSession newSession = new WhatsAppSession();
                    newSession.setWhatsAppContact(contact);
                    newSession.setCurrentState(SessionState.INITIAL);
                    newSession.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
                    return sessionRepository.save(newSession);
                });

        SessionState state = session.getCurrentState() != null
                ? session.getCurrentState()
                : SessionState.INITIAL;

        // In development the doctor's phone can also act as a patient. DOC_IDLE means
        // the doctor session is not in any doctor flow, so the patient intake may start.
        boolean patientMode = doctorPhone != null && !doctorPhone.isBlank()
                && fromPhone.equals(doctorPhone)
                && properties.getDoctor().isPatientMode();

        if (state == SessionState.INITIAL || state == SessionState.COMPLETED
                || (patientMode && state == SessionState.DOC_IDLE)) {
            // STEP 1 & 2: Patient greets the bot -> Bot responds asking for their full name & age
            String greetingPrompt = "Hola! Bienvenido a Dental SmileDesign :).\n"
                    + "Por favor escriba su *Primer Nombre * y *Edad* para que podamos notificar al doctor para su cita";

            whatsAppClient.sendTextMessage(fromPhone, greetingPrompt);
            saveMessageRecord(contact, null, "OUTGOING", greetingPrompt);

            session.setCurrentState(SessionState.AWAITING_PATIENT_INFO);
            session.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            sessionRepository.save(session);

        } else if (state == SessionState.AWAITING_PATIENT_INFO) {
            // STEP 3: Patient replies with their details
            ParsedPatientInfo parsedInfo = parsePatientInfo(messageBody);

            if (parsedInfo == null || parsedInfo.name == null || parsedInfo.age == null) {
                String retryPrompt = "No pudimos extraer sus datos claramente. Inténtelo de nuevo como se muestra en el ejemplo* (Ejemplo: *Gabriel Santos, 25*).";
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
            appointmentRepository.save(appointment);

            // Acknowledge receipt to the Patient
            String patientAck = "Gracias, " + parsedInfo.name + "! Hemos recibido tu información y notificado al Doctor para que confirme tu cita.";
            whatsAppClient.sendTextMessage(fromPhone, patientAck);
            saveMessageRecord(contact, null, "OUTGOING", patientAck);

            // Forward notification to the Doctor
            String doctorMessage = "El paciente " + parsedInfo.name + " con edad " + parsedInfo.age
                    + ", Cuando desearía recibir a este paciente?, aquí hay algunas sugerencias: \n"
                    + suggestedAppointment + ". Si desea, puede poner la fecha ud mismo de la siguiente manera \n"
                    + "*<día> de <mes> <hora> (Ejemplo: 20 de noviembre de 2026)*";
            String recipientDoctor = (doctorPhone != null && !doctorPhone.isBlank()) ? doctorPhone : fromPhone;
            WhatsAppSession doctorSession =
                    getOrCreateDoctorSession(getOrCreateDoctorContact(recipientDoctor));
            setDoctorState(doctorSession, SessionState.DOC_AWAITING_PATIENT_DATE);
            whatsAppClient.sendTextMessage(recipientDoctor, doctorMessage);
            log.info("Forwarded patient details to Doctor ({})", recipientDoctor);

            session.setCurrentState(SessionState.COMPLETED);
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
     * @param doctorContact   The persisted contact of the doctor (session owner)
     * @return true when the message was handled as a doctor action; false when the
     *         doctor's number is in patient mode and the message must continue to the
     *         patient intake flow
     */
    private boolean handleDoctorMessage(String doctorPhone,
                                        WhatsAppWebhookPayload.IncomingMessage incomingMessage,
                                        String messageBody,
                                        WhatsAppContact doctorContact) {
        String selectedOptionId = incomingMessage.getInteractiveSelectionId();
        WhatsAppSession session = getOrCreateDoctorSession(doctorContact);
        SessionState state = session.getCurrentState() != null
                ? session.getCurrentState()
                : SessionState.DOC_IDLE;
        String text = messageBody == null ? "" : messageBody.trim();
        boolean patientMode = properties.getDoctor().isPatientMode();

        // 0) Doctor greeting -> interactive main menu. Skipped in patient mode so the
        //    same phone can exercise the patient intake flow during development.
        if (!patientMode && ("hola".equalsIgnoreCase(text)
                || "menu".equalsIgnoreCase(text)
                || "menú".equalsIgnoreCase(text))) {
            sendDoctorMenu(doctorPhone);
            return true;
        }

        boolean wantsAppointments = "CITAS".equals(selectedOptionId);
        boolean wantsPayment = "PAGO".equals(selectedOptionId);
        boolean wantSearchPatient = "SRCH_PAT".equals(selectedOptionId);
        boolean wantsUpdateAppointment = "UPD_CITA".equals(selectedOptionId);
        boolean wantsCreatePatient = "CRT_PAT".equals(selectedOptionId);

        // 1) Menu actions backed by static WhatsApp Flows (forms).
        if (wantsCreatePatient) {
            sendDoctorPatientRegistrationFlow(doctorPhone);
            return true;
        }
        if (wantsUpdateAppointment) {
            sendDoctorUpdateSearchFlow(doctorPhone);
            setDoctorState(session, SessionState.DOC_UPD_SEARCHING);
            return true;
        }
        if (wantSearchPatient) {
            sendDoctorSearchPatientFlow(doctorPhone);
            return true;
        }

        // 2) Doctor selected a patient from the update search results.
        if (selectedOptionId != null && selectedOptionId.startsWith(UPD_PAT_PREFIX)) {
            return handleUpdatePatientSelected(doctorPhone, session, selectedOptionId);
        }

        // 3) Doctor confirming the date of an appointment created by the patient flow.
        if (state == SessionState.DOC_AWAITING_PATIENT_DATE) {
            return handlePatientDateConfirmation(doctorPhone, session, messageBody);
        }

        // 3) Show recent appointments.
        if (wantsAppointments) {
            Page<Appointment> appointmentsPage = showAppointmentsPage(0);
            showAppointmentsPageMessage(doctorPhone, 0, appointmentsPage);
            return true;
        }

        // 4) Pagination of the appointment list.
        if (selectedOptionId != null && selectedOptionId.startsWith("CITAS_PAGE_")) {
            try {
                int nextPage = Integer.parseInt(selectedOptionId.replace("CITAS_PAGE_", ""));
                Page<Appointment> appointmentsPage = showAppointmentsPage(nextPage);
                showAppointmentsPageMessage(doctorPhone, nextPage, appointmentsPage);
            } catch (NumberFormatException e) {
                log.error("Failed to parse page number from action id: {}", selectedOptionId, e);
            }
            return true;
        }


        // 6) Payment flow: doctor types PAGO -> choose the patient (or go to treatments).
        if (wantsPayment) {
            List<SendInteractiveListRequest.Row> patientRows = patientRepository
                    .findAllByPatientState(PatientState.ON_TRACKING).stream()
                    .limit(10)
                    .map(p -> new SendInteractiveListRequest.Row(
                            "PAT_" + p.getPatientUUID(),
                            p.getPatientName(),
                            "Edad: " + (p.getPatientAge() != null ? p.getPatientAge() : "N/A")))
                    .toList();

            if (!patientRows.isEmpty()) {
                whatsAppClient.sendInteractiveList(doctorPhone,
                        "Cuál paciente se acabó de atender?", "Ver Pacientes", patientRows);
                isLookingForPatient = true;
                return true;
            }

            return sendTreatmentList(doctorPhone);
        }

        // 7) Doctor tapped a treatment from the interactive list.
        if (selectedOptionId != null && selectedOptionId.startsWith("TREAT_")) {
            long treatmentId = Long.parseLong(selectedOptionId.replace("TREAT_", ""));
            Optional<Treatments> treatmentsOptional = treatmentsRepository.findById(treatmentId);

            if (treatmentsOptional.isPresent()) {
                Treatments selectedTreatment = treatmentsOptional.get();
                String confirmationText = "Tratamiento registrado con éxito: *" + messageBody + "*.\n"
                        + "El pago del paciente es de: $" + selectedTreatment.getPrice();
                whatsAppClient.sendTextMessage(doctorPhone, confirmationText);
                log.info("Doctor selected treatment option: id={}, title={}, price={}",
                        selectedTreatment.getId(),
                        selectedTreatment.getTreatment(),
                        selectedTreatment.getPrice());
            }

            whatsAppClient.sendInteractiveButtons(doctorPhone,
                    "De qué forma realizó el pago el paciente?: \n", paymentMethodButtons());
            return true;
        }

        // 8) Payment method / payment status buttons.
        boolean isPaymentAction = "PAY_EFECTIVO".equals(selectedOptionId)
                || "PAY_TRANSFERENCIA".equals(selectedOptionId)
                || "PAT_STAT_COMPLETE".equals(selectedOptionId)
                || "PAT_STAT_PARTIALLY".equals(selectedOptionId)
                || "PAT_STAT_INDEBT".equals(selectedOptionId);

        if (isPaymentAction) {
            try {
                Optional<Appointment> latestAppt = appointmentRepository.findTopByOrderByCreatedAtDesc();
                if (latestAppt.isPresent()) {
                    Appointment appt = latestAppt.get();
                    Patient patient = appt.getPatientAppointment();

                    if (patient != null) {
                        if ("PAT_STAT_COMPLETE".equals(selectedOptionId)) {
                            patient.setPatientPaymentState(PatientPaymentState.COMPLETE);
                        } else if ("PAT_STAT_PARTIALLY".equals(selectedOptionId)) {
                            patient.setPatientPaymentState(PatientPaymentState.PARTIALLY);
                        } else if ("PAT_STAT_INDEBT".equals(selectedOptionId)) {
                            patient.setPatientPaymentState(PatientPaymentState.IN_DEBT);
                        }
                        patientRepository.save(patient);
                    }

                    appointmentRepository.save(appt);
                    whatsAppClient.sendInteractiveButtons(doctorPhone,
                            "El Paciente ha pagado todo, parcialmente, o queda debiéndole?",
                            paymentStatusButtons());
                }
            } catch (Exception e) {
                log.error("Failed to update the patient payment state", e);
                whatsAppClient.sendTextMessage(doctorPhone,
                        "Ha fallado al obtener el método de pago en el sistema");
            }
            return true;
        }

        // Unknown message. In patient mode let the patient flow handle it; otherwise
        // show the menu again so the doctor always has a way forward.
        if (patientMode) {
            return false;
        }
        sendDoctorMenu(doctorPhone);
        return true;
    }

    private void sendDoctorMenu(String doctorPhone) {
        whatsAppClient.sendInteractiveList(
                doctorPhone,
                "¿Qué desea hacer?",
                "Ver Opciones",
                doctorMainOptions());
    }

    // ===================================================================================
    // WHATSAPP FLOWS (static forms)
    // ===================================================================================

    private boolean sendDoctorPatientRegistrationFlow(String doctorPhone) {
        String flowId = properties.getFlows().getPatientRegistration();
        if (flowId == null || flowId.isBlank()) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "El formulario de registro de paciente no está configurado.");
            return false;
        }
        String flowToken = FLOW_TOKEN_REGISTER + UUID.randomUUID();
        return whatsAppClient.sendFlow(
                doctorPhone, flowId, flowToken,
                "Abrir formulario",
                "Registrar paciente",
                "Complete los datos del paciente y la fecha de la cita.",
                null, "PATIENT_REGISTRATION", null);
    }

    private boolean sendDoctorUpdateSearchFlow(String doctorPhone) {
        String flowId = properties.getFlows().getSearchPatient();
        if (flowId == null || flowId.isBlank()) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "El formulario de búsqueda de pacientes no está configurado.");
            return false;
        }
        String flowToken = FLOW_TOKEN_UPDATE_SEARCH + UUID.randomUUID();
        return whatsAppClient.sendFlow(
                doctorPhone, flowId, flowToken,
                "Buscar",
                "Buscar paciente",
                "Escriba el nombre o la fecha del paciente que desea actualizar.",
                null, "SEARCH_PATIENT", null);
    }

    private boolean sendDoctorUpdateAppointmentFlow(String doctorPhone,
                                                    Patient patient,
                                                    Appointment appointment) {
        String flowId = properties.getFlows().getUpdateAppointment();
        if (flowId == null || flowId.isBlank()) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "El formulario para modificar citas no está configurado.");
            return false;
        }

        String flowToken = FLOW_TOKEN_UPDATE + patient.getPatientUUID()
                + ":" + UUID.randomUUID();

        Map<String, Object> initialData = new LinkedHashMap<>();
        initialData.put("default_name",
                patient.getPatientName() == null ? "" : patient.getPatientName());

        if (appointment.getStartAppointmentAt() != null) {
            LocalDateTime start = appointment.getStartAppointmentAt().toLocalDateTime();
            initialData.put("default_date", start.toLocalDate().toString());
            initialData.put("default_time",
                    start.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")));
        } else {
            initialData.put("default_date", "");
            initialData.put("default_time", "");
        }

        initialData.put("default_treatment", currentTreatmentName(appointment));
        initialData.put("default_payment_method",
                mapPaymentMethodToFlowId(appointment.getPaymentMethod()));
        initialData.put("default_payment_status",
                mapPaymentStatusToFlowId(patient.getPatientPaymentState()));

        return whatsAppClient.sendFlow(
                doctorPhone, flowId, flowToken,
                "Abrir formulario",
                "Modificar cita",
                "Los datos actuales están precargados. Cambie lo que necesite.",
                null, "UPDATE_APPOINTMENT", initialData);
    }

    private boolean sendDoctorSearchPatientFlow(String doctorPhone) {
        String flowId = properties.getFlows().getSearchPatient();
        if (flowId == null || flowId.isBlank()) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "El formulario de búsqueda de pacientes no está configurado.");
            return false;
        }
        String flowToken = FLOW_TOKEN_SEARCH + UUID.randomUUID();
        return whatsAppClient.sendFlow(
                doctorPhone, flowId, flowToken,
                "Buscar",
                "Buscar paciente",
                "Escriba el nombre del paciente que desea buscar.",
                null, "SEARCH_PATIENT", null);
    }

    private String currentTreatmentName(Appointment appointment) {
        if (appointment.getTreatments() == null || appointment.getTreatments().isEmpty()) {
            return "";
        }
        return appointment.getTreatments().get(0).getTreatment();
    }

    private String mapPaymentMethodToFlowId(PaymentMethod method) {
        if (method == null) {
            return "";
        }
        return switch (method) {
            case CASH -> "PAY_EFECTIVO";
            case TRANSFER -> "PAY_TRANSFERENCIA";
        };
    }

    private String mapPaymentStatusToFlowId(PatientPaymentState status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case COMPLETE -> "PAT_STAT_COMPLETE";
            case PARTIALLY -> "PAT_STAT_PARTIALLY";
            case IN_DEBT -> "PAT_STAT_INDEBT";
            case UNDEFINED_PRICE -> "";
        };
    }

    /**
     * Handles the webhook message produced when a user completes a Flow.
     * The form type is identified by the {@code flow_token} prefix.
     */
    private void handleFlowResponse(String fromPhone, String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            log.warn("Empty Flow response from {}", fromPhone);
            return;
        }

        Map<String, Object> data;
        try {
            data = objectMapper.readValue(
                    responseJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Invalid Flow response JSON from {}: {}", fromPhone, responseJson, e);
            whatsAppClient.sendTextMessage(fromPhone, "No pudimos leer el formulario. Intente de nuevo.");
            return;
        }

        String token = asString(data.get("flow_token"));
        log.info("Flow response from {} (token={}): {}", fromPhone, token, data);

        if (token == null) {
            whatsAppClient.sendTextMessage(fromPhone, "No pudimos identificar el formulario.");
            return;
        }
        if (token.startsWith(FLOW_TOKEN_REGISTER)) {
            handleDoctorPatientRegistrationFlow(fromPhone, data);
        } else if (token.startsWith(FLOW_TOKEN_UPDATE_SEARCH)) {
            handleUpdatePatientSearchFlow(fromPhone, data);
        } else if (token.startsWith(FLOW_TOKEN_UPDATE)) {
            handleDoctorUpdateAppointmentFlow(fromPhone, data);
        } else if (token.startsWith(FLOW_TOKEN_SEARCH)) {
            handleDoctorSearchPatientFlow(fromPhone, data);
        } else {
            log.warn("Unknown Flow token '{}' from {}", token, fromPhone);
            whatsAppClient.sendTextMessage(fromPhone, "Formulario no reconocido.");
        }
    }

    private void handleDoctorPatientRegistrationFlow(String doctorPhone, Map<String, Object> data) {
        String name = asString(data.get("full_name"));
        Integer age = asInteger(data.get("age"));
        LocalDate date = asLocalDate(data.get("appointment_date"));
        LocalTime time = asLocalTime(data.get("appointment_time"));

        if (name == null || name.isBlank() || age == null || date == null) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "Faltan datos del paciente. Abra el formulario e intente de nuevo.");
            return;
        }
        if (time == null) {
            time = LocalTime.of(10, 0);
        }

        Patient patient = new Patient();
        patient.setPatientName(name);
        patient.setPatientAge(age);
        patient.setPatientState(PatientState.ON_TRACKING);
        patient.setPatientPaymentState(PatientPaymentState.UNDEFINED_PRICE);
        patient = patientRepository.save(patient);

        LocalDateTime start = LocalDateTime.of(date, time);
        Appointment appointment = new Appointment();
        appointment.setPatientAppointment(patient);
        appointment.setCategory("Consultation");
        appointment.setAppointmentStatus(APPOINTMENT_CONFIRMED);
        appointment.setPatientState(PatientState.UNDEFINED);
        appointment.setCreatedAt(now());
        appointment.setStartAppointmentAt(Timestamp.valueOf(start));
        dentistRepository.findFirstByIsDentistActiveTrue().ifPresent(appointment::setDentistAppointment);
        appointmentRepository.save(appointment);

        patient.setAppointment(start);
        patientRepository.save(patient);

        whatsAppClient.sendTextMessage(doctorPhone,
                "✅ Paciente *" + name + "* registrado con cita para el *"
                        + formatAppointment(start) + "*.");
    }

    private void handleDoctorUpdateAppointmentFlow(String doctorPhone, Map<String, Object> data) {
        String token = asString(data.get("flow_token"));
        String patientName = asString(data.get("patient_name"));
        LocalDate date = asLocalDate(data.get("new_date"));
        LocalTime time = asLocalTime(data.get("new_time"));
        String treatmentName = asString(data.get("patient_treatment"));
        String paymentMethodId = asString(data.get("payment_method"));
        String paymentStatusId = asString(data.get("payment_status"));

        UUID patientUUID = parseUpdatePatientUUID(token);

        Optional<Appointment> latest;
        if (patientUUID != null) {
            latest = appointmentRepository
                    .findTopByPatientAppointment_PatientUUIDOrderByCreatedAtDesc(patientUUID);
        } else {
            // Fallback for older forms. New forms always carry the patient UUID in the token.
            if (patientName == null || patientName.isBlank()) {
                whatsAppClient.sendTextMessage(doctorPhone,
                        "No pudimos identificar al paciente de este formulario.");
                return;
            }
            latest = appointmentRepository
                    .findTopByPatientAppointment_PatientNameContainingIgnoreCaseOrderByCreatedAtDesc(patientName);
        }

        if (latest.isEmpty()) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "No encontré una cita para el paciente indicado.");
            return;
        }

        Appointment appointment = latest.get();
        Patient patient = appointment.getPatientAppointment();
        LocalDateTime updatedStart = appointment.getStartAppointmentAt() == null
                ? null
                : appointment.getStartAppointmentAt().toLocalDateTime();

        if (date != null) {
            LocalTime effectiveTime = time != null ? time : LocalTime.of(10, 0);
            updatedStart = LocalDateTime.of(date, effectiveTime);
            appointment.setStartAppointmentAt(Timestamp.valueOf(updatedStart));
            appointment.setAppointmentStatus(APPOINTMENT_CONFIRMED);
            if (patient != null) {
                patient.setAppointment(updatedStart);
            }
        }

        if (treatmentName != null && !treatmentName.isBlank()) {
            treatmentsRepository.findByTreatmentIgnoreCase(treatmentName)
                    .ifPresent(treatment -> appointment.setTreatments(List.of(treatment)));
        }

        if (paymentMethodId != null) {
            PaymentMethod method = mapPaymentMethod(paymentMethodId);
            if (method != null) {
                appointment.setPaymentMethod(method);
            }
        }

        if (paymentStatusId != null && patient != null) {
            PatientPaymentState status = mapPaymentStatus(paymentStatusId);
            if (status != null) {
                patient.setPatientPaymentState(status);
            }
        }

        appointmentRepository.save(appointment);
        if (patient != null) {
            patientRepository.save(patient);
        }

        String when = updatedStart == null ? "la fecha indicada" : formatAppointment(updatedStart);
        String displayName = patient != null && patient.getPatientName() != null
                ? patient.getPatientName()
                : (patientName == null || patientName.isBlank() ? "paciente" : patientName);
        whatsAppClient.sendTextMessage(doctorPhone,
                "✅ La cita de *" + displayName + "* fue actualizada para el *" + when + "*.");

        if (patient != null && patient.getPatientPhone() != null) {
            whatsAppClient.sendTextMessage(patient.getPatientPhone(),
                    "¡Hola! Tu cita fue actualizada para el *" + when + "*.");
        }
    }

    private UUID parseUpdatePatientUUID(String token) {
        if (token == null || !token.startsWith(FLOW_TOKEN_UPDATE)) {
            return null;
        }
        String rest = token.substring(FLOW_TOKEN_UPDATE.length());
        int separator = rest.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        try {
            return UUID.fromString(rest.substring(0, separator));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid update flow token '{}'", token);
            return null;
        }
    }

    private PaymentMethod mapPaymentMethod(String id) {
        return switch (id) {
            case "PAY_EFECTIVO", "cash" -> PaymentMethod.CASH;
            case "PAY_TRANSFERENCIA", "transfer" -> PaymentMethod.TRANSFER;
            default -> null;
        };
    }

    private PatientPaymentState mapPaymentStatus(String id) {
        return switch (id) {
            case "PAT_STAT_COMPLETE", "complete" -> PatientPaymentState.COMPLETE;
            case "PAT_STAT_PARTIALLY", "partially" -> PatientPaymentState.PARTIALLY;
            case "PAT_STAT_INDEBT", "inDebt" -> PatientPaymentState.IN_DEBT;
            default -> null;
        };
    }

    private void handleDoctorSearchPatientFlow(String doctorPhone, Map<String, Object> data) {
        String name = asString(data.get("patient_name"));
        LocalDate date = asLocalDate(data.get("new_date"));

        if ((name == null || name.isBlank()) && date == null) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "Escriba un nombre o seleccione una fecha para buscar.");
            return;
        }

        List<Appointment> appointments = searchAppointments(name, date);
        if (appointments.isEmpty()) {
            whatsAppClient.sendTextMessage(doctorPhone, "No encontré resultados.");
            return;
        }

        StringBuilder sb = new StringBuilder("🔎 Resultados:\n\n");
        for (Appointment appointment : appointments) {
            Patient patient = appointment.getPatientAppointment();
            sb.append("• ").append(patient != null ? patient.getPatientName() : "Sin nombre");
            if (patient != null && patient.getPatientAge() != null) {
                sb.append(", ").append(patient.getPatientAge()).append(" años");
            }
            if (appointment.getStartAppointmentAt() != null) {
                sb.append(" — ").append(appointment.getStartAppointmentAt().toLocalDateTime()
                        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mma",
                                Locale.of("es", "ES"))));
            }
            sb.append("\n");
        }
        whatsAppClient.sendTextMessage(doctorPhone, sb.toString().trim());
    }

    private List<Appointment> searchAppointments(String name, LocalDate date) {
        boolean hasName = name != null && !name.isBlank();

        if (hasName && date != null) {
            return appointmentRepository
                    .findByPatientAppointment_PatientNameContainingIgnoreCaseAndStartAppointmentAtBetween(
                            name, startOfDay(date), startOfNextDay(date));
        }
        if (hasName) {
            return appointmentRepository
                    .findByPatientAppointment_PatientNameContainingIgnoreCase(name);
        }
        return appointmentRepository.findByStartAppointmentAtBetween(
                startOfDay(date), startOfNextDay(date));
    }

    private void handleUpdatePatientSearchFlow(String doctorPhone, Map<String, Object> data) {
        String name = asString(data.get("patient_name"));
        LocalDate date = asLocalDate(data.get("new_date"));

        if ((name == null || name.isBlank()) && date == null) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "Escriba un nombre o seleccione una fecha para buscar.");
            return;
        }

        List<Appointment> appointments = searchAppointments(name, date);
        if (appointments.isEmpty()) {
            whatsAppClient.sendTextMessage(doctorPhone, "No encontré resultados.");
            return;
        }

        Map<UUID, Patient> patients = new LinkedHashMap<>();
        for (Appointment appointment : appointments) {
            Patient patient = appointment.getPatientAppointment();
            if (patient != null) {
                patients.putIfAbsent(patient.getPatientUUID(), patient);
            }
        }

        List<SendInteractiveListRequest.Row> rows = patients.values().stream()
                .limit(10)
                .map(patient -> new SendInteractiveListRequest.Row(
                        UPD_PAT_PREFIX + patient.getPatientUUID(),
                        truncate(patient.getPatientName(), 24),
                        "Edad: " + (patient.getPatientAge() != null
                                ? patient.getPatientAge() : "N/A")))
                .toList();

        whatsAppClient.sendInteractiveList(doctorPhone,
                "Seleccione el paciente a actualizar", "Ver Pacientes", rows);

        WhatsAppSession doctorSession =
                getOrCreateDoctorSession(getOrCreateDoctorContact(doctorPhone));
        setDoctorState(doctorSession, SessionState.DOC_UPD_SELECTING_PATIENT);
    }

    private boolean handleUpdatePatientSelected(String doctorPhone,
                                                WhatsAppSession session,
                                                String selectedOptionId) {
        UUID patientUUID;
        try {
            patientUUID = UUID.fromString(
                    selectedOptionId.substring(UPD_PAT_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            whatsAppClient.sendTextMessage(doctorPhone, "Paciente inválido.");
            return true;
        }

        Optional<Patient> patientOpt = patientRepository.findById(patientUUID);
        if (patientOpt.isEmpty()) {
            whatsAppClient.sendTextMessage(doctorPhone, "Paciente no encontrado.");
            return true;
        }

        Optional<Appointment> appointmentOpt = appointmentRepository
                .findTopByPatientAppointment_PatientUUIDOrderByCreatedAtDesc(patientUUID);
        if (appointmentOpt.isEmpty()) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "Ese paciente no tiene una cita para actualizar.");
            return true;
        }

        sendDoctorUpdateAppointmentFlow(
                doctorPhone, patientOpt.get(), appointmentOpt.get());
        resetDoctorFlow(session);
        return true;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private Timestamp startOfDay(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
    }

    private Timestamp startOfNextDay(LocalDate date) {
        return Timestamp.valueOf(date.plusDays(1).atStartOfDay());
    }

    private String formatAppointment(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern(
                "dd/MM/yyyy 'a las' hh:mma", Locale.of("es", "ES")));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate asLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value).trim());
        } catch (DateTimeException e) {
            return null;
        }
    }

    private LocalTime asLocalTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalTime.parse(String.valueOf(value).trim());
        } catch (DateTimeException e) {
            return null;
        }
    }

    private boolean sendTreatmentList(String doctorPhone) {
        List<Treatments> treatments = treatmentsRepository.findAll();
        if (treatments.isEmpty()) {
            whatsAppClient.sendTextMessage(doctorPhone, "No hay tratamientos registrados en el sistema :(");
            return true;
        }

        List<SendInteractiveListRequest.Row> treatmentRows = treatments.stream()
                .limit(10) // Meta allows a maximum of 10 rows per interactive menu
                .map(t -> new SendInteractiveListRequest.Row(
                        "TREAT_" + t.getId(),
                        t.getTreatment(),
                        "Precio: $" + t.getPrice()))
                .toList();

        whatsAppClient.sendInteractiveList(doctorPhone,
                "Doctor, por favor seleccione el tratamiento que recibió el paciente:",
                "Ver Tratamientos", treatmentRows);
        log.info("Dispatched interactive treatment list to Doctor ({})", doctorPhone);
        return true;
    }

    /** Confirms the date of an appointment created by the patient intake flow. */
    private boolean handlePatientDateConfirmation(String doctorPhone, WhatsAppSession session, String messageBody) {
        LocalDate customDate = parsedDoctorCustomizedDate(messageBody);
        if (customDate == null) {
            whatsAppClient.sendTextMessage(doctorPhone,
                    "No logré reconocer la fecha. Indíquela como *día de mes hora* " +
                            "(Ejemplo: *24 de noviembre 10:00am*).");
            return true;
        }
        LocalTime customTime = parsedDoctorCustomizedTime(messageBody);
        LocalDateTime customDateTime = LocalDateTime.of(customDate, customTime);

        Optional<Appointment> pending =
                appointmentRepository.findTopByAppointmentStatusOrderByCreatedAtDesc("PENDING");
        if (pending.isEmpty()) {
            resetDoctorFlow(session);
            whatsAppClient.sendTextMessage(doctorPhone, "No hay citas pendientes por confirmar.");
            return true;
        }

        Appointment appointment = pending.get();
        appointment.setStartAppointmentAt(Timestamp.valueOf(customDateTime));
        appointment.setAppointmentStatus("CONFIRMED");
        appointmentRepository.save(appointment);
        resetDoctorFlow(session);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                "dd/MM/yyyy 'a las' hh:mma", Locale.of("es", "ES"));
        String formattedDate = customDate.format(formatter);
        whatsAppClient.sendTextMessage(doctorPhone,
                "✅ Cita confirmada con éxito para el *" + formattedDate + "*.");

        if (appointment.getPatientAppointment() != null
                && appointment.getPatientAppointment().getPatientPhone() != null) {
            whatsAppClient.sendTextMessage(
                    appointment.getPatientAppointment().getPatientPhone(),
                    "¡Hola! El doctor ha confirmado tu cita para el *" + formattedDate +
                            "*. Te esperamos en la clínica.");
        }
        return true;
    }

    // ===================================================================================
    // SESSION HELPERS
    // ===================================================================================

    private WhatsAppSession getOrCreateDoctorSession(WhatsAppContact contact) {
        return sessionRepository.findByWhatsAppContact_WaUserId(contact.getWaUserId())
                .orElseGet(() -> {
                    WhatsAppSession session = new WhatsAppSession();
                    session.setWhatsAppContact(contact);
                    session.setCurrentState(SessionState.DOC_IDLE);
                    session.setUpdatedAt(now());
                    return sessionRepository.save(session);
                });
    }

    private WhatsAppContact getOrCreateDoctorContact(String phoneNumber) {
        return contactRepository.findByWaUserId(phoneNumber)
                .orElseGet(() -> {
                    WhatsAppContact contact = new WhatsAppContact();
                    contact.setWaUserId(phoneNumber);
                    contact.setDisplayNumber(phoneNumber);
                    contact.setCreatedAt(now());
                    contact.setLastSeenAt(now());
                    return contactRepository.save(contact);
                });
    }

    private void setDoctorState(WhatsAppSession session, SessionState state) {
        session.setCurrentState(state);
        session.setUpdatedAt(now());
        sessionRepository.save(session);
    }

    private void resetDoctorFlow(WhatsAppSession session) {
        session.setCurrentState(SessionState.DOC_IDLE);
        session.setUpdatedAt(now());
        sessionRepository.save(session);
    }

    // ===================================================================================
    // INTERACTIVE MESSAGE BUILDERS
    // ===================================================================================

    private static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    private List<SendInteractiveListRequest.Row> doctorMainOptions() {
        return List.of(
            new SendInteractiveListRequest.Row("CRT_PAT","Registrar Paciente","Registre un nuevo paciente"),
            new SendInteractiveListRequest.Row("CITAS","Ver Citas","Vea las 10 últimas citas"),
            new SendInteractiveListRequest.Row("PAGO","Cobrar al Paciente","Efectivo o Transferencia"),
            new SendInteractiveListRequest.Row("UPD_CITA","Modificar cita","Cambiar nombre, fecha, etc de una cita"),
            new SendInteractiveListRequest.Row("SRCH_PAT","Buscar Paciente","Busque paciente por nombre, apellido o fecha")
        );
    }

    private List<SendInteractiveButtonRequest.Button> paymentMethodButtons() {
        return List.of(
                new SendInteractiveButtonRequest.Button("reply",
                        new SendInteractiveButtonRequest.Reply("PAY_EFECTIVO", "EFECTIVO")),
                new SendInteractiveButtonRequest.Button("reply",
                        new SendInteractiveButtonRequest.Reply("PAY_TRANSFERENCIA", "TRANSFERENCIA")));
    }

    private List<SendInteractiveButtonRequest.Button> paymentStatusButtons() {
        return List.of(
                new SendInteractiveButtonRequest.Button("reply",
                        new SendInteractiveButtonRequest.Reply("PAT_STAT_COMPLETE",
                                PatientPaymentState.COMPLETE.toString())),
                new SendInteractiveButtonRequest.Button("reply",
                        new SendInteractiveButtonRequest.Reply("PAT_STAT_PARTIALLY",
                                PatientPaymentState.PARTIALLY.toString())),
                new SendInteractiveButtonRequest.Button("reply",
                        new SendInteractiveButtonRequest.Reply("PAT_STAT_INDEBT",
                                PatientPaymentState.IN_DEBT.toString())));
    }

    /**
     * Renders up to 10 appointments per page with formatted details and a "Ver más citas" pagination button.
     *
     * @param doctorPhone Recipient phone number of the doctor
     * @param pageNumber  Zero-based page index (0 = first 10, 1 = next 10, etc.)
     */

    /// a method for showing just the message, with a Page<Appointment> parameter

    private void showAppointmentsPageMessage(String doctorPhone, int pageNumber, Page<Appointment> appointmentPage){
        int pageSize = 10;

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


    private Page<Appointment> showAppointmentsPage(int pageNumber) {
        return appointmentRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(pageNumber, 10));
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
    private LocalTime parsedDoctorCustomizedTime(String messageDoctor){
        String input = messageDoctor == null ? "" : messageDoctor.trim();
        // -----------------------------------------------------------------------------------
        // EXTRACT TIME (or default to 10:00 AM standard appointment hour)
        // -----------------------------------------------------------------------------------

        LocalTime timeFound = findTime(input);
        if (timeFound == null) {
            // Default clinic appointment time if doctor didn't specify an hour
            timeFound = LocalTime.of(10, 0);
        }
        return timeFound;
    }
    private LocalDate parsedDoctorCustomizedDate(String messageDoctor) {
        if (messageDoctor == null || messageDoctor.isBlank()) {
            log.warn("Doctor message is null or blank for custom date parsing.");
            return null;
        }
        String input = messageDoctor.trim();

        // -----------------------------------------------------------------------------------
        // EXTRACT DATE (Relative words, Numeric format, or Spanish written format)
        // -----------------------------------------------------------------------------------
        int day = 0;
        int monthValue = 0;
        int year = LocalDate.now().getYear(); // Default to current year if omitted

        // Pattern 0: Relative words like "hoy", "mañana" (o "manana"), "pasado mañana"
        Pattern relativeDatePattern = Pattern.compile("\\b(hoy|mañana|manana|pasado\\s+mañana|pasado\\s+manana)\\b", Pattern.CASE_INSENSITIVE);
        Matcher relativeMatcher = relativeDatePattern.matcher(input);

        // Pattern A: Numeric date dd/MM/yyyy or dd-MM-yyyy or dd/MM (e.g., "24/11/2026", "24/11")
        Pattern numericDatePattern = Pattern.compile("\\b(\\d{1,2})[/\\-](\\d{1,2})(?:[/\\-](\\d{4}))?\\b");
        Matcher numericMatcher = numericDatePattern.matcher(input);

        // Pattern B: Written Spanish date: e.g. "5 de noviembre del 2026", "el 12 nov 2026", "5 de noviembre"
        Pattern writtenDatePattern = Pattern.compile(
                "\\b(\\d{1,2})\\s*(?:de)?\\s*([a-zA-ZáéíóúÁÉÍÓÚñÑ]+)(?:\\s*(?:del?|de)?\\s*(\\d{4}))?\\b",
                Pattern.CASE_INSENSITIVE
        );
        Matcher writtenMatcher = writtenDatePattern.matcher(input);

        if (relativeMatcher.find()) {
            String matchedWord = relativeMatcher.group(1).toLowerCase();
            LocalDate targetDate;
            if (matchedWord.contains("pasado")) {
                targetDate = LocalDate.now().plusDays(2);
            } else if (matchedWord.startsWith("mañan") || matchedWord.startsWith("manan")) {
                targetDate = LocalDate.now().plusDays(1);
            } else {
                targetDate = LocalDate.now();
            }
            day = targetDate.getDayOfMonth();
            monthValue = targetDate.getMonthValue();
            year = targetDate.getYear();
        } else if (numericMatcher.find()) {
            day = Integer.parseInt(numericMatcher.group(1));
            monthValue = Integer.parseInt(numericMatcher.group(2));
            if (numericMatcher.group(3) != null) {
                year = Integer.parseInt(numericMatcher.group(3));
            }
        } else if (writtenMatcher.find()) {
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

        // -----------------------------------------------------------------------------------
        // VALIDATE AND BUILD LOCAL DATE
        // -----------------------------------------------------------------------------------
        if (day < 1 || day > 31 || monthValue < 1 || monthValue > 12) {
            log.warn("Could not extract a valid date from doctor's message: '{}'", messageDoctor);
            return null;
        }

        try {
            LocalDate date = LocalDate.of(year, monthValue, day);
            log.info("Successfully parsed customized doctor appointment: {}", date);
            return date;
        } catch (DateTimeException e) {
            log.error("Invalid calendar date constructed from doctor message '{}': {}", messageDoctor, e.getMessage());
            return null;
        }
    }

    private record ParsedPatientInfo(String name, Integer age) {}
}
