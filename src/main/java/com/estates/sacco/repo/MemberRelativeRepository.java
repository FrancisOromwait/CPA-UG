package com.estates.sacco.repo;

import com.estates.sacco.domain.MemberRelative;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemberRelativeRepository extends JpaRepository<MemberRelative, Long> {
    List<MemberRelative> findByMemberIdOrderByKindAscPositionAsc(Long memberId);

    void deleteByMemberId(Long memberId);
}
