package com.recoverpro.server.entity;

import com.recoverpro.server.security.encryption.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "lucien_chat_sessions", indexes = {
        @Index(name = "idx_session_agent_id", columnList = "agent_id"),
        @Index(name = "idx_session_active",   columnList = "agent_id, is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatSession {

    @Id
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private String id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** When set, this session is a visit-interview bound to a single case (Lucien stands in for
     * the manager, coaching the FO through a doorstep negotiation) rather than the general
     * FO-wide assistant. Drives which system prompt/context LucienServiceImpl resolves. */
    @Column(name = "allocation_id")
    private UUID allocationId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "agent_first_name", nullable = false)
    private String agentFirstName;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "total_messages", nullable = false)
    @Builder.Default
    private Integer totalMessages = 0;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<ChatMessage> messages = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @PrePersist
    void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID().toString();
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
