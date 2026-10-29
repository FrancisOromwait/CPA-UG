package com.estates.sacco.security;

import com.estates.sacco.domain.AppUser;
import com.estates.sacco.domain.Role;
import com.estates.sacco.repo.UserRepository;
import com.estates.sacco.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Stops password guessing. Every wrong password for a login is counted; a right one sets the count back to 0. At MAX_TRIES wrong
 * passwords in a row the login is locked: even the right password is refused until its password is reset by whoever manages it
 * (the branch admin for treasurers, cashiers and members; the support login for anyone). The support login has nobody above it
 * to unlock it, so its lock ends by itself after SUPPORT_LOCK. Locks are kept in the audit trail.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoginGuard {
    public static final int MAX_TRIES = 4;
    public static final Duration SUPPORT_LOCK = Duration.ofMinutes(10);

    private final UserRepository users;
    private final AuditService audit;

    /** The support login's lock has run out (other logins stay locked until their password is reset). */
    static boolean lockOver(AppUser u) {
        return u.getRole() == Role.MANAGER && u.getLockedAt() != null && u.getLockedAt().plus(SUPPORT_LOCK).isBefore(Instant.now());
    }

    @EventListener
    @Transactional
    public void wrongPassword(AuthenticationFailureBadCredentialsEvent e) {
        users.findByUsername(String.valueOf(e.getAuthentication().getName())).ifPresent(u -> {
            u.setFailedLogins(u.getFailedLogins() + 1);
            if (u.getFailedLogins() >= MAX_TRIES && (!u.isLocked() || lockOver(u))) {
                u.setLocked(true);
                u.setLockedAt(Instant.now());
                log.warn("Login '{}' locked after {} wrong passwords in a row", u.getUsername(), u.getFailedLogins());
                audit.logSystem(u.getBranch(), "LOCK", "Login", u.getId(), "Login " + u.getUsername() + " locked after " + MAX_TRIES
                        + " wrong passwords" + (u.getRole() == Role.MANAGER ? " (opens again after " + SUPPORT_LOCK.toMinutes() + " minutes)"
                        : u.getRole() == Role.BRANCH_ADMIN ? " (the support login unlocks it)" : " (the branch admin or the support login unlocks it)"));
            }
            users.save(u);
        });
    }

    /** A right password: the count goes back to 0 (written only when there was something to clear, not on every request). */
    @EventListener
    @Transactional
    public void signedIn(AuthenticationSuccessEvent e) {
        if (e.getAuthentication().getPrincipal() instanceof SaccoPrincipal p && (p.getFailedLogins() > 0 || p.isLockedMark())) {
            users.findById(p.getUserId()).ifPresent(LoginGuard::clear);
        }
    }

    /** Unlocks a login and forgets its wrong passwords (a password reset by the support login, or a right password). */
    public static void clear(AppUser u) {
        u.setFailedLogins(0);
        u.setLocked(false);
        u.setLockedAt(null);
    }
}
