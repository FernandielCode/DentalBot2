package com.example.Project1.repository;

import com.example.Project1.Model.Entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {
    List<Appointment> findByAppointmentStatus(String appointmentStatus);
    List<Appointment> findByPatientAppointment_PatientUUID(UUID patientUUID);
}
