package com.example.Project1.repository;

import com.example.Project1.Model.Entity.WhatsAppContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppContactRepository extends JpaRepository<WhatsAppContact, UUID> {
    Optional<WhatsAppContact> findByWaUserId(String waUserId);
}
