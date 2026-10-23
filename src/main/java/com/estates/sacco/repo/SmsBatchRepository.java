package com.estates.sacco.repo;

import com.estates.sacco.domain.SmsBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SmsBatchRepository extends JpaRepository<SmsBatch, Long> {
    List<SmsBatch> findTop100ByBranchIdOrderByIdDesc(Long branchId);
    List<SmsBatch> findTop100ByOrderByIdDesc();
}
