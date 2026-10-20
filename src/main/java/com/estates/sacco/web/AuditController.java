package com.estates.sacco.web;

import com.estates.sacco.domain.AuditLog;
import com.estates.sacco.repo.AuditLogRepository;
import com.estates.sacco.security.BranchScope;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** The record of who edited or deleted what. */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {
    private final AuditLogRepository audit;
    private final BranchScope scope;

    public record AuditDto(Long id, Instant when, String branchCode, String user, String action, String entityType,
                           Long entityId, String details) {
        static AuditDto of(AuditLog a) {
            return new AuditDto(a.getId(), a.getCreatedAt(), a.getBranch() == null ? null : a.getBranch().getCode(),
                    a.getUsername(), a.getAction(), a.getEntityType(), a.getEntityId(), a.getDetails());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<AuditDto> list(@RequestParam(required = false) Long branchId) {
        Long b = scope.readBranch(branchId);
        return (b == null ? audit.findTop200ByOrderByIdDesc() : audit.findTop200ByBranchIdOrderByIdDesc(b))
                .stream().map(AuditDto::of).toList();
    }
}
