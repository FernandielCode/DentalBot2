package com.example.Project1.repository;

import com.example.Project1.Model.Entity.MessageThread;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MessageThreadRepository extends JpaRepository<MessageThread, UUID> {
}
