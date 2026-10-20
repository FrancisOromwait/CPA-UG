package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Who edited or deleted what, and what it looked like before. Written whenever a record is changed or removed. */
@Entity
@Table(name = "audit_log", indexes = @Index(columnList = "branch_id"))
@Getter @Setter @NoArgsConstructor
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(nullable = false, length = 60)
    private String username;

    /** EDIT or DELETE. */
    @Column(nullable = false, length = 20)
    private String action;

    @Column(nullable = false, length = 40)
    private String entityType;

    private Long entityId;

    @Column(length = 1000)
    private String details;

    private Instant createdAt = Instant.now();
}
