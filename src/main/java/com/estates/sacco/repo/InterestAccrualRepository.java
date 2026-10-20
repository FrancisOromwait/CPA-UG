package com.estates.sacco.repo;

import com.estates.sacco.domain.InterestAccrual;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterestAccrualRepository extends JpaRepository<InterestAccrual, Long> {
    List<InterestAccrual> findByRunIdOrderByIdAsc(Long runId);
    List<InterestAccrual> findByLoanIdOrderByIdAsc(Long loanId);
    boolean existsByLoanIdAndMonth(Long loanId, String month);
    void deleteByRunId(Long runId);
    void deleteByLoanId(Long loanId);
}
