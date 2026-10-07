package com.example.Project1.Service;

import com.example.Project1.Model.DTO.WhatsAppWebhookPayload;
import com.example.Project1.Model.Entity.*;
import com.example.Project1.config.WhatsAppProperties;
import com.example.Project1.repository.*;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WhatsAppBotService {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppBotService.class);

    private final WhatsAppClient whatsAppClient;
    private final WhatsAppProperties properties;
    private final WhatsAppContactRepository contactRepository;
    private final WhatsAppSessionRepository sessionRepository;
    private final WhatsAppMessageRepository messageRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final DentistRepository dentistRepository;

    public WhatsAppBotService(WhatsAppClient whatsAppClient,
                              WhatsAppProperties properties,
                              WhatsAppContactRepository contactRepository,
                              WhatsAppSessionRepository sessionRepository,
                              WhatsAppMessageRepository messageRepository,
                              PatientRepository patientRepository,
                              AppointmentRepository appointmentRepository,
                              DentistRepository dentistRepository) {
        this.whatsAppClient = whatsAppClient;
        this.properties = properties;
        this.contactRepository = contactRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.patientRepository = patientRepository;
        this.appointmentRepository = appointmentRepository;
        this.dentistRepository = dentistRepository;
    }

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

    private void handleIncomingMessage(WhatsAppWebhookPayload.IncomingMessage incomingMessage,
                                       java.util.List<WhatsAppWebhookPayload.Contact> contacts) {
        String fromPhone = incomingMessage.from();
        String messageBody = incomingMessage.text() != null ? incomingMessage.text().body() : "";
        if (fromPhone == null || messageBody == null || messageBody.isBlank()) {
            return;
        }

        log.info("Received WhatsApp message from [{}]: {}", fromPhone, messageBody);

        // 1. Find or create Contact
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

        // 2. Find or create Session
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
            // STEP 1 & 2: Patient types to the bot -> Bot responds asking for their information
            String greetingPrompt = "Hello! Welcome to Dental Clinic.\n"
                    + "Please reply with your *Full Name* and *Age* (for example: *John Doe, 28*) so we can schedule your appointment with the Doctor.";

            whatsAppClient.sendTextMessage(fromPhone, greetingPrompt);
            saveMessageRecord(contact, null, "OUTGOING", greetingPrompt);

            session.setCurrentState("AWAITING_PATIENT_INFO");
            session.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            sessionRepository.save(session);

        } else if ("AWAITING_PATIENT_INFO".equalsIgnoreCase(state)) {
            // STEP 3: Patient responds with their information
            ParsedPatientInfo parsedInfo = parsePatientInfo(messageBody);

            if (parsedInfo == null || parsedInfo.name == null || parsedInfo.age == null) {
                String retryPrompt = "We couldn't quite extract your details. Please reply with your *Full Name* and *Age* (e.g. *Gabriel Santos, 25*).";
                whatsAppClient.sendTextMessage(fromPhone, retryPrompt);
                saveMessageRecord(contact, null, "OUTGOING", retryPrompt);
                return;
            }

            // Save/Update Patient in DB
            Patient patient = patientRepository.findByPatientPhone(fromPhone)
                    .orElseGet(() -> {
                        Patient newPatient = new Patient();
                        newPatient.setPatientPhone(fromPhone);
                        return newPatient;
                    });

            patient.setPatientName(parsedInfo.name);
            patient.setPatientAge(parsedInfo.age);
            patient = patientRepository.save(patient);

            contact.setPatientContact(patient);
            contactRepository.save(contact);

            // Compute suggested appointment slots
            String suggestedAppointment = generateSuggestedAppointment();

            // Create Appointment record
            Appointment appointment = new Appointment();
            appointment.setPatientAppointment(patient);
            appointment.setCategory("Consultation");
            appointment.setAppointmentStatus("PENDING");
            appointment.setNotes("Suggested: " + suggestedAppointment);
            appointment.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            // Default slot timestamp: tomorrow at 10 AM
            appointment.setStartAppointmentAt(Timestamp.valueOf(LocalDate.now().plusDays(1).atTime(10, 0)));

            // Assign default active dentist if exists
            Optional<Dentist> activeDentist = dentistRepository.findFirstByIsDentistActiveTrue();
            activeDentist.ifPresent(appointment::setDentistAppointment);

            appointmentRepository.save(appointment);

            // Confirm receipt to patient
            String patientAck = "Thank you, " + parsedInfo.name + "! We have received your information and notified the Doctor to confirm your appointment time.";
            whatsAppClient.sendTextMessage(fromPhone, patientAck);
            saveMessageRecord(contact, null, "OUTGOING", patientAck);

            // Send formatted message to Doctor's WhatsApp contact:
            // "The patient [name of patient] with age [number age], When would you like to schedule the appointment of this patient?, here are some suggested appointments: [suggested appointment 1]"
            String doctorMessage = "The patient " + parsedInfo.name + " with age " + parsedInfo.age
                    + ", When would you like to schedule the appointment of this patient?, here are some suggested appointments: "
                    + suggestedAppointment;

            String doctorPhone = properties.getDoctor() != null ? properties.getDoctor().getPhoneNumber() : null;
            if (doctorPhone == null || doctorPhone.isBlank()) {
                doctorPhone = fromPhone; // Fallback to sender during testing if not set
            }

            whatsAppClient.sendTextMessage(doctorPhone, doctorMessage);
            log.info("Forwarded patient details to Doctor ({})", doctorPhone);

            session.setCurrentState("COMPLETED");
            session.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            sessionRepository.save(session);
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
