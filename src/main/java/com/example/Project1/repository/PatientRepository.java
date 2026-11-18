package com.example.Project1.repository;

import com.example.Project1.Model.Entity.Patient;
import com.example.Project1.Model.Entity.PatientState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientRepository extends JpaRepository<Patient, UUID> {
    Optional<Patient> findByPatientPhone(String patientPhone);
    List<Patient> findAllByPatientState(PatientState patientState);
}
