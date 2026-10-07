package com.example.Project1.Service;

import com.example.Project1.Model.Entity.Patient;
import com.example.Project1.repository.PatientRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PatientService {
    private final PatientRepository patientRepository;

    @Autowired
    public PatientService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    public List<Patient> getAllPatients() {
        return patientRepository.findAll();
    }

    public Optional<Patient> getPatientById(UUID patientUUID) {
        return patientRepository.findById(patientUUID);
    }

    public Optional<Patient> getPatientByPhone(String phone) {
        return patientRepository.findByPatientPhone(phone);
    }

    @Transactional
    public Patient savePatient(Patient patient) {
        return patientRepository.save(patient);
    }

    @Transactional
    public void deletePatient(UUID patientUUID) {
        Optional<Patient> patient = patientRepository.findById(patientUUID);
        if (patient.isPresent()) {
            patientRepository.delete(patient.get());
        } else {
            throw new IllegalStateException("Patient does not exist");
        }
    }
}
