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

    /** Rows of [date, credit minus debit] of one member's savings lines up to a date: what a savings entry's interest is worked out on. */
    @org.springframework.data.jpa.repository.Query("""
        select m.postedOn, m.credit - m.debit from MemberLedgerLine m
        where m.member.id = :memberId and m.category = com.estates.sacco.domain.ProductCategory.SAVINGS and m.postedOn <= :to
        order by m.postedOn, m.id
        """)
    List<Object[]> savingsMovesOf(Long memberId, java.time.LocalDate to);

    /** Rows of [memberId, savings interest booked less taken back] in a period: what a month's preview shows as already booked. */
    @org.springframework.data.jpa.repository.Query("""
        select m.member.id, sum(m.credit) - sum(m.debit) from MemberLedgerLine m join m.ledgerEntry le
        where m.branch.id = :branchId and m.category = com.estates.sacco.domain.ProductCategory.SAVINGS_INTEREST
        and le.type in (com.estates.sacco.domain.TxType.SAVINGS_INTEREST, com.estates.sacco.domain.TxType.SAVINGS_INTEREST_REVERSAL)
        and m.postedOn between :from and :to
        group by m.member.id
        """)
    List<Object[]> savingsInterestBooked(Long branchId, java.time.LocalDate from, java.time.LocalDate to);

    /** Rows of [memberId, last savings date] in a period: where a member's booked interest stood before the month end was booked. */
    @org.springframework.data.jpa.repository.Query("""
        select m.member.id, max(m.postedOn) from MemberLedgerLine m
        where m.branch.id = :branchId and m.category = com.estates.sacco.domain.ProductCategory.SAVINGS and m.postedOn between :from and :to
        group by m.member.id
        """)
    List<Object[]> lastSavingsDates(Long branchId, java.time.LocalDate from, java.time.LocalDate to);

    /** Rows of [memberId, interest owed] for the members whose accrued savings interest is more than nothing at a date. */
    @org.springframework.data.jpa.repository.Query("""
        select m.member.id, sum(m.credit) - sum(m.debit) from MemberLedgerLine m
        where m.branch.id = :branchId and m.category = com.estates.sacco.domain.ProductCategory.SAVINGS_INTEREST and m.postedOn <= :to
        group by m.member.id having sum(m.credit) - sum(m.debit) > 0
        """)
    List<Object[]> savingsInterestOwed(Long branchId, java.time.LocalDate to);

    /** Rows of [memberId, category, transaction type, sum(debit), sum(credit)] up to a date, for one branch or (null) all: the member summary. */
    @org.springframework.data.jpa.repository.Query("""
        select m.member.id, m.category, le.type, sum(m.debit), sum(m.credit) from MemberLedgerLine m left join m.ledgerEntry le
        where (:branchId is null or m.branch.id = :branchId) and m.postedOn <= :to
        group by m.member.id, m.category, le.type
        """)
    List<Object[]> totalsByMember(Long branchId, java.time.LocalDate to);
}
