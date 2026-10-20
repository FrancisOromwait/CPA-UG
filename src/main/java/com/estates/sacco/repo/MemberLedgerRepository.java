package com.estates.sacco.repo;

import com.estates.sacco.domain.MemberLedgerLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemberLedgerRepository extends JpaRepository<MemberLedgerLine, Long> {
    boolean existsByLedgerEntryId(Long ledgerEntryId);
    boolean existsByProductId(Long productId);
    void deleteByLedgerEntryId(Long ledgerEntryId);
    List<MemberLedgerLine> findByLedgerEntryIdOrderByIdAsc(Long ledgerEntryId);
    List<MemberLedgerLine> findByLedgerEntry_DepositGroupOrderByIdAsc(String depositGroup);
    java.util.Optional<MemberLedgerLine> findFirstByLedgerEntryId(Long ledgerEntryId);
    List<MemberLedgerLine> findByMemberIdOrderByIdAsc(Long memberId);

    List<MemberLedgerLine> findByMemberIdAndPostedOnOrderByIdAsc(Long memberId, java.time.LocalDate postedOn);

    /** Rows of [memberId, date, credit minus debit] of every savings line of a branch up to a date: the basis of daily interest. */
    @org.springframework.data.jpa.repository.Query("""
        select m.member.id, m.postedOn, m.credit - m.debit from MemberLedgerLine m
        where m.branch.id = :branchId and m.category = com.estates.sacco.domain.ProductCategory.SAVINGS and m.postedOn <= :to
        order by m.postedOn, m.id
        """)
    List<Object[]> savingsMoves(Long branchId, java.time.LocalDate to);

    /** Rows of [memberId, interest owed] for the members whose accrued savings interest is more than nothing at a date. */
    @org.springframework.data.jpa.repository.Query("""
        select m.member.id, sum(m.credit) - sum(m.debit) from MemberLedgerLine m
        where m.branch.id = :branchId and m.category = com.estates.sacco.domain.ProductCategory.SAVINGS_INTEREST and m.postedOn <= :to
        group by m.member.id having sum(m.credit) - sum(m.debit) > 0
        """)
    List<Object[]> savingsInterestOwed(Long branchId, java.time.LocalDate to);
}
