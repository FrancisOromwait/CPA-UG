package com.estates.sacco.repo;

import com.estates.sacco.domain.MobileMoneyNumber;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MobileMoneyNumberRepository extends JpaRepository<MobileMoneyNumber, Long> {
    List<MobileMoneyNumber> findByBranchIdOrderByIdAsc(Long branchId);
    List<MobileMoneyNumber> findByBranchIdNot(Long branchId);
    List<MobileMoneyNumber> findAllByOrderByBranchIdAscIdAsc();
    long countByBranchId(Long branchId);
    boolean existsByNumber(String number);
    boolean existsByNumberAndIdNot(String number, Long id);
}
