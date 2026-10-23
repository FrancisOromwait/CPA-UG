package com.estates.sacco.repo;

import com.estates.sacco.domain.MemberPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberPhotoRepository extends JpaRepository<MemberPhoto, Long> {
    Optional<MemberPhoto> findByMemberId(Long memberId);
    void deleteByMemberId(Long memberId);
}
