package com.estates.sacco.repo;

import com.estates.sacco.domain.MemberDetails;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberDetailsRepository extends JpaRepository<MemberDetails, Long> {
    Optional<MemberDetails> findByMemberId(Long memberId);

    void deleteByMemberId(Long memberId);
}
