package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** One employer's monthly payroll deduction remittance, posted to the ledger line by line. */
@Entity
@Table(name = "payroll_batches")
@Getter @Setter @NoArgsConstructor
public class PayrollBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(nullable = false)
    private String employer;

    /** yyyy-MM */
    @Column(nullable = false, length = 7)
    private String period;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** The date the deductions were received. */
    private java.time.LocalDate paymentDate;

    /** The chart-of-accounts account that received the money. */
    @ManyToOne
    @JoinColumn(name = "receiving_account_id")
    private Account receivingAccount;

    private int lineCount;
    private String createdBy;
    private Instant createdAt = Instant.now();
}
