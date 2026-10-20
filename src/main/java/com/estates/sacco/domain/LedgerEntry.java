package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Record of every money movement; all balances are derived from these rows. */
@Entity
@Table(name = "ledger_entries", indexes = {
        @Index(columnList = "branch_id,type"),
        @Index(columnList = "member_id,type"),
        @Index(columnList = "loan_id")})
@Getter @Setter @NoArgsConstructor
public class LedgerEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @ManyToOne(optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne
    @JoinColumn(name = "loan_id")
    private Loan loan;

    @ManyToOne
    @JoinColumn(name = "payroll_batch_id")
    private PayrollBatch payrollBatch;

    /** The product this entry was posted to (set when it is posted to the ledgers). */
    @ManyToOne
    @JoinColumn(name = "product_id")
    private ProductDef product;

    /** Chosen cash-side account (where the money was received or paid from). Null: derived from the source. */
    @ManyToOne
    @JoinColumn(name = "receiving_account_id")
    private Account receivingAccount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private TxType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    private String reference;

    /** Parts of one regular deposit share this key; their bank (receiving account) debit is posted once, as a total. */
    @Column(length = 40)
    private String depositGroup;

    /** Free-text notes, e.g. from the deposit screen. */
    private String notes;

    /** Savings withdrawal fee: part of the amount withdrawn that the sacco keeps, so the member is paid the amount less the fee. */
    @Column(nullable = false, precision = 19, scale = 2, columnDefinition = "decimal(19,2) not null default 0")
    private java.math.BigDecimal fee = java.math.BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDate postedOn;

    private String createdBy;
    private Instant createdAt = Instant.now();
}
