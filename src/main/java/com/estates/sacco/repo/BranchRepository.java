package com.estates.sacco.repo;

import com.estates.sacco.domain.Branch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, Long> {
    boolean existsByCodeIgnoreCase(String code);
}
