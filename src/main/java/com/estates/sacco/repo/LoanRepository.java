package com.estates.sacco.repo;

import com.estates.sacco.domain.Loan;
import com.estates.sacco.domain.LoanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface LoanRepository extends JpaRepository<Loan, Long> {
    List<Loan> findByMemberId(Long memberId);

    @Query("""
        select l from Loan l
        where (:branchId is null or l.branch.id = :branchId)
          and (:status is null or l.status = :status)
        order by l.id desc
        """)
    List<Loan> search(Long branchId, LoanStatus status);

    long countByBranchIdAndStatus(Long branchId, LoanStatus status);

    /** Total principal plus interest owed on every loan that has been paid out. */
    @Query("""
        select coalesce(sum(l.totalDue), 0) from Loan l
        where l.branch.id = :branchId
          and l.status in (com.estates.sacco.domain.LoanStatus.ACTIVE, com.estates.sacco.domain.LoanStatus.CLEARED)
        """)
    BigDecimal totalDueByBranch(Long branchId);
}
