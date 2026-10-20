package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line of the chart of accounts. There is a single chart shared by every branch,
 * maintained by the manager; branches post to it but cannot change it.
 */
@Entity
@Table(name = "accounts", uniqueConstraints = @UniqueConstraint(columnNames = {"code"}))
@Getter @Setter @NoArgsConstructor
public class Account {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    /** Optional parent, to group sub-accounts under a heading. Always the same type. */
    @ManyToOne
    @JoinColumn(name = "parent_id")
    private Account parent;

    private String description;

    private boolean active = true;

    /** Part of the standard chart. */
    private boolean systemAccount;
}
