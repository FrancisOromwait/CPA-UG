package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_users")
@Getter @Setter @NoArgsConstructor
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** Null for MANAGER; required for BRANCH_ADMIN. */
    @ManyToOne
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** Set for a member's own login (role MEMBER). */
    @ManyToOne
    @JoinColumn(name = "member_id")
    private Member member;

    private boolean enabled = true;

    /** Wrong passwords in a row; a right one sets it back to 0. At {@code LoginGuard.MAX_TRIES} the login is locked. */
    @Column(nullable = false, columnDefinition = "int not null default 0")
    private int failedLogins;

    /** Locked after too many wrong passwords, until the support login resets the password (the support login itself: for 10 minutes, LoginGuard.SUPPORT_LOCK). */
    @Column(nullable = false, columnDefinition = "bit not null default 0")
    private boolean locked;

    private java.time.Instant lockedAt;
}
