package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One line on a member product account (the member ledger, ML). */
@Entity
@Table(name = "member_ledger", indexes = {
        @Index(columnList = "member_id,category"),
        @Index(columnList = "ledger_entry_id")})
@Getter @Setter @NoArgsConstructor
public class MemberLedgerLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** Sacco ID of the branch, stored on the row so the ledger reads on its own. */
    @Column(length = 20)
    private String branchCode;

    @ManyToOne(optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(optional = false)
    @JoinColumn(name = "product_id")
    private ProductDef product;

    /** Copied from the product at posting time so history stays readable if the product changes. */
    @Column(nullable = false, length = 20)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductCategory category;

    @ManyToOne(optional = false)
    @JoinColumn(name = "ledger_entry_id")
    private LedgerEntry ledgerEntry;

    @Column(nullable = false)
    private LocalDate postedOn;

    private String description;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;

    private Instant createdAt = Instant.now();
}
