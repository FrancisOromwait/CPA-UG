package com.estates.sacco.repo;

import com.estates.sacco.domain.SavingsInterestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SavingsInterestRunRepository extends JpaRepository<SavingsInterestRun, Long> {
    @Query("select r from SavingsInterestRun r where (:branchId is null or r.branch.id = :branchId) order by r.periodEnd desc, r.id desc")
    List<SavingsInterestRun> search(Long branchId);

    boolean existsByBranchIdAndKindAndPeriod(Long branchId, String kind, String period);

    Optional<SavingsInterestRun> findFirstByBranchIdOrderByPeriodEndDescIdDesc(Long branchId);
}
