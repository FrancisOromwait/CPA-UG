package com.estates.sacco.repo;

import com.estates.sacco.domain.PayrollBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PayrollBatchRepository extends JpaRepository<PayrollBatch, Long> {
    boolean existsByBranchIdAndEmployerIgnoreCaseAndPeriod(Long branchId, String employer, String period);
    boolean existsByBranchIdAndEmployerIgnoreCaseAndPeriodAndIdNot(Long branchId, String employer, String period, Long id);
    boolean existsByReceivingAccountId(Long accountId);

    @Query("select p from PayrollBatch p where (:branchId is null or p.branch.id = :branchId) order by p.id desc")
    List<PayrollBatch> search(Long branchId);
}
