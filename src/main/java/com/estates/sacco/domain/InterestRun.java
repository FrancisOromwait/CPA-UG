package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One month's interest computation for a branch: the interest charged on its loans for installments falling due up to month end. */
@Entity
@Table(name = "interest_runs")
@Getter @Setter @NoArgsConstructor
public class InterestRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(length = 20)
    private String branchCode;

    /** yyyy-MM */
    @Column(name = "run_month", nullable = false, length = 7)
    private String month;

    /** Last day of the month. */
    @Column(nullable = false)
    private LocalDate monthEnd;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalInterest = BigDecimal.ZERO;

    private int loanCount;
    private String createdBy;
    private Instant createdAt = Instant.now();
}
