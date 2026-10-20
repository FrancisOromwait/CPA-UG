package com.estates.sacco.web;

import com.estates.sacco.repo.BranchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Unauthenticated endpoints needed by the login page. Names only, no figures. */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {
    private final BranchRepository branches;
    private final com.estates.sacco.service.DeploymentSettings settings;

    public record LoginBranch(Long id, String code, String name) {}

    public record Config(boolean singleBranch, String appName) {}

    /** Whether this is a one-branch installation, and the name to show. */
    @GetMapping("/config")
    Config config() {
        return new Config(settings.isSingleBranch(), settings.getAppName());
    }

    @GetMapping("/branches")
    List<LoginBranch> branches() {
        return branches.findAll().stream()
                .filter(b -> b.isActive())
                .map(b -> new LoginBranch(b.getId(), b.getCode(), b.getName()))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }
}
