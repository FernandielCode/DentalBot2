package com.example.Project1.repository;

import com.example.Project1.Model.Entity.Dentist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DentistRepository extends JpaRepository<Dentist, UUID> {
    List<Dentist> findByIsDentistActiveTrue();
    Optional<Dentist> findFirstByIsDentistActiveTrue();
}
