package com.estates.sacco.repo;

import com.estates.sacco.domain.AppUser;
import com.estates.sacco.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByRole(Role role);
    List<AppUser> findByBranchId(Long branchId);
    Optional<AppUser> findByMemberId(Long memberId);
}
