package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A manual journal: any number of debit and credit lines that balance. Its lines live in the general ledger.
 * Journals never touch member accounts, only the branch's own books (expenses, bank charges, adjustments).
 */
@Entity
@Table(name = "journal_entries", uniqueConstraints = @UniqueConstraint(columnNames = {"branch_id", "journalNo"}))
@Getter @Setter @NoArgsConstructor
public class JournalEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** JV0001, JV0002 ... within the branch. */
    @Column(nullable = false, length = 20)
    private String journalNo;

    @Column(nullable = false)
    private LocalDate postedOn;

    private String reference;

    @Column(length = 500)
    private String narration;

    /** Sum of the debit side (equal to the credit side). */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    private String createdBy;
    private Instant createdAt = Instant.now();
}
