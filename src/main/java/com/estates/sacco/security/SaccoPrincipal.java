package com.estates.sacco.security;

import com.estates.sacco.domain.AppUser;
import com.estates.sacco.domain.Role;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** Authenticated user carrying the branch they are locked to (null for the manager). */
@Getter
public class SaccoPrincipal implements UserDetails {
    private final Long userId;
    private final String username;
    private final String password;
    private final Role role;
    private final Long branchId;
    private final boolean enabled;
    /** Wrong passwords in a row before this sign-in, and whether the login was marked locked (a right password clears both). */
    private final int failedLogins;
    private final boolean lockedMark;
    private final boolean accountNonLocked;

    public SaccoPrincipal(AppUser u) {
        this.userId = u.getId();
        this.username = u.getUsername();
        this.password = u.getPasswordHash();
        this.role = u.getRole();
        this.branchId = u.getBranch() == null ? null : u.getBranch().getId();
        this.enabled = u.isEnabled() && (u.getBranch() == null || u.getBranch().isActive());
        this.failedLogins = u.getFailedLogins();
        this.lockedMark = u.isLocked();
        this.accountNonLocked = !u.isLocked() || LoginGuard.lockOver(u);
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
