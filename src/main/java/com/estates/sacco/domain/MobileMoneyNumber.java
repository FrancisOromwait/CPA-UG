package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A mobile money number a branch receives money on (a branch has up to two, for example one MTN and one Airtel).
 * Each number has its own account in the chart (under Mobile Money 1300), so its balance shows separately.
 */
@Entity
@Table(name = "mobile_money_numbers", uniqueConstraints = @UniqueConstraint(columnNames = {"number"}))
@Getter @Setter @NoArgsConstructor
public class MobileMoneyNumber {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** MTN, Airtel or any other network, as typed. */
    @Column(nullable = false, length = 30)
    private String network;

    @Column(nullable = false, length = 20)
    private String number;

    /** The name the number is registered in. */
    private String holderName;

    @ManyToOne(optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    private boolean active = true;
}
