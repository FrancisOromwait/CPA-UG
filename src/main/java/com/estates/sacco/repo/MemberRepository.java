package com.estates.sacco.repo;

import com.estates.sacco.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MemberRepository extends JpaRepository<Member, Long> {
    boolean existsByBranchIdAndMemberNo(Long branchId, String memberNo);
    long countByBranchId(Long branchId);

    /** The pattern is built by the caller (e.g. "%smith%") so no SQL string functions are needed. */
    @Query("""
        select m from Member m
        where (:branchId is null or m.branch.id = :branchId)
          and (:pattern is null or lower(m.fullName) like :pattern or lower(m.memberNo) like :pattern)
        order by m.branch.id, m.memberNo
        """)
    List<Member> search(Long branchId, String pattern);
}
