package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One monthly installment of a loan's repayment schedule (table loan_schedule).
 * The rows are created when the loan is paid out and agree with the terms of the loan;
 * the paid columns are rebuilt from the loan's repayments whenever they change.
 */
@Entity
@Table(name = "loan_schedule", uniqueConstraints = @UniqueConstraint(columnNames = {"loan_id", "installmentNo"}))
@Getter @Setter @NoArgsConstructor
public class LoanInstallment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "loan_id")
    private Loan loan;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(length = 20)
    private String branchCode;

    @Column(nullable = false)
    private int installmentNo;

    @Column(nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal openingBalance;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal principalDue;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal interestDue;

    /** Principal plus interest: what must be paid for this month. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal installmentDue;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal closingBalance;

    /** How much of this installment has been paid (repayments are matched to the oldest installment first). */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /** The date of the repayment that completed this installment; empty until it is fully paid. */
    private LocalDate paidOn;
}
