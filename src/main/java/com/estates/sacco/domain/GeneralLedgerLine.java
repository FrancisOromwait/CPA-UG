package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One debit or credit line against a chart-of-accounts account (the general ledger, GL). */
@Entity
@Table(name = "general_ledger", indexes = {
        @Index(columnList = "branch_id,account_id"),
        // covers the balance and trial-balance sums, which add up years of migrated history without reading the table rows
        @Index(columnList = "branch_id,account_id,posted_on,debit,credit"),
        @Index(columnList = "posted_on"),
        @Index(columnList = "ledger_entry_id"),
        @Index(columnList = "journal_id")})
@Getter @Setter @NoArgsConstructor
public class GeneralLedgerLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** Sacco ID of the branch, stored on the row so the ledger reads on its own. */
    @Column(length = 20)
    private String branchCode;

    @ManyToOne(optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    /** Lines created by the same transaction share a ledger entry (the journal). */
    @ManyToOne
    @JoinColumn(name = "ledger_entry_id")
    private LedgerEntry ledgerEntry;

    /** Set instead of ledgerEntry for lines of a manual journal. */
    @ManyToOne
    @JoinColumn(name = "journal_id")
    private JournalEntry journal;

    @ManyToOne
    @JoinColumn(name = "member_id")
    private Member member;

    /** Set only on the single receiving-account debit of a regular deposit (the total of its parts). */
    @Column(length = 40)
    private String depositGroup;

    @Column(length = 20)
    private String productCode;

    @Column(nullable = false)
    private LocalDate postedOn;

    private String description;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;

    private Instant createdAt = Instant.now();
}
