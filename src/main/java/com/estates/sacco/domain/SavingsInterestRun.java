package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One booking of savings interest for a branch: either a month's interest earned (ACCRUAL, kept as owed to the members)
 * or the payment of what has built up into the members' savings (PAYOUT). The amounts themselves are ledger entries.
 */
@Entity
@Table(name = "savings_interest_runs")
@Getter @Setter @NoArgsConstructor
public class SavingsInterestRun {
    public static final String ACCRUAL = "ACCRUAL";
    public static final String PAYOUT = "PAYOUT";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(length = 20)
    private String branchCode;

    @Column(nullable = false, length = 10)
    private String kind;

    /** yyyy-MM for a month's interest, yyyy-MM-dd for a payout. */
    @Column(name = "run_period", nullable = false, length = 10)
    private String period;

    /** Last day of the month, or the day of the payout. */
    @Column(nullable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    private int memberCount;

    /** Yearly rate used (accruals). */
    @Column(precision = 6, scale = 2)
    private BigDecimal ratePercent;

    /** The reference written on every ledger entry of this run. */
    private String reference;

    private String createdBy;
    private Instant createdAt = Instant.now();
}
