package com.estates.sacco.web;

import com.estates.sacco.domain.AppUser;
import com.estates.sacco.domain.Role;
import com.estates.sacco.repo.BranchRepository;
import com.estates.sacco.repo.UserRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.AuditService;
import com.estates.sacco.web.Dtos.UserDto;
import com.estates.sacco.web.Dtos.UserReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Users. The manager creates branch admins and any other user. A branch admin creates and manages the staff of their own
 * branch (treasurers, cashiers), who can do everything a branch admin can except manage users.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserRepository users;
    private final BranchRepository branches;
    private final PasswordEncoder encoder;
    private final BranchScope scope;
    private final AuditService audit;

    public record PasswordReq(@NotBlank @Size(min = 8) String password) {}

    @GetMapping
    @Transactional(readOnly = true)
    List<UserDto> list() {
        // members' own logins are not listed here: they are managed from the member list
        if (scope.isManager()) return users.findAll().stream().filter(u -> u.getRole() != Role.MEMBER).map(UserDto::of).toList();
        return users.findByBranchId(scope.me().getBranchId()).stream().filter(u -> u.getRole() != Role.MEMBER).map(UserDto::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    UserDto create(@Valid @RequestBody UserReq req) {
        if (users.existsByUsername(req.username().trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "Username already taken");
        }
        AppUser u = new AppUser();
        u.setUsername(req.username().trim());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setFullName(req.fullName().trim());
        u.setRole(req.role());
        if (req.role() == Role.MEMBER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A member's login is created automatically when the member is added");
        }
        if (scope.isManager()) {
            if (req.role() == Role.MANAGER) {
                if (req.branchId() != null) throw new ApiException(HttpStatus.BAD_REQUEST, "A manager is not tied to a branch");
            } else {
                if (req.branchId() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "A branch is required for this user");
                u.setBranch(branches.findById(req.branchId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Branch not found")));
            }
        } else {
            if (!req.role().isStaff()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "A branch admin can create treasurers and cashiers only");
            }
            u.setBranch(branches.findById(scope.me().getBranchId()).orElseThrow());
        }
        AppUser saved = users.save(u);
        if (saved.getBranch() != null) {
            audit.log(saved.getBranch(), "CREATE", "User", saved.getId(), saved.getUsername() + " (" + saved.getRole() + ") " + saved.getFullName());
        }
        return UserDto.of(saved);
    }

    @PutMapping("/{id}/enabled/{value}")
    @Transactional
    UserDto setEnabled(@PathVariable Long id, @PathVariable boolean value) {
        AppUser u = manageable(id);
        if (u.getId().equals(scope.me().getUserId())) throw new ApiException(HttpStatus.BAD_REQUEST, "You cannot disable your own login");
        u.setEnabled(value);
        audit(u, value ? "Enabled login " : "Disabled login ");
        return UserDto.of(users.save(u));
    }

    @PutMapping("/{id}/password")
    @Transactional
    UserDto resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordReq req) {
        AppUser u = manageable(id);
        u.setPasswordHash(encoder.encode(req.password()));
        audit(u, "Reset the password of ");
        return UserDto.of(users.save(u));
    }

    /** The manager can change anyone; a branch admin only the treasurers and cashiers of their own branch. */
    private AppUser manageable(Long id) {
        AppUser u = users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (scope.isManager()) return u;
        if (u.getBranch() == null || !u.getBranch().getId().equals(scope.me().getBranchId()) || !u.getRole().isStaff()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only manage the treasurers and cashiers of your own branch");
        }
        return u;
    }

    private void audit(AppUser u, String what) {
        if (u.getBranch() != null) audit.log(u.getBranch(), "EDIT", "User", u.getId(), what + u.getUsername());
    }
}
