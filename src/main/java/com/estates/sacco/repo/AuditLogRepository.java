package com.estates.sacco.repo;

import com.estates.sacco.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findTop200ByOrderByIdDesc();
    List<AuditLog> findTop200ByBranchIdOrderByIdDesc(Long branchId);
}
