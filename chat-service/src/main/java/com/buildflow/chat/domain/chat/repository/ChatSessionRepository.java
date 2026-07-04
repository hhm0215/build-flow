package com.buildflow.chat.domain.chat.repository;

import com.buildflow.chat.domain.chat.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {
}
