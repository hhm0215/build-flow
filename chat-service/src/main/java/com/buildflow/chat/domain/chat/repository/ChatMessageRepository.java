package com.buildflow.chat.domain.chat.repository;

import com.buildflow.chat.domain.chat.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 최신 메시지부터 Pageable 크기만큼만 읽는다. 같은 시각은 id로 순서를 고정한다. */
    List<ChatMessage> findBySessionIdOrderByCreatedAtDescIdDesc(String sessionId, Pageable pageable);
}
