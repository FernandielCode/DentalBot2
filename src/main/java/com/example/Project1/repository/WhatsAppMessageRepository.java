package com.example.Project1.repository;

import com.example.Project1.Model.Entity.WhatsAppMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WhatsAppMessageRepository extends JpaRepository<WhatsAppMessage, UUID> {
}
