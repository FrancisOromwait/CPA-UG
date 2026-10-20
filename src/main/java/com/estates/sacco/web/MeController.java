package com.estates.sacco.web;

import com.estates.sacco.domain.AppUser;
import com.estates.sacco.repo.UserRepository;
import com.estates.sacco.security.BranchScope;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {
    private final UserRepository users;
    private final BranchScope scope;
    private final PasswordEncoder encoder;

    public record PasswordChange(@jakarta.validation.constraints.NotBlank String currentPassword,
                                 @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min = 8, message = "must be at least 8 characters") String password) {}

    /** Who is logged in; the web app uses this to decide which screens to show. */
    @GetMapping
    @Transactional(readOnly = true)
    Dtos.UserDto me() {
        AppUser u = users.findById(scope.me().getUserId()).orElseThrow();
        return Dtos.UserDto.of(u);
    }

    /** Change my own password: the current one is asked for, so a borrowed screen cannot lock the owner out. */
    @PutMapping("/password")
    @Transactional
    void changePassword(@jakarta.validation.Valid @RequestBody PasswordChange req) {
        AppUser u = users.findById(scope.me().getUserId()).orElseThrow();
        if (!encoder.matches(req.currentPassword(), u.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The current password is not right");
        }
        u.setPasswordHash(encoder.encode(req.password()));
        users.save(u);
    }
}
