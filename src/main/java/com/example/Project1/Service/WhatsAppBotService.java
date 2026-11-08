package com.example.Project1.Service;

import com.example.Project1.Config.WhatsAppProperties;
import com.example.Project1.Model.DTO.SendInteractiveListRequest;
import com.example.Project1.Model.DTO.WhatsAppWebhookPayload;
import com.example.Project1.Model.Entity.*;
import com.example.Project1.repository.*;
import jakarta.transaction.Transactional;
import org.hibernate.type.descriptor.java.LocalDateJavaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.convert.threeten.Jsr310JpaConverters;
import org.springframework.stereotype.Service;

import java.sql.Time;
import java.sql.Timestamp;
import java.text.BreakIterator;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
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
     * @param doctorPhone     The verified phone number of the doctor
     * @param incomingMessage The incoming webhook message payload
     * @param messageBody     Text content of the message or selected menu option
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
                    + "El pago del paciente es de: $" + treatmentsRepository.findPriceByTreatment(selectedTreatmentId);
            whatsAppClient.sendTextMessage(doctorPhone, confirmationText);

            // Command 5: Bot asks the doctor what method of payment did the patient selected, and if the patient paid the full price or partially

            String promptForPayment = "De qué forma realizó el pago el paciente?: \n";
            String buttonTextPayment = "Ver métodos de pago";
            String selectedPaymentMethodId = incomingMessage.getInteractiveSelectionId();
            List<SendInteractiveListRequest.Row> paymentMethods = List.of(
                    new SendInteractiveListRequest.Row("PAY_TRANSFERENCIA", "TRANSFERENCIA", "Por Banca móvil de cualquier banco"),
                    new SendInteractiveListRequest.Row("PAY_EFECTIVO", "EFECTIVO", "Billetes/monedas")
            );
            whatsAppClient.sendInteractiveList(doctorPhone, promptForPayment, buttonTextPayment, paymentMethods);
            if (selectedPaymentMethodId.equals("PAY_TRANSFERENCIA")) {
                String promptConfirmCompletePayment = "El Paciente ha pagado todo, parcialmente, o queda debiéndole?";
                whatsAppClient.sendTextMessage(doctorPhone, promptConfirmCompletePayment);
            }
            return true;
        }
        return false;
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
