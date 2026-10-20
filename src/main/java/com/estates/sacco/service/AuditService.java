package com.estates.sacco.service;

import com.estates.sacco.domain.AuditLog;
import com.estates.sacco.domain.Branch;
import com.estates.sacco.repo.AuditLogRepository;
import com.estates.sacco.security.BranchScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository repo;
    private final BranchScope scope;

    /** For changes the system makes itself, with no signed-in user (start-up conversions). */
    public void logSystem(Branch branch, String action, String entityType, Long entityId, String details) {
        write(branch, "system", action, entityType, entityId, details);
    }

    public void log(Branch branch, String action, String entityType, Long entityId, String details) {
        write(branch, scope.me().getUsername(), action, entityType, entityId, details);
    }

    private void write(Branch branch, String username, String action, String entityType, Long entityId, String details) {
        AuditLog a = new AuditLog();
        a.setBranch(branch);
        a.setUsername(username);
        a.setAction(action);
        a.setEntityType(entityType);
        a.setEntityId(entityId);
        a.setDetails(details == null ? null : details.length() > 990 ? details.substring(0, 990) : details);
        repo.save(a);
    }
}
