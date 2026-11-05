package com.example.Project1.Service;

import com.example.Project1.Config.WhatsAppProperties;
import com.example.Project1.Model.DTO.SendInteractiveListRequest;
import com.example.Project1.Model.DTO.WhatsAppWebhookPayload;
import com.example.Project1.Model.Entity.*;
import com.example.Project1.repository.*;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
            if (handleDoctorMessage(doctorPhone, incomingMessage, messageBody)) {
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

            if(appointment.getPaymentMethod() == null) {
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
                    + ", Cuando desearía recibir a este paciente?, aquí hay algunas sugerencias: "
                    + suggestedAppointment;

            String recipientDoctor = (doctorPhone != null && !doctorPhone.isBlank()) ? doctorPhone : fromPhone;
            whatsAppClient.sendTextMessage(recipientDoctor, doctorMessage);
            log.info("Forwarded patient details to Doctor ({})", recipientDoctor);

            session.setCurrentState("COMPLETED");
            session.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            sessionRepository.save(session);
        }
    }

    /**
     * Handles administrative commands and interactive menu responses sent by the Doctor.
     *
     * @param doctorPhone The verified phone number of the doctor
     * @param incomingMessage The incoming webhook message payload
     * @param messageBody Text content of the message or selected menu option
     * @return true if the message was handled as a doctor command, false to allow default flow
     */
    private boolean handleDoctorMessage(String doctorPhone,
                                        WhatsAppWebhookPayload.IncomingMessage incomingMessage,
                                        String messageBody) {

        String selectedOptionId = incomingMessage.getInteractiveSelectionId();

        // Command 1: Doctor types "citas" -> Display Page 0 (most recent 10 appointments)
        if ("citas".equalsIgnoreCase(messageBody.trim())) {
            showAppointmentsPage(doctorPhone, 0);
            return true;
        }

        // Command 2: Doctor tapped "Ver más citas" -> Display the next page of older appointments
        if (selectedOptionId != null && selectedOptionId.startsWith("CITAS_PAGE_")) {
            try {
                int nextPage = Integer.parseInt(selectedOptionId.replace("CITAS_PAGE_", ""));
                showAppointmentsPage(doctorPhone, nextPage);
            } catch (NumberFormatException e) {
                log.error("Failed to parse page number from action id: {}", selectedOptionId, e);
            }
            return true;
        }

        // Command 3: Doctor types "PAGO" -> Present an interactive list of treatments
        if ("PAGO".equalsIgnoreCase(messageBody.trim())) {
            List<SendInteractiveListRequest.Row> treatmentRows = treatmentsRepository.getAllTreatmentsIgnoreCase();

            String prompt = "Doctor, por favor seleccione el tratamiento que recibió el paciente:";
            String buttonTreatmentText = "Ver Tratamientos";

            whatsAppClient.sendInteractiveList(doctorPhone, prompt, buttonTreatmentText, treatmentRows);
            log.info("Dispatched interactive treatment list to Doctor ({})", doctorPhone);
            return true;
        }

        // Command 4: Doctor tapped an option from the Interactive List Menu
        String selectedTreatmentId = incomingMessage.getInteractiveSelectionId();
        if (selectedTreatmentId != null) {
            log.info("Doctor selected treatment option: id={}, title={}", selectedTreatmentId, messageBody);
            String confirmationText = "Tratamiento registrado con éxito: *" + messageBody + "*.\n"
                    + "El pago del paciente es de: $"+treatmentsRepository.findPriceByTreatment(selectedTreatmentId);
            whatsAppClient.sendTextMessage(doctorPhone, confirmationText);

            // Command 5: Bot asks the doctor what method of payment did the patient selected, and if the patient paid the full price or partially

            String promptForPayment = "De qué forma realizó el pago el paciente?: \n";
            String buttonTextPayment = "Ver métodos de pago";
            String selectedPaymentMethodId = incomingMessage.getInteractiveSelectionId();
            List<SendInteractiveListRequest.Row> paymentMethods =List.of(
                    new SendInteractiveListRequest.Row("PAY_TRANSFERENCIA","TRANSFERENCIA", "Por Banca móvil de cualquier banco"),
                    new SendInteractiveListRequest.Row("PAY_EFECTIVO","EFECTIVO","Billetes/monedas")
            );
            whatsAppClient.sendInteractiveList(doctorPhone,promptForPayment,buttonTextPayment,paymentMethods);
            if (selectedPaymentMethodId.equals("PAY_TRANSFERENCIA")){
                String promptConfirmCompletePayment= "El Paciente ha pagado todo, parcialmente, o queda debiéndole?";
                whatsAppClient.sendTextMessage(doctorPhone,promptConfirmCompletePayment);
            }
            return true;
        }
        return false;
    }

    /**
     * Renders up to 10 appointments per page with formatted details and a "Ver más citas" pagination button.
     *
     * @param doctorPhone Recipient phone number of the doctor
     * @param pageNumber Zero-based page index (0 = first 10, 1 = next 10, etc.)
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
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEEE, MMM d 'at' 10:00 AM", Locale.ENGLISH);
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

    private record ParsedPatientInfo(String name, Integer age) {}
}
