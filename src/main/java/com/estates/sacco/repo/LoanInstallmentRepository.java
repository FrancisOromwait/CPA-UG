package com.estates.sacco.repo;

import com.estates.sacco.domain.LoanInstallment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanInstallmentRepository extends JpaRepository<LoanInstallment, Long> {
    List<LoanInstallment> findByLoanIdOrderByInstallmentNoAsc(Long loanId);
    boolean existsByLoanId(Long loanId);
    void deleteByLoanId(Long loanId);
}
