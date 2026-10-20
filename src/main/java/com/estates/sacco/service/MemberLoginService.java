package com.estates.sacco.service;

import com.estates.sacco.domain.AppUser;
import com.estates.sacco.domain.Member;
import com.estates.sacco.domain.Role;
import com.estates.sacco.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Every member gets a login that can only view and download their own statement. The username and the first password are
 * the member's full names. If another login already has that name (two members with the same name), the member number is
 * added to the username so that it stays unique across the sacco.
 */
@Service
@RequiredArgsConstructor
public class MemberLoginService {
    private static final int MAX = 60;

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public Optional<AppUser> loginOf(Member m) {
        return users.findByMemberId(m.getId());
    }

    /** Creates the member's login if they do not have one yet; returns it. */
    @Transactional
    public AppUser ensureLogin(Member m) {
        Optional<AppUser> existing = loginOf(m);
        if (existing.isPresent()) return existing.get();
        AppUser u = new AppUser();
        u.setUsername(freeUsername(m, name(m)));
        u.setPasswordHash(encoder.encode(name(m)));
        u.setFullName(m.getFullName());
        u.setRole(Role.MEMBER);
        u.setBranch(m.getBranch());
        u.setMember(m);
        return users.save(u);
    }

    /** The member's name changed: the username follows it, unless it was changed by hand. The password stays. */
    @Transactional
    public void renamed(Member m, String oldName) {
        loginOf(m).ifPresent(u -> {
            u.setFullName(m.getFullName());
            if (u.getUsername().equals(clip(oldName.trim())) && !users.existsByUsername(name(m))) u.setUsername(name(m));
            users.save(u);
        });
    }

    /** Back to the first password (the full names), and switched on. Returns the username. */
    @Transactional
    public String reset(Member m) {
        AppUser u = ensureLogin(m);
        u.setPasswordHash(encoder.encode(name(m)));
        u.setEnabled(true);
        return users.save(u).getUsername();
    }

    @Transactional
    public void remove(Member m) {
        loginOf(m).ifPresent(users::delete);
    }

    private String freeUsername(Member m, String base) {
        String name = base;
        if (users.existsByUsername(name)) name = withSuffix(base, " " + m.getMemberNo());
        if (users.existsByUsername(name)) name = withSuffix(base, " " + m.getMemberNo() + "-" + m.getId());
        return name;
    }

    private static String name(Member m) {
        return clip(m.getFullName().trim());
    }

    private static String clip(String s) {
        return s.length() > MAX ? s.substring(0, MAX) : s;
    }

    private static String withSuffix(String s, String suffix) {
        return (s.length() + suffix.length() > MAX ? s.substring(0, MAX - suffix.length()) : s) + suffix;
    }
}
