package com.estates.sacco.repo;


import com.estates.sacco.domain.Employer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployerRepository extends JpaRepository<Employer, Long> {

    java.util.List<Employer> findByBranchIdOrderByNameAsc(Long branchId);

}


