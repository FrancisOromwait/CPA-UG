package com.estates.sacco.repo;

import com.estates.sacco.domain.InterestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InterestRunRepository extends JpaRepository<InterestRun, Long> {
    List<InterestRun> findByBranchIdAndMonthOrderByIdAsc(Long branchId, String month);

    @Query("select r from InterestRun r where (:branchId is null or r.branch.id = :branchId) order by r.monthEnd desc, r.id desc")
    List<InterestRun> search(Long branchId);
}
