package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "loans")
@Getter @Setter @NoArgsConstructor
public class Loan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @ManyToOne(optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal principal;

    /** Flat annual interest rate in percent, e.g. 12 = 12% p.a. */
    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal annualRatePercent;

    @Column(nullable = false)
    private int termMonths;

    /** Principal plus flat interest, fixed at application time. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalDue;

    /** How the interest is worked out. Existing loans are straight-line (the column default); new loans default to reducing balance. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) default 'STRAIGHT_LINE'")
    private InterestMethod interestMethod = InterestMethod.REDUCING_BALANCE;

    /**
     * True once the loan's interest is recorded month by month (the monthly interest run) instead of in full at payout.
     * Loans paid out before that change are false until they are converted at start-up.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean interestAccrual;

    /** Principal owed at the start of this system, carried over from the old one: owed, but not paid out through the ledger here. */
    @Column(nullable = false, precision = 15, scale = 2, columnDefinition = "decimal(15,2) default 0")
    private BigDecimal broughtForward = BigDecimal.ZERO;

    /** Fee charged for processing the application. */
    @Column(nullable = false, precision = 15, scale = 2, columnDefinition = "decimal(15,2) default 0")
    private BigDecimal applicationFee = BigDecimal.ZERO;

    /** The other charges taken out of the payout: process fee, insurance and legal charges (the application fee is above). */
    @Column(nullable = false, precision = 15, scale = 2, columnDefinition = "decimal(15,2) default 0")
    private BigDecimal processFee = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2, columnDefinition = "decimal(15,2) default 0")
    private BigDecimal insuranceFee = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2, columnDefinition = "decimal(15,2) default 0")
    private BigDecimal legalCharges = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LoanStatus status = LoanStatus.PENDING;

    private LocalDate appliedOn = LocalDate.now();
    private LocalDate disbursedOn;

    /** Set when this loan is a top-up: the earlier loan whose balance its payout clears. */
    @ManyToOne
    @JoinColumn(name = "top_up_of_loan_id")
    private Loan topUpOf;

    /** What the payout of this top-up cleared on the earlier loan (principal still owed plus interest charged). */
    @Column(precision = 15, scale = 2)
    private BigDecimal topUpSettled;
}
