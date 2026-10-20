package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** The interest charged on one loan for one calendar month, in a monthly interest run (table interest_charges). */
@Entity
@Table(name = "interest_charges", uniqueConstraints = @UniqueConstraint(columnNames = {"loan_id", "charge_month"}),
        indexes = {@Index(columnList = "run_id")})
@Getter @Setter @NoArgsConstructor
public class InterestAccrual {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "run_id")
    private InterestRun run;

    @ManyToOne(optional = false)
    @JoinColumn(name = "loan_id")
    private Loan loan;

    @ManyToOne(optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(length = 20)
    private String branchCode;

    /** yyyy-MM */
    @Column(name = "charge_month", nullable = false, length = 7)
    private String month;

    /** Principal still owed at the start of the month (or at payout, if that was later). */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal openingPrincipal;

    /** Principal still owed at the end of the month. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal closingPrincipal;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal ratePercent;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal interest;

    /** The ledger entry that carries it. */
    @ManyToOne
    @JoinColumn(name = "ledger_entry_id")
    private LedgerEntry ledgerEntry;
}
