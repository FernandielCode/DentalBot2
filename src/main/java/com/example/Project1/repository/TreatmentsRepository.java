package com.example.Project1.repository;

import com.example.Project1.Model.Entity.Treatments;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TreatmentsRepository extends JpaRepository<Treatments, Long> {

    List<Treatments> findAll();

    Optional<Treatments> findByTreatmentIgnoreCase(String treatment);
}
