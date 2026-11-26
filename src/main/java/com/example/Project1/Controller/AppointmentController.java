package com.example.Project1.Controller;

import com.example.Project1.Model.Entity.Appointment;
import com.example.Project1.Model.Entity.PatientConfirmationStatus;
import com.example.Project1.Service.AppointmentReminderService;
import com.example.Project1.repository.AppointmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/appointments")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000", "http://localhost:5157"})
public class AppointmentController {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentReminderService reminderService;

    @Autowired
    public AppointmentController(AppointmentRepository appointmentRepository,
                                 AppointmentReminderService reminderService) {
        this.appointmentRepository = appointmentRepository;
        this.reminderService = reminderService;
    }

    @GetMapping
    public List<Appointment> getAllAppointments() {
        return appointmentRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Appointment> getAppointmentById(@PathVariable UUID id) {
        return appointmentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Appointment> updateStatus(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return appointmentRepository.findById(id)
                .map(appointment -> {
                    String previousStatus = appointment.getAppointmentStatus();
                    boolean statusChanged = body.containsKey("status");

                    if (statusChanged) {
                        appointment.setAppointmentStatus(body.get("status"));
                    }
                    if (body.containsKey("notes")) {
                        appointment.setNotes(body.get("notes"));
                    }

                    Appointment saved = appointmentRepository.save(appointment);

                    if (statusChanged) {
                        if ("CANCELLED".equalsIgnoreCase(saved.getAppointmentStatus())) {
                            reminderService.cancelFor(saved);
                        } else {
                            // Reactivating a cancelled appointment requires a fresh patient answer.
                            if ("CANCELLED".equalsIgnoreCase(previousStatus)) {
                                saved.setPatientConfirmationStatus(PatientConfirmationStatus.PENDING);
                                saved.setPatientRespondedAt(null);
                                saved = appointmentRepository.save(saved);
                            }
                            reminderService.scheduleFor(saved);
                        }
                    }

                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
