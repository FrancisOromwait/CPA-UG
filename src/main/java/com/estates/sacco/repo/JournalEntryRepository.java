package com.estates.sacco.repo;

import com.estates.sacco.domain.JournalEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {
    Optional<JournalEntry> findFirstByBranchIdOrderByIdDesc(Long branchId);

    @Query("""
        select j from JournalEntry j
        where (:branchId is null or j.branch.id = :branchId)
          and j.postedOn >= :from and j.postedOn <= :to
        order by j.postedOn desc, j.id desc
        """)
    List<JournalEntry> search(Long branchId, LocalDate from, LocalDate to, Pageable page);
}
